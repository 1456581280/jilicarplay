# 0.2.15 验证记录

本次变更仅涉及正式版本号、首页展示和用户 GitHub 仓库的版本更新功能。既有连接、车辆属性、本地报告、设置逻辑保留。

- `:mobile:assembleRelease`、`:mobile:lintRelease` 成功。
- 41 项相关单元/界面测试通过：HomeCompactLayoutTest、ReleasePolicyTest、LocalOnlyProfilesTest、DiagnosticExportFallbackTest、DiagnosticExportStoreTest、DiagnosticExportUiTest、BydSettingsReconnectTest、ExistingWifiSettingsTest、SettingsWidgetsTest。
- 原生渲染截图覆盖 960×540 横屏、400×800 竖屏、800×360 短屏和 1.6 倍字体；所有首页按钮维持至少 48dp 点击高度，窄屏和大字体可滚动访问完整页面。
- 测试覆盖版本递增比较、忽略草稿/预发布、拒绝其他仓库和非 HTTPS 下载地址、缺失校验值、文件篡改/截断检测。
- 源码及 APK 无旧私人服务器地址、车辆报告上传服务、方向盘上传服务；旧隐私回归检查保留。
- 继续使用 0.2.14-local.1 的 Android 签名，可覆盖升级；包名 `com.shihab.diplay`，版本码 41。
- 签名私钥、密码和 CarPlay 认证资源不提交 Git。

尚未验证：实车安装、实车网络环境下从后续版本完成整条安装流程。仓库仍按用户要求保留当前可见性；只有在用户开放仓库访问且车机网络可达 GitHub 后，匿名自动检查才可工作。

更新规则和维护步骤见 [GITHUB-UPDATES.md](GITHUB-UPDATES.md)。
