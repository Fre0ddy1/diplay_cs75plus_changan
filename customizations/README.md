# CA75PLUS 0.2.22 定制模块

本支线基于Gitee发行标签 `v0.2.22-hud-test-20261025`，提交 `c83b33b3220c3bbe937a1996a98acd4f25e3dac3`。0.2.21维护线独立保留；master只同步上游原历史。

| 位置 | 职责 |
|---|---|
| `api/` | 会话、导航、设置、诊断、投屏生命周期及控制的通用契约，不含车厂编号 |
| `changan-hud/` | 标准高德广播到interactive的HUD输出、会话保持、投屏握手静默、外部覆盖补偿 |
| `changan-projection/` | 等待实际小屏反馈后有限切全屏、取消与确认策略 |
| `changan-audio/` | 独立媒体键策略与设置，默认CarPlay，关闭后明确交给外部控制；不重写音道 |
| `compact-logging/` | 发行日志筛选、文件/历史/写入队列预算；独立于车辆协议与音频行为 |
| `app-lifecycle/` | 用户退出后的持久停止、仅恢复本应用组件与有界清理；手动打开后恢复 |
| `glass-ui/` | 明亮磨玻璃主题、静态柔化背景、玻璃面板、控件色板与首页文案；不处理CarPlay视频 |
| `hotspot-startup/` | 打开/返回DiPlay前台时检查车机热点，复用现有开启接口，合并请求与有限补试、系统授权入口 |
| `android9-hotspot-stubs/` | 旧系统回调的compileOnly签名；禁止作为运行依赖或打入APK |
| `shared/.../customization/UpstreamNavigationAdapter.kt` | 复用上游解析器、图标映射、串行队列的单一边界 |

依赖：`shared → api`，`changan-hud → shared + api`，`changan-projection → api`，`changan-audio → api`，`common → 定制模块 + shared`。模块私有provider负责注册，设置通过通用描述展示，首次默认开启、始终可见，不按车型隐藏；保存的关闭状态保留。初始化本身不发导航指令。

## 新版接入

- Controller分发会话和原始导航帧，SessionService退出任务兜底结束，重复关闭幂等。
- Host连接诊断、设置刷新和手动接管；新版通道watcher通过通用策略决定是否激活，自动启动明确标记`automatic=true`。
- InCall桥转发布局反馈和投屏源握手状态，提供既有Binder控制入口；模块不新建厂商连接。
- 启动时通道已开则只接纳真实InCall状态，补发本地生命周期/缓存布局事件，不重发激活序列；后续仍按实际布局决定是否恢复全屏。
- 停止布局重试和允许关闭通道恢复分别判断：正在全屏恢复时暂停watcher，确认全屏后不再重申布局，但将来通道不可用时仍可执行上游有限激活。
- 新上游已删除CarAmapLauncher；本支线不复活旧/proc探测器，采用500ms通道采样、真实横向尺寸就绪以及5/15/30/60秒有限激活。0.2.21的三态进程修复仅留在旧维护线。
- 保留双路原生导航流。新增仅地图可选项，整个本地导航卡片组只在自定义模式创建；详见`docs/CHANGAN_CARDS.md`。

## 合并和验证

```sh
python3 scripts/check_public_tree.py
python3 scripts/check_customization_integration.py
./gradlew :vehicle-customization-api:testDebugUnitTest :changan-hud:testDebugUnitTest :changan-projection:testDebugUnitTest :changan-audio:testDebugUnitTest :app-lifecycle:testDebugUnitTest :mobile:assembleDebug :mobile:lintDebug
```

结构检查不能替代运行测试。新版须与精确c83原始基线逐项比较，不能沿用旧版24项失败的数字；完整结果见`VALIDATION.md`。合并上游若变更解析/生命周期，优先调整边界和通用入口，不能覆盖整个新版Activity来消除冲突。

HUD保留版本39的导航会话保持修复。巡航冲突方案是外部清零后的受限补偿，不能拦截原厂广播，也不保证物理HUD绝不闪烁。45版已在S311观察到HUD、内容选择、双流内容显示及关闭ADB后的自动全屏成功样本；重连长期稳定性仍需观察，详见验证记录。

发行应用名为`diplay-cs75plus`。品牌资源和上游合并保护见[发行维护](../docs/CS75PLUS_DISTRIBUTION.md)，不将显示名称混入车辆协议模块。

48候选增加独立音频策略模块，删除47的混合补发方案；帧后来源接管与资源/音流/焦点处理见[48实现说明](../docs/CS75PLUS-48-音频与内容接管.md)。新音频偏好不继承旧蓝牙优先值，但保留旧值供回退；后续用户明确关闭新策略会被保存。

50发行加入`compact-logging`，通过通用`DiagnosticPolicy`接入：常规会话单文件256 KiB、两份历史、队列128行；仅清理应用私有目录已知旧日志槽位。上游未注册策略时保持原筛选与历史数量。视频计数继续更新以供冻结检测，仅压缩诊断输出。文件容量按UTF-8字节在批次中分段轮转，避免一次批写越界。

52候选从上游master选择性吸收显示器上电就绪、服务重启后丢弃过期回调及用户关闭后暂停自动恢复的行为。恢复策略在`changan-projection/ProjectionPowerRecovery`中，每个显示器/服务代次最多4次；不套用上游无限5秒重试。首页与连接页设置共用“退出DiPlay”，退出后USB、开机与后台入口被拦截，直到手动打开主启动器。实现、边界与实车验收项见[52实现说明](../docs/CS75PLUS-52-上电恢复与退出.md)。

53候选重设计首页、设置分类页与CarPlay连接/设置菜单。`glass-ui`统一主题资源和轻量Drawable；在Android9上使用静态渐变柔化背景、半透明表面、描边与阴影实现磨玻璃观感，不请求Android12背景模糊，也不截取或模糊实时视频。未保存的仪表内容改用仅地图，单独卡片可见性和音乐封面默认关闭；显式保存的选择继续优先。详见[53界面说明](../docs/CS75PLUS-53-磨玻璃界面.md)。

54候选新增`hotspot-startup`，在“车载热点”无线模式下打开/返回DiPlay前台时恢复原车热点。缺省开启、显式保存的关闭优先；已有热点不重启，使用原配置。未授权时提供系统“修改系统设置”入口，不使用ADB授予权限。复用shared既有`CarHotspotTethering`开启与实际状态确认；说明及静态依据见[54热点恢复](../docs/CS75PLUS-54-启动热点恢复.md)。

54实车失败后，55按本车框架元数据改用允许名单里的ConnectivityManager框架入口与回调，避免直接反射受限Binder方法。实现通过通用PlatformHotspotStarter提供，状态确认和日志仍复用shared。APK额外核验编译桩未打包，不修改系统隐藏接口策略。详见[55说明](../docs/CS75PLUS-55-热点接口修正.md)。
