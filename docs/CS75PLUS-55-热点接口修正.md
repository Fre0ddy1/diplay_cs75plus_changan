# 55候选：热点接口修正

54实车在15:15:16—15:15:26返回UNSUPPORTED，用户确认15:15:30左右为手动打开热点。之后READY只能证明检测到已开，不能作为自动启动成功样本。本轮当前WRITE_SETTINGS为allow，设备为Android9/API28、54应用targetSdk37；原日志未保存反射异常类型。

只读复制本车boot-framework.vdex后，按Android9 ART的隐藏API编码规则解析类/方法元数据：ConnectivityManager.startTethering的3/4参数框架入口、OnStartTetheringCallback构造和两个回调均为whitelist；IConnectivityManager及其非公开Stub.Proxy的startTethering为dark-greylist。54直接反射后者，不等同于原厂设置使用的前者。另有Java访问非公开代理类的风险；55同时消除该访问形式在兼容兜底中的问题。

55新增模块内FrameworkHotspotStarter，使用本车允许名单里的ConnectivityManager框架入口和系统回调，让框架内部完成Binder调用；Android原有调用者权限检查照常执行。没有降低targetSdk、修改hidden_api_policy、使用VMRuntime豁免或ADB授予权限。静态受限路径及代码缺陷支持此次修复方向；旧日志没有精确异常栈，最终实车结果仍须重测。

## 编译和链路

当前编译SDK未公开Android9旧回调类型，新增android9-hotspot-stubs只提供compileOnly签名；运行时由系统提供基类。APK检查必须确认系统回调类未被打包，同时确认应用自己的Android9HotspotCallback继承该系统类。

`前台模块/连接控制器 → shared状态确认和同步锁 → 通用PlatformHotspotStarter → hotspot-startup框架适配 → ConnectivityManager.startTethering → 系统权限检查和热点服务 → 回调 + AP实际ON确认`。

仅收到onTetheringStarted仍不当作AP已打开。保留原配置、取消和有限补试；不发送关闭热点或配置改写指令。诊断区分AP状态不可读、字段/方法查找、Java访问、系统权限拒绝、平台返回码及超时；拒绝消息只提取权限名称，不记录原始错误载荷。已允许WRITE_SETTINGS却仍被系统拒绝时，界面不再反复要求同一项授权。

## 依据与限制

本车VDEX SHA-256为8f67c5e877c5d97674c6d4bf25184e107793e0217c71425749e178f58f3e3a7d；结构化结果及原始日志仅保存在本地“分析研究/2026-10-28-热点失败取证”。元数据解析不包含机器码反编译或在车机执行测试代码。

- [ART Android9隐藏API访问标志编码](https://android.googlesource.com/platform/art/+/b71a6853524fd0cb985b8126b8dbc96e3a7d918b/libdexfile/dex/hidden_api_access_flags.h)
- [Android非SDK接口限制及Android9分类说明](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces)

版本55=`0.2.22-ca75plus.13`，389项定向回归全部通过，mobile lint0错误、19警告。相同白名单签名v1/v2/v3、16KiB对齐、运行资产及平台回调签名未打包核验通过。APK SHA-256为`5042f9a70049401d7a8d69d51b118e74e3c517b1c39839361f7c175dad4677d5`。未重跑完整测试；历史失败和本轮中间失败证据保留。

没有变更HUD、音频、玻璃界面和卡片默认值。系统允许名单与单元测试不能替代真实热点OFF→ON验收；安装和设备状态操作需本轮明确确认。用户已要求交付直接安装，并确认驻车、手机导航退出；后续实装与实体热点结果单独记录。

文档由Codex（GPT-6.1 Sol）编写。
