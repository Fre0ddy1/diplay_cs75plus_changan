# 长安音频适配

定制模块负责当前CarPlay手机的原车蓝牙音频交接、媒体按键策略和必要诊断。通用层只提供无线会话生命周期与显式播放准备回调。

## 无线交接

`WirelessAudioHandoffGate`同时等待本次手机会话的disableBluetooth、Wi-Fi内iAP2认证就绪与RFCOMM握手释放，再通知模块。目标地址来自本次实际选中的BluetoothDevice，不使用手机上报的其他标识猜测地址。新会话重新取得证明，旧会话的就绪和结束不能作用于新会话。

`AndroidTargetBluetoothLink`只对当前目标的A2DP Sink请求逐设备disconnect，并观察A2DP与AVRCP Controller是否实际断开；不对HFP Client、PBAP Client或PAN请求断开。HFP只作可用时的通话检查。服务端接口已对照本车保存的系统APK核对权限；隐藏API访问与AVRCP是否跟随断开仍需实车验证。权限或接口不可用时记录失败，不修改隐藏API策略。

不调用原车SDK忽略地址的disconnect入口，不移除配对、不改连接优先级、不写系统配置、不关闭整个蓝牙。AudioManager不在NORMAL或可读的HFP通话列表非空时推迟媒体交接；ROM缺少可选的getCurrentCalls方法时，允许只处理A2DP，不据此断开电话协议。其他查询异常仍推迟处理。每次确认最多2秒、每个协议最多3次请求、每个会话自动请求总量最多20次，防止持续争抢。连接存续期间观察原车重连；结束后注销监听并释放代理，由原车策略决定后续连接，不自动切蓝牙播放。

## 用户主动恢复

只对明确的CarPlay播放/切歌操作准备音频。没有媒体流或Android焦点被短暂打断时先做一次有期限的蓝牙状态检查，再发一次原本的媒体按键；准备结果不是手机物理输出路由的确认。NEXT/PREV遇到短暂焦点丢失且蓝牙媒体交接未确认时，不再次抢焦点，仍只发原来的按键，不补PLAY。重复交付去重，延迟结果不能跨手机会话；用户暂停、切换为外部按键或结束连接会取消等待中的旧请求。

显式操作允许在没有媒体流时申请Android焦点；5秒仍未恢复媒体流会释放本次临时取得的焦点。播放元数据和重复资源通知不能触发焦点重抢，不循环发送PLAY/NEXT。

Android音轨就绪/数据流与手机实际输出路由、扬声器听感分别验证。本模块不会因微信/抖音退出就猜测用户意图并强制播放，也不更改媒体12、导航10或降音设置。

## 依据与验证

- [Apple WWDC17无线CarPlay交接流程](https://developer.apple.com/videos/play/wwdc2017/717/)
- [Apple WWDC16媒体按键](https://developer.apple.com/videos/play/wwdc2016/722/)
- [Apple WWDC16资源管理](https://developer.apple.com/videos/play/wwdc2016/723/)
- [Android 9公开源中的隐藏API允许列表](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/config/hiddenapi-light-greylist.txt)：可核对A2DP Sink、HFP Client及PAN的disconnect；不能据此替代本车验证。

实车需分别检查首次连接、原车仪表媒体卡、单次切歌、微信语音、导航混音、通话/Siri和断连重连。未经实车验证不得把候选实现表述为已解决全部音频路由场景。

文档由Codex（GPT-6.1 Sol）编写。
