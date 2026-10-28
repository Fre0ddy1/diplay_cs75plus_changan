# 54候选：启动时恢复车机热点

长时间停车或休眠后热点可能关闭。现在打开DiPlay、从休眠或其他页面返回前台时，会在选用“车载热点”无线连接模式且开关开启的条件下检查并恢复；实际热点已经开启则不重复启动。旧接收进程/连接对象仍存在不作为跳过检查的理由。不是后台永久保活，也不修改车机系统的热点自动关闭策略。

“设置 → 连接 → 车机热点恢复”提供独立开关及缺少权限时的授权按钮。定制版缺省开启，已保存的关闭状态优先。无线直连、现有Wi-Fi及USB连接不进入该恢复路径。首次可能需要手动允许“修改系统设置”；授权页无法打开时给出提示，不绕过系统或自动执行ADB授权。

链路：`DiPlayActivity.onResume → 通用前台环境控制器 → hotspot-startup串行工作器 → shared既有CarHotspotTethering → 原车ConnectivityManager服务 → 读取Wi-Fi AP实际状态`。连接控制器沿用相同既有开启接口，底层同步锁和状态重查避免成功启动后重复请求；主线程不等待开启。

工作器合并重复前台事件；单次实际状态等待15秒，失败/超时最多等待2秒后补试一次。权限不足、接口不支持不盲目重复。退出后的持久停止标记、用户关闭开关或模式变更会取消未发出的后续操作。已经送入系统的请求不能撤回，应用不因此发送关闭热点命令。热点名称、密码、频段均使用原车保存配置，不调用配置改写、配对清理或Wi-Fi关闭接口。

## 静态依据与限度

本地原车`com.autopai.system.settings.apk`，SHA-256 `9ef557c8bf1ec300f612d19c5a6ca88912aacc4d7d52c6dac5403edf5f57829d`：

- `MobileDataActivity`持有`com.android.settingslib.wifi.tethering.pie.CarApPie`。
- `CarApPie.setApEnabled(true)`在AP状态12/13时不再启动，否则调用startTethering。
- `CarApPie.startTethering()`调用`ConnectivityManager.startTethering(0, true, callback, mainHandler)`。

这是APK静态观察，未执行该APK，不证明第三方应用已获得相同权限。既有DiPlay实现经Connectivity服务Binder请求同一种Wi-Fi tethering，再读取实际ON确认；[Android9服务源码](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r30/services/core/java/com/android/server/ConnectivityService.java)确认对应签名和权限检查。系统授权界面与canWrite依据为[Android WRITE_SETTINGS文档](https://developer.android.com/reference/android/Manifest.permission#WRITE_SETTINGS)。车机裁剪授权界面、隐藏接口限制及实际开启能力需要实测。

本地完整静态证据在`分析研究/2026-10-28-54版启动热点恢复/原厂热点静态证据.json`；不将车机APK或日志提交仓库。

## 模块边界

- `customizations/hotspot-startup`：默认策略、有限工作器、设置与授权文案、私有初始化provider及测试。
- `customizations/api`：前台环境/控制、可选缺省策略、设置操作与分类通用契约。
- `DiPlayActivity`：前台通知、保留无模块时的旧启动路径、渲染通用设置操作；模块只持有应用Context和不捕获Activity的读状态控制器。
- `CarHotspotSettings`：缺省值从可选模块获取；无模块仍默认关闭，保存值优先。原有BYD入口不改为全局放开。

54=`0.2.22-ca75plus.12`，继承53界面和卡片默认值、52退出/上电恢复及已有HUD和音频修复。本轮仅本地分析、构建与测试；安装、车机授权及热点开关试验需当前具体确认。

文档由Codex（GPT-6.1 Sol）编写。
