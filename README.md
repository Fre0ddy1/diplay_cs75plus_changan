# diplay-Changan（DiPlay 长安定制版）

在车机（长安 UNI-V 2022 款）上运行 **无线 CarPlay** 的适配版本，基于开源项目 [DiPlay](https://github.com/shihabal3amri/DiPlay)（v0.2.11）改造而来。

> ⚠️ **仅供尝鲜，不对后续 Bug 负责。**
> 本项目仅在 **22 款 UNI-V 尊贵版**（飞鱼 OS / Android 9 / 已破解车机）上测试通过。
> 其他车型可能有用，但请**自行尝试**，使用前请确认你的车机支持并接受相应风险。

---

## 这是什么

DiPlay 是一个运行在 Android 车机上的 CarPlay 接收端：车机开启热点后，iPhone 通过无线方式建立 CarPlay 会话，实现导航、音乐、电话在车机屏幕上运行。

本仓库在 DiPlay 0.2.11 基础上针对长安 UNI-V（飞鱼 OS）做了以下适配与修复：

### 长安专属改造
- **仪表盘投屏**：新增通用"仪表盘"分区入口，支持**整屏镜像**（整个 CarPlay 界面投到仪表）与**导航流**两种模式；非比亚迪车机默认开启；Display 枚举带通用兜底（任意非默认 display），长安车机可直接使用
- **无线热点 2.4GHz 放行**：长安热点工作在 2.4GHz，修复原版对频段的强制检查导致的无法启动
- **热点地址 IPv4 优先**：修复长安 Manual 热点场景下 iPhone 无法发现车机 AirPlay 服务的问题（IPv6 link-local 兜底）

### 连接稳定性修复
- **主动下发 Wi-Fi 配置（0x5703）**：主循环主动推送 + transport 就绪后重发，解决 iPhone 不请求热点配置导致的卡死
- **Android 9 TCP 半开检测**：原版在 API<29 完全跳过 keepalive 调优（断线后界面永久卡死），本版用 `Os.dup` 实现约 12 秒判死并自动回到连接界面重试
- **断线自动重连**：iPhone 断开后自动退回连接界面并指数退避重试（上限 30s）；尺寸未知时等待屏幕检测后自动拉起
- **重连防过期**：restartGeneration 校验，旧的重连回调不会干扰新会话

### 诊断与适配
- **无文件选择器诊断导出**：长安车机缺少 DocumentsUI，支持保存到应用目录 / 屏幕直接显示诊断全文 / 分享（自动清理，仅保留最新 8 个报告）
- 屏幕诊断快照：连接状态、热点后端、iAP2 命令序列、AirPlay TCP 状态一目了然

## 版本

- 当前版本：**v0.2.11-hud-test**（versionCode 30，debug 签名，包名 `com.shihab.diplay.hudtest`）
- Release 页提供编译好的 APK（`dica.apk`），可直接覆盖安装

## 快速开始（用户）

1. 从 [Releases](https://gitee.com/jiuyueji/diplay_changan/releases) 下载最新 `dica.apk`
2. 安装到车机（车机需允许未知来源安装）
3. 打开 DiPlay，开启车机热点，iPhone 蓝牙配对后进入 CarPlay

> 认证说明：DiPlay 使用本地离线 MFi 认证资产编译（不随源码/APK 分发）。编译与使用仅供个人尝鲜，请勿用于商业用途。

## 构建（开发者）

环境：Android Studio / JDK 21 / Android SDK 37（compileSdk 37）

```bash
# 1. 准备认证资产（不随仓库分发，自行生成或从原项目获取）
export DIPLAY_AUTH_ASSETS_DIR=/path/to/diplay-auth-assets   # 内含 offline-mfi/{certificate.p7b, identity.pk8}

# 2. 编译
./gradlew assembleDebug --no-daemon
# 产物：mobile/build/outputs/apk/debug/mobile-debug.apk
```

本地构建需自行提供 `DIPLAY_AUTH_ASSETS_DIR`（含 `offline-mfi/` 目录）；缺失时仅能构建不带认证身份的源码包。

## 致谢与协议

- 基于 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0）二次开发，仪表盘 Display 枚举兜底思路参考 AMap Companion
- 本仓库遵循 **GPL-3.0**（见 [LICENSE](LICENSE)）
- 仅供个人尝鲜与研究，禁止用于商业量产
