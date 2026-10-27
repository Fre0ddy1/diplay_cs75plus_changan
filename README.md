# diplay-cs75plus

面向 **2022款第二代长安 CS75PLUS／梧桐 S311_MCA（Android 9）** 的 DiPlay 定制版，将 iPhone CarPlay 导航接入车机主屏、仪表投屏与实体 HUD。

[下载发行版](https://github.com/Fre0ddy1/diplay_cs75plus_changan/releases/latest) · [当前定制分支](https://github.com/Fre0ddy1/diplay_cs75plus_changan/tree/codex/ca75plus-hud-0.2.22) · [定制模块说明](customizations/README.md) · [验证记录](customizations/VALIDATION.md)

## 本项目增加了什么

- **长安 HUD 导航**：读取 CarPlay 导航数据，通过本车原厂导航接口显示转向、距离和路名；不需要高德助手转发。
- **导航保持与结束清除**：静止、切换到车机桌面时保持有效 HUD 指引；手机结束导航或 CarPlay 会话结束后清除。
- **仪表自动全屏与重连恢复**：接收原车仪表反馈后自动切换全屏；针对断连重连、新窗口、旧状态缓存增加有限恢复和回调隔离。
- **仪表内容选择修复**：可选择“仅地图”“地图和自定义卡片”“Apple 完整导航”；选择非自定义模式时整组 DiPlay 导航卡片隐藏。
- **保留上游原生导航卡片**：支持上游双路地图／指令卡视频。CarPlay 视频卡、DiPlay 自绘卡和原车仪表转向卡是不同来源，详见[卡片说明](docs/CARPLAY_NATIVE_CARD.md)。
- **独立定制模块**：HUD、仪表策略和通用接入契约分开维护，便于继续合并上游更新。定制开关始终显示，首次默认开启，保留用户之后的关闭选择。

基础 CarPlay 连接、音乐、电话、显示校准和其他通用能力沿用上游。上述实车结论限于本车和测试条件，不表示其他长安车型均已验证。

## 下载与升级

当前发行版：**0.2.22-ca75plus.4（内部版本46）**，应用显示名 **diplay-cs75plus**。

支线新增 **48版修复候选（0.2.22-ca75plus.6）**：根据早间日志补强视频帧后的仪表来源接管；默认使用CarPlay媒体控制，删除47实验性的蓝牙反馈补发，区分实际音流、资源归属与Android焦点，并增加HUD长路名诊断。详见[实现与实车验收边界](docs/CS75PLUS-48-音频与内容接管.md)。48尚未实车验收，发行页的46版保持不变；[47说明](docs/CS75PLUS-47-修复说明.md)保留为历史记录。

1. 从本仓库 [Releases](https://github.com/Fre0ddy1/diplay_cs75plus_changan/releases) 下载 APK，可用随附件提供的 SHA256 校验文件核对。
2. 已安装本项目同签名测试版的用户可覆盖升级，保留应用数据和 CarPlay 配对；系统包名继续使用 `com.shihab.diplay.hudtest`。
3. 安装完成后打开 `diplay-cs75plus`，按现有方式连接 iPhone CarPlay。
4. 在驻车状态下检查手机高德导航、HUD、仪表内容选择及断连重连。

46版在已实车使用的45版基础上更改应用名称、版本信息和项目说明，沿用相同构建方案与白名单签名。未把新的音频参数或待验证方案纳入此次发行。

## 本车使用条件与已知限制

- **仪表投屏时关闭 ADB**：本车多轮测试中，ADB开启会干扰仪表投屏；HUD与仪表投屏需分别判断。
- **保留原厂高德后台**：仪表投屏依赖原厂环境准备。使用手机高德导航时，车机高德保持未导航状态。
- **关闭原厂自动巡航**：原厂巡航仍可能持续覆盖导航数据，导致HUD反复闪烁；当前补偿不能彻底消除此竞争。关闭自动巡航并退出巡航后已观察到恢复稳定。
- **自动全屏已有成功样本，仍需长期观察**：45版已观察到重连后自动全屏；较早日志也有长等待和未确认结果，部分轮次ADB状态不完整，不承诺每次即时恢复。
- **原车转向卡保留**：接入HUD后，速度表上方可能同时显示原车转向卡；本版没有隐藏它。“仅地图”只控制相应视频／自绘内容，不关闭实体HUD或原车卡。
- **音频设置保持原样**：媒体12、导航10继续沿用；原厂导航和DiPlay导航均已观察到进入导航bus3。音乐输出及主观响度差仍待固定音量条件下补充对照，不据此调整音量曲线。

Android最低版本声明为API25，但本定制功能仅在上述Android9车机验证。其他系统与车型需要单独适配。

## 版本与上游关系

| 代码线 | 用途 |
| --- | --- |
| `master` | 保存／同步上游原始历史 |
| `codex/ca75plus-hud-0.2.22` | 当前发行与后续CS75PLUS定制 |
| `codex/ca75plus-hud-0.2.21` | 旧版独立维护线 |

本发行基于 Gitee 长安定制版标签 `v0.2.22-hud-test-20261025`（`c83b33b`），承接本仓库0.2.21维护线的HUD和仪表修复。发行标签指向定制分支的提交，master不合入本次定制改动。

Gitee长安定制版原始车型说明保留在[历史长安README](docs/UPSTREAM-CHANGAN-README.md)；公版文档见[原始README](docs/UPSTREAM-README.md)／[中文说明](docs/UPSTREAM-README.zh-CN.md)。UNI-V／飞鱼系统的参考结论不直接当作本车结论。

## 开发与合并上游

定制代码入口：

- `customizations/changan-hud/`：本车HUD协议、会话保持及外部覆盖处理。
- `customizations/changan-projection/`：自动全屏、重连确认和有界恢复。
- `customizations/changan-audio/`：CarPlay媒体键策略与独立设置；不改用户音道配置。
- `customizations/api/`：不含厂商编号的通用契约。
- `shared/.../customization/UpstreamNavigationAdapter.kt`：对上游导航解析的单一适配边界。
- `mobile/src/main/res/values/strings_cs75plus.xml`：独立应用品牌资源；集成检查防止名称被上游覆盖。

源码构建需 JDK25、项目所要求的Android SDK与Gradle环境。可先执行：

```sh
python3 scripts/check_public_tree.py
python3 scripts/check_customization_integration.py
./gradlew :vehicle-customization-api:testDebugUnitTest :changan-hud:testDebugUnitTest :changan-projection:testDebugUnitTest :changan-audio:testDebugUnitTest :mobile:assembleDebug :mobile:lintDebug
```

普通源码构建不含CarPlay离线认证身份。独立可连接APK需显式提供本地 `DIPLAY_AUTH_ASSETS_DIR`，目录内含所需 `offline-mfi/` 输入，再执行 `:mobile:assembleStandaloneDebug`。发行APK包含运行所需认证数据；原始认证输入、APK签名私钥和车机日志不进入Git或源码包。

测试应分别记录定向回归、全量基线和实车结果。[验证记录](customizations/VALIDATION.md)保留上游既有失败，不把定向通过写成全量通过。

## 致谢与许可证

- 原始 DiPlay：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)。
- 长安定制基础：[jiuyueji/diplay_changan](https://gitee.com/jiuyueji/diplay_changan)。
- 本车CS75PLUS适配：[Fre0ddy1/diplay_cs75plus_changan](https://github.com/Fre0ddy1/diplay_cs75plus_changan)。

源代码沿用 **GPL-3.0**，见 [LICENSE](LICENSE)。保留上游作者、历史与许可证。
