# 长安音频适配

定制模块负责当前CarPlay手机的原车蓝牙音频交接、媒体按键策略和必要诊断。通用层只提供无线会话生命周期与显式播放准备回调。

## 无线交接

`WirelessAudioHandoffGate`同时等待本次手机会话的disableBluetooth、Wi-Fi内iAP2认证就绪与RFCOMM握手释放，再通知模块。目标地址来自本次实际选中的BluetoothDevice，不使用手机上报的其他标识猜测地址。新会话重新取得证明，旧会话的就绪和结束不能作用于新会话。

`AndroidTargetBluetoothLink`对当前目标的A2DP Sink、HFP Client、PBAP Client、PAN请求逐设备disconnect，并观察AVRCP Controller随媒体连接结束；各角色已断开且目标ACL确认未连接才报告交接完成。未绑定的可选角色只有在适配器确认其全局未连接时才可判目标未连接。其他活动角色或ACL状态未知不能当作完整交接。

不调用原车SDK忽略地址的disconnect入口，不移除配对、不改连接优先级、不写系统配置、不关闭整个蓝牙。AudioManager不在NORMAL、目标存在通话或通话状态未知时推迟交接。Android9 Java查询入口不可见时，通过已核对的原车只读服务查询目标连接状态与通话数量；保留系统权限检查，不解析电话号码，不发送通话控制。每次确认最多2秒、每个协议最多3次请求、每个会话自动请求总量最多20次，防止持续争抢。连接存续期间观察原车重连和通话事件；结束后注销监听并释放代理，由原车策略决定后续连接，不自动切蓝牙播放。

## 用户主动恢复

只对明确的CarPlay播放/切歌操作准备音频。凡操作预期继续播放，即使媒体流已活动也先检查当前交接，再发一次原本的媒体按键；已完成的交接立即返回，未完成的检查有期限。准备结果不是手机物理输出路由的确认。NEXT/PREV遇到短暂焦点丢失且蓝牙媒体交接未确认时，不再次抢焦点，仍只发原来的按键，不补PLAY。重复交付去重，延迟结果不能跨手机会话；暂停立即发送并取消等待中的旧请求，切换为外部按键或结束连接也会取消。

显式操作允许在没有媒体流时申请Android焦点；5秒仍未恢复媒体流会释放本次临时取得的焦点。播放元数据和重复资源通知不能触发焦点重抢，不循环发送PLAY/NEXT。

Android音轨就绪/数据流与手机实际输出路由、扬声器听感分别验证。本模块不会因微信/抖音退出就猜测用户意图并强制播放，也不更改媒体12、导航10或降音设置。

媒体按键开关只影响按键归属，不取消已认证的无线音频交接。67版规则、只读ABI依据及验证限制见[详细说明](../../docs/CS75PLUS-67-Apple音频与媒体显示.md)。

## 依据与验证

- [Apple WWDC17无线CarPlay交接流程](https://developer.apple.com/videos/play/wwdc2017/717/)
- [Apple WWDC16媒体按键](https://developer.apple.com/videos/play/wwdc2016/722/)
- [Apple WWDC16资源管理](https://developer.apple.com/videos/play/wwdc2016/723/)
- [Android 9公开源中的隐藏API允许列表](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/config/hiddenapi-light-greylist.txt)：可核对A2DP Sink、HFP Client及PAN的disconnect；不能据此替代本车验证。

实车需分别检查首次连接、原车仪表媒体卡、单次切歌、微信语音、导航混音、通话/Siri和断连重连。未经实车验证不得把候选实现表述为已解决全部音频路由场景。

文档由Codex（GPT-6.1 Sol）编写。
