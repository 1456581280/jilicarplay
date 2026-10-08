#!/usr/bin/env python3
"""Regression gate for private-server uploads in source and optionally an APK."""
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = (
    b"carlito.i234.me", b"diplay-profiles/v1", b"VehicleReportUploadService",
    b"VehicleReportDelivery", b"SteeringProfileUploadService", b"DiagnosticReportUpload",
    b"vehicle-report-outbox", b"vehicle-report-upload", b"diplay-report-upload",
)
failures = []
for module in ("common", "vehicle-probe", "shared", "mobile", "automotive"):
    for path in (ROOT / module / "src/main").rglob("*"):
        if path.is_file():
            data = path.read_bytes()
            if any(token in data for token in FORBIDDEN):
                failures.append(str(path.relative_to(ROOT)))
if len(sys.argv) > 1:
    with zipfile.ZipFile(sys.argv[1]) as apk:
        for name in apk.namelist():
            if name.endswith((".dex", ".so")) or name == "AndroidManifest.xml":
                data = apk.read(name)
                if any(token in data or token.decode().encode("utf-16le") in data for token in FORBIDDEN):
                    failures.append("APK:" + name)
if failures:
    raise SystemExit("Upload regression detected: " + ", ".join(failures))
print("PASS: private report/profile upload endpoints and services are absent.")
