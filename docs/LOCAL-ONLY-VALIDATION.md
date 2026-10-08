# 本地版本验证记录

- 源码基线：`08aa5fd3f89424591100f299f25169d82a9d1e5b`。
- 环境：Windows、JDK 25、SDK 37.0、NDK 28.2.13676358、Gradle 9.5.0。
- `:mobile:assembleRelease`：成功，独立 RSA 3072 签名，APK Signature Scheme v2 验证通过。
- `:mobile:lintRelease`：通过，0 错误、4 警告（localeConfig API 兼容性、原有 TLS trust manager 两条、一个未用资源）。没有为通过检查而关闭规则。
- 全量 `:common:testDebugUnitTest`：641 项，638 通过，3 项 shell 夹具在 Windows 下因命令行引号传递失败。
- 将测试夹具改为通过 stdin 传入脚本，保留原断言，不修改应用的 USB 授权行为。
- 重跑 `*UsbPermissionSetupTest`、`*LocalOnlyProfilesTest`、`*DiagnosticExport*`：32 项全部通过，包含原失败的 3 项。
- `scripts/check_no_upload.py`：应用源码及签名 APK 检查通过。
- `scripts/check_public_tree.py`：源码不含认证资源和签名私钥。
- APK 合并清单中不存在两个上传服务；APK 中不存在已知私人服务器地址或上传实现。
- 包名 `com.shihab.diplay`、版本码 40、版本 `0.2.14-local.1`、最低 Android API 28。
- `zipalign -c -P 16 4` 校验通过。
- 两项 CarPlay 运行时认证资源与用户提供 APK 中的原资源逐字节一致。
- 新签名证书 SHA-256：`3e818c66d6dabe8bdcd79497f51f93736688f27e06612c3351b9d9c9b4ef124a`。

未验证：实车安装、iPhone 有线/无线连接、GD 车辆数据桥对新签名的授权、实际网络抓包。
安装和本地签名备份说明见 [LOCAL-ONLY.md](LOCAL-ONLY.md)。
