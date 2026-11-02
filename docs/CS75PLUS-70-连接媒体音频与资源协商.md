# 70版：连接媒体音频与mainAudio资源协商

2026-11-02。基于68版提交`5f585642adc7859e37fc5279eecb3fa997600c71`，分支`codex/ca75plus-audio-policy-70`，APK版本70 / `0.2.22-ca75plus.28-audio.1`。69版停止采用，历史代码、安装与失败证据保留；70版不包含其旧VehicleSettingService音乐律动试验。

## 目标与事实边界

解决两个独立问题：连接时管理目标手机的蓝牙媒体音频，去掉逐按键蓝牙等待；补齐本接收端mainAudio资源请求与确认流程。关闭A2DP能减少蓝牙媒体与CarPlay并存竞争，不能消除原车收音机、通话、导航或Android焦点政策带来的占用。这里没有承诺修复全部手机应用自动恢复播放行为。

本版离线核验和构建不连接车机，未安装、未改车机设置。动作能否在本车权限下执行、能否解除用户观察到的延迟，仍需实车验收。

## 连接与恢复链路

```text
点击“连接手机” → CarPlayController选定本次BluetoothDevice
  → 通用连接生命周期回调 → changan-audio模块
  → 保存该设备原优先级 → A2DP Sink setPriority(0)并读回
  → 仅断开该设备已有媒体连接一次
  → 原无线认证、Wi-Fi CarPlay与RFCOMM引导继续运行

CarPlay断开 / 连接失败 / 选项关闭 / DiPlay退出
  → 读取恢复记录 → 当前仍为0时恢复原值并读回 → 清除记录
```

设置入口：**设置 → 连接 → CarPlay连接与音频 → 连接期间关闭目标手机的蓝牙媒体音频**，默认开启。仅在选定已配对目标后生效，不在打开设置页面时修改蓝牙。开关不改变方向盘按键路由设置。

原值0不写入、不强制开启；100或1000原样保存和恢复；其他值或读取失败不修改。先落盘再写策略。其他来源已经修改原值时保留其结果。恢复失败保留记录并阻止覆盖成另一部手机的记录。进程强杀、掉电期间无法运行恢复，下次启动再试；正常退出等待有界。恢复的是原权限策略，不主动选择蓝牙为iPhone的播放输出。

原HFP/PBAP/PAN逐设备断开、ACL轮询及逐按键交接均退出活动链路。保留旧实现及测试以便回查，70版初始化不再创建旧交接器。

## 按键和资源链路

```text
方向盘 / CarPlay媒体控制 → 原有按键去重与通话保护
  ├─ 原按键一次 → CarPlay HID按下/释放，不等蓝牙检查或资源ACK
  └─ 需要播放且存在本机资源占用 → 异步changeModes

原车Android焦点永久/临时丢失 → mainAudio TAKE / BORROW
临时焦点恢复 → UNBORROW；用户主动恢复媒体 → 释放本机借用/占有
  → 加密event通道POST command → 手机modesChanged确认
  → 更新mainAudio归属、媒体PCM允许状态与本地焦点
```

只处理resourceID=2的mainAudio；不改主屏资源，不猜测手机appState。请求写出或RTSP响应不是资源授权。手机主动授予归属同样有效；缺失、重复行、非法类型或未知归属不会被当作PHONE。连接早期确认会缓存，避免UI或控制器较晚就绪时丢失。队列有界，超时记录失败，不自动重放按键或循环抢占。

每条手机会话独立维护资源和通话/Siri/提示音状态。手机自己的音频流引起本地焦点变化时，不误判为原车向手机借用音频。旧会话回调不能更新新会话。归还确认允许恢复焦点，但重复PHONE通知不重复申请焦点。按键后5秒无媒体流只清理临时焦点，不自动发送PLAY；该计时不阻塞按键。

## 修改文件与职责

| 文件 | 作用 |
|---|---|
| `customizations/changan-audio/.../MediaAudioPolicyLease.kt` | 连接持有、原值保存、恢复、失败与退出边界 |
| `.../AndroidA2dpMediaPolicyPort.kt` | API28目标设备Binder适配及权限、身份、读回核对 |
| `.../SharedMediaPolicyJournal.kt` | 应用私有恢复记录 |
| `.../ChanganAudioCustomization.kt`、模块资源/Manifest | 新连接开关、初始化与退出接入 |
| `customizations/api/.../VehicleCustomizations.kt` | 通用连接起止和退出准备回调，不包含车型协议 |
| `shared/.../orchestration/CarPlayController.kt` | 选定设备、连接生命周期、资源协调器与会话隔离 |
| `shared/.../airplay/CarPlayMainAudioCoordinator.kt` | mainAudio请求/确认/借用/归还状态机 |
| `.../CarPlayAudioResources.kt` | 严格解析手机资源归属 |
| `.../AirPlaySession.kt` | 加密事件命令队列、早期确认缓存、会话音频标记 |
| `.../CarPlayMediaEngine.kt` | 音频流启动/结束标记，区分手机与原车占用 |
| `common/.../CarPlayMediaKeys.kt` | 取消按键前蓝牙等待，保留单次按键与焦点规则 |
| `.../CarPlayHostActivity.kt` | 把确认后的资源状态应用到音频输出和媒体控制 |
| `.../DiPlayAppExit.kt` | 正常退出等待模块恢复原策略 |
| 相应模块测试、`scripts/check_customization_integration.py` | 状态转换、协议序列、退出/恢复、按键和集成回归 |
| `mobile/build.gradle.kts` | 70版版本标记 |

HUD、仪表投屏、媒体卡模块与`AndroidMediaSink.kt`保持68版代码。音量流号、采样率、PCM增益和UI设计未随本次调整。

## 依据与核验

- [Apple WWDC16资源管理](https://developer.apple.com/videos/play/wwdc2016/723/)：资源分take与borrow，请求通过changeModes，最终归属由modesChanged通知。
- [Apple WWDC16媒体控制](https://developer.apple.com/videos/play/wwdc2016/722/)与[WWDC17无线CarPlay](https://developer.apple.com/videos/play/wwdc2017/717/)：媒体控制与无线音视频/蓝牙引导分工。
- [AOSP Android9 A2dpSinkProfile](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/packages/SettingsLib/src/com/android/settingslib/bluetooth/A2dpSinkProfile.java)与[BluetoothA2dpSink](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/core/java/android/bluetooth/BluetoothA2dpSink.java)：媒体开关使用优先级；公开包装只接受0/100。
- 本车导出`com.android.bluetooth.apk` SHA256：`614d335dae7052b005acb605790116c01508cb92ef379a0717ee3a036dc31311`。离线方法证据表明setPriority同步持久化原值，connect及状态机okToConnect均拒绝0。通过原服务正常权限检查恢复1000，未绕过权限或更改系统APK。
- Binder接口`android.bluetooth.IBluetoothA2dpSink`的disconnect=2、setPriority=7、getPriority=8，按API28 Stub与本车服务分别核验；不是车控/音频路由编号。
- Apple公开演讲未公布这些传输枚举的数字。TAKE=1、UNTAKE=2、BORROW=3、UNBORROW=4另以[CarPlay接收端兼容实现](https://github.com/maxafanasiev/mib2q-carplay-wireless-cockpit/blob/main/hook/mainmirror/main_mirror.c)的ABI注释交叉核对；只参考协议事实，未复制其实现代码。请求优先级和约束沿用本接收端既有/info值100。

因此应称“按Apple公开资源管理规则补齐本实现的mainAudio闭环”。这不等于持有完整MFi私有规范、通过认证，或证明所有原厂音源/通话场景已符合规范。

## 验证与待测

本轮检查连接目标、原值0/100/1000、恢复失败、进程恢复、退出期限、旧回调、单次按键即发、焦点回归、加密事件命令、早期确认和手机音频会话隔离。395项定向测试通过（失败/错误/跳过均0），应用构建通过，Lint为0错误、19条警告；集成检查和公开树检查通过。精确结果随签名包提供。测试中原来“等待交接才送按键”的断言改为用户本轮要求的“原按键立即送一次”；原68测试文本另外保存在本地证据目录。旧媒体卡测试直接写所有者字段的条件改为通过正式资源状态入口建立，原断言保留。

未选入本轮的既有失败`ClusterMapPresentationTest.unrelatedPresentationDisplayIsNotUsedForTheCluster`、`NowPlayingArtworkQueueTest.emptyArtworkCanClearThePublishedImage`保留，不能宣称全仓库测试通过。

实车优先验收：开关关闭的对照；打开后目标媒体音频关闭、通话和连接仍正常；连续单次切歌；微信语音/原车音源临时占用后恢复；断开及退出精确恢复原值；原先已关闭的设备保持关闭；异常断电后的下一次启动恢复。安装和首次执行新策略需当轮授权，不把本地通过当作实车通过。

文档由Codex编写。
