> 自定义首页：设置中粘贴 AI 生成的 HTML，可预览、保存或恢复默认。见 [HTML 首页与功能入口](docs/HTML-HOME.md)。

# JiliCarPlay：本地数据版本

当前版本：**0.2.17**，自定义首页支持 4 MiB 格式化代码，包含自适应首页及 [蓝奏云更新和应用内日志](docs/APP-UPDATES.md)。安装包见 [蓝奏云文件夹](https://1280ds.lanzoue.com/b03anv53fc)，密码 `f6uq`。

基于 carlito12345/DiPlay，移除车辆扫描报告、方向盘配置和诊断报告的私人服务器上传功能。扫描、属性应用、方向盘映射、本地诊断导出和用户主动分享保留。

- 不包含报告上传端点、上传队列或后台上传服务。
- 关闭定时合并上游，避免自动引入上传代码。
- 安装、签名和验证说明见 [本地版本说明](docs/LOCAL-ONLY.md)。
- 原项目说明及许可证保留如下；其中云端上传描述不适用于本分支。

---

# DiPlay

**CarPlay for compatible Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay`.

This fork adds generic audio coordination and map projection, plus Geely vehicle integration through a separately installed GD vehicle bridge. Compatibility depends on the head unit's interfaces and permissions. Android 9 or later is required.

[Download v0.2.13.1](https://github.com/carlito12345/DiPlay/releases/tag/v0.2.13.1) · [Update notes](docs/UPDATE-NOTES-0.2.13.1.md) · [Report a problem](https://github.com/carlito12345/DiPlay/issues)

## 0.2.13.1

Bluetooth music handoff and restoration, shared audio focus for navigation/calls/Siri, independent output and microphone selection, and device reconnect recovery. Full map projection supports compatible secondary displays, automatic dimensions, three-finger switching and volume-button/rotary zoom. Install the separate GD bridge for supported vehicle properties, buttons and native instrument modes. Physical vehicle validation remains necessary.

See [audio coordination](docs/AUDIO-COORDINATION.md) and [map projection](docs/VEHICLE_MAP_PROJECTION.md). The upstream 0.2.13 information below is retained for reference.

![DiPlay home](site/assets/home.png)

## 0.2.13 — public preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. Core CarPlay does not require ADB; optional dashboard, battery, wheel-speed and parked-video features do. Your head unit must permit APK installation. The APK supports Android 9+ (API 28); wireless supports Wi-Fi Direct, the car’s existing hotspot or Existing Wi-Fi / Same LAN. Android 9 Wi-Fi Direct uses a firmware-dependent legacy path with generated group credentials and unverified requested frequency; see [Android 9 Wi-Fi Direct](docs/ANDROID9_WIFI_DIRECT.md). Android 10+ verifies its negotiated group frequency.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

Earlier releases were tested on the development DiLink5.1 car: live windshield guidance and street names work, Car hotspot now starts CarPlay, and Wi-Fi Direct performance is substantially improved. Occasional audio cutouts remain and are deferred to a later update. The floating-map test build was installed on the development DiLink 5.1 car; feedback led to the pinch corrections in 0.2.9. Earlier wheel-speed and video contributions were tested on a BYD Tang with DiLink 5.0 and an iPhone 15 Pro on iOS 27; wheel-speed dead reckoning in tunnels remains unverified. Broader head-unit and iOS compatibility is not guaranteed. The HUD firmware scope and cleanup limits are documented in [BYD navigation](docs/BYD_NAVIGATION.md).

## What’s new in 0.2.13

- Android 9 Wi-Fi Direct, IPv4-first hotspot endpoints, safer Auto channel ordering and wireless startup without unused NSD/USB services.
- Guarded rendered-video handoff fallback, safe wireless-to-USB switching and checked, cancellable permission setup.
- Narrow USBMUX trailer recovery that preserves valid payload replies.
- Optional live dock/split-screen areas and square-canvas rotation, plus the selected-decoder capability check and default-off experimental side panel.
- DiLink 4 casting/calibration and live cluster picture controls; checked DiLink 3 projection entry with compensation.
- Recent turn-card retention across wireless replacement, a finer dashboard map choice, battery-protocol fallback and eligible wheel-service recovery.
- The observed Siri microphone timestamp correction and TCP_NODELAY touch events, with device-specific performance limits.
- **Off by default:** independent experimental DiLink 3 call keys/dashboard calls and AAC-LC buffered music. Read their firmware/audio/restoration limits before opting in. Eligible hotspot join repair is a separately confirmed Check/Apply/Restore action.
- Clearer settings, saved-menu behavior, car-button customization and day/night-aware waiting screens.
- Traditional Chinese (Taiwan), bringing both the app and release website to seven languages.

See [0.2.13 release notes](docs/RELEASE-NOTES-0.2.13.md) and [validation](docs/VALIDATION.md) for all reviewed contributions, hardware evidence and remaining physical tests. Higher resolution and large square canvases cost more decoder/GPU work. General stutter, calls/Siri, decoder, old-iOS startup and model-specific reports remain under investigation. [0.2.12 notes](docs/RELEASE-NOTES-0.2.12.md) remain available as historical guidance.

If a problem remains, reproduce it on **0.2.13**, then use **Settings → Diagnostics → Save diagnostic report**. Android 10+ normally saves to **Downloads/DiPlay**; Android 9 uses the document picker. If unavailable, use **View report** or **Share** from the confirmation, which identifies external/private fallback storage. Review the `.txt` and add it to a matching [existing issue](https://github.com/shihabal3amri/DiPlay/issues), or [create one](https://github.com/shihabal3amri/DiPlay/issues/new/choose). Include vehicle/head-unit model, exact firmware and Android/DiLink, phone/iOS, connection backend, relevant settings, steps and failure time. Reports are shared only when you choose; never post your hotspot password.

## Documentation

[Existing Wi-Fi / Same LAN](docs/EXISTING_WIFI.md) keeps the iPhone and head unit
on an external router. See the guide for setup, build requirements and the
BYD DiLink 4.0 / Android 10 clean-install validation result.

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Smooth wireless CarPlay](docs/SMOOTH_WIRELESS.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md)
- [Validation](docs/VALIDATION.md)
- [Release notes](CHANGELOG.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

The app and release website are available in English, Arabic, Russian, Ukrainian, Spanish, Simplified Chinese and Traditional Chinese (Taiwan). Traditional Chinese uses Taiwan wording; the app also recognizes Hong Kong/Macao and explicit Hant selections without claiming separate regional translations. Choose the app language in Settings; on Android 13+, it stays synchronized with Android’s per-app language setting.

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong to Apple Inc.; no Apple or BYD affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly select an external asset directory. Publishing the APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
