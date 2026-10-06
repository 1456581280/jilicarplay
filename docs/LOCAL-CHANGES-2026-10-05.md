# 官方 CarPlay 对照与修改记录

本记录汇总 2026 年 10 月 5 日对官方 CarPlay APK 的分析，以及据此完成的 DiPlay 修改。最初按用户要求保留在本地；用户随后要求提交 GitHub，以便清理电脑 C 盘，因此本轮提前提交。当前版本保持 0.2.12。

## 合并基线与约定

- 基线提交：`84050d6ad85d6c24170811427fb04a5b6e7c3221`，分支 `main`。
- 本轮代码、修改记录和更新说明一并提交到 GitHub，后续合并上游继续保留本记录中的改动。
- 提交主分支后，由仓库既有 Actions 执行检查和构建，并在检查成功后更新 0.2.12 安装包；具体结果以对应 Actions 为准。
- 屏幕尺寸继续来自实际窗口，保留分辨率、界面缩放、物理尺寸和安全区域设置。
- 官方模板是特定车机的参数，不导入其固定触控尺寸、视频尺寸或物理尺寸。
- 普通页面使用产品文案；实现细节保留在本记录和已解锁的诊断页面。

## 官方配置提取与证据

分析对象为 `com.autolink.carplay.apk`，SHA256 为 `a267cd284410c73a8b00a03c49d028d9565e747f4e9a5cf8c4b14f76690d0601`。

原始配置文件为 `carplay_config.json`，结构化分析为 `apk-analysis.json`；它们与参考 AutoAudio APK、提交前补丁及清单一起保存在仓库外的分析目录，并打包备份到云端用户目录的 `codex-cache/analysis-backups/official-carplay-analysis-2026-10-05.zip`。这些分析附件不写入公开源码树。

| 发现 | 原始证据 | 对本项目的处理 |
| --- | --- | --- |
| APK 内含配置模板，实车启动读取外部配置 | `ConfigManager.java` 读取 `/vendor/etc/carplay/carplay_config.json` | 不能把 APK 模板认定为实车有效配置 |
| 模板使用 H.264，关闭 HEVC 和主画面缓冲标志 | `assets/carplay_config.json` 的 `SupportFeatures` | 参考低延迟目标；编码选择仍由用户设置和当前设备能力决定 |
| 触控尺寸与视频尺寸存在方向差异 | 同文件的 `ScreenAttr` | 不套用；保留动态窗口尺寸与现有触控映射 |
| 官方把 Surface 交给原生接收器 | `CarPlayNative.setSurface`、`NativeLib.set_surface` | 现项目继续直接向 Surface 解码；本轮未替换 TextureView 或移植车机原生服务 |
| 原车音频焦点按音频用途申请 | `AudioAdapter.requestAudioFocus` 使用传入的 usage 构造 AudioAttributes | 优先原车 guidance usage；没有可确认的统一头枕数字通道 |
| 官方可以控制整车音频压低与恢复 | `AudioAdapter.duckAudio/unduckAudio` 调用 CarAudioManager | 记为后续可借鉴项；需确认对应车机接口与权限后再实现 |
| 模板中的音频 usage/source 全部为 1 | `AudioAttrs` | 这些值不能证明头枕、导航或通话路由；沿用对占位值的过滤 |

## 本轮代码修改

| 文件 | 修改内容 | 原因及合并注意事项 |
| --- | --- | --- |
| `shared/.../media/VideoDecoderSupport.kt` | 新增统一的尺寸、帧率和硬件能力查询；硬件解码器优先，同类中优先满足当前帧率的解码器 | 原能力检查只看系统列出的第一项。以后合并应同时保留协商与播放使用同一排序 |
| `common/.../CarPlayHostActivity.kt` | 放大画布能力检查使用统一查询；HEVC 没有满足当前尺寸和所需帧率的硬件路径而 H.264 有时，在本次连接使用 H.264；只有所选帧率大于 30 且硬件明确支持当前尺寸的 30 帧时，才使用 30 帧 | 保留实际尺寸与用户偏好；不永久改写编码和帧率设置，尊重显式软件 HEVC 选择。未知能力时不推断固定车机规格 |
| `shared/.../media/AndroidMediaSink.kt` | 逐个尝试满足尺寸的硬件解码器，再尝试兼容配置及软件路径；接收时间贯穿输入与输出；输入等待超时后恢复，过期的已解码画面不再显示；关键帧请求异常隔离 | 维持原有 250 毫秒积压限制，避免解码阶段掩盖已经积压的画面。跳过已解码输出不会删掉解码器需要的压缩参考帧 |
| `shared/.../airplay/ScreenStream.kt` | 关闭状态与 socket 引用跨线程可见；监听创建和接入后检查关闭状态并关闭新连接 | 避免断开与接入并发时留下尚未关闭的视频连接 |
| `common/.../AirPlayPersistence.kt` | 未保存导航通道时使用自动用途路由；继续继承明确保存过的旧导航通道 | 官方证据不支持为所有吉利车机默认强制数字通道 14。保留已保存的用户选择 |
| `common/.../AudioChannelPreview.kt` | 自动通道试听采用与吉利实际播放一致的原车音乐或导航用途，并沿用用途被 ROM 拒绝后的标准用途回退；按音频焦点设置申请短暂试听焦点并释放 | 修正自动通道试听与实际播放路径不一致的问题，减少对头枕路由的误判 |
| `common/.../DiPlayActivity.kt` | 向音频通道试听传入应用上下文 | 让试听获得原车配置和音频焦点设置 |
| `docs/GEELY-FACTORY-CARPLAY.md` | 更新导航默认路由说明 | 删除没有官方依据的通用吉利通道 14 描述 |
| `shared/.../media/AudioOutputDevice.kt` | 枚举当前音频输出，保存设备偏好并在连接时重新查找 | 优先按类型和地址匹配；没有地址时按编号、类型及名称匹配。设备消失后使用自动路由，避免只靠重启后可能改变的编号误选 |
| `common/.../AirPlayPersistence.kt` | 增加独立的导航输出设备偏好 | 与旧数字 stream 设置区分；原有保存值继续可用 |
| `common/.../DiPlayActivity.kt` | 新增导航输出设备列表、数值输入框和试听按钮 | 用户选择设备或手填当前设备编号进行试听；无法找到编号时提示不可用。选物理设备时改用自动导航用途；重新选择旧导航通道时清除物理设备偏好 |
| `common/.../AudioChannelPreview.kt`、`common/.../CarPlayHostActivity.kt`、`shared/.../media/AndroidMediaSink.kt` | 试听与正式导航音轨使用相同的设备偏好；正式播放记录请求和实际路由结果 | 调用 AudioTrack.setPreferredDevice；不保证 ROM 一定遵从请求，实际设备编号仅记录在诊断中 |
| `common/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml` | 增加导航设备选择、数值输入、试听和不可用提示的英文及中文文案 | 普通页面只呈现选择操作，编号用于与 AutoAudio 的枚举结果对应 |
| `shared/.../media/AndroidMediaSink.kt` | 通话结束等待通话音轨释放、麦克风关闭，再恢复音频模式和音乐焦点；使旧焦点回调失效，并在音乐线程暂停后继续同一音轨 | 针对用户报告的 USB 挂断后需调整 iPhone 音量才恢复声音。保留音乐缓冲，不调整系统音量；这是一项待实车确认的修正 |
| `docs/UPDATE-NOTES-0.2.12.md` | 补充导航设备选择与试听、画面播放改进和通话后音乐恢复说明 | 仅描述用户能感知的变化，版本与上游保持一致 |

## 导航输出与头枕试听

当前通道选择器的 0 至 20 是 legacy stream 选择，不是车机物理扬声器列表。自动通道可以交给原车 CarPlay guidance usage 路由，但头枕是否参与仍由车机音频策略和车辆设置决定。

在本次查看的官方 Java 代码中，未找到可直接移植的独立头枕通道编号或头枕输出调用。

用户随后提供 [AutoAudio 作者分析](https://github.com/Mikhail2092/geely-third-party-software-analysis/blob/main/apps/AutoAudio/analysis/report.en.md)，并要求先按其方式枚举设备设置导航通道、保留数值框，让用户自行试听。作者报告描述 AudioDeviceInfo 输出枚举与 preferred-device 路由；它没有把固定编号 44 证明为所有车型的头枕。

因此新增的是当前车机输出列表和设备编号输入，而不是固定头枕名称或扩大旧 stream 范围。`#44` 若出现在当前输出枚举中，可以选中或手填 44 后试听。没有该设备时显示不可用；保存后设备消失、或正式播放拒绝偏好时保留自动用途路由。设备的名称以 ROM 提供的产品名称为准，实际发声位置需用户测试。

AutoAudio 参考 APK 仅用于静态分析，保存在仓库外，未安装或运行。文件 SHA256 为 `e37a058d8cdb88d5e464dd08d291d1e51a80ce20a3b151bc0f7b6b5c6ff68104`。本轮对设备路由的实现依据作者报告和 Android 官方 API 文档，单类反编译未完成，未将其视作已验证的源码证据。

## USB 通话结束后的音频恢复

用户报告：USB 连接下，电话挂断后需要调整 iPhone 音量才恢复正常声音。代码中原来在 AudioRenderer.close 发出异步停止后就恢复模式和焦点，此时通话 AudioTrack 可能尚未释放；麦克风停止也可能早于通话下行停止。

修改后，通话音轨释放并退出焦点协调器、麦克风关闭、全部通话流结束后，才恢复原音频模式和重新申请音乐焦点。重新创建焦点请求会使旧回调失效；音乐音轨在其自身工作线程中暂停后继续播放，保留已缓冲 PCM。音频模式恢复失败时保留待恢复状态，允许后续关闭路径再次恢复，不提前把状态清掉。音轨工作线程若提前退出，挂断通知仍可完成恢复，不等待已经结束的线程再次回调；焦点释放或恢复异常写入诊断，不阻止资源清理。

这些代码修正不能证明用户现象的唯一原因。尚未复现真实车机的音频策略行为；如果问题仍存在，应结合通话结束的 mode/focus 日志和实际 routeDevice 继续判断。

## 其他值得借鉴的能力

1. **整车音频压低与恢复**：官方可以处理外部音乐源，现项目主要处理自身轨道；下一步需要核对 CarAudioManager 的车机接口与权限。
2. **原车用途与输入源匹配**：项目已有原车 usage/source 读取及占位值过滤，应在合并时保留；具体编号必须来自安装配置或车机 framework。
3. **原车服务与界面分离**：官方接收服务独立于界面，项目已有后台会话和 Surface 重挂接。后续可以继续核查休眠、唤醒、分屏重建和 USB 拔插，不应重复创建一套会话管理。

## G733 按键反馈与发布检查修正

用户随后提供车机型号显示为 G733 的照片：按键识别页面提示暂时无法连接车机，且所有功能都无法收到按键。

- 已确认该提示来自 `SteeringLogAccess.request`，其尝试连接车机自身的 `127.0.0.1:5555`，再授权应用读取系统日志。`UNAVAILABLE` 也涵盖连接不可达或不支持当前连接方式；它不能证明车机明确拒绝权限，也不能证明 iPhone 连接失败。
- 现有 OneOS 监听在进入识别时会尝试启动，不受 G636、FX11、KX11 的默认启用名单限制。仅把 G733 加入默认型号名单，无法证明能解决问题，因此没有据此套用其他车机的协议。
- `SteeringControlsActivity.kt`：授权按钮依据实际注册成功的按键监听或日志权限显示；不再仅凭型号隐藏。授权过程中保留当前识别提示，授权成功时重启正在使用的日志监听。
- `SteeringLogAccess.kt`：保存最近授权尝试的实际连接与授权结果，区分授权命令中断和明确未获授权。异常保留原因，不笼统归为权限被拒绝。
- `GeelySteeringWheelInputChannel.kt`、`CarPlayMediaKeys.kt`：记录原车服务绑定、事务失败及注册状态，并保留识别结束前的监听快照。技术内容只出现在已解锁的诊断或用户主动收集的报告中。
- 中英文文案：说明自动开启按键读取失败，并提供识别或收集日志的后续操作；不再把所有情况描述成车机不允许连接。
- G733 的具体接入阻断点仍需该车机的按键诊断或日志确认。这些修正完善状态判断与诊断，不等于已经完成 G733 实车适配。

用户提供的云端报告 `DiPlay-20261005-085556-952.txt` 确认为银河 L6、QTI G733、Android 11。收集时 `systemLogAccess=false`、`oneOs INACTIVE`、`logMonitor INACTIVE`；同期无线 CarPlay 会话正常，画面基本维持 30 fps。报告未保留按键服务绑定或本地授权连接的失败原因，不能据此判定服务缺失或车机明确拒绝授权；需更新后再次识别并收集包含失败状态的报告。

上次提交 `e69910d` 的 [Android checks](https://github.com/carlito12345/DiPlay/actions/runs/37246184712) 有一项现有检查未通过：放大分辨率应在用户选定的帧率下通过能力检查，而本轮提前降低帧率绕过了这个限制。`CarPlayHostActivity.kt` 已调整为先按选定帧率检查放大画布，再按最终尺寸选择播放帧率。保留动态屏幕与可用硬件回退，不套用官方模板的固定尺寸。本次未新增或本地运行测试；推送后由既有 Actions 重新执行检查和发布。

## 验证记录

- `git diff --check`：通过，仅有 Git 的 LF/CRLF 提示。
- 初始显示优化与自动导航用途修改，已两次通过本地 `:common:compileDebugKotlin`（分别用时 10 分 13 秒、3 分 51 秒）。
- 新增设备选择及 USB 通话恢复后，完整编译通过（6 分 10 秒），随后两次增量编译通过（1 分 36 秒、1 分 6 秒），覆盖 `e69910d` 的全部代码修改。
- 后续按键诊断修正的本地编译因主机内存不足中止（JVM metaspace 分配失败），没有获得编译结果；由 GitHub Actions 完成后续编译和发布检查。
- 本地未运行单元测试、Lint 或实车性能测量；GitHub 提交后的自动检查由既有 Actions 执行。
- 未验证真实头枕输出、实际持续帧率、温度变化或弱网恢复时间。

提交前另保存 `local-changes.patch` 和 `local-changes-manifest.json`，并包含在上述云端备份中。补丁包含 10 个已跟踪文件的修改及 3 个新增文件；清单记录基线提交和各文件摘要，便于恢复与合并时对照。提交后以 GitHub 上的源码与本记录为主要依据。

Android 输出缓冲处理依据：[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)。尺寸与帧率查询依据：[VideoCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities)。这些 API 说明不能代替实车性能测量。

设备选择依据：[AudioTrack.setPreferredDevice](https://developer.android.com/reference/android/media/AudioTrack#setPreferredDevice(android.media.AudioDeviceInfo))、[AudioDeviceInfo.getId](https://developer.android.com/reference/android/media/AudioDeviceInfo#getId())。preferred device 是请求，最终路由仍由车机策略决定。

## 下次合并上游时

- 对照上述基线和文件逐项保留修改，尤其是显示协商、解码器排序与导航通道偏好迁移。
- 重新检查窗口旋转、分屏、分辨率缩放和安全区域，避免导入官方固定 ScreenAttr。
- 保留导航输出枚举、设备编号数值框和试听；用户试听确认实际发声位置后，再决定是否需要独立头枕接口。
- 实车检查 USB 通话挂断后的音乐音量、音质和路由恢复，无需调整 iPhone 音量时才算现象解决；同时检查连续通话、拒接和关闭会话。
- 按当时的上游版本继续更新用户可感知的版本说明，保留本轮已提交的导航输出、画面适配及通话恢复修改。

## KX11 本地热点兼容版本

- 基于公开版本 `8f53b27`，独立分支 `local/kx11-hotspot-compat-20261005`；保留导航设备选择、数值框、头枕输出相关设置及 USB 通话恢复修改。
- 本地版本名 `0.2.12-kx11-hotspot-test`，APK 文件名 `DiPlay-KX11-0.2.12-hotspot-test.apk`；不修改公开版本号。
- 根据 KX11 设置 APK 中的实际调用，读取 ECARX `WifiAp`、`Wifi6Ap` 的状态、频段和客户端信息；与 Android 热点识别共同使用，关闭连接时释放本次建立的服务连接。
- 对 SDK 返回的客户端地址检查本机直连路由；只有确认对应本机地址和网段后，才接受桥接接口。继续保留 Android 热点及原有自动连接回退。
- Wifi6 热点启用时，已保存的热点名称和密码优先于另一套 Android 热点配置。技术信息只进入已有连接诊断记录，不新增普通页面的技术字段。
- 设置 APK 提供的是 SDK 调用依据，不能证明实车网络可直接访问；本版本仍需实车确认无线 CarPlay 能否建立连接。
- 使用既有 GitHub Actions 签名与 MFi 输入构建；签名和证书一致性在产物生成后单独核对。本次不新增或运行单元测试。
