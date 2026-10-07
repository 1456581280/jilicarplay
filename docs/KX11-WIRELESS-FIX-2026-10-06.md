# KX11 无线连接修复包

基于公开源码 8f53b27 与昨晚保存的 kx11-test-source.patch 制作，版本 0.2.12-kx11-wireless-fix1 / 32。当前 GD 与 DiPlay 的模块开发备份位于 Y:/codex-cache/vehicle-progress-20261006-before-kx11-fix，保持原工作区。

日志确认蓝牙 iAP2、MFi 和 StartSession 成功，但没有 AirPlay TCP。手动热点只提供一个 IPv6 地址；wlan0 实际同时具备私有 IPv4 与链路本地 IPv6。

本次修改：保留 ECARX WifiAp/Wifi6Ap 读取及已验证客户端路由；无客户端路由证据时优先选热点私有 IPv4；复用既有多地址监听与 Bonjour 功能覆盖同一热点接口的两个地址族；iAP2 发送与监听一致的地址集；热点频段只报告实际观测值。新代码带 carlito 归属标签。

不加入尚未完成的车桥新模块，不修改 Wi-Fi Direct 的 IPv6 地址选择，不关闭系统热点。只构建安装包，不新增或运行单元测试。使用既有 Actions 签名与昨晚 APK 内相同的本地 MFi 材料。

仍需实车确认客户端接口、路由及车机访问策略；构建通过不能证明无线会话已建立。
