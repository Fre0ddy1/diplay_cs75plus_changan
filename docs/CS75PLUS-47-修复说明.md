# CS75PLUS 47版修复候选

版本：`0.2.22-ca75plus.5` / 47。基于46版`f7aaadc902bd9ecb3867d4f1d0d64becd88d861c`；只修改0.2.22定制支线，旧0.2.21维护线和master不变。本版需要实车验收，不把本地测试描述成已经解决现场故障。

## 首次全屏仍显示原厂高德

原车`onRequestLVDS(3)`是全屏请求，`onIPNavigateStatus(3)`是全局通道状态，都不包含画面所属应用。旧实现采用现有通道后会结束布局恢复，未独立保证当前CarPlay窗口已执行来源接管。45版的新回调序号不能补足来源身份；46沿用该逻辑。

本版把**布局恢复**与**内容接管请求**分开处理：

1. 当前投影窗口所选TextureView实际收到视频帧后，标记本地视频就绪。会话结束、Surface销毁或切换所选视频流时重置此标记。
2. Host的现有watcher通过通用接口上报“窗口有视频”。只有自动飞屏开启、交互服务Binder存活且观察者仍属于该窗口时才进入接管策略。
3. `ContentHandoffRecovery`等待1500ms，调用唯一的厂商执行入口。全局全屏回调不能取消这项任务。
4. 该入口执行既有来源广播序列及Binder `tx2[1]`；原来已全屏时不先发小屏模式。只有同一窗口确实执行过来源握手时才静默复用，不从持久化状态推导来源归属。
5. 发送失败或无结果最多尝试两次，失败重试间隔5秒；窗口更换、手动接管、会话结束及服务断开会取消或隔离旧任务。服务尚未绑定时继续等待就绪，不提前消耗接管预算。

`tx2`成功与本地视频帧是本应用执行过接管操作的证据，仍不是实体仪表像素的确认。原车接口没有可靠的来源身份反馈；原厂窗口稍后覆盖等情况仍要在车上复验。既有布局恢复与内容恢复各自有预算，不能把本地“已发送”日志读成“实体屏确认”。

## 切歌后无声与重复响应

用户反馈：旧维护线方向盘操作会出现蓝牙及CarPlay两路响应；新版开启“方向盘媒体键交给车机蓝牙”后有一次切歌失声，CarPlay界面提示检查音频。没有这次故障的现场日志，提示的具体生成方也未证实。

静态代码确认，新版开关开启时`CarPlayMediaKeys.start()`直接返回，不创建MediaSession、不申请媒体焦点；但接收端仍声明和播放CarPlay音频。这确有控制归属与音频源脱节的风险，不能据此断言用户那次失声只有这一种原因。

本版沿用CarPlay播放音乐及导航的方案，并进行以下修正：

- 两种按键模式都保留CarPlay媒体会话和媒体焦点。手机会话断开后释放焦点、取消待发送按键；控制器保留等待重连时也执行清理。
- 窗口与MediaSession共用`WheelMediaKeyRouter`，相同方向按键在250ms内合并。不会用停止音频、关闭蓝牙或撤销焦点来去重。
- 收到近期按键对应的外部切歌反馈后，若CarPlay焦点已丢失则重新申请；普通播放进度不会触发争抢焦点，通话期间也不会抢回。
- 开启“方向盘按键去重（蓝牙优先）”时，最多等待1秒的歌曲或播放状态变化；有反馈就取消本地重复发送，否则向CarPlay补发一次。普通播放进度更新和同一歌曲的封面更新不计作切歌成功。
- 关闭该选项时立即发送CarPlay媒体键，仍合并Android内部重复入口。此模式不能单独消除车机独立发送到iPhone的AVRCP事件。
- 等待期间断开会话、开始通话或进入车机视频播放时，不再补发过期的CarPlay按键。Siri和导航音量键原有入口保留。

外部AVRCP事件不会直接经过DiPlay，因此反馈超过1秒、相邻歌曲元数据相同或用户同时操作手机等情况仍可能误判或重复。该方案是有边界的反馈去重，不能承诺任意车机、任意音乐应用都严格执行一次。开启后的操作可能延迟约1秒，需重点实测。

## 媒体与导航音道

该开关的调用点没有修改`mediaChannel`、`navigationChannel`、导航音量键路由或`disableAudioOutput`；后者仍为false。关闭开关不等同于把导航和媒体合成同一音道。默认保存值仍为媒体12、导航10；编号是继承设置，不能当作本车物理输出总线的编号。

本版不修改`AudioChannelMapper`与`AndroidMediaSink`的音轨映射。实际输出还取决于手机发来的音频类型、音频映射模式及原车服务，应在下一轮分别播放音乐和导航语音观察usage/bus与实体音量，不能仅从开关名判断。

## 文件职责

| 文件 | 作用 |
| --- | --- |
| `customizations/api/.../ProjectionCustomization.kt` | 增加通用内容接管控制契约，不含车辆协议 |
| `customizations/api/.../VehicleCustomizations.kt` | 分发窗口内容就绪和丢失事件 |
| `customizations/changan-projection/.../ContentHandoffRecovery.kt` | 独立的有限内容接管策略与窗口代次 |
| `customizations/changan-projection/.../ChanganProjectionCustomization.kt` | 接入生命周期、设置、手动取消及服务失效 |
| `common/.../ClusterMapPresentation.kt` | 报告所选视频流的本地帧就绪，失效时重置 |
| `common/.../CarPlayHostActivity.kt` | 少量通用调用点：视频就绪、窗口销毁、会话断开、共用媒体键入口 |
| `common/.../InCallClusterBridge.kt` | 复用既有厂商协议，执行当前窗口接管与旧任务失效；仍是唯一厂商Binder入口 |
| `common/.../WheelMediaKeyRouter.kt` | 通用Android入口合并与外部播放反馈去重，不含长安包名或协议 |
| `common/.../CarPlayMediaKeys.kt` | 保留音频焦点、会话清理、接入统一媒体键路由 |
| `common/.../AirPlayPersistence.kt`、中英文strings | 更正开关说明，保留原偏好键与保存值 |
| `mobile/build.gradle.kts` | 版本递增为47，包名、应用名、构建类型保持一致 |
| `scripts/check_customization_integration.py` | 防止后续上游合并丢失新接入点 |

测试文件：`ContentHandoffRecoveryTest`、`InCallContentHandoffTest`、`WheelMediaKeyRouterTest`、`CarPlayMediaFocusOwnershipTest`。另运行既有HUD、仪表布局/重连/卡片选择、媒体回调与音道映射回归及lint。完整结果随本地交付报告保存，不提交原始车机日志、认证素材、签名输入或APK到Git。

## 下一轮实车验收

驻车后复验首次连接：原厂高德后台、不导航、自动巡航关闭，记录ADB状态，观察仪表第一次是否直接显示CarPlay。随后验证断开重连、后台、退出导航清HUD及两种视频内容模式。

音频分别验证去重开/关，单次下一首、上一首、播放暂停及连续两次操作；记录有无失声、提示、跳两首和响应延迟。音乐与导航语音分别记录路由，结束导航、断开CarPlay后确认媒体源释放。车机任何安装或设置操作仍需单独获得用户确认。
