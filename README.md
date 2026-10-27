# diplay-cs75plus

面向 **2022款第二代长安CS75PLUS／梧桐S311_MCA（Android 9）** 的DiPlay定制版，基于DiPlay和长安定制版继续适配。

[下载APK](https://github.com/Fre0ddy1/diplay_cs75plus_changan/releases/latest) · [GitHub支线](https://github.com/Fre0ddy1/diplay_cs75plus_changan/tree/codex/ca75plus-hud-0.2.22) · [Gitee支线](https://gitee.com/freddy_q/diplay_cs75plus_changan/tree/codex/ca75plus-hud-0.2.22)

## CS75PLUS定制功能

- **实体HUD导航**：将CarPlay导航的转向、距离和路名接入原车HUD。
- **HUD保持与清除**：停车或DiPlay退到后台时保持导航，结束手机导航或断开CarPlay后清除指引。
- **仪表自动全屏**：适配原车仪表投屏、自动全屏、视频就绪后的画面接管和断连重连恢复。
- **仪表内容选择**：支持仅地图、地图与自定义卡片、Apple完整导航，保留CarPlay视频自带的转向和音乐卡片。
- **CarPlay媒体控制**：音乐经CarPlay播放，播放/暂停和方向盘切歌使用CarPlay控制，统一处理应用内部重复按键入口。
- **导航与音乐独立配置**：媒体和导航音道可分别设置，导航播报期间支持压低音乐音量，播报后恢复。
- **微信语音后恢复音乐**：优化音频焦点处理，本车已实测微信语音播放结束后音乐自动恢复。
- **精简日志**：保留连接、HUD、仪表接管、音频状态变化及错误事件；常规会话日志单份不超过256 KiB，保留两份历史，写入队列最多128行。
- **模块化定制**：HUD、仪表策略、音频控制和日志策略独立维护，便于继续合并上游更新。

## 上游功能

沿用上游的有线/无线CarPlay、导航、音乐、电话、Siri、车机位置回传、画面缩放/裁剪、主题跟随及连接方式设置。详细功能和配置参照[DiPlay](https://github.com/shihabal3amri/DiPlay)与[长安定制版](https://gitee.com/jiuyueji/diplay_changan)。

上游文档保留在[长安版说明](docs/UPSTREAM-CHANGAN-README.md)和[通用中文说明](docs/UPSTREAM-README.zh-CN.md)。

## 下载与使用

当前发行：**0.2.22-ca75plus.8（50）**，应用名称 **diplay-cs75plus**。

1. 在[发行页](https://github.com/Fre0ddy1/diplay_cs75plus_changan/releases/latest)下载APK，可使用附件中的SHA256校验值核对。
2. 同签名旧版可覆盖升级并保留应用数据、配对和设置，包名为`com.shihab.diplay.hudtest`。
3. 打开应用，按上游方式连接iPhone CarPlay。媒体和导航默认配置沿用本车的12、10，可在设置中分别调整。
4. 使用仪表投屏时保留原厂高德后台并关闭ADB；手机导航期间，原厂高德保持未导航、未巡航。
5. 根据需要选择仪表画面来源、显示内容和卡片位置，在驻车状态下完成配置。

常规日志保存在应用私有目录并自动轮转，升级后连接CarPlay会清理多余旧日志槽位；应用内仍可导出关键诊断信息。手动导出的文件和车机系统日志由各自功能管理。

## 注意事项

**务必关闭原车高德的「自动进入巡航」开关，以免影响HUD显示。**

## 源码与同步

当前定制支线为`codex/ca75plus-hud-0.2.22`，GitHub与Gitee同步维护相同提交和发行标签。`master`保留上游代码历史，0.2.21维护线独立保存。

定制模块见[customizations](customizations/README.md)。合并上游后执行：

```sh
python3 scripts/check_public_tree.py
python3 scripts/check_customization_integration.py
./gradlew :vehicle-customization-api:testDebugUnitTest :changan-hud:testDebugUnitTest :changan-projection:testDebugUnitTest :changan-audio:testDebugUnitTest :compact-logging:testDebugUnitTest :mobile:assembleDebug :mobile:lintDebug
```

构建使用JDK25及项目指定Android SDK。独立可连接APK需要显式提供本地`DIPLAY_AUTH_ASSETS_DIR`运行资产；认证原始输入、签名私钥、实车日志和APK不放入源码仓库。测试记录见[VALIDATION](customizations/VALIDATION.md)。

## 致谢与许可证

- DiPlay原项目：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)。
- 长安定制基础：[jiuyueji/diplay_changan](https://gitee.com/jiuyueji/diplay_changan)。
- CS75PLUS适配：[Fre0ddy1/diplay_cs75plus_changan](https://github.com/Fre0ddy1/diplay_cs75plus_changan)／[freddy_q/diplay_cs75plus_changan](https://gitee.com/freddy_q/diplay_cs75plus_changan)。

源代码沿用[GPL-3.0](LICENSE)，保留上游历史、作者署名与许可证。
