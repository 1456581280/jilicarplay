# 本地数据版本 0.2.14-local.1

## 来源与变更

源码基线：carlito12345/DiPlay，提交 `08aa5fd3f89424591100f299f25169d82a9d1e5b`。
用户提供的原 APK：`DiPlay-0.2.14 (2).apk`。仓库源码与该 APK 的逐字节对应关系未获证实。

已移除：

- 扫描完成、扫描服务启动和重试按钮触发的车辆报告上传。
- VehicleReportDelivery 上传队列、VehicleReportUploadService、JobScheduler 持久化网络重试。
- 保存方向盘配置时的上传、队列和 SteeringProfileUploadService。
- 手动诊断上传入口及共享 HTTP 报告传输实现。
- 原项目定时合并上游工作流。

保留扫描、本地保存/导出、属性自动应用、方向盘配置、诊断脱敏及 Android 主动分享。
CarPlay 与 iPhone 的局域网通信仍需 INTERNET 权限，不能为了阻断报告上传而删除整个应用的网络权限。
本版本不宣称无网络通信；用户主动配置的远程 MFi 或在线媒体功能仍按原有设置工作。

## 安装

包名 `com.shihab.diplay`，版本码 40，Android 9 及以上。
本地版本使用新的独立签名，不能覆盖作者签名的原安装；先在原版内导出需要的配置，再卸载原版并安装本版本。卸载会清除原应用私有数据及原有后台作业。

新签名可能不被独立 GD 车辆数据桥授权。若提示签名未授权，需要数据桥提供的正规授权方式或兼容构建；本仓库不绕过桥的签名校验。未进行实车、iPhone 连接或数据桥兼容性验证。

## 本地构建

要求 JDK 25、Android SDK 37.0、Build Tools 36.0.0、NDK 28.2.13676358，使用仓库 Gradle wrapper。

按 `docs/BUILD.md` 设置 `DIPLAY_AUTH_ASSETS_DIR` 与四个 `ANDROID_*` 签名环境变量，然后执行：

```sh
python scripts/check_no_upload.py
./gradlew :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
python scripts/check_no_upload.py mobile/build/outputs/apk/release/mobile-release.apk
```

本次安装包的两个 CarPlay 运行时认证资源取自用户提供的 APK，并仅通过外部资产目录输入构建。认证资源和新 Android 签名私钥不提交 Git。
务必备份工作区外层 `.build-tools/jilicarplay-release.jks` 及 `.build-tools/signing-password.txt`，后续升级沿用同一签名。不要上传这两个文件。
GitHub Actions 的普通 debug 构建不含运行时认证资源，不能当作可独立连接 iPhone 的安装包。

## 检查范围

`check_no_upload.py` 检查应用源码及 APK DEX/原生库/清单中是否重新出现已知上传端点、上传实现和服务；CI 同样运行此检查。
`LocalOnlyProfilesTest` 验证方向盘配置保存/读取/禁用仍工作，且不会创建上传队列或调度后台作业。
这些检查不替代实车网络抓包和功能验证。
