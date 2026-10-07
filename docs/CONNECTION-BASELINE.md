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
reader, physical-interface monitor, KX11 gateway override, is used in normal connection startup. The focused wireless correction below
adds LAN address coverage without changing USB bring-up.

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

Detected Geely head units retain the working APK's OEM Ethernet eligibility,
with IPv4 preferred when available. Positive platform AP ownership takes precedence;
Wi-Fi upstreams remain excluded. When AP ownership is hidden,
private IPv4 alternatives on eligible up OEM LAN interfaces are served on
the same port, together with the primary interface's scoped IPv6 fallback. This also
covers a private OEM Ethernet default LAN as an alternate or as the primary when no
other eligible IPv4 LAN is visible, since the Android default route alone cannot
establish where the OEM hotspot is bridged. No route is changed. Interface
and address stability are validated before publication and iAP2 StartSession.
No IP subnet, Wi-Fi channel or unobserved phone association is invented.

The TCP listener, interface Bonjour and iAP2 endpoint advertise the same address list.
Bonjour resolution retains the receiving local address: connect probes bind to that
address and use its IPv6 scope. Independent LAN probes cannot block discovery on
other interfaces; cancellation closes all probe sockets and workers.

Explicit saved manual hotspot credentials take precedence, including in automatic
mode's existing-hotspot attempt. Readable system configuration supplies credentials
only when there is no saved network name. A second Geely hotspot's configuration
does not override the selected network; observed metadata is used only for matching
SSID. Diagnostic reports record configuration agreement without credentials.

Other head units retain their AP-evidence policy. USB, MFi authentication, package
identity and startup deadlines are unchanged. The failed wireless report shows
successful Bluetooth/MFi but zero AirPlay TCP, with IPv4 available on the selected
IPv6-only published interface; it does not prove which OEM VLAN the phone uses.
A successful physical KX11 session remains to be confirmed. An embedded Git revision
is not proof of APK behavior equivalence.

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
