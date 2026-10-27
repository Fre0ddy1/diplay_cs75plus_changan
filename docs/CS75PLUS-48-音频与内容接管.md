# CS75PLUS 48版修复候选

版本：`0.2.22-ca75plus.6` / 48，基于47提交 `8c91ea36f7fdcd63aef56a82a6f2fbbfe2dc84aa`。仅更新0.2.22定制支线；master与0.2.21维护线不变。48尚未实车验收，正式发行仍为46。

## 本次证据与边界

早间安装的是45，不是47。2026-10-27本地保存的应用日志覆盖05:44:49—06:04:39，启动片段和中断片段均可回查；现存main日志从06:07:36开始，故障之前的完整蓝牙/AudioFlinger事件已不在缓冲区。以下区分日志事实、静态代码与用户反馈，不将手机输出路由的猜测写成已证实。

- 首次连接：05:44:58来源握手成功，05:45:00会话active，05:45:01才解码出视频。用户确认首次全屏显示的是原厂地图。47对该时序仍可能误将视频之前的握手视为当前画面接管完成；未修改47的离线反例已经复现。
- 音频：05:52:21手机TEARDOWN了102音乐流，直至05:59:31重连才重新建立。05:55—05:57多次方向盘事件被交给蓝牙。用户确认失声与该时段相符，关闭蓝牙优先并重连后恢复。导航/视频继续不代表音乐流存在。
- 日志没有证实这次失声必然是手机转到A2DP。CarPlay资源状态、Android焦点、音轨存在、实际可听声音分别判断；不能把歌曲元数据更新或Android焦点成功当成手机输出路由确认。

## 公开Apple资料与旧版的关系

[WWDC2016 Session 722](https://developer.apple.com/videos/play/wwdc2016/722/)说明无线CarPlay在初始蓝牙发现/建立之后通过Wi-Fi承载音视频和控制，并展示车端媒体按键接入CarPlay的方式。[Session 723](https://developer.apple.com/videos/play/wwdc2016/723/)说明资源请求与实际资源分配不同，`modesChanged`反映分配状态，导航alternate audio可以独立混音。[WWDC2023音频说明](https://developer.apple.com/videos/play/wwdc2023/10150/)继续区分main、alternate和其他音频类型。

0.2.21旧维护线已经通过CarPlay媒体控制指令处理按键，音乐播放时创建Android MediaSession和媒体焦点，暂停后保留媒体会话。该方向与公开架构一致，但不等同于完整实现Apple资源管理。旧版窗口直接发送，MediaSession内部单独250ms去重，两个入口未共享去重；车机独立AVRCP也不经过这些入口。

48保留CarPlay控制方向，替换47的蓝牙反馈等待/超时补发实验。公开资料并未要求用蓝牙AVRCP接管CarPlay切歌。本版没有猜测或新增`changeModes`参数，也不声称通过完整MFi规范认证。

## 音频实现

```text
方向盘Android事件/MediaSession
    → 共用WheelMediaKeyRouter（相同指令250ms合并）
    → CarPlay媒体控制指令 → iPhone

iPhone实际音乐流 → AndroidMediaSink → 原有媒体音道
iPhone导航语音   → AndroidMediaSink → 原有导航音道/混音
modesChanged(mainAudio) → 媒体播放许可 + MediaSession焦点资格
```

1. 新增独立`changan-audio`模块和私有初始化provider，提供“CarPlay媒体控制（推荐）”，首次默认开启。旧蓝牙偏好保留在原文件，但不自动使新策略进入兼容模式；新开关关闭才明确选用外部蓝牙处理。该选项不重写媒体12、导航10及用户保存的音道。
2. 默认直接发送CarPlay媒体键；窗口和MediaSession共用250ms去重，按下/抬起只处理一次。兼容模式只让出硬件键，不再等待歌曲反馈后补发CarPlay指令。Android媒体面板上的明确播放/暂停指令仍通过CarPlay发送。
3. 从现有接收协议解析`resourceID=2`的有效资源行，跟踪该接收端已有的entity身份。无效、重复、未知或不完整行不改变状态；连接初期未知状态保持兼容行为。原车占用或未分配时让出媒体焦点和媒体音轨增益，Phone拥有时仅在音乐流实际存在且无通话竞争时恢复。
4. 导航/alternate不受mainAudio媒体静音控制，保留原独立提示与duck行为。通话和助手原有focus机制保留。媒体焦点由MediaSession统一管理，Sink不再为同一音乐重复申请第二份媒体焦点。
5. 歌名、播放状态、封面更新只更新元数据，不作为路由成功判据。音乐流停止不自动播放、切歌或重连；明确用户操作后5秒仍无音乐流时记录诊断。断连释放状态，过期音流/焦点回调不得复活旧会话。

原车可能独立发送AVRCP，因此本地去重不保证消除原车侧所有重复。现有原厂蓝牙SDK接口只确认有播放控制、状态与静音等方法，尚未找到可独立关闭硬件媒体键的可靠入口；本版不通过停蓝牙、断开音频profile或静音来冒充按键隔离。下一轮重点验证一次按键是否跳两首、播放暂停、电话、导航混音及失声。

## 仪表首次内容接管

`contentReadyWindow`只在当前选定视频实际有帧之后由通用入口标记。执行来源握手之前记录是否已经有帧；只有握手开始前已就绪且窗口仍相同，成功结果才可确认本地内容接管。启动阶段的视频前握手可以完成通道/布局准备，但不能取消视频就绪后的有限接管任务。

保持47的最多两次内容恢复和既有布局预算；同窗口已经执行过有效的帧后握手时可复用，避免反复抢源。窗口/会话/服务失效时清除标记。日志和测试证明执行时序，原车接口仍不提供可验证的最终像素来源，实体仪表必须复验。

## HUD长路名

用户反馈约15:24完整路名“北横通道 长宁路”，实体HUD显示“北横通道 长”。本地复制日志在15:24:05记录了完整输入，8个Unicode字符、22个UTF-8字节；原有发送端上限是64字节，并未触发。所见短文本恰为16字节，只能作为原车长度/宽度限制的线索，不能确认硬件上限就是16。

48不缩短、不重排、不删空格，也不提高既有64字节保护。新增路名变化时的实际输出文本与UTF-8字节数诊断；回归确认这条22字节路名完整保留，以及超过64字节时不切断Unicode字符。下次实车需对照发送日志、interactive/车辆属性日志和用户目视，才能定位剩余截断发生处。

## 修改文件与职责

| 文件/模块 | 作用 |
| --- | --- |
| `customizations/changan-audio/` | 独立保存策略、默认值、设置说明和注册，不引入车厂音道编号 |
| `customizations/api/.../MediaControlCustomization.kt`、`VehicleCustomizations.kt` | 通用媒体键目标契约和隔离分发 |
| `common/.../AirPlayPersistence.kt`、`DiPlayActivity.kt` | 读取模块策略，展示新开关并避免同时出现旧开关 |
| `common/.../WheelMediaKeyRouter.kt` | 两个明确目标和Android入口去重，删除反馈补发 |
| `common/.../CarPlayMediaKeys.kt` | 实际音流/资源/焦点分离，媒体会话、过期回调隔离和诊断 |
| `shared/.../airplay/CarPlayAudioResources.kt` | 有效资源增量解析，独立于Android音道编号 |
| `shared/.../media/AndroidMediaSink.kt` | 仅媒体的资源许可增益、保持导航混音，消除重复媒体focus请求 |
| `common/.../CarPlayHostActivity.kt` | 接入资源回调、实际音流及统一按键入口；修正旧误导日志 |
| `common/.../InCallClusterBridge.kt` | 区分视频前与视频后来源握手，清理失效标记 |
| `customizations/changan-hud/.../ChanganHudBridge.kt` | 补充路名输出诊断，HUD协议和字符处理不变 |
| `settings.gradle.kts`、`common/build.gradle.kts` | 引入独立音频策略模块 |
| `mobile/build.gradle.kts` | 版本48，包名、应用名与签名身份不变 |
| `scripts/check_customization_integration.py` | 保护新模块provider和上游接入点 |

新增资源解析、媒体增益、策略默认值、长路名测试；扩展首次接管、会话/焦点生命周期与按键测试。具体结果见[验证记录](../customizations/VALIDATION.md)。没有将车机日志、认证输入、签名素材或APK放入Git，也没有自动安装到车机。
