# diplay-Changan（DiPlay 长安定制版）

在车机（长安 UNI-V 2022 款）上运行 **无线 CarPlay** 的适配版本。

**原作者**：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（v0.2.11）｜原项目文档站：[https://shihabal3amri.github.io/DiPlay/](https://shihabal3amri.github.io/DiPlay/)

本仓库基于上述原项目二次开发。

> ⚠️ 
>
> **仅供尝鲜，不对后续 Bug 负责。**
> 本项目仅在 
>
> **22 款 UNI-V 尊贵版**
>
> （飞鱼 OS / Android 9 / 已破解车机）上测试通过。
> 其他车型可能有用，但请
>
> **自行尝试**
>
> ，使用前请确认你的车机支持并接受相应风险。

**系统要求**：APK 支持 Android 7.1+（API 25）；Android 7.1–8.1 支持为新增能力（尚未在实车上验证）。无线支持 Wi-Fi Direct、车机热点或同一局域网 / Existing Wi-Fi。Android 9 的 Wi-Fi Direct 使用依赖固件的旧路径（生成组凭据、频段未经核实）；Android 10+ 会校验协商后的组频段。

***

## 本项目实现的功能

**CarPlay 接收端（基础能力，继承自 DiPlay）**：车机开启热点后，iPhone 通过无线方式建立 CarPlay 会话，实现导航、音乐、电话在车机屏幕上运行。

**针对长安 UNI-V（飞鱼 OS）的适配与修复**：

### 长安专属改造



* **仪表盘投屏**：新增通用 "仪表盘" 分区入口，支持**整屏镜像**（整个 CarPlay 界面投到仪表）与**导航流**两种模式；非比亚迪车机默认开启；Display 枚举带通用兜底（任意非默认 display），长安车机可直接使用

* **无线热点 2.4GHz 放行**：长安热点工作在 2.4GHz，修复原版对频段的强制检查导致的无法启动

* **热点地址 IPv4 优先**：修复长安 Manual 热点场景下 iPhone 无法发现车机 AirPlay 服务的问题（IPv6 link-local 兜底）

### 手势与仪表盘控制



* **三指左右滑飞屏**：三指左滑 / 右滑切换仪表盘显示内容（导航流 ↔ 整机界面镜像），切换结果持久保存。**注意：需要仪表盘先处于投屏状态**（原车高德在后台运行、仪表盘已有画面）才会实时生效；未投屏时仅保存模式，下次投屏自动按新模式显示。滑动语义为"前进 / 后退"：左滑前进（整机界面 → 导航流），右滑后退（导航流 → 整机界面 → 关闭投屏）

* **三指滑动判定优化**：左 / 右滑支持带轻微下倾的滑动（水平方向主导即可），不再受固定垂直偏移限制

* **三指下拉打开设置（可能偶尔失败）**：若在设置中将"下拉打开设置"设为三指，可能因车机触摸识别偶尔失败，**多尝试几次或改用四指下拉**

* **仪表盘画面调整（已可用）**：设置中的 "仪表盘画面调整" 支持调整仪表盘投屏区域（方框 rect）——通过滑块调整左上角位置与宽高，投屏画面等比填充到该区域

### 音频

* **导航与媒体音量分开调节**：支持分别设置媒体与导航的音频声道——**媒体声道可设置为 12，导航声道可设置为 10**（需车机支持高级音频通道映射，设置中开启"高级音频通道映射"后生效）

* **蓝牙设置需关闭"媒体音频"**：请在车机蓝牙设置中关闭 iPhone 的"媒体音频"选项（保留通话），否则可能出现无声音，或方向盘切歌时一下切换两首（车机蓝牙 AVRCP 与 CarPlay 各发送一次）

### 车机联动



* **自动启动原车高德**：连接时自动拉起原车高德（com.autonavi.amapauto）并立即返回 DiPlay—— 长安仪表盘投屏依赖原车高德后台运行；高德进程若被关闭 / 退出，下次连接时会自动重新拉起

* **自定义车辆图标**：默认使用**长安车标**（256×256 程序生成），OEM 名称默认 **CA**，可在设置中更换

* **昼夜模式跟随**：自动跟随车机昼夜主题切换 CarPlay 深色 / 浅色外观

* **画面调节**：CarPlay 画面裁剪 / 缩放调节（CarPlayPicture）

* **Wi-Fi Direct 连接**：支持 Android 9 legacy P2P 组（DIRECT-xx）连接方式

* **现有 Wi-Fi / 同局域网连接**：不创建热点，直接复用车机已连接的现有 Wi-Fi（如路由器 / 随身 Wi-Fi）。设置中切换 "现有 Wi-Fi" 模式并填写外部 Wi-Fi 的 SSID 与 WPA2 密码（开放网络可留空）后，车机与 iPhone 保持同一网络即可建立 CarPlay；要求路由为 WPA2 或 WPA2/WPA3 混合模式并关闭客户端隔离

### 连接稳定性修复



* **主动下发 Wi-Fi 配置（0x5703）**：主循环主动推送 + transport 就绪后重发，解决 iPhone 不请求热点配置导致的卡死

* **Android 9 TCP 半开检测**：原版在 API<29 完全跳过 keepalive 调优（断线后界面永久卡死），本版用 `Os.dup` 实现约 12 秒判死并自动回到连接界面重试

* **断线自动重连**：iPhone 断开后自动退回连接界面并指数退避重试（上限 30s）；尺寸未知时等待屏幕检测后自动拉起

* **重连防过期**：restartGeneration 校验，旧的重连回调不会干扰新会话

* **USB 有线连接**：USB 连接取决于车机 ROM 限制，部分车机可能无法识别到手机（仅充电、无授权信任弹窗），建议更换数据线或改用无线连接

### 诊断与适配



* **无文件选择器诊断导出**：长安车机缺少 DocumentsUI，支持保存到应用目录 / 屏幕直接显示诊断全文 / 分享（自动清理，仅保留最新 8 个报告）

* 屏幕诊断快照：连接状态、热点后端、iAP2 命令序列、AirPlay TCP 状态一目了然

## 版本



* 当前版本：**v0.2.14-hud-test**（versionName `0.2.14-hud-test`，versionCode 33，debug 签名，包名 `com.shihab.diplay.hudtest`）

* Release 页提供编译好的 APK（`dica.apk`），可直接覆盖安装

## 快速开始（用户）



1. 从 [Releases](https://gitee.com/jiuyueji/diplay_changan/releases) 下载最新 `dica.apk`

2. 安装到车机（车机需允许未知来源安装）

3. 打开 DiPlay，开启车机热点，iPhone 蓝牙配对后进入 CarPlay

> 认证说明：DiPlay 使用本地离线 MFi 认证资产编译（不随源码 / APK 分发）。编译与使用仅供个人尝鲜，请勿用于商业用途。

## 构建（开发者）

环境：Android Studio / JDK 21 / Android SDK 37（compileSdk 37）



```
# 1. 准备认证资产（不随仓库分发，自行生成或从原项目获取）
export DIPLAY_AUTH_ASSETS_DIR=/path/to/diplay-auth-assets   # 内含 offline-mfi/{certificate.p7b, identity.pk8}

# 2. 编译
./gradlew assembleDebug --no-daemon
# 产物：mobile/build/outputs/apk/debug/mobile-debug.apk
```

本地构建需自行提供 `DIPLAY_AUTH_ASSETS_DIR`（含 `offline-mfi/` 目录）；缺失时仅能构建不带认证身份的源码包。

## 致谢与协议



* 原作者：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0）—— 本项目的全部基础能力均源自该开源项目

* 仪表盘 Display 枚举兜底思路参考 AMap Companion

* 本仓库遵循 **GPL-3.0**（见 [LICENSE](LICENSE)）

* 仅供个人尝鲜与研究，禁止用于商业量产



***

> 本项目由 
>
> **豆包 AI（Doubao）**
>
>  辅助生成与维护。