# 公版0.2.14至0.2.22逐项清单及72版处理

2026-11-05重新核验官方9个发行页。最新公版仍是v0.2.22 / 28d6002ba00320f0ba745212160b08759e4f60b9。以下285条为发行正文项目，包含新增、修复、优化和开发事项，不是285个独立新功能；另补0.2.15摘要独有的4组。

**纠正历史判断：60/61成品已作废，71并不继承其修复提交。本次在71基线上重新移植、解决冲突并重新测试，不能把旧包通过当成72通过。**

“本轮吸收相关修复”只指明列出的PR部分；涉及多个模块的整条发行说明不因此视为全部完成。未修改71基线UI、HUD协议、音道路由、媒体按键及媒体卡发布协议；高德/飞屏默认值按用户本次要求调整。


## v0.2.14

[官方发行页](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.14)

|编号|新增、优化或修复|72版处理|来源|
|---|---|---|---|
|v0.2.14-01|重做设置总览、搜索和分类，适应横竖屏、分屏、键盘及阿拉伯语布局；标明需要重连的改动。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#369](https://github.com/shihabal3amri/DiPlay/pull/369)|
|v0.2.14-02|界面文字和控件支持自动、100%、125%、150%、200%缩放，与CarPlay画面尺寸分开。|可选或依赖新增模块；本轮不擅自启用|[#378](https://github.com/shihabal3amri/DiPlay/pull/378)|
|v0.2.14-03|自动连接可选上次、无线或USB；已连接时点击桌面图标返回CarPlay。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#373](https://github.com/shihabal3amri/DiPlay/pull/373) [#376](https://github.com/shihabal3amri/DiPlay/pull/376)|
|v0.2.14-04|按车机时间安排CarPlay日间/夜间模式，支持跨午夜。|可选或依赖新增模块；本轮不擅自启用|[#365](https://github.com/shihabal3amri/DiPlay/pull/365)|
|v0.2.14-05|自定义车标选图增加文件浏览器及无系统选择器时的回退。|可选或依赖新增模块；本轮不擅自启用|[#364](https://github.com/shihabal3amri/DiPlay/pull/364) [#377](https://github.com/shihabal3amri/DiPlay/pull/377)|
|v0.2.14-06|修复特定USB诊断帧尾、被拒绝的大块读取、蓝牙接收队列和RFCOMM诊断。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#362](https://github.com/shihabal3amri/DiPlay/pull/362)|
|v0.2.14-07|车机热点同时发布可用IPv4和带作用域IPv6；未建立隧道时保留蓝牙控制与歌曲信息。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#394](https://github.com/shihabal3amri/DiPlay/pull/394) [#396](https://github.com/shihabal3amri/DiPlay/pull/396)|
|v0.2.14-08|无硬件加速时改用SurfaceView并校正触摸；优先平台视频解密，失败回退。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#362](https://github.com/shihabal3amri/DiPlay/pull/362) [#332](https://github.com/shihabal3amri/DiPlay/pull/332)|
|v0.2.14-09|实验性平滑视频：帧节奏控制、后台返回和解码器释放优化；可能增加触摸延迟。|可选或依赖新增模块；本轮不擅自启用|[#367](https://github.com/shihabal3amri/DiPlay/pull/367)|
|v0.2.14-10|旋转/分屏计入系统栏；非方形专辑图在仪表方形区域保持比例。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#389](https://github.com/shihabal3amri/DiPlay/pull/389) [#375](https://github.com/shihabal3amri/DiPlay/pull/375)|
|v0.2.14-11|方向盘按键唤醒Siri，通话和未连接时保留原车动作；麦克风源被拒时回退。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#360](https://github.com/shihabal3amri/DiPlay/pull/360) [#371](https://github.com/shihabal3amri/DiPlay/pull/371)|
|v0.2.14-12|可选音频焦点处理：临时失焦静音，重新获焦恢复。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#339](https://github.com/shihabal3amri/DiPlay/pull/339)|
|v0.2.14-13|BYD仪表通话观察器、方向盘通话键和DiLink 3通话静音修复。|不直接移植：平台/车型接口不适用|[#370](https://github.com/shihabal3amri/DiPlay/pull/370)|
|v0.2.14-14|实验性软件通话回声消除及人声清晰度，失败保留平台路径。|可选或依赖新增模块；本轮不擅自启用|[#370](https://github.com/shihabal3amri/DiPlay/pull/370)|
|v0.2.14-15|识别特定DiLink 3的1280×480仪表表面，不代表通用仪表支持。|不直接移植：平台/车型接口不适用|[#388](https://github.com/shihabal3amri/DiPlay/pull/388)|

## v0.2.15

[官方发行页](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.15)

摘要另有4组：Android7.1兼容（#407，保留已有兼容）；BYD首次引导（#416，不直接移植）；主题/布局/语言（#420/#422，保留本车UI）；手动更新与校验（#413，须定制发行源与签名适配，未加入）。

|编号|新增、优化或修复|72版处理|来源|
|---|---|---|---|
|v0.2.15-01|音乐缓冲耗尽后重新预缓冲，修正PCM计数，避免暂停的AudioTrack阻塞补充。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#401](https://github.com/shihabal3amri/DiPlay/pull/401)|
|v0.2.15-02|为主屏解码器设置目标帧率提示，不支持时回退。|可选或依赖新增模块；本轮不擅自启用|[#417](https://github.com/shihabal3amri/DiPlay/pull/417)|
|v0.2.15-03|Wi-Fi Direct增加自动5GHz、自动2.4GHz选项。|可选或依赖新增模块；本轮不擅自启用|[#432](https://github.com/shihabal3amri/DiPlay/pull/432)|
|v0.2.15-04|指定iPhone蓝牙重连时可自动打开DiPlay，默认关闭，受系统后台启动限制。|可选或依赖新增模块；本轮不擅自启用|[#439](https://github.com/shihabal3amri/DiPlay/pull/439)|
|v0.2.15-05|实验性整段CarPlay音频改走原车蓝牙；与我们保留CarPlay音频的方向不同。|暂缓：保留本车保护，需要单独兼容性验证|[#432](https://github.com/shihabal3amri/DiPlay/pull/432)|
|v0.2.15-06|方形画布旋转时上报屏幕长边的真实物理宽度。|可选或依赖新增模块；本轮不擅自启用|[#404](https://github.com/shihabal3amri/DiPlay/pull/404)|
|v0.2.15-07|自绘仪表转向卡的位置调整细化至1%。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#440](https://github.com/shihabal3amri/DiPlay/pull/440)|
|v0.2.15-08|小窗车标布局可按前台应用自动变化；通过ADB/使用权限探测，模式变化会重连。|不直接移植：平台/车型接口不适用|[#438](https://github.com/shihabal3amri/DiPlay/pull/438)|
|v0.2.15-09|可选本地ADB修复自身开机启动权限，不保证绕过车厂后台管理。|暂缓：保留本车保护，需要单独兼容性验证|[#441](https://github.com/shihabal3amri/DiPlay/pull/441)|
|v0.2.15-10|修正乌克兰语的多窗口、仪表措辞。|可选或依赖新增模块；本轮不擅自启用|[#399](https://github.com/shihabal3amri/DiPlay/pull/399)|

## v0.2.16

[官方发行页](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.16)

|编号|新增、优化或修复|72版处理|来源|
|---|---|---|---|
|v0.2.16-01|增加无系统Opus编码器时的软件编码与编码器诊断，面向Siri和无线通话。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#468](https://github.com/shihabal3amri/DiPlay/pull/468)|
|v0.2.16-02|回声消除参考音频连续化，降噪顺序调整为消回声之后，仍属实验性。|暂缓：保留本车保护，需要单独兼容性验证|[#421](https://github.com/shihabal3amri/DiPlay/pull/421)|
|v0.2.16-03|实验性缓冲音乐在预填充后控制读取节奏，减少与实时音视频争用。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#484](https://github.com/shihabal3amri/DiPlay/pull/484)|
|v0.2.16-04|导出通信模式切换和被丢弃的焦点回调，诊断失败不影响主流程。|可选或依赖新增模块；本轮不擅自启用|[#499](https://github.com/shihabal3amri/DiPlay/pull/499)|
|v0.2.16-05|修改自动让出连接时显示需要重连的提示。|可选或依赖新增模块；本轮不擅自启用|[#482](https://github.com/shihabal3amri/DiPlay/pull/482)|
|v0.2.16-06|USB NCM包边界填充、Android 8读取上限、大请求失败逐级缩小至2KiB。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#478](https://github.com/shihabal3amri/DiPlay/pull/478) [#495](https://github.com/shihabal3amri/DiPlay/pull/495)|
|v0.2.16-07|首次AirPlay连接超时后尝试另一地址族，短暂等待可用IPv6。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#474](https://github.com/shihabal3amri/DiPlay/pull/474)|
|v0.2.16-08|Android 17无线连接增加本地网络权限请求。|可选或依赖新增模块；本轮不擅自启用|[#465](https://github.com/shihabal3amri/DiPlay/pull/465)|
|v0.2.16-09|缺失VPN授权页时不崩溃；增加WPA3/混合安全模式、保留安全设置和识别拒绝诊断。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#517](https://github.com/shihabal3amri/DiPlay/pull/517)|
|v0.2.16-10|窗口未变化或取消未修改的设置时保留健康连接，减少无意义重连。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#501](https://github.com/shihabal3amri/DiPlay/pull/501)|
|v0.2.16-11|修复DiLink 3无线启动与断开竞争时Wi-Fi扫描暂停未释放。|不直接移植：平台/车型接口不适用||
|v0.2.16-12|低延迟解码、直接Surface输出和FPS统计；前两项默认关闭，直接输出不应用画面调节。|暂缓：保留本车保护，需要单独兼容性验证|[#496](https://github.com/shihabal3amri/DiPlay/pull/496)|
|v0.2.16-13|平滑视频优先硬件低延迟H.264，向高通解码器申请按解码顺序输出。|暂缓：保留本车保护，需要单独兼容性验证|[#456](https://github.com/shihabal3amri/DiPlay/pull/456)|
|v0.2.16-14|视频积压恢复保留短暂突发、持续积压才恢复；阻塞音频写入前释放解码缓冲。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#347](https://github.com/shihabal3amri/DiPlay/pull/347)|
|v0.2.16-15|握手时声明各显示器的自动昼夜外观，再接收运行时昼夜更新。|可选或依赖新增模块；本轮不擅自启用|[#346](https://github.com/shihabal3amri/DiPlay/pull/346)|
|v0.2.16-16|侧栏支持拖动三种尺寸和边缘拉出；Android 12模糊，BYD可读胎压胎温。|不直接移植：平台/车型接口不适用|[#480](https://github.com/shihabal3amri/DiPlay/pull/480)|
|v0.2.16-17|设置内联搜索，下拉结果、回车打开、返回清空。|可选或依赖新增模块；本轮不擅自启用|[#450](https://github.com/shihabal3amri/DiPlay/pull/450)|
|v0.2.16-18|退出快捷设置或打开完整设置前，确认是否丢弃尚未保存的修改。|可选或依赖新增模块；本轮不擅自启用|[#500](https://github.com/shihabal3amri/DiPlay/pull/500)|
|v0.2.16-19|可以关闭CarPlay下拉快捷菜单手势。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#487](https://github.com/shihabal3amri/DiPlay/pull/487)|
|v0.2.16-20|优化设置留白、按钮对齐和快捷菜单宽度，诊断/高级进入总览。|可选或依赖新增模块；本轮不擅自启用|[#486](https://github.com/shihabal3amri/DiPlay/pull/486)|
|v0.2.16-21|BYD全屏/小窗车标支持1%滑块及旧值迁移。|不直接移植：平台/车型接口不适用|[#494](https://github.com/shihabal3amri/DiPlay/pull/494)|
|v0.2.16-22|自绘转向卡增加独立的小窗位置、大小、透明度和昼夜设置。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#493](https://github.com/shihabal3amri/DiPlay/pull/493)|
|v0.2.16-23|仪表等待画面跟随应用主题并显示加载动画。|可选或依赖新增模块；本轮不擅自启用|[#481](https://github.com/shihabal3amri/DiPlay/pull/481)|
|v0.2.16-24|DiLink 3简易导航按高德格式发送预计到达时间。|不直接移植：平台/车型接口不适用|[#458](https://github.com/shihabal3amri/DiPlay/pull/458)|
|v0.2.16-25|新增2023唐DM-i Platform 21仪表任务路由，默认关闭。|不直接移植：平台/车型接口不适用|[#348](https://github.com/shihabal3amri/DiPlay/pull/348)|
|v0.2.16-26|方向盘音量键调整导航播报音量，默认关闭。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#344](https://github.com/shihabal3amri/DiPlay/pull/344)|
|v0.2.16-27|CarPlay期间延迟暂停整车蓝牙、结束后恢复，需要ADB；不作为本车默认方案。|暂缓：保留本车保护，需要单独兼容性验证|[#307](https://github.com/shihabal3amri/DiPlay/pull/307)|
|v0.2.16-28|BYD音乐律动氛围灯，关闭后恢复原有设置。|不直接移植：平台/车型接口不适用|[#345](https://github.com/shihabal3amri/DiPlay/pull/345)|
|v0.2.16-29|补充设置布局规则及回归保护。|不移植运行功能：开发/发行流程项|[#490](https://github.com/shihabal3amri/DiPlay/pull/490) [#491](https://github.com/shihabal3amri/DiPlay/pull/491)|
|v0.2.16-30|问题反馈表要求附带诊断日志。|不移植运行功能：开发/发行流程项|[#459](https://github.com/shihabal3amri/DiPlay/pull/459)|

## v0.2.17

[官方发行页](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.17)

|编号|新增、优化或修复|72版处理|来源|
|---|---|---|---|
|v0.2.17-01|推荐车机热点，解释Wi-Fi Direct周期性卡顿的原因。|可选或依赖新增模块；本轮不擅自启用|[#570](https://github.com/shihabal3amri/DiPlay/pull/570)|
|v0.2.17-02|已批准ADB时暂停Wi-Fi Direct期间的网络扫描；连接结束恢复，热点/同LAN不暂停。|暂缓：保留本车保护，需要单独兼容性验证|[#570](https://github.com/shihabal3amri/DiPlay/pull/570)|
|v0.2.17-03|损坏的NCM传输块重新同步到下个合法块，交由TCP重传；填充按USB真实包长处理。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#575](https://github.com/shihabal3amri/DiPlay/pull/575)|
|v0.2.17-04|旧iOS因不认识部分消息而拒绝识别时，删除被拒消息后仅重试一次。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#575](https://github.com/shihabal3amri/DiPlay/pull/575)|
|v0.2.17-05|USB无障碍自动确认识别Android 10+和中文权限框；缺失标准设置页时回退厂商页。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#575](https://github.com/shihabal3amri/DiPlay/pull/575)|
|v0.2.17-06|旧iOS拒绝USB CarPlay模式时给出解锁/升级/无线建议，记录当前USB配置。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#575](https://github.com/shihabal3amri/DiPlay/pull/575)|
|v0.2.17-07|USB NCM接口被暂时占用时有限重试，并记录网络启动异常链。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#575](https://github.com/shihabal3amri/DiPlay/pull/575)|
|v0.2.17-08|USB向iPhone通告正常2.4A、降低1.5A或低0.5A充电需求；不是直接限制端口电流。|可选或依赖新增模块；本轮不擅自启用|[#583](https://github.com/shihabal3amri/DiPlay/pull/583)|
|v0.2.17-09|消息类应用请求录音而不播放下行音频时，仍打开车机麦克风。|本轮吸收相关修复：#548|[#548](https://github.com/shihabal3amri/DiPlay/pull/548)|
|v0.2.17-10|16kHz Opus语音录音按16kHz推进RTP时钟，修正语音拖慢失真。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#547](https://github.com/shihabal3amri/DiPlay/pull/547)|
|v0.2.17-11|BYD Android 12L前台CarPlay隐藏原车通话弹窗，结束后恢复。|不直接移植：平台/车型接口不适用|[#539](https://github.com/shihabal3amri/DiPlay/pull/539)|
|v0.2.17-12|BYD氛围灯根据专辑封面提取基色并随音乐变化。|不直接移植：平台/车型接口不适用|[#537](https://github.com/shihabal3amri/DiPlay/pull/537)|
|v0.2.17-13|氛围灯助手容忍厂商热补丁输出的额外前置文本。|不直接移植：平台/车型接口不适用|[#550](https://github.com/shihabal3amri/DiPlay/pull/550)|
|v0.2.17-14|蓝牙键盘、遥控器、旋钮可作为CarPlay外部控制器。|可选或依赖新增模块；本轮不擅自启用|[#549](https://github.com/shihabal3amri/DiPlay/pull/549)|
|v0.2.17-15|首次引导按BYD控制器版本识别DiLink代次。|不直接移植：平台/车型接口不适用|[#531](https://github.com/shihabal3amri/DiPlay/pull/531)|
|v0.2.17-16|每日检查公版更新并提示，验证APK后提供安装；定制版不能直接使用公版更新源。|暂缓：保留本车保护，需要单独兼容性验证|[#488](https://github.com/shihabal3amri/DiPlay/pull/488)|
|v0.2.17-17|Android 9及以下允许后将报告保存到Download/DiPlay，失败退回应用存储。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#541](https://github.com/shihabal3amri/DiPlay/pull/541)|
|v0.2.17-18|补充发行说明、隐私及安装文档。|不移植运行功能：开发/发行流程项|[#576](https://github.com/shihabal3amri/DiPlay/pull/576)|

## v0.2.18

[官方发行页](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.18)

|编号|新增、优化或修复|72版处理|来源|
|---|---|---|---|
|v0.2.18-01|USB切换CarPlay配置前处理占用iPhone音频接口的内核驱动。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#618](https://github.com/shihabal3amri/DiPlay/pull/618)|
|v0.2.18-02|无VPN授权页、网络ADB已批准时，尝试为自身设置ACTIVATE_VPN授权。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#618](https://github.com/shihabal3amri/DiPlay/pull/618)|
|v0.2.18-03|特定ROM拒绝Bluetooth socket flush时继续已成功写入的连接。|可选或依赖新增模块；本轮不擅自启用|[#618](https://github.com/shihabal3amri/DiPlay/pull/618)|
|v0.2.18-04|旧iOS拒绝USB切换时记录实际返回的三字节状态。|可选或依赖新增模块；本轮不擅自启用|[#618](https://github.com/shihabal3amri/DiPlay/pull/618)|
|v0.2.18-05|Android 7.1有线链路给IPv6监听地址附加VPN接口作用域。|可选或依赖新增模块；本轮不擅自启用|[#614](https://github.com/shihabal3amri/DiPlay/pull/614)|
|v0.2.18-06|开机ADB暂未就绪时有限重试恢复方向盘键服务，回前台也复查。|用户已选择新增，默认关闭；下次连接生效|[#622](https://github.com/shihabal3amri/DiPlay/pull/622) [#625](https://github.com/shihabal3amri/DiPlay/pull/625)|
|v0.2.18-07|USB读完成与超时取消竞争时保留已完成的数据，不结束会话。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#625](https://github.com/shihabal3amri/DiPlay/pull/625)|
|v0.2.18-08|热点名称含中文或其他非英文字符时警告兼容性。|用户已选择新增，默认关闭；下次连接生效|[#622](https://github.com/shihabal3amri/DiPlay/pull/622)|
|v0.2.18-09|各语言显示真实连接阶段/失败原因，避免始终显示正在准备。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#624](https://github.com/shihabal3amri/DiPlay/pull/624)|
|v0.2.18-10|检测厂商蓝牙伪连接，不再对根本不可用的无线路径无限重试。|可选或依赖新增模块；本轮不擅自启用|[#590](https://github.com/shihabal3amri/DiPlay/pull/590)|
|v0.2.18-11|多iPhone配对时从A2DP sink/免提客户端查找当前连接手机。|可选或依赖新增模块；本轮不擅自启用|[#585](https://github.com/shihabal3amri/DiPlay/pull/585)|
|v0.2.18-12|非BYD设备不存在SOME/IP网关时停止高频绑定。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#587](https://github.com/shihabal3amri/DiPlay/pull/587)|
|v0.2.18-13|音频解码器短暂忙碌时不立即丢AAC包，减少短断音；需结合后续0.2.19修正。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#618](https://github.com/shihabal3amri/DiPlay/pull/618)|
|v0.2.18-14|报告比较反馈给iPhone的播放位置和最新接收音频，区分欠载与手机主动停流。|可选或依赖新增模块；本轮不擅自启用|[#625](https://github.com/shihabal3amri/DiPlay/pull/625)|
|v0.2.18-15|USB导航语音可不再通告8/16kHz低采样率，电话音频不变。|用户已选择新增，默认关闭；下次连接生效|[#622](https://github.com/shihabal3amri/DiPlay/pull/622)|
|v0.2.18-16|可禁用媒体键音频焦点，解决部分长安S202音量键被焦点劫持；本车不可默认照搬。|暂缓：保留本车保护，需要单独兼容性验证|[#615](https://github.com/shihabal3amri/DiPlay/pull/615)|
|v0.2.18-17|通过Telecom保留原车通话音频路径和回声处理，默认关闭。|不直接移植：平台/车型接口不适用|[#595](https://github.com/shihabal3amri/DiPlay/pull/595)|
|v0.2.18-18|Harman全屏模式，另补32位x86原生库。|不直接移植：平台/车型接口不适用|[#613](https://github.com/shihabal3amri/DiPlay/pull/613)|
|v0.2.18-19|CarPlay请求方角、无安全区画面，保留原安全区设置。|可选或依赖新增模块；本轮不擅自启用|[#596](https://github.com/shihabal3amri/DiPlay/pull/596)|
|v0.2.18-20|物理长度调整从50mm细化到25mm。|用户已选择新增，默认关闭；下次连接生效|[#622](https://github.com/shihabal3amri/DiPlay/pull/622)|
|v0.2.18-21|发送给iPhone的自定义车标缩小到192像素，避免大PNG拖慢或阻断连接。|用户已选择新增，默认关闭；下次连接生效|[#622](https://github.com/shihabal3amri/DiPlay/pull/622)|
|v0.2.18-22|MediaTek主屏低延迟解码及逐级回退，默认关闭，须本车实测。|暂缓：保留本车保护，需要单独兼容性验证|[#608](https://github.com/shihabal3amri/DiPlay/pull/608)|
|v0.2.18-23|BYD可通过ADB读取速度并叠加仪表速度表，默认关闭。|不直接移植：平台/车型接口不适用|[#607](https://github.com/shihabal3amri/DiPlay/pull/607)|
|v0.2.18-24|连接等待页和快捷菜单统一按钮样式，支持遥控/D-pad焦点框。|可选或依赖新增模块；本轮不擅自启用|[#594](https://github.com/shihabal3amri/DiPlay/pull/594)|
|v0.2.18-25|无接听键的BYD方向盘在来电时可用上/下一首接听拒接。|用户已选择新增，默认关闭；下次连接生效|[#622](https://github.com/shihabal3amri/DiPlay/pull/622)|
|v0.2.18-26|FCE/Coagent T7原厂HAL转向/距离输出，无需ADB，非本车协议。|不直接移植：平台/车型接口不适用|[#599](https://github.com/shihabal3amri/DiPlay/pull/599)|
|v0.2.18-27|主屏前台时不依赖键服务也可将上/下一首交给CarPlay，通话时除外。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#600](https://github.com/shihabal3amri/DiPlay/pull/600)|
|v0.2.18-28|吉利/领克geely_fx11方向盘媒体/Siri及特定麦克风接入。|不直接移植：平台/车型接口不适用|[#626](https://github.com/shihabal3amri/DiPlay/pull/626)|
|v0.2.18-29|关于页面整合进设置分类，宽屏保留分类栏。|可选或依赖新增模块；本轮不擅自启用|[#597](https://github.com/shihabal3amri/DiPlay/pull/597)|
|v0.2.18-30|升级安装支持系统或其他安装器，减少不受支持的权限页；保留定制包名/签名。|暂缓：保留本车保护，需要单独兼容性验证|[#593](https://github.com/shihabal3amri/DiPlay/pull/593)|
|v0.2.18-31|手机扫码导入导出允许的设置，不包含配对密钥、认证和热点密码；页面有限时关闭。|可选或依赖新增模块；本轮不擅自启用|[#598](https://github.com/shihabal3amri/DiPlay/pull/598)|
|v0.2.18-32|补充本版发行说明。|不移植运行功能：开发/发行流程项|[#627](https://github.com/shihabal3amri/DiPlay/pull/627)|

## v0.2.19

[官方发行页](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.19)

|编号|新增、优化或修复|72版处理|来源|
|---|---|---|---|
|v0.2.19-01|USB建立前被拒绝的网络写入快速放弃，避免识别/认证每步等2秒。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-02|iOS 26返回缺少CarPlay模式的USB配置时优先请求正确模式。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-03|自动确认识别被系统标为Dialog的USB权限对话框。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-04|方向盘服务崩溃后延迟移除/重加授权，避免始终卡在正在启动。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-05|MIUI 13/Android 12仍请求兼容的旧蓝牙权限。|可选或依赖新增模块；本轮不擅自启用|[#673](https://github.com/shihabal3amri/DiPlay/pull/673)|
|v0.2.19-06|接受热点额外广播的_5G网络名，并沿用实际安全配置。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-07|Android 7.1–9缺失USB授权页时可通过已批准ADB授予USB设备权限。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#689](https://github.com/shihabal3amri/DiPlay/pull/689)|
|v0.2.19-08|蓝牙关闭时先提示开启，避免先建立网络再失败。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#679](https://github.com/shihabal3amri/DiPlay/pull/679)|
|v0.2.19-09|连接失败时按无线模式/原因给出相应处理提示。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#677](https://github.com/shihabal3amri/DiPlay/pull/677)|
|v0.2.19-10|标准蓝牙服务返回空适配器时增加发现回退。|可选或依赖新增模块；本轮不擅自启用|[#641](https://github.com/shihabal3amri/DiPlay/pull/641)|
|v0.2.19-11|BYD首次引导按DiLink版本设置导航流14或15；不得替换本车12/10设置。|不直接移植：平台/车型接口不适用|[#656](https://github.com/shihabal3amri/DiPlay/pull/656)|
|v0.2.19-12|16kHz通话/Siri/导航音频反馈按实际采样率计时，修正按48kHz计时。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-13|通话开始第一次录音读取失败时有限重启麦克风。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-14|音频解码等待从0.5秒调整为至多0.1秒并先排空，避免后台积压和持续欠载。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-15|修复Android 9及以下设置对话框无背景。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-16|仪表启动等待主窗口焦点稳定，避免首次触摸改变主屏尺寸并重连。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#663](https://github.com/shihabal3amri/DiPlay/pull/663)|
|v0.2.19-17|倒车摄像头临时隐藏系统栏时保持CarPlay原尺寸，避免返回后缩小。|可选或依赖新增模块；本轮不擅自启用|[#674](https://github.com/shihabal3amri/DiPlay/pull/674)|
|v0.2.19-18|快捷设置改居中面板、遮罩和固定保存按钮，退出远离保存。|可选或依赖新增模块；本轮不擅自启用|[#642](https://github.com/shihabal3amri/DiPlay/pull/642)|
|v0.2.19-19|自绘转向卡在明确结束导航后及时清理，断网续接单独处理；保留本车静止导航修复。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-20|从桌面/倒车返回时保留上一帧并请求新帧，减少黑屏。|可选或依赖新增模块；本轮不擅自启用|[#669](https://github.com/shihabal3amri/DiPlay/pull/669)|
|v0.2.19-21|设置搜索按需展开，避免窄屏标题按钮被挤压。|可选或依赖新增模块；本轮不擅自启用|[#645](https://github.com/shihabal3amri/DiPlay/pull/645)|
|v0.2.19-22|仪表可选封面卡，换歌短暂显示歌名歌手；仍遵守本车卡片默认关闭。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#660](https://github.com/shihabal3amri/DiPlay/pull/660)|
|v0.2.19-23|手机网页可下载与应用导出一致的诊断报告。|可选或依赖新增模块；本轮不擅自启用|[#686](https://github.com/shihabal3amri/DiPlay/pull/686)|
|v0.2.19-24|报告网页除扫码外提供地址和六位码，错误次数有限。|可选或依赖新增模块；本轮不擅自启用|[#687](https://github.com/shihabal3amri/DiPlay/pull/687)|
|v0.2.19-25|报告网页并发处理，避免一个空闲浏览器连接阻塞后续请求。|可选或依赖新增模块；本轮不擅自启用|[#685](https://github.com/shihabal3amri/DiPlay/pull/685)|
|v0.2.19-26|热点开启失败日志区分Android拒绝、ADB状态和厂商不支持。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#667](https://github.com/shihabal3amri/DiPlay/pull/667)|
|v0.2.19-27|录音报告加入峰值/静音和通话播放统计，便于定位录音无声。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-28|歌曲/导航元数据及USB逐帧日志采样限流，避免报告淹没关键记录。|本轮吸收相关修复：#644|[#644](https://github.com/shihabal3amri/DiPlay/pull/644)|
|v0.2.19-29|新增日语及对应手机网页/网站翻译。|可选或依赖新增模块；本轮不擅自启用|[#646](https://github.com/shihabal3amri/DiPlay/pull/646) [#691](https://github.com/shihabal3amri/DiPlay/pull/691)|
|v0.2.19-30|补全繁体中文并调整台湾用词。|可选或依赖新增模块；本轮不擅自启用|[#682](https://github.com/shihabal3amri/DiPlay/pull/682) [#681](https://github.com/shihabal3amri/DiPlay/pull/681)|
|v0.2.19-31|补充阿拉伯语连接、音频、仪表和诊断文字。|可选或依赖新增模块；本轮不擅自启用|[#631](https://github.com/shihabal3amri/DiPlay/pull/631)|
|v0.2.19-32|兼容性文档记录厂商蓝牙伪连接案例。|不移植运行功能：开发/发行流程项|[#680](https://github.com/shihabal3amri/DiPlay/pull/680)|

## v0.2.20

[官方发行页](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.20)

|编号|新增、优化或修复|72版处理|来源|
|---|---|---|---|
|v0.2.20-01|USB占用接口释放时不重新绑回音频驱动，再切换CarPlay模式。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#699](https://github.com/shihabal3amri/DiPlay/pull/699)|
|v0.2.20-02|Android缺少常规热点接口时，通过已批准shell请求系统热点；保留本车白名单方案。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#699](https://github.com/shihabal3amri/DiPlay/pull/699)|
|v0.2.20-03|完整设置可切换本地认证/USB CH341，不必先连上CarPlay。|可选或依赖新增模块；本轮不擅自启用|[#699](https://github.com/shihabal3amri/DiPlay/pull/699)|
|v0.2.20-04|保存并重连时对同一次USB枚举重新请求CarPlay模式，避免等待手机重置。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#714](https://github.com/shihabal3amri/DiPlay/pull/714)|
|v0.2.20-05|Wi-Fi Direct自动模式不向不支持5GHz的设备优先请求5GHz。|本轮吸收相关修复：#740|[#740](https://github.com/shihabal3amri/DiPlay/pull/740)|
|v0.2.20-06|USBMUX VERSION响应前容忍已观测到的四字节尾部，修复长度0错误。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#744](https://github.com/shihabal3amri/DiPlay/pull/744)|
|v0.2.20-07|Android 17有线连接也请求本地网络权限。|可选或依赖新增模块；本轮不擅自启用|[#708](https://github.com/shihabal3amri/DiPlay/pull/708)|
|v0.2.20-08|厂商蓝牙socket管理器缺失时明确报不支持，建议有线，不重复误报手机不可用。|可选或依赖新增模块；本轮不擅自启用|[#725](https://github.com/shihabal3amri/DiPlay/pull/725)|
|v0.2.20-09|未保存热点信息时不显示空SSID。|可选或依赖新增模块；本轮不擅自启用|[#726](https://github.com/shihabal3amri/DiPlay/pull/726)|
|v0.2.20-10|Wi-Fi Direct可选择是否暂停原车Wi-Fi搜索，避免影响车机自动联网。|暂缓：保留本车保护，需要单独兼容性验证|[#768](https://github.com/shihabal3amri/DiPlay/pull/768)|
|v0.2.20-11|蓝牙完全不可用于应用时，选手机入口直接解释限制。|可选或依赖新增模块；本轮不擅自启用|[#745](https://github.com/shihabal3amri/DiPlay/pull/745)|
|v0.2.20-12|Android 8–10非BYD热点自动开启支持修改系统设置权限，BYD保留ADB路线。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#748](https://github.com/shihabal3amri/DiPlay/pull/748) [#775](https://github.com/shihabal3amri/DiPlay/pull/775) [#777](https://github.com/shihabal3amri/DiPlay/pull/777)|
|v0.2.20-13|未选iPhone时移除各语言中的英文占位名。|可选或依赖新增模块；本轮不擅自启用|[#778](https://github.com/shihabal3amri/DiPlay/pull/778)|
|v0.2.20-14|热点误报关闭时点击连接可继续，不反复弹相同对话框。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#789](https://github.com/shihabal3amri/DiPlay/pull/789)|
|v0.2.20-15|识别BYD控制器13/17的特定仪表显示名。|不直接移植：平台/车型接口不适用|[#714](https://github.com/shihabal3amri/DiPlay/pull/714)|
|v0.2.20-16|DiLink 3不再展示会阻止其仪表显示的DiLink 4路由。|不直接移植：平台/车型接口不适用|[#714](https://github.com/shihabal3amri/DiPlay/pull/714)|
|v0.2.20-17|豹5 Ultra使用匹配固件的全图/侧图图层。|不直接移植：平台/车型接口不适用|[#714](https://github.com/shihabal3amri/DiPlay/pull/714)|
|v0.2.20-18|没有硬件HEVC解码器时退回H.264，避免误用软件解码导致卡顿。|本轮吸收相关修复：#734|[#734](https://github.com/shihabal3amri/DiPlay/pull/734)|
|v0.2.20-19|USB会话首页不再显示无线连接说明。|可选或依赖新增模块；本轮不擅自启用|[#744](https://github.com/shihabal3amri/DiPlay/pull/744)|
|v0.2.20-20|树莓派特定FFmpeg解码器改用直接Surface输出。|不直接移植：平台/车型接口不适用|[#707](https://github.com/shihabal3amri/DiPlay/pull/707)|
|v0.2.20-21|修复Android 7.1–9设置搜索调用Android 10专有接口导致崩溃。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#705](https://github.com/shihabal3amri/DiPlay/pull/705)|
|v0.2.20-22|快捷菜单按连接、显示、导航、车辆、诊断等目标分组。|可选或依赖新增模块；本轮不擅自启用|[#710](https://github.com/shihabal3amri/DiPlay/pull/710)|
|v0.2.20-23|等待连接页面跟随应用主题，与发给CarPlay的昼夜模式分开。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#747](https://github.com/shihabal3amri/DiPlay/pull/747)|
|v0.2.20-24|BYD氛围灯设置改色板、亮度滑块及模式摘要。|不直接移植：平台/车型接口不适用|[#749](https://github.com/shihabal3amri/DiPlay/pull/749)|
|v0.2.20-25|音乐暂停时氛围灯可常亮、延后交还原车或保持最低亮度。|不直接移植：平台/车型接口不适用|[#772](https://github.com/shihabal3amri/DiPlay/pull/772)|
|v0.2.20-26|消息应用仅请求录音、不发送音频时，0.5秒后仍启动麦克风。|本轮吸收相关修复：#698|[#698](https://github.com/shihabal3amri/DiPlay/pull/698)|
|v0.2.20-27|麦克风1秒无输入时有限重启，避免空读忙循环。|本轮吸收相关修复：#698|[#698](https://github.com/shihabal3amri/DiPlay/pull/698)|
|v0.2.20-28|电话/平板充当车机时可选择扬声器和免提麦克风。|可选或依赖新增模块；本轮不擅自启用|[#704](https://github.com/shihabal3amri/DiPlay/pull/704)|
|v0.2.20-29|音频接收、播放、录音线程提高到Android音频优先级，减少360摄像头等抢占。|本轮吸收相关修复：#740|[#740](https://github.com/shihabal3amri/DiPlay/pull/740)|
|v0.2.20-30|USB和无线分别保存音乐缓冲，USB增加更短档位。|可选或依赖新增模块；本轮不擅自启用|[#746](https://github.com/shihabal3amri/DiPlay/pull/746)|
|v0.2.20-31|导航播报独立衰减滑块，只降低导航而非音乐。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#752](https://github.com/shihabal3amri/DiPlay/pull/752)|
|v0.2.20-32|BYD原车电话路径选用实测较少削波的录音源，吉利路径不变。|不直接移植：平台/车型接口不适用|[#770](https://github.com/shihabal3amri/DiPlay/pull/770)|
|v0.2.20-33|BYD语音键双代码去重，增加仅长按Siri选项。|不直接移植：平台/车型接口不适用|[#774](https://github.com/shihabal3amri/DiPlay/pull/774) [#786](https://github.com/shihabal3amri/DiPlay/pull/786)|
|v0.2.20-34|按音乐缓冲修正车机/iPhone时钟漂移，后续0.2.21/22继续修正算法。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#788](https://github.com/shihabal3amri/DiPlay/pull/788)|
|v0.2.20-35|通话被系统错误路由听筒时，有限回退到扬声器。|可选或依赖新增模块；本轮不擅自启用|[#797](https://github.com/shihabal3amri/DiPlay/pull/797)|
|v0.2.20-36|保存上次应用崩溃堆栈并脱敏，可随诊断导出。|可选或依赖新增模块；本轮不擅自启用|[#700](https://github.com/shihabal3amri/DiPlay/pull/700)|
|v0.2.20-37|手机报告网页停止时及时退出accept等待，避免卡住。|可选或依赖新增模块；本轮不擅自启用|[#714](https://github.com/shihabal3amri/DiPlay/pull/714)|
|v0.2.20-38|BYD网关拒绝绑定时指数退避，减少高频重试和日志。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#714](https://github.com/shihabal3amri/DiPlay/pull/714)|
|v0.2.20-39|记录暂停/恢复原车蓝牙的各步骤和失败原因。|本轮吸收相关修复：#740|[#740](https://github.com/shihabal3amri/DiPlay/pull/740)|
|v0.2.20-40|报告保留VoIP音频设置的语音编码信息。|本轮吸收相关修复：#740|[#740](https://github.com/shihabal3amri/DiPlay/pull/740)|
|v0.2.20-41|报告保留有限条方向盘键码/扫描码/处理结果，不记录文本输入。|可选或依赖新增模块；本轮不擅自启用|[#702](https://github.com/shihabal3amri/DiPlay/pull/702)|
|v0.2.20-42|报告记录媒体按键是否发送给CarPlay及焦点变化。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#788](https://github.com/shihabal3amri/DiPlay/pull/788)|
|v0.2.20-43|检查ADB除了修改状态文字，还显示结果提示。|可选或依赖新增模块；本轮不擅自启用|[#768](https://github.com/shihabal3amri/DiPlay/pull/768)|
|v0.2.20-44|音乐重缓冲、恢复和队列满日志限次，统计保留总量。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#759](https://github.com/shihabal3amri/DiPlay/pull/759)|
|v0.2.20-45|等待CH341时列出系统可见USB设备，找到后记录节点。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#793](https://github.com/shihabal3amri/DiPlay/pull/793)|

## v0.2.21

[官方发行页](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.21)

|编号|新增、优化或修复|72版处理|来源|
|---|---|---|---|
|v0.2.21-01|最低Android降为7.0，车机厂商兼容性仍需验证。|可选或依赖新增模块；本轮不擅自启用|[#847](https://github.com/shihabal3amri/DiPlay/pull/847)|
|v0.2.21-02|新增意大利语。|可选或依赖新增模块；本轮不擅自启用|[#863](https://github.com/shihabal3amri/DiPlay/pull/863)|
|v0.2.21-03|无线启动失败和自动重试停止时显示真实原因与下一步。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#824](https://github.com/shihabal3amri/DiPlay/pull/824)|
|v0.2.21-04|按缺失权限类型显示提示，并从系统设置返回后复查。|可选或依赖新增模块；本轮不擅自启用|[#795](https://github.com/shihabal3amri/DiPlay/pull/795)|
|v0.2.21-05|DiLink 1.0缺失标准设置页时打开BYD专用蓝牙/热点页。|不直接移植：平台/车型接口不适用|[#794](https://github.com/shihabal3amri/DiPlay/pull/794)|
|v0.2.21-06|用户允许忽略热点关闭状态后，确实找不到热点时重新提示。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#827](https://github.com/shihabal3amri/DiPlay/pull/827)|
|v0.2.21-07|从DiYou桌面卡启动独立任务被拒时安全回退，避免应用退出。|不直接移植：平台/车型接口不适用|[#827](https://github.com/shihabal3amri/DiPlay/pull/827)|
|v0.2.21-08|导入原车热点名称/密码；读不到时写入新配置必须确认，不默认改变本车热点。|暂缓：保留本车保护，需要单独兼容性验证|[#769](https://github.com/shihabal3amri/DiPlay/pull/769)|
|v0.2.21-09|有线切换请求无回应时有限等待后重新检查当前USB模式，不永久卡住。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#841](https://github.com/shihabal3amri/DiPlay/pull/841)|
|v0.2.21-10|先前失败的延迟重试不得拆掉已经恢复的健康连接。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#876](https://github.com/shihabal3amri/DiPlay/pull/876)|
|v0.2.21-11|Android 13取消关闭热点的请求后，不反复弹Wi-Fi Direct冲突框。|可选或依赖新增模块；本轮不擅自启用|[#893](https://github.com/shihabal3amri/DiPlay/pull/893)|
|v0.2.21-12|增加非标准热点接口发现和手动指定，例如eth0.3；新版0.2.22又修正误选回归。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#885](https://github.com/shihabal3amri/DiPlay/pull/885)|
|v0.2.21-13|蓝牙正在开启时短暂等待，而非立即判定失败。|可选或依赖新增模块；本轮不擅自启用|[#822](https://github.com/shihabal3amri/DiPlay/pull/822)|
|v0.2.21-14|USB未配置热点时仍允许保存并重连，不被无关热点校验挡住。|可选或依赖新增模块；本轮不擅自启用|[#853](https://github.com/shihabal3amri/DiPlay/pull/853)|
|v0.2.21-15|iPhone接受有线启动却不连AirPlay时，限时启动下一次尝试。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#907](https://github.com/shihabal3amri/DiPlay/pull/907)|
|v0.2.21-16|滑动临时显示系统栏5秒，不改变CarPlay尺寸或重连。|可选或依赖新增模块；本轮不擅自启用|[#818](https://github.com/shihabal3amri/DiPlay/pull/818)|
|v0.2.21-17|黑色圆角遮罩及实验性iPhone圆角遮罩，失败回退。|可选或依赖新增模块；本轮不擅自启用|[#811](https://github.com/shihabal3amri/DiPlay/pull/811)|
|v0.2.21-18|说明方形旋转画布在iPhone截图中会包含不可见区域。|可选或依赖新增模块；本轮不擅自启用|[#864](https://github.com/shihabal3amri/DiPlay/pull/864)|
|v0.2.21-19|声明DiLink 7.0特有宽分屏支持。|不直接移植：平台/车型接口不适用|[#875](https://github.com/shihabal3amri/DiPlay/pull/875)|
|v0.2.21-20|音乐时钟计入未解码积压，避免延迟切歌、变速及重播上一曲尾部。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#788](https://github.com/shihabal3amri/DiPlay/pull/788) [#814](https://github.com/shihabal3amri/DiPlay/pull/814)|
|v0.2.21-21|手机重新开始播放时恢复媒体焦点；本车已修过反复抢焦点诱发蓝牙暂停，不能整段覆盖。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#804](https://github.com/shihabal3amri/DiPlay/pull/804)|
|v0.2.21-22|软件Opus内部AssertionError不再导致应用退出，丢当前帧并重置编码器。|本轮吸收相关修复：#866|[#866](https://github.com/shihabal3amri/DiPlay/pull/866)|
|v0.2.21-23|导航衰减直接作用PCM，避免部分ROM忽略AudioTrack音量。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#841](https://github.com/shihabal3amri/DiPlay/pull/841)|
|v0.2.21-24|BYD隐藏原车电话弹窗扩展到Android 10+。|不直接移植：平台/车型接口不适用|[#845](https://github.com/shihabal3amri/DiPlay/pull/845)|
|v0.2.21-25|明确通话/Siri/媒体按键设置名称与行为说明。|可选或依赖新增模块；本轮不擅自启用|[#842](https://github.com/shihabal3amri/DiPlay/pull/842)|
|v0.2.21-26|BYD断开后延迟交还仪表窗口；本车已有自己的InCall交还和重连逻辑。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#909](https://github.com/shihabal3amri/DiPlay/pull/909)|
|v0.2.21-27|桌面悬浮气泡打开DiPlay、移动和锁定，默认关闭。|可选或依赖新增模块；本轮不擅自启用|[#844](https://github.com/shihabal3amri/DiPlay/pull/844)|
|v0.2.21-28|使用BYD原车音乐律动，由其音频服务控制氛围灯。|不直接移植：平台/车型接口不适用|[#695](https://github.com/shihabal3amri/DiPlay/pull/695)|
|v0.2.21-29|向较旧BYD原车律动发送16频段音乐数据。|不直接移植：平台/车型接口不适用|[#908](https://github.com/shihabal3amri/DiPlay/pull/908)|
|v0.2.21-30|学习空闲方向盘/键盘键作为播放暂停，保留保留键与通话行为。|可选或依赖新增模块；本轮不擅自启用|[#823](https://github.com/shihabal3amri/DiPlay/pull/823)|
|v0.2.21-31|学习键双击唤醒Siri，并配置双击间隔和延迟。|可选或依赖新增模块；本轮不擅自启用|[#813](https://github.com/shihabal3amri/DiPlay/pull/813)|
|v0.2.21-32|崩溃记录补充应用自身堆栈，避免全是系统帧。|可选或依赖新增模块；本轮不擅自启用|[#827](https://github.com/shihabal3amri/DiPlay/pull/827)|
|v0.2.21-33|等待USB手机时只在可见设备列表变化后重记，区分线材/端口/手机问题。|可选或依赖新增模块；本轮不擅自启用|[#830](https://github.com/shihabal3amri/DiPlay/pull/830)|
|v0.2.21-34|轮速上报每分钟统计GPS和轮速条数，用于隧道定位排查。|不直接移植：平台/车型接口不适用|[#862](https://github.com/shihabal3amri/DiPlay/pull/862)|

## v0.2.22

[官方发行页](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.22)

|编号|新增、优化或修复|72版处理|来源|
|---|---|---|---|
|v0.2.22-01|修复0.2.21热点接口误选；只在确实无已知热点接口时回退，并排除移动数据接口。|本轮补移动数据接口排除；保留本车热点探测与开启链路|[#936](https://github.com/shihabal3amri/DiPlay/pull/936) [#950](https://github.com/shihabal3amri/DiPlay/pull/950)|
|v0.2.22-02|音乐时钟使用突发包间的时间平均缓冲，避免持续加速至1.5%后断音。|本轮吸收相关修复：#993|[#993](https://github.com/shihabal3amri/DiPlay/pull/993)|
|v0.2.22-03|每首开始/切歌重新测量稳定缓冲目标，计入阻塞写入期间到达的音频，修正起播与缓冲音乐变速。|本轮吸收相关修复：#993,#1070,#1080|[#993](https://github.com/shihabal3amri/DiPlay/pull/993) [#1070](https://github.com/shihabal3amri/DiPlay/pull/1070) [#1080](https://github.com/shihabal3amri/DiPlay/pull/1080)|
|v0.2.22-04|mDNS创建前缓存本机名称，避免反向DNS查询拖慢无线连接。|本轮吸收相关修复：#972|[#972](https://github.com/shihabal3amri/DiPlay/pull/972)|
|v0.2.22-05|USB网络延后开放时补发一次启动请求，减少带线重连必须二次操作。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#962](https://github.com/shihabal3amri/DiPlay/pull/962)|
|v0.2.22-06|修正Android 8/9 socket文件描述符处理，支持断网判死与重连；与长安历史闪退保护冲突需实测。|暂缓：保留本车保护，需要单独兼容性验证|[#1042](https://github.com/shihabal3amri/DiPlay/pull/1042)|
|v0.2.22-07|蓝牙重新开启后立即重试，避免等待旧退避计时。|可选或依赖新增模块；本轮不擅自启用|[#914](https://github.com/shihabal3amri/DiPlay/pull/914)|
|v0.2.22-08|Wi-Fi Direct旧组尚未清理时等待其移除，避免更新应用后的首次连接误失败。|可选或依赖新增模块；本轮不擅自启用|[#915](https://github.com/shihabal3amri/DiPlay/pull/915)|
|v0.2.22-09|导入热点开关打开时请求ADB批准，保存导入值不再误报将覆盖系统配置。|暂缓：保留本车保护，需要单独兼容性验证|[#919](https://github.com/shihabal3amri/DiPlay/pull/919)|
|v0.2.22-10|Android 9及以下快捷菜单重新提供Wi-Fi Direct，修正文案。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#937](https://github.com/shihabal3amri/DiPlay/pull/937)|
|v0.2.22-11|快捷菜单关闭重开、Activity重建后保留热点名称/频段/信道摘要。|可选或依赖新增模块；本轮不擅自启用|[#924](https://github.com/shihabal3amri/DiPlay/pull/924)|
|v0.2.22-12|区分网络ADB未响应与开关未打开，提供相应提示。|可选或依赖新增模块；本轮不擅自启用|[#1045](https://github.com/shihabal3amri/DiPlay/pull/1045)|
|v0.2.22-13|后台已经连接/连接中时自启动将CarPlay带到前台，不只打开首页。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#1085](https://github.com/shihabal3amri/DiPlay/pull/1085)|
|v0.2.22-14|分清未配对、多手机未选择、信道不支持、热点关闭/不匹配、USB无设备等原因。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#1058](https://github.com/shihabal3amri/DiPlay/pull/1058) [#1060](https://github.com/shihabal3amri/DiPlay/pull/1060) [#1062](https://github.com/shihabal3amri/DiPlay/pull/1062)|
|v0.2.22-15|首页和报告保留最近连接故障及时间，成功后清除。|可选或依赖新增模块；本轮不擅自启用|[#1066](https://github.com/shihabal3amri/DiPlay/pull/1066)|
|v0.2.22-16|缺失VPN授权页时给出原因及可用授权指令，不只显示通用错误。|可选或依赖新增模块；本轮不擅自启用|[#1096](https://github.com/shihabal3amri/DiPlay/pull/1096)|
|v0.2.22-17|主屏10秒没有首帧时提示手机未送视频及可尝试恢复方法。|可选或依赖新增模块；本轮不擅自启用|[#1097](https://github.com/shihabal3amri/DiPlay/pull/1097)|
|v0.2.22-18|USB网络开放较晚时最长等待45秒，配合二次启动请求。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#1057](https://github.com/shihabal3amri/DiPlay/pull/1057)|
|v0.2.22-19|同LAN连接允许指定实际网卡，例如USB以太网eth1。|可选或依赖新增模块；本轮不擅自启用|[#1113](https://github.com/shihabal3amri/DiPlay/pull/1113)|
|v0.2.22-20|多点触摸按手指身份保留坐标，修复抬起一根手指后的地图缩放跳动。|本轮吸收相关修复：#912|[#912](https://github.com/shihabal3amri/DiPlay/pull/912)|
|v0.2.22-21|打开快捷设置的多指手势不再误点下层地图；保留长安三指手势优先级。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#922](https://github.com/shihabal3amri/DiPlay/pull/922)|
|v0.2.22-22|尺寸设置显示真实物理长度及自定义值，快捷调整细化至5mm。|可选或依赖新增模块；本轮不擅自启用|[#1032](https://github.com/shihabal3amri/DiPlay/pull/1032)|
|v0.2.22-23|设置正确显示快捷菜单保存的帧率，例如45fps。|可选或依赖新增模块；本轮不擅自启用|[#934](https://github.com/shihabal3amri/DiPlay/pull/934)|
|v0.2.22-24|快捷菜单显示实际请求分辨率并补齐视频/全屏翻译。|可选或依赖新增模块；本轮不擅自启用|[#923](https://github.com/shihabal3amri/DiPlay/pull/923)|
|v0.2.22-25|Activity重建接管活跃会话时恢复引用，避免昼夜、Siri和方向盘命令失效。|本轮吸收相关修复：#933|[#933](https://github.com/shihabal3amri/DiPlay/pull/933)|
|v0.2.22-26|可让CarPlay留在桌面卡片/画中画同一任务，默认关闭。|可选或依赖新增模块；本轮不擅自启用|[#1052](https://github.com/shihabal3amri/DiPlay/pull/1052)|
|v0.2.22-27|网络慢时合并同组手指尚未发送的移动事件，只保留最新位置；按下/抬起仍完整发送。|本轮吸收相关修复：#1134|[#1134](https://github.com/shihabal3amri/DiPlay/pull/1134)|
|v0.2.22-28|仅确实需要TRACE时才生成协议十六进制日志，减少触摸热路径CPU和分配。|本轮吸收相关修复：#1131|[#1131](https://github.com/shihabal3amri/DiPlay/pull/1131)|
|v0.2.22-29|通话结束撤销自身通信模式请求，不把进入前的原车通话模式重新设成自己持有。|本轮吸收相关修复：#994|[#994](https://github.com/shihabal3amri/DiPlay/pull/994)|
|v0.2.22-30|学习只进入当前窗口、未进入全局按键服务的Siri/播放暂停键。|可选或依赖新增模块；本轮不擅自启用|[#1025](https://github.com/shihabal3amri/DiPlay/pull/1025)|
|v0.2.22-31|通话及麦克风可使用通话自身采样率，不强制48kHz。|用户已选择新增，默认关闭；下次连接生效|[#898](https://github.com/shihabal3amri/DiPlay/pull/898)|
|v0.2.22-32|可选逐条导航播报申请临时可压低音量的焦点；本车已有导航焦点实现需统一。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#1051](https://github.com/shihabal3amri/DiPlay/pull/1051)|
|v0.2.22-33|USB 48kHz PCM麦克风分包按MTU缩短，避免微信语音/备忘录/听写无声；不是音乐自动恢复功能。|本轮吸收相关修复：#1148|[#1148](https://github.com/shihabal3amri/DiPlay/pull/1148)|
|v0.2.22-34|DiLink 3仪表增加半屏/全屏形状选择，与本车InCall全屏不同。|不直接移植：平台/车型接口不适用|[#956](https://github.com/shihabal3amri/DiPlay/pull/956)|
|v0.2.22-35|领克03 geely_fx11仪表申请/交还与原车地图释放后的恢复。|不直接移植：平台/车型接口不适用|[#843](https://github.com/shihabal3amri/DiPlay/pull/843)|
|v0.2.22-36|空/无效封面不覆盖旧图；歌曲和封面渐变、音乐卡缩放及封面色背景。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#660](https://github.com/shihabal3amri/DiPlay/pull/660) [#1033](https://github.com/shihabal3amri/DiPlay/pull/1033)|
|v0.2.22-37|自动打开BYD媒体中心一次以激活原车仪表音乐卡，默认关闭。|不直接移植：平台/车型接口不适用|[#959](https://github.com/shihabal3amri/DiPlay/pull/959)|
|v0.2.22-38|腾势N9特定固件与2560×720仪表布局支持，部分按照片推测待实测。|不直接移植：平台/车型接口不适用|[#1049](https://github.com/shihabal3amri/DiPlay/pull/1049)|
|v0.2.22-39|主屏首帧后才建立仪表输出；迟到的兼容显示器可加入现有会话。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#981](https://github.com/shihabal3amri/DiPlay/pull/981)|
|v0.2.22-40|按专辑色显示的自绘速度表自动选择黑白字以确保对比度。|不直接移植：平台/车型接口不适用|[#1072](https://github.com/shihabal3amri/DiPlay/pull/1072)|
|v0.2.22-41|桌面气泡不能拖出屏幕或进入系统栏下面。|可选或依赖新增模块；本轮不擅自启用|[#930](https://github.com/shihabal3amri/DiPlay/pull/930)|
|v0.2.22-42|兼容2023汉原车律动的另一种音频服务调用。|不直接移植：平台/车型接口不适用|[#957](https://github.com/shihabal3amri/DiPlay/pull/957)|
|v0.2.22-43|音乐律动发送间隔内的短促鼓点也保留。|不直接移植：平台/车型接口不适用|[#938](https://github.com/shihabal3amri/DiPlay/pull/938)|
|v0.2.22-44|关闭律动时即使能力探测仍在进行，也保持关闭。|不直接移植：平台/车型接口不适用|[#939](https://github.com/shihabal3amri/DiPlay/pull/939)|
|v0.2.22-45|修复悬浮气泡设置图标和氛围灯提示对齐。|可选或依赖新增模块；本轮不擅自启用|[#1034](https://github.com/shihabal3amri/DiPlay/pull/1034) [#1035](https://github.com/shihabal3amri/DiPlay/pull/1035)|
|v0.2.22-46|后台发现新版后回前台立即刷新升级提示；定制版更新源必须独立。|暂缓：保留本车保护，需要单独兼容性验证|[#947](https://github.com/shihabal3amri/DiPlay/pull/947)|
|v0.2.22-47|设置备份补充缩放比例、氛围灯延迟、仪表路由、扬声器通话、导航音量和气泡。|可选或依赖新增模块；本轮不擅自启用|[#928](https://github.com/shihabal3amri/DiPlay/pull/928)|
|v0.2.22-48|设置搜索包含滑块及HEVC开关。|可选或依赖新增模块；本轮不擅自启用|[#935](https://github.com/shihabal3amri/DiPlay/pull/935) [#969](https://github.com/shihabal3amri/DiPlay/pull/969)|
|v0.2.22-49|未知DiLink代次时不显示容易误解的推荐文字。|不直接移植：平台/车型接口不适用|[#929](https://github.com/shihabal3amri/DiPlay/pull/929)|
|v0.2.22-50|手机报告/备份网页增加意大利语。|可选或依赖新增模块；本轮不擅自启用|[#925](https://github.com/shihabal3amri/DiPlay/pull/925)|
|v0.2.22-51|短时间重开手机网页保持地址不变。|可选或依赖新增模块；本轮不擅自启用|[#932](https://github.com/shihabal3amri/DiPlay/pull/932)|
|v0.2.22-52|新增韩语。|可选或依赖新增模块；本轮不擅自启用|[#1105](https://github.com/shihabal3amri/DiPlay/pull/1105)|
|v0.2.22-53|新增土耳其语。|可选或依赖新增模块；本轮不擅自启用|[#999](https://github.com/shihabal3amri/DiPlay/pull/999)|
|v0.2.22-54|升级下载限定GitHub HTTPS、简单APK文件名和64MiB上限；仅在引入升级模块时需要。|可选或依赖新增模块；本轮不擅自启用|[#1143](https://github.com/shihabal3amri/DiPlay/pull/1143)|
|v0.2.22-55|设置导入只接受已知键和正确类型，防止畸形备份阻止启动；仅在引入备份模块时需要。|可选或依赖新增模块；本轮不擅自启用|[#1136](https://github.com/shihabal3amri/DiPlay/pull/1136)|
|v0.2.22-56|ECARX通过其SDK向iPhone上报车速和挡位，不能直接用于梧桐。|不直接移植：平台/车型接口不适用|[#1089](https://github.com/shihabal3amri/DiPlay/pull/1089)|
|v0.2.22-57|声明车道引导存储能力并解析/记录0x5204；公版还没有绘制车道箭头。|用户已选择新增，默认关闭；下次连接生效|[#1028](https://github.com/shihabal3amri/DiPlay/pull/1028)|
|v0.2.22-58|麦克风全静音时记录系统模式、其他录音源和实际所选音源。|可选或依赖新增模块；本轮不擅自启用|[#995](https://github.com/shihabal3amri/DiPlay/pull/995)|
|v0.2.22-59|无轮速上报时记录车速/挡位读取结果。|不直接移植：平台/车型接口不适用|[#996](https://github.com/shihabal3amri/DiPlay/pull/996)|
|v0.2.22-60|记录CarPlay不识别的媒体键码，方便新增车型按键映射。|可选或依赖新增模块；本轮不擅自启用|[#970](https://github.com/shihabal3amri/DiPlay/pull/970)|
|v0.2.22-61|PCM录音诊断正确标注RTP麦克风时钟。|可选或依赖新增模块；本轮不擅自启用|[#963](https://github.com/shihabal3amri/DiPlay/pull/963)|
|v0.2.22-62|查看长报告只加载头部和最近日志，导出仍保留完整文件。|可选或依赖新增模块；本轮不擅自启用|[#1132](https://github.com/shihabal3amri/DiPlay/pull/1132)|
|v0.2.22-63|日志后台写入、过载丢行统计；本车已有异步队列及更小归档预算，勿重复叠加。|保留71相关实现/待逐项差异评估，不声明完整覆盖该PR|[#1133](https://github.com/shihabal3amri/DiPlay/pull/1133)|
|v0.2.22-64|进一步脱敏带横杠USB标识、热点名称，减少逐USB确认日志。|本轮补USB短格式标识脱敏；不扩大日志量|[#1141](https://github.com/shihabal3amri/DiPlay/pull/1141) [#1139](https://github.com/shihabal3amri/DiPlay/pull/1139) [#1144](https://github.com/shihabal3amri/DiPlay/pull/1144)|
|v0.2.22-65|限制RTSP/iAP/Bonjour/plist/USB NCM输入和分配大小，防止异常输入耗尽内存。|本轮吸收相关修复：#1122,#1125,#1126,#1128|[#1122](https://github.com/shihabal3amri/DiPlay/pull/1122) [#1125](https://github.com/shihabal3amri/DiPlay/pull/1125) [#1126](https://github.com/shihabal3amri/DiPlay/pull/1126) [#1128](https://github.com/shihabal3amri/DiPlay/pull/1128)|
|v0.2.22-66|限制其他应用伪造USB授权、滥用嵌入地图；备份/蓝牙唤醒保护按实际存在模块移植。|本轮吸收相关修复：#1129|[#1138](https://github.com/shihabal3amri/DiPlay/pull/1138) [#1129](https://github.com/shihabal3amri/DiPlay/pull/1129) [#1140](https://github.com/shihabal3amri/DiPlay/pull/1140)|
|v0.2.22-67|远程MFI公网只接受HTTPS，私网可HTTP；本地认证使用不受影响。|可选或依赖新增模块；本轮不擅自启用|[#1127](https://github.com/shihabal3amri/DiPlay/pull/1127)|
|v0.2.22-68|氛围灯锁文件拒绝符号链接、BYD工具路径转义；示例启动器绑定明确地图服务。|不直接移植：平台/车型接口不适用|[#1135](https://github.com/shihabal3amri/DiPlay/pull/1135) [#1137](https://github.com/shihabal3amri/DiPlay/pull/1137) [#1142](https://github.com/shihabal3amri/DiPlay/pull/1142)|
|v0.2.22-69|视频/iAP/设置/事件和时钟回复仅接受当前控制会话手机；须实测本车热点及有线地址兼容。|暂缓：保留本车保护，需要单独兼容性验证|[#1123](https://github.com/shihabal3amri/DiPlay/pull/1123)|
