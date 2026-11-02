# 68：选择性吸收上游仪表与音频修复

版本`0.2.22-ca75plus.26-upstream.1`，内部版本68。基于67提交`c67493d`，选择性移植Gitee长安上游`eaf7f7a`的三项修复，不代表整条支线已升级为上游0.2.23。

## 本轮吸收

1. **仪表双流识别与裁剪。** Apple完整导航双流不再永久按SETUP顺序绑定地图与卡片；根据有效解码宽度识别小卡片和接近仪表宽度的地图。尺寸不明或两路都像全屏时不猜测，不把整屏图片扩大成卡片。完整coded buffer和crop rectangle一起传到显示层，以负偏移显示真实裁剪区，保留直角画面。普通地图/自绘卡片模式不启用双流换绑。
2. **解码器有界重试与资源缩减。** 内部卡片解码器的初始请求降为960×600。候选配置全部失败后，最多等800ms再试一轮；关闭或中断时取消，不在UI线程等待。没有新增常驻解码器、定时恢复循环或录屏功能。
3. **PCM单处降音与渐变。** 导航压低只作用于媒体PCM，不再与同样比例的AudioTrack音量叠乘。AudioTrack仅负责资源归属的0/1静音门控；主音频不归手机时立即静音，不用渐变泄漏旧音频。增益变化在一段PCM缓冲内平滑完成，立体声左右声道保持同一增益。

来源固定为[Gitee上游eaf7f7a](https://gitee.com/jiuyueji/diplay_changan/commit/eaf7f7a0671dc14605f81b6e35bf0f420f2aed0f)。采用最终代码思路，没有移植中途又撤回的试验。

## 为本项目保留的边界

- 保留67的目标手机蓝牙交接、通话保护、显式按键准备、单次HID、媒体显示令牌和暂停逻辑；不采用上游默认把按键交给车机蓝牙的策略。
- 保留64以来的HUD、原厂高德引导及投屏激活顺序，不改为已作废的直接全屏/注入方案。
- 保留关闭自绘卡片、普通地图选择以及UI外观设置。裁剪容器不添加圆角、模糊或CPU取帧。
- 本轮不加入暖重连窗口、新导航音量键轮询、车道解析、原车音乐律动写入或H.264诊断录制。
- 本轮不等同于完整Apple音频状态机改造；`changeModes`资源请求/借用/归还闭环仍是独立待办。

## 和直接复制上游的区别

Surface只能有一个生产者。只按顺序调用两个异步clearSurface，并不能保证两个解码线程已经真正释放旧Surface。因此本版加入线程确认：两边完成分离后才接入新Surface；新的换绑请求替代旧请求，关闭时不再接入。阻塞、替换及关闭三个反例已加入回归。

解码尺寸回调只接受当前存活的解码器，镜像解码器不修改主流身份。格式缓存用于后台重用和晚创建的仪表窗口，关闭/流结束时清除；UI处理时再核对sink、控制器代际和当前geometry对象，避免旧回调改动新会话。

仪表真实尺寸也可能晚于解码格式到达。首次发现仪表或检测到宽度变化时，复用现有监听在UI线程重放缓存，避免在尺寸未知阶段放弃识别后一直等待另一次格式变化；没有新增尺寸轮询。

## 主要文件

| 文件 | 作用 |
|---|---|
| shared/.../media/AndroidMediaSink.kt | 几何回调/缓存、Surface交接、卡片解码尺寸、重试与媒体增益接入 |
| shared/.../media/ScreenStreamGeometry.kt | 编码尺寸及裁剪区，检查越界和整数溢出 |
| shared/.../media/VideoDecoderRetry.kt | 最多两轮、支持取消的配置尝试 |
| shared/.../media/MediaPcmGain.kt | PCM16媒体增益及左右声道一致的渐变 |
| shared/.../media/VideoDecodeQueue.kt | Surface切换的完成确认 |
| common/.../ClusterStreamBinding.kt | 双流尺寸识别、会话重置与卡片几何选择 |
| common/.../CarPlayHostActivity.kt | 接入几何、绑定和窗口生命周期，不触碰投屏激活协议 |
| common/.../ClusterMapPresentation.kt | coded buffer负偏移和裁剪容器，普通卡片内容选择继续生效 |
| 对应测试、mobile/build.gradle.kts | 反例/回归、版本号 |

## 验证说明

双重降音反例已先在67上复现：6项测试中2项失败，保存在本地red-baseline.zip。所有旧断言保留，新增检查实际AudioTrack的左右音量，防止只测PCM计算却漏掉第二次衰减。新增回归还包括立体声渐变、资源拒绝立即静音、解码重试取消、双流乱序、裁剪越界和Surface生命周期。

最终定向检查362项：361项通过，1项旧失败；新增29项全部通过。失败的`ClusterMapPresentationTest.unrelatedPresentationDisplayIsNotUsedForTheCluster`已在完整67原源码上独立复现，相同断言和结果，未改测试或显示器选择策略。APK构建任务通过，lint为0错误、19警告；Gradle整轮退出非零是该旧测试导致，不能称为整轮全绿。白名单签名及16KiB对齐通过，无新增权限。APK SHA256为`366edbc5edaebe8b5e0949ec28a1c0821874a518f87cac89b106ee6854f79677`；最终记录保存在本地68版交付目录。已有NowPlayingArtworkQueueTest.emptyArtworkCanClearThePublishedImage失败保留，本轮为定向回归，不把它写成全项目测试全绿。

本版尚未实车验证。驻车安装另行确认；首次连接/冷启动、暖重连、双流与仅地图、导航降音、方向盘切歌、通话/Siri及实体HUD仍需分开验收。本地吸收不授权操作车机。

文档由Codex（6.1sol）编写。
