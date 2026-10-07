# 0.2.13.2 connection baseline

Source baseline: `8f53b27b3168aedb661e9f9bb7122ea344b75ada`.
Feature source: `29b3cb3248a70c6eece3688f1805873f8e2fa076`.
App version: `0.2.13.2`, upgrade code `36`.

Published as `v0.2.13.2` to keep the previous release reproducible.
Automatic upstream synchronization fetches changes but does not merge them into this
stable baseline; subsequent updates must selectively migrate features and review connection paths.

## Retained baseline paths

The shared network and transport runtime, hotspot configuration migration,
USB enumeration/bring-up, Bluetooth paired/connected-peer detection, RFCOMM bootstrap,
wireless endpoint publication, Bonjour probes, VPN/NCM attachment, AirPlay session
ownership, and startup/watchdog timing are taken from the baseline. No ECARX snapshot
reader, physical-interface monitor, KX11 gateway override, or post-baseline address
fan-out is used in normal connection startup.

The connection controller starts with the baseline source. Only view-area controls,
telephony buttons, full-map projection ownership/zoom, and post-session Bluetooth
music handoff are migrated. Music handoff operates after AirPlay activates; it does
not change Bluetooth peer detection, iAP2 RFCOMM, HFP, or MFi discovery.

## Actual Geely APK compatibility correction

The working `吉利修改版-0.2.12-release.apk` and the released 0.2.13.2 APK were
compared directly. Their manual-hotspot interface selection differs despite the
working APK's embedded revision marker: the working APK scores eligible up
interfaces, including Ethernet interfaces; 0.2.13.2 required platform AP evidence.
An observable empty AP-interface list consequently rejected the KX11 Ethernet
paths shown in the failed report before Bluetooth iAP2 startup.

Detected Geely head units now use the working APK's interface exclusions, address
selection, scores and first-candidate tie behavior. The same selection policy is
used during sampling, stable readiness and pre-publication validation. Scoped IPv6,
network-consistency checks, cancellation and startup deadlines are retained.
Other head units retain the existing AP-evidence policy. Connection modes,
credential selection, USB transport, authentication and package identity are unchanged.
This corrects the startup rejection; a successful physical KX11 session remains
to be confirmed. An embedded Git revision is not proof of APK behavior equivalence.

## Migrated features

Vehicle bridge clients and steering callbacks, property presets/report import, navigation
editor and vehicle data, three-finger projection, full-map output and rotary zoom,
adaptive screen geometry, separate audio outputs/microphones, audio focus coordination,
device reconnect fallback, and upstream non-connection display/media improvements.
Optional privileged hotspot repair remains confined to unlocked diagnostics and only
runs when requested there.

The existing Settings → Display and performance → Hide top status bar switch also
applies immediately to the DiPlay home/settings window, is restored on resume/window
focus, and uses Android transient swipe behavior. CarPlay keeps its existing preference.

## Package identity and limits

Production application ID and the existing GitHub signing/MFi configuration are retained.
The supplied known-good APK uses a different application ID, signing certificate, and
version-code override; rebasing the source does not reproduce that APK's factory grants
or installed preferences. Physical KX11 behavior still requires confirmation on the car.

No new tests are added or manually run for this migration. Compilation and the repository's
existing publication workflow supply build evidence; neither proves in-car operation.
