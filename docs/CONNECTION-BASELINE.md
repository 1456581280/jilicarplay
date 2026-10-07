# DiPlay 0.2.13.2 connection integration

Connection source: upstream `7887bb7bf2b52258e663a2a4ea1332382ed80ad8` (0.2.14).
Previous feature baseline: `8f53b27b3168aedb661e9f9bb7122ea344b75ada`.
App version remains `0.2.13.2`, version code `36`, at the user's request.

## Connection scope

Changed network files and connection transport files are copied from the pinned upstream
revision: IPv4-first wireless selection, interface Bonjour, manual hotspot readiness and
configuration, Android 9 P2P channels, Bluetooth RFCOMM ownership, USB reads/queues and
wireless iAP2 handoff. Controller connection hunks retain projection, HUD and audio hooks.
The prior Geely interface scorer, extra LAN fanout, independent probe workers and
multi-address iAP2 endpoint are removed. Saved AUTOMATIC settings use the upstream default
P2P backend and timeout. Automatic mode no longer chains manual/local-only/P2P attempts.
Manual hotspot and existing LAN remain explicit choices with existing connection settings.

Upstream prefers usable IPv4 for every model and retains scoped IPv6 fallback. USB NCM's
IPv6 endpoint is retained: supplied reports include working USB sessions. Upstream's AP
evidence policy is restored exactly; hidden OEM Ethernet AP ownership cannot be inferred.

## Evidence and limits

The five reports contain different stages and versions. Two G636 reports are 0.2.13.1.
G636 and KX11 failures already use IPv4, while sa8155 selects IPv6 first. Several attempts
complete Bluetooth authentication and StartSession but have no discovery or AirPlay TCP.
Others fail earlier at P2P, Bluetooth or USB. Neither universal IPv6 incompatibility nor
one common root cause is proven. Unchanged version numbers do not mean unchanged APKs.
The custom working APK has a different package/signing context; upstream code does not
reproduce factory grants. Signing, authentication secrets and package identity are retained.
Compile and source review precede submission; actual operation needs in-car confirmation.

## Features and sound

Bridge/steering interception, scanning/profile application, navigation editor, HUD and
instrument displays, three-finger projection, full map, rotary zoom, adaptive geometry and
fullscreen preferences are retained. Post-session music handoff does not change discovery.
Sound settings show navigation and media only. Non-navigation audio shares media output,
usage and legacy stream selection, retaining logical call/voice focus priorities. Old
per-call output/microphone overrides are ignored. Factory output renders CarPlay locally.
Bluetooth output retains native A2DP sink connections and observes actual playback for the
session's phone. Only confirmed Bluetooth music suppresses local media music and its focus.
Calls, voice, ringing, navigation and microphones remain negotiated and rendered normally.
Connection alone never mutes media; unsupported playback state keeps CarPlay fallback.
Neither mode forces the phone's selected destination. All non-navigation output uses media
attributes/device/stream preferences without forcing communication mode or microphone device.

## Reports

Scans use DiPlay-Vehicle timestamps, separate fallback folders and a header with model/source,
hardware/firmware, Android, ABI/screen and app/bridge versions. Unknown models stay unidentified.
Raw schema/rows remain importable. A fresh scan supplies metadata absent from old reports.
Downloads retains the DiPlay directory with distinct filenames. Cloud intake classifies
validated scans into data/vehicle-scans, accepting legacy filenames and unchanged partial
upload metadata. Total report limit is 10 MB, with at most 640 chunks. Historical scan
archives are separated with a recoverable migration manifest, without altering their content.
