# CA75PLUS 定制模块

本分支以 **0.2.21** 的 `v0.2.21-hud-test` / `9af1457` 为基础。仓库 master 中已有的 0.2.22 改动未合入本分支。

| 位置 | 职责 |
|---|---|
| `api/` (`:vehicle-customization-api`) | 会话、导航、投屏、设置与诊断的通用扩展契约及分发器，不含车厂协议 |
| `changan-hud/` (`:changan-hud`) | 长安广播协议、状态机、开关、资源、初始化 provider 和测试 |
| `changan-projection/` (`:changan-projection`) | 自动启动后的单次全屏切换策略、设置、初始化与测试 |
| `shared/.../customization/UpstreamNavigationAdapter.kt` | 复用上游内部解析器、图标映射及串行工作队列的单一适配层 |

模块依赖方向为：`shared → api`，`changan-hud → shared + api`，`changan-projection → api`，`common → 两个长安模块 + shared`。shared 不依赖具体长安实现。初始化 provider 由模块清单合并到应用，向注册表注册；设置页按通用描述渲染，因此增加其他定制设置无需再写车型判断。

## 接入点

1. `settings.gradle.kts`：包含两个定制模块；`shared/build.gradle` 公开依赖 API；`common/build.gradle.kts` 引入长安实现。
2. `CarPlayController`：构建/重连开始、会话结束/关闭、原始导航帧三个扩展点。
3. `DiPlaySessionService.onTaskRemoved`：兜底结束；与会话关闭重复调用时输出必须幂等。
4. `InCallClusterBridge.broadcastSource`：在投屏的导航状态发送前通知扩展，保持原有握手。
5. `DiPlayActivity`：读取通用设置描述；`CarPlayHostActivity`：连接诊断监听。

扩展异常由 API 分发器隔离；导航帧分别复制给各扩展。模块必须自行排队耗时工作，不能堵塞手机接收线程。初始化 provider 只注册模块，不启动导航或发送 HUD 数据。

## 合并上游时

定制文件单独归目录可减少合并冲突，但不能保证任何上游重构都自动兼容。若上游改变解析器或生命周期，只调整适配层/通用入口，保留模块实现与测试；不要用旧版整个 Activity、Controller 或显示文件覆盖新版。

先运行：

```sh
python3 scripts/check_public_tree.py
python3 scripts/check_customization_integration.py
./gradlew :vehicle-customization-api:testDebugUnitTest :changan-hud:testDebugUnitTest :changan-projection:testDebugUnitTest :mobile:assembleDebug :mobile:lintDebug
```

`check_customization_integration.py` 核对模块依赖、初始化和关键调用点，避免合并后静默丢失入口。它是结构检查，不能证明运行时控制流或车机兼容性。还需完整回归及驻车实测。

完整回归维持原有失败可见：0.2.21 的共享/公共模块已有 24 项测试失败，缺失的 USB 测试夹具单独从该仓库 `b9f9489` 恢复；没有移植该提交的生产代码。定制模块检查在独立工作流中运行，完整回归仍运行并保留失败报告。

## HUD 定制策略

开关在本定制版始终显示，首次默认开启，不按 Build.MODEL 或 Android 版本隐藏/阻断。用户保存的关闭选择会保留。没有 interactive 接收包时只记录诊断，不循环发送；设置仍显示。已实测的硬件是梧桐 S311_MCA，去除显示条件不代表其他车机已完成兼容性验证。

输出跟随 CarPlay 会话，切换前台应用本身不结束。实际手机导航数据才触发输出，没有导航时不清理其他来源。协议和实车验收边界见 `docs/CHANGAN_HUD.md`。

自动全屏策略独立于 HUD，接入通用投屏启动、布局请求、手动接管、释放与重试策略接口。实际小屏请求触发最多3次带确认等待的全屏恢复，重复会话通知不重置策略，重连保留已确认全屏；不把不同协议枚举混用。详见 `docs/CHANGAN_PROJECTION.md`。

版本42还区分高德运行/缺失/不可见，避免hidepid导致的25秒等待与重复拉起。HUD模块内有独立的外部零值/退出覆盖补偿开关，只有当前有效手机指引才可恢复，有限流、握手静默与防回环；不会修改原厂应用。补偿不是接收端仲裁，是否消除目视闪烁仍待实车验证，见 `docs/CHANGAN_CRUISE_RECOVERY.md`。
