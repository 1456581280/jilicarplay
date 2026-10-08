# 自定义 HTML 首页

设置 → 自定义 HTML 首页 → 编辑首页。可复制完整示例，或复制 AI 提示词和功能入口交给 AI；把生成的完整 HTML 粘贴回编辑器，预览后点“保存并应用”。代码仅保存在本机。未保存时继续使用默认原生首页。顶部始终保留原生“编辑首页 / 默认首页 / 设置”按钮；恢复默认会删除已保存的自定义代码。

示例：[custom-home-example.html](../common/src/main/assets/custom-home-example.html)。无需构建即可粘贴更换首页。预览中的功能入口只显示名称，不会发起连接或修改设置。

## 给 AI 的要求

生成完整 UTF-8 HTML（不超过 256 KB），内嵌 CSS、JavaScript、SVG，图片只能使用 data URI；不使用 CDN、外部图片、iframe、远程请求或文件路径。适配横屏、竖屏、大字体，窄屏单列，页面可纵向滚动，按钮至少 48px。保留以下用户点击入口，不要自动触发导航：

```html
<a href="diplay://action/connect">无线连接 / 打开 CarPlay</a>
<a href="diplay://action/usb">USB 连接</a>
<a href="diplay://action/choose-device">选择设备</a>
<a href="diplay://action/disconnect">断开连接</a>
<a href="diplay://action/settings">设置</a>
<a href="diplay://action/check-update">检查更新</a>
<a href="diplay://action/source">项目源码</a>
<a href="diplay://action/edit-home">编辑首页</a>
<a href="diplay://action/default-home">恢复默认首页</a>
```

状态由原生代码单向发送，不含车辆属性、设备标识或手机名称：

```html
<div id="status">准备连接</div>
<script>
function show(state) {
  document.getElementById('status').textContent = state.status;
  // state.connected、state.connecting 为布尔值；state.version 为版本文本。
}
window.addEventListener('diplay-state', function (event) { show(event.detail); });
if (window.DiPlayState) show(window.DiPlayState);
</script>
```

WebView 禁止联网、文件和 content URI 访问；不注入 JavaScriptInterface。不提供任意 URL、Intent 或命令执行接口。仅允许主页面中用户点击的白名单动作，拒绝子框架和无手势导航。自定义 HTML 不会改变原有 CarPlay 和设置实现。

GitHub 自动更新属于 Android APK 更新：检测正式 Release 后提示用户，用户确认下载并通过系统确认安装；不是自动执行远程网页或静默替换代码。
