# 自定义 HTML 首页

设置 → 自定义 HTML 首页 → 编辑首页。可复制完整示例，或复制 AI 提示词和功能入口交给 AI；把生成的完整 HTML 粘贴回编辑器，预览后点“保存并应用”。代码仅保存在本机。未保存时继续使用默认原生首页。自定义首页全屏显示，不再附加顶部三个原生按钮；按系统返回键可进入设置，再次编辑或恢复默认。恢复默认会删除已保存的自定义代码。

编辑区独立滚动，复制、粘贴、预览和保存操作保持在编辑区外。粘贴不会截断代码；从 0.2.17 起，容量由 256 KB 提高到 4 MiB（4,194,304 字节，UTF-8）。完整保留换行、缩进、空格和中文注释，无需压缩代码；`pre` 内容和 JavaScript 模板字符串中的空白也保持不变。预览、保存和重新读取使用相同容量限制，发现不完整或超限时提示修改，保留原有首页。支持粘贴由单个 Markdown HTML 代码块包裹的代码，仅移除代码块标记。旧版本保存的不完整页面会转入设置以便修复，原代码保留。

示例：[custom-home-example.html](../common/src/main/assets/custom-home-example.html)。无需构建即可粘贴更换首页。预览中的功能入口只显示名称，不会发起连接或修改设置。

## 给 AI 的要求

生成完整 UTF-8 HTML（不超过 4 MiB），使用正常换行和缩进，无需压缩。内嵌 CSS、JavaScript、SVG，图片只能使用 data URI；不使用 CDN、外部图片、iframe、远程请求或文件路径。适配横屏、竖屏、大字体，窄屏单列，页面可纵向滚动，按钮至少 48px。保留以下用户点击入口，不要自动触发导航：

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

应用每次打开会检查蓝奏云文件夹中的新版本，由用户选择是否下载及安装；跳过更新可以继续正常使用。安装新版本后的飞书日志以原生弹窗显示。详见 [更新说明](APP-UPDATES.md)。
