# 71版：断连时视频线程收尾异常

2026-11-02。在70版`3c75e0644f965cce3f692c0c8b93c6bfc2c8628f`基础上修复一个退出路径；保留70版全部蓝牙媒体策略和mainAudio资源逻辑，不更改音频解码、音量、按键和仪表启动策略。

## 实车发现

用户点击断开连接后退回桌面。应用日志18:05:16记录carplay-video线程的InterruptedException，调用栈为VideoDecodeQueue.poll → VideoDecoder.run(AndroidMediaSink.kt:1160)，随后PID为空。蓝牙恢复已在18:05:16.668成功，目标媒体策略读回100、恢复记录清空，因此不能把崩溃归因于策略恢复失败。

系统crash缓冲还有今天15:36:18的相同异常，属于早先记录；本次新崩溃以应用自有日志为据，分别保留。原车日志和设备地址只存本地，不提交仓库。

## 根因与最小修复

68版提交`5f585642`增加视频线程退出时释放待处理Surface回调的收尾循环，但使用`queue.poll(0)`。它实际上调用LinkedBlockingQueue的**带超时重载**，即使超时是0，获取锁时仍可响应中断。解码器close通过interrupt唤醒工作线程；重复close或正好退出循环时的中断会让finally中的poll再次抛出异常。外层catch无法捕获自身finally内的新异常，最终进入全局崩溃处理器，还可能漏掉Surface解绑完成回调。

- `VideoDecodeQueue.kt`新增`pollNow()`，使用JDK无参poll；无定时等待，不因线程中断而抛InterruptedException。
- `AndroidMediaSink.kt`仅把最终清理循环改为调用pollNow。正常解码循环的可中断等待保留，回调仍在工作线程锁外调用。
- `VideoDecoderShutdownTest.kt`直接实例化真实VideoDecoder线程，不配置硬件codec：持有工作线程所需的monitor，等待首次close到达清理阶段，再放入Surface回调并重复close。分别检查空队列和3个待处理回调，确保线程正常终止、无未捕获异常、回调恰好完成且在锁外执行。
- `mobile/build.gradle.kts`标记71 / `0.2.22-ca75plus.29-audio.2`。

修复前两项真实线程回归均捕获InterruptedException并失败，原始XML和构建日志保存在本地。修复后同一测试及70版相关测试共407项通过（失败/错误/跳过均0），应用构建通过，Lint为0错误、19条警告；集成和公开树检查通过。精确结果随签名包保存。本次不把全仓库既有失败隐藏或归零，也不把本地通过当作断连实车验收。

安装71需当轮授权，保留数据覆盖；实车只需重连播放后再次点击断开，核对不崩溃和蓝牙媒体策略恢复。70版首次连接、策略关闭、连续切歌不再自动暂停及本次断连策略恢复的实测记录独立保留。
