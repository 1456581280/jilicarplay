# 0.2.17 自定义首页容量修复验证

- `:common:testDebugUnitTest --tests com.shilapi.xcertplay.HtmlHomeTest`：12 项通过，无失败。
- 回归验证超过旧 256 KB 限制的中文格式化 HTML：粘贴、预览、保存、读取和重新编辑均保持原文，包括 CRLF、缩进、首尾空白、`pre` 和 JavaScript 模板字符串。
- 验证 4 MiB UTF-8 边界，超限保存失败且保留已有首页。
- Android 模拟器真实 WebView 验证：超过 256 KB 的格式化代码完整存储，内嵌 CSS 和 JavaScript 正常执行，无顶部三个原生按钮。
- `:common:assembleDebugAndroidTest :mobile:assembleRelease`：构建通过，release lint 通过。
- 发行 APK：`com.shihab.diplay`，versionCode 43，versionName 0.2.17。签名验证通过，与 0.2.16 使用相同证书。
- 本次未重新执行云端更新集成测试；未安装发行包覆盖用户现有应用，未上传蓝奏云。
