#!/usr/bin/env python3
"""Private intake for named DiPlay steering profiles. No uploaded content is served or executed."""
import hashlib
import base64
import json
import os
import re
import shutil
import threading
import time
import unicodedata
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

BASE_PATH = "/diplay-profiles"
MAX_BYTES = 32 * 1024
MAX_REPORT_BYTES = 10 * 1024 * 1024
MAX_REPORT_REQUEST_BYTES = 31 * 1024
REPORT_CHUNK_BYTES = 16 * 1024
MAX_REPORT_CHUNKS = (MAX_REPORT_BYTES + REPORT_CHUNK_BYTES - 1) // REPORT_CHUNK_BYTES
OPERATIONS = {"play_pause", "next", "previous", "siri"}
PROFILE_FIELDS = {
    "schemaVersion", "backend", "carModel", "headUnitModel", "manufacturer",
    "firmware", "androidVersion", "savedAt", "bindings",
}
BINDING_FIELDS = {"operation", "keyCode", "event", "source", "logTag", "broadcastAction", "keyExtra", "eventExtra", "logContains"}
REPORT_FIELDS = {
    "schemaVersion", "submittedAt", "fileName", "issueDescription",
    "uploadId", "chunkIndex", "chunkCount", "content",
}


def profile_part(value):
    value = unicodedata.normalize("NFKC", value).strip()
    value = re.sub(r'[\\/:*?"<>|\x00-\x1f\x7f-\x9f]', "_", value).strip(". ") or "unknown"
    result = ""
    for letter in value:
        if len((result + letter).encode("utf-8")) > 64:
            break
        result += letter
    return result


def validate_profile(profile):
    if not isinstance(profile, dict) or set(profile) != PROFILE_FIELDS:
        raise ValueError("Invalid profile fields")
    if type(profile["schemaVersion"]) is not int or profile["schemaVersion"] != 1 or profile["backend"] != "system_key_events":
        raise ValueError("Unsupported profile format")
    for field in ("carModel", "headUnitModel", "manufacturer", "firmware", "androidVersion"):
        value = profile[field]
        if not isinstance(value, str) or len(value) > 120 or any(unicodedata.category(c) == "Cc" for c in value):
            raise ValueError("Invalid head unit information")
    if not profile["carModel"].strip() or not profile["headUnitModel"].strip():
        raise ValueError("Vehicle and head unit model required")
    if type(profile["savedAt"]) is not int or not 0 < profile["savedAt"] < 10**15:
        raise ValueError("Invalid save time")
    bindings = profile["bindings"]
    if not isinstance(bindings, list) or not 1 <= len(bindings) <= 4:
        raise ValueError("At least one steering button required")
    operations, inputs = set(), set()
    for binding in bindings:
        if not isinstance(binding, dict) or set(binding) != BINDING_FIELDS:
            raise ValueError("Invalid button fields")
        operation, code = binding["operation"], binding["keyCode"]
        if not isinstance(operation, str) or operation not in OPERATIONS or operation in operations:
            raise ValueError("Invalid or repeated button operation")
        if type(code) is not int or not 0 <= code <= 1_000_000:
            raise ValueError("Invalid key code")
        if type(binding["event"]) is not int or not 0 <= binding["event"] <= 4:
            raise ValueError("Invalid button event")
        if binding["source"] not in ("broadcast", "logcat", "oneos"):
            raise ValueError("Unsupported input source")
        tag = binding["logTag"]
        if not isinstance(tag, str) or len(tag) > 100 or any(unicodedata.category(c) == "Cc" for c in tag):
            raise ValueError("Invalid log source")
        if binding["source"] == "logcat" and not tag.strip():
            raise ValueError("Log source required")
        for field, limit in (("broadcastAction", 160), ("keyExtra", 120), ("eventExtra", 120)):
            value = binding[field]
            if not isinstance(value, str) or len(value) > limit or any(unicodedata.category(c) == "Cc" for c in value):
                raise ValueError("Invalid broadcast input")
        if binding["source"] == "broadcast" and (not re.fullmatch(r"[A-Za-z0-9_.]+", binding["broadcastAction"]) or not binding["keyExtra"].strip()):
            raise ValueError("Broadcast action and key field required")
        fragments = binding["logContains"]
        if not isinstance(fragments, list) or len(fragments) > 4 or (binding["source"] != "logcat" and fragments):
            raise ValueError("Invalid log fragments")
        for fragment in fragments:
            if not isinstance(fragment, str) or not fragment.strip() or len(fragment) > 160 or any(unicodedata.category(c) == "Cc" for c in fragment):
                raise ValueError("Invalid log fragment")
        if code == 0 and not (binding["source"] == "logcat" and fragments):
            raise ValueError("A key code or explicit log rule is required")
        input_id = (binding["source"], code, tag, binding["broadcastAction"], binding["keyExtra"], binding["eventExtra"], tuple(sorted(fragments)))
        if input_id in inputs:
            raise ValueError("Repeated button input")
        operations.add(operation)
        inputs.add(input_id)
    return profile


def validate_report_chunk(payload):
    if not isinstance(payload, dict) or set(payload) != REPORT_FIELDS:
        raise ValueError("Invalid report fields")
    if type(payload["schemaVersion"]) is not int or payload["schemaVersion"] != 1:
        raise ValueError("Unsupported report format")
    now = int(time.time() * 1000)
    if type(payload["submittedAt"]) is not int or not now - 30 * 86400 * 1000 < payload["submittedAt"] < now + 86400 * 1000:
        raise ValueError("Invalid submission time")
    if not re.fullmatch(r"DiPlay-(?:Vehicle-)?[0-9]{8}-[0-9]{6}-[0-9]{3}\.txt", payload["fileName"]):
        raise ValueError("Invalid report name")
    description = payload["issueDescription"]
    if not isinstance(description, str) or not description.strip() or len(description) > 2000:
        raise ValueError("Invalid problem description")
    if any(unicodedata.category(letter) == "Cc" and letter not in "\r\n\t" for letter in description):
        raise ValueError("Invalid problem description")
    upload_id = payload["uploadId"]
    chunk_index = payload["chunkIndex"]
    chunk_count = payload["chunkCount"]
    if not isinstance(upload_id, str) or not re.fullmatch(r"[0-9a-f]{64}", upload_id):
        raise ValueError("Invalid upload identifier")
    if type(chunk_index) is not int or type(chunk_count) is not int or not 1 <= chunk_count <= MAX_REPORT_CHUNKS or not 0 <= chunk_index < chunk_count:
        raise ValueError("Invalid report chunk")
    content = payload["content"]
    if not isinstance(content, str):
        raise ValueError("Invalid report chunk")
    decoded = base64.b64decode(content.encode("ascii"), validate=True)
    if not decoded or len(decoded) > REPORT_CHUNK_BYTES or (chunk_index < chunk_count - 1 and len(decoded) != REPORT_CHUNK_BYTES):
        raise ValueError("Invalid report chunk size")
    return payload, decoded


class ProfileStore:
    def __init__(self, root):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = threading.Lock()
        self.requests = defaultdict(deque)
        self.used_bytes = sum(file.stat().st_size for file in self.root.rglob("*.json") if file.is_file())
        self.max_bytes = int(os.environ.get("DIPLAY_PROFILES_MAX_STORAGE", str(100 * 1024 * 1024)))

    def admit(self, remote, limit=60):
        current = time.monotonic()
        with self.lock:
            if len(self.requests) >= 4096:
                self.requests = defaultdict(deque, {ip: times for ip, times in self.requests.items() if times and times[-1] > current - 3600})
                if len(self.requests) >= 4096 and remote not in self.requests:
                    return False
            times = self.requests[remote]
            while times and times[0] < current - 3600:
                times.popleft()
            if len(times) >= limit:
                return False
            times.append(current)
            return True

    def save(self, content):
        profile = validate_profile(json.loads(content.decode("utf-8")))
        receipt = hashlib.sha256(content).hexdigest()
        car, unit, firmware = (profile_part(profile[field]) for field in ("carModel", "headUnitModel", "firmware"))
        name = f"{car}_{unit}_{firmware}.json"
        directory = self.root / car / unit / receipt
        destination = directory / name
        with self.lock:
            if destination.is_file():
                return receipt, name
            if self.used_bytes + len(content) > self.max_bytes:
                raise OSError("Profile storage is full")
            directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            temporary = directory / (name + ".tmp")
            with temporary.open("wb") as output:
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
            temporary.replace(destination)
            destination.chmod(0o600)
            self.used_bytes += len(content)
        return receipt, name


class ReportStore:
    def __init__(self, root):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        # carlito: vehicle scans and connection diagnostics have separate final directories.
        self.vehicle_root = (self.root.parent / "vehicle-scans").resolve()
        self.vehicle_root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.pending = (self.root / ".pending").resolve()
        self.pending.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = threading.Lock()
        self.used_bytes = sum(file.stat().st_size for root in (self.root, self.vehicle_root)
                              for file in root.rglob("*.txt") if file.is_file())
        self.max_bytes = int(os.environ.get("DIPLAY_REPORTS_MAX_STORAGE", str(1024 * 1024 * 1024)))

    def save_chunk(self, content):
        payload, decoded = validate_report_chunk(json.loads(content.decode("utf-8")))
        receipt = payload["uploadId"]
        upload = self.pending / receipt
        metadata = {
            key: payload[key]
            for key in ("schemaVersion", "submittedAt", "fileName", "issueDescription", "uploadId", "chunkCount")
        }
        with self.lock:
            self._discard_expired()
            upload.mkdir(parents=True, exist_ok=True, mode=0o700)
            metadata_file = upload / "metadata.json"
            if metadata_file.is_file():
                if json.loads(metadata_file.read_text(encoding="utf-8")) != metadata:
                    raise ValueError("Report metadata changed")
            else:
                metadata_file.write_text(json.dumps(metadata, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
                metadata_file.chmod(0o600)
            chunk_file = upload / f"{payload['chunkIndex']:03d}.part"
            if not chunk_file.is_file():
                temporary = chunk_file.with_suffix(".tmp")
                temporary.write_bytes(decoded)
                temporary.replace(chunk_file)
                chunk_file.chmod(0o600)
            chunks = [upload / f"{index:03d}.part" for index in range(payload["chunkCount"])]
            if not all(chunk.is_file() for chunk in chunks):
                return receipt, payload["fileName"], False
            report_bytes = b"".join(chunk.read_bytes() for chunk in chunks)
            if len(report_bytes) > MAX_REPORT_BYTES:
                raise ValueError("Diagnostic report too large")
            report = report_bytes.decode("utf-8")
            diagnostic = report.startswith("DiPlay ") and "diagnostic report" in report[:200]
            header_lines = report[:8192].splitlines()
            vehicle = "schema=geely_property_probe_v2" in header_lines and any(
                line.startswith("chain_id,kind,area_id,property_name,property_id,property_hex,")
                for line in header_lines
            )
            if not (diagnostic or vehicle):
                raise ValueError("Invalid report")
            expected = hashlib.sha256(payload["issueDescription"].strip().encode("utf-8") + b"\0" + report_bytes).hexdigest()
            if expected != receipt:
                raise ValueError("Diagnostic report checksum mismatch")
            stamp = time.strftime("%Y-%m-%d", time.localtime(payload["submittedAt"] / 1000))
            if payload["fileName"].startswith("DiPlay-Vehicle-") and not vehicle:
                raise ValueError("Vehicle filename requires a vehicle report")
            root = self.vehicle_root if vehicle else self.root
            destination = root / stamp / receipt / payload["fileName"]
            body = (
                ("DiPlay vehicle property report\n" if vehicle else "DiPlay user report\n") +
                f"Submitted at: {payload['submittedAt']}\n\n"
                "Problem description:\n"
                f"{payload['issueDescription'].strip()}\n\n" +
                ("--- Vehicle property report ---\n" if vehicle else "--- Diagnostic report ---\n") +
                f"{report}"
            ).encode("utf-8")
            if destination.is_file():
                shutil.rmtree(upload, ignore_errors=True)
                return receipt, destination.name, True
            if self.used_bytes + len(body) > self.max_bytes:
                raise OSError("Report storage is full")
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            temporary = destination.with_suffix(destination.suffix + ".tmp")
            with temporary.open("wb") as output:
                output.write(body)
                output.flush()
                os.fsync(output.fileno())
            temporary.replace(destination)
            destination.chmod(0o600)
            self.used_bytes += len(body)
            shutil.rmtree(upload, ignore_errors=True)
        return receipt, destination.name, True

    def _discard_expired(self):
        cutoff = time.time() - 86400
        for directory in self.pending.iterdir():
            if directory.is_dir() and directory.stat().st_mtime < cutoff:
                shutil.rmtree(directory, ignore_errors=True)


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def reply(self, status, data):
        body = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if urlsplit(self.path).path == BASE_PATH + "/health":
            self.reply(200, {"ok": True, "schemaVersion": 1})
        else:
            self.reply(404, {"ok": False, "error": "Not found"})

    def do_POST(self):
        path = urlsplit(self.path).path
        if path not in (BASE_PATH + "/v1/profiles", BASE_PATH + "/v1/reports"):
            self.reply(404, {"ok": False, "error": "Not found"})
            return
        remote = self.headers.get("X-Real-IP", self.client_address[0])[:100]
        request_limit = 60 if path.endswith("/profiles") else 2000
        if not self.server.store.admit(f"{remote}|{path}", request_limit):
            self.reply(429, {"ok": False, "error": "Try again later"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            limit = MAX_BYTES if path.endswith("/profiles") else MAX_REPORT_REQUEST_BYTES
            if length > limit:
                self.reply(413, {"ok": False, "error": "Submission too large"})
                return
            if length <= 0 or self.headers.get("Transfer-Encoding") or self.headers.get_content_type() != "application/json":
                raise ValueError("JSON profile required")
            content = self.rfile.read(length)
            if len(content) != length:
                raise ValueError("Incomplete profile")
            if path.endswith("/profiles"):
                receipt, name = self.server.store.save(content)
                complete = True
            else:
                receipt, name, complete = self.server.report_store.save_chunk(content)
            self.reply(201, {"ok": True, "receipt": receipt, "fileName": name, "complete": complete})
        except (ValueError, UnicodeError, TypeError, KeyError, RecursionError):
            self.reply(422, {"ok": False, "error": "Invalid submission"})
        except OSError:
            self.reply(503, {"ok": False, "error": "Storage temporarily unavailable"})


def main():
    os.umask(0o077)
    root = Path(os.environ.get("DIPLAY_PROFILES_DATA", str(Path(__file__).parent / "data" / "profiles")))
    report_root = Path(os.environ.get("DIPLAY_REPORTS_DATA", str(Path(__file__).parent / "data" / "reports")))
    server = ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("DIPLAY_PROFILES_PORT", "18794"))), Handler)
    server.daemon_threads = True
    server.store = ProfileStore(root)
    server.report_store = ReportStore(report_root)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
