# KX11 factory-routed hotspot compatibility

carlito | Evidence-based behavior compatibility, 2026-10-06.

The user supplied GeelyCarPlay-0.3.2-wheel-map-zoom-debug.apk (KX11). Static inspection confirms a separate Android 9 KX11 path: an up eth* interface with IPv4 198.18.*, saved car hotspot credentials, explicit-address AirPlay/mDNS listening, one primary iAP endpoint, unknown channel and no Ethernet MAC presented as Wi-Fi BSSID. This is an implemented APK behavior, not proof that every KX11 firmware needs this path.

## Implementation boundaries

- Strict Android API 28 / MODEL KX11 / DEVICE kx11* policy. Other vehicles keep the existing generic address rules.
- Proven vendor client routes, Android AP ownership and confirmed local hotspot candidates retain higher priority. The factory gateway is a fallback; eth0 wins over other matching Ethernet interfaces.
- Reuse stable interface sampling, cancellation, live address validation and bounded failed-path preferences. Android AP=false does not reject this factory gateway, but it does not become evidence that the factory hotspot is switched on.
- For this selected fallback, use the saved SSID/security/password, channel 0, unknown frequency and no Wi-Fi BSSID. Do not read or change unrelated Android SoftAP configuration.
- AirPlay, mDNS/probe, dynamic listener updates and iAP bootstrap use the selected primary address. Only this strictly matched gateway bypasses Android Network.bindSocket; explicit socket source binding remains.
- All wireless endpoint addresses are filtered against the actual currently bound listeners, with the primary required and listed first.
- USB, audio and MFi paths are unchanged by this compatibility module.

## Remaining verification

Source inspection and compilation cannot prove car routing or phone reachability. The known-good 0.2.12 APK uses a different older path and remains a separate baseline. No global public-address allowlist or general network rollback is justified by these APKs.

The 0.3.2 fork additionally uses a real iPhone instrument map stream, transparent lower instrument area, frame-confirmed OEM DIM mode ownership, and a timed steering-knob zoom lease. Those findings are recorded in the independent analysis report; the navigation/card editor is not equivalent to the fork's complete map projection implementation.