# 0.2.21 CA75PLUS 模块化 HUD 本地验证

日期：2026-10-24。基线 `v0.2.21-hud-test` / `9af1457`，构建版本 `0.2.21-ca75plus.2-hud-test`，versionCode 38。master 的 0.2.22 生产改动未合入。

| 测试模块 | 用例数 | 失败数 |
|---|---:|---:|
| customizations/api | 8 | 0 |
| customizations/changan-hud | 29 | 0 |
| shared | 536 | 16 |
| common | 296 | 8 |
| samples/home | 4 | 0 |

共 873 项，849 通过、24 失败。**定制模块 37 项全部通过**，覆盖导航协议/状态、设置始终显示与默认开启、保留手动关闭、模块隔离及初始化。

shared/common 的 832 个既有用例与修改前对照基线逐用例结果完全一致，仍有 24 项原有失败；未跳过、删除或改弱断言。基线与本分支都恢复了缺失的 USB 测试夹具，来源 `b9f9489`，未移植该提交的生产代码。

mobile/home/maphost 三个应用构建完成，三项 lint 均 0 错误。公开树检查、`git diff --check` 及定制接入结构检查通过。验证使用 JDK 25.0.2、Gradle 9.5.0、SDK 37.0、Build Tools 36.0.0、NDK 28.2.13676358。

GitHub 的完整检查保留上述基线失败；独立的 `CA75PLUS customization checks` 工作流检查模块契约、编译接入和 lint。本文记录本机结果，不将尚未完成的远端运行视为通过。

这是离线代码与打包验证。新 APK 未安装到车机，真实手机导航、仪表投屏与 HUD 共存尚需驻车验收。取消车型条件和默认开启并不扩展已实测硬件范围。
