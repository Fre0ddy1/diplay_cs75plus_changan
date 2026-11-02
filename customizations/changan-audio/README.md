# 长安音频适配

70版以68版为基础，替换67/68的逐按键蓝牙交接检查，不包含已停止采用的69版旧氛围灯接口试验。

## 连接期间关闭目标手机的蓝牙媒体音频

入口为**设置 → 连接 → CarPlay连接与音频**，默认开启。用户点击连接手机、控制器选定本次配对手机后，模块保存该手机原来的A2DP Sink优先级，将其设为关闭，并仅请求一次该手机的媒体连接断开。不关闭整个蓝牙，不改配对、通话、电话簿或其他设备。

实现使用Android 9蓝牙服务现有的Binder接口及正常权限检查。已离线核对本车蓝牙APK的`setPriority/getPriority/connect/okToConnect`，优先级0会阻止主动和被动媒体重连。本实现限定API28；服务、权限、原值或读回不明确时记录失败，不转用循环断连。

CarPlay断开、连接失败、关闭该选项或执行DiPlay退出时，恢复保存的原值。原先已经关闭的设备不会被自动打开；期间用户或系统已改成其他值时保留该改动。恢复失败保留记录，下次启动继续尝试。强杀或断电无法保证立即执行恢复。恢复媒体音频权限不等于强制建立蓝牙播放连接。

## 按键与资源分工

- 方向盘媒体控制仍由原有去重路由送入CarPlay，每次只发送原来的一个按键。不再调用蓝牙交接检查，不等待2秒检查或2.5秒兜底；不补发PLAY。
- Android焦点、通话保护与CarPlay资源归属仍需判断。移除的是按键前蓝牙检查，不是删除音频所有权规则。
- 通用层的`CarPlayMainAudioCoordinator`负责mainAudio的请求、手机确认、临时借用归还、超时和会话隔离。资源请求异步发送，只有有效`modesChanged`才确认归属；写出成功不等于获准。
- 手机通话、Siri、提示音会话与原车临时占用分开；重复资源通知不会重复抢焦点。

旧`TargetBluetoothHandoff`等实现和回归测试保留作历史参照，70版活动入口不创建它们。无线认证及RFCOMM引导交接保留；它们不再驱动模块断开HFP/PBAP/PAN。

更完整的链路、源码清单、依据和验证边界见[70版实现说明](../../docs/CS75PLUS-70-连接媒体音频与资源协商.md)。

## 依据与验证

- [Apple WWDC17无线CarPlay交接流程](https://developer.apple.com/videos/play/wwdc2017/717/)
- [Apple WWDC16媒体按键](https://developer.apple.com/videos/play/wwdc2016/722/)
- [Apple WWDC16资源管理](https://developer.apple.com/videos/play/wwdc2016/723/)
- [Android 9 A2DP Sink设置实现](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/packages/SettingsLib/src/com/android/settingslib/bluetooth/A2dpSinkProfile.java)：不能据此替代本车权限及界面验收。

实车需分别检查首次连接、原车仪表媒体卡、单次切歌、微信语音、导航混音、通话/Siri和断连重连。未经实车验证不得把候选实现表述为已解决全部音频路由场景。

文档由Codex（GPT-6.1 Sol）编写。
