# 66 仪表全屏请求参数模拟试验

仅在 `codex/ca75plus-lvds-request-probe-66` 试验支线启用，版本66 / `0.2.22-ca75plus.24-probe1`。恢复使用现成64包，不重编恢复包。不合入master，不作为发行版。

## 已核实的入口

65实车手动菜单“多功能键→导航→全屏导航”对应以下原车日志：

```
16:44:27.681 onData:cmd=0x1b 0x5 dataLen=1 data=3
16:44:27.681 IP request lvds code=3
16:44:27.681 current auto status 2 / callback lvds code 3
16:44:27.683 sendLVDSACKResponse ack_code=3 error_code=0
16:44:27.684 DiPlay INavigateCallback code=1 value=3
16:44:28.205 DiPlay INavigateCallback code=2 value=3
```

原厂APK静态代码：`com.incall.interactive.socket.iowork.datahandle.IPDataCallback.dealLVDSCode(3)` 检查 `IP_REQUEST_LVDS` 功能开关，再进入 `DoubleNavigateManager.dealLVDSRequest(3)`。原车Socket仅接纳两个固定来源IP；普通APK的本机连接不能据此视为仪表请求。

本试验复用现存 `IPDataCallback.mInstance`，调用同一个 `dealLVDSCode(3)`。不创建替代接收器、不改车型开关、不改导航管理器状态、不覆盖Socket连接。原厂APK参考SHA-256为 `2b2973c9f9a83b121aa87942200883dabec3d48edf85fbe850b159c3cf92cfc5`；注入前还须核对当前车机包版本/代码兼容性。

## 链路和范围

```
当前无线CarPlay连接
  → 当前视频窗口就绪
  → 原有画面来源握手tx2[1]成功
  → DiPlay私有诊断事务（每连接一次）
  → 原车进程RAM中的临时Binder处理器
  → 原有IPDataCallback.dealLVDSCode(3)
  → 原有导航管理器/真实仪表应答路径
```

这是**参数层模拟**，不是在网络上伪造来源IP或重放完整TCP帧。私有事务 `0x00660001` 只属于临时诊断通道，不是发现了原车正式API。它携带原请求的 `0x1B / 0x05 / len1 / 3`，代理校验后只传入原函数实际接收的整数3；代理不会伪造DiPlay布局/通道回调。调用线程为Binder线程，与原收包线程不同，仍须实车验证。

APK中实验模块消费普通布局请求，阻止原有tx21滑动、tx8布局ACK和tx1完成事务；保留高德前置准备、画面来源握手及已有源状态通知。缺少临时代理、异常、无响应或原接收实例未就绪时均不补发、不回退滑动逻辑。视频窗口替换不重新发送；新的无线连接才重新获得一次尝试机会。

HUD、CarPlay音频、热点和UI沿用64功能。本试验不合入暂停验证的61吸收支线。

## 临时代理约束

- 只处理DiPlay包对应UID、固定接口令牌、固定协议版本和完整参数，拒绝其他命令及额外数据。
- 同一请求票据只执行一次；先消费票据再调用原函数，异常也不重发。10分钟内最多3个不同请求。
- 到期拒绝新请求，并恢复原Binder处理；其他原有事务始终交给原实现。
- 为允许用户关闭ADB，脚本加载成功后使用Frida `eternalize()` 保留在进程内。计时到期恢复方法不等于卸载整个调试运行库；重启车机才能可靠清除已注入运行库。系统休眠时定时恢复可能延后，但elapsed-time检查仍拒绝过期请求。
- `original-handler-returned` 只证明原函数返回，不证明全屏、画面来源或无小屏过渡。

Frida服务二进制、Java桥依赖、编译bundle和日志只留本地，不进Git/APK。当前方案需要原车进程的调试/附加权限，尚未验证现车权限和Android ROM兼容性；不为此改系统APK、解锁、重刷或放宽SELinux。

## 文件职责与验证

- `LvdsRequestProbe.kt`：连接与视频窗口门控、一次性私有事务、无代理时停止。
- `agent/agent.js`：在原车进程临时接入、校验来源、调用原接收函数、过期恢复。
- `agent/attach.mjs`：核对目标PID和进程名，加载已编译脚本；只有显式批准注入后才执行。
- 通用API和 `InCallClusterBridge`：两个调用点，替代布局发送并通知当前画面来源握手完成；车辆编号仅放实验模块。
- 模块测试与 `agent/agent.test.mjs`：覆盖一次发送、过期窗口、连接替换、无代理、不重试、其他事务透传、UID/参数校验和期限/次数上限；common集成测试覆盖禁止普通布局写入但保留来源握手。

本地编译、测试和签名不能代替实车权限、注入稳定性和实体仪表验证。车机安装、上传/启动诊断服务、附加原车进程及试验触发均需要用户本轮明确同意。

## 实车计划

1. 驻车、退出手机导航、断开CarPlay。只读核对ADB身份/ABI、当前Incall版本、目标PID及可用调试权限；没有可用权限就停止，不改变安全配置。
2. 获得具体授权后，只覆盖安装66并保留数据，上传临时诊断服务到专用临时路径，以本机环回监听启动，附加唯一目标进程并记录准备完成时间。
3. 用户关闭ADB，只连接一次CarPlay，不选小屏/全屏、不三指、不重复重连；观察是否直接全屏、是否仍有小屏、是否为CarPlay地图，并记录HUD/音乐是否正常。
4. 用户重新开启ADB，仅取这轮关键日志。对齐66发送票据、原函数日志、新布局3/通道3及用户目视；不能只看私有事务返回值。
5. 保存结果后按另行确认的动作停止/移除临时服务；重启清除注入运行库。若需恢复，安装已有64同签名包，不卸载或清数据。

## 2026-10-31本地核验与现车权限

66已编译并完成与64相同的白名单签名，v1/v2/v3及16KiB对齐核验通过。173项Gradle定向测试和4项代理逻辑测试通过；lint为0错误、19警告。测试使用替代端点验证约束，不代表Frida已在原车ROM运行。APK SHA-256：`44be2738f022fc4bef568c434b4f4be983fb4c6419eac14fb636082548ac5223`。

18:12后只读核验：现车ABI为arm64，Incall仍为3.0.2(21)，重新复制的APK与上述静态参考哈希一致。ADB身份为uid2000且CapEff/CapPrm/CapInh均为0；Incall为uid1000且非debuggable；`ro.debuggable=0`。常见四个su路径不存在。SELinux当前为Permissive只是读取到的既有状态，本轮没有修改；这也不能替代跨UID附加权限。

当前白名单签名与Incall签名不同，不能把“白名单可安装”当作“具备系统用户/平台调试权限”。因此此时尚不具备已验证可用的进程附加路径，未安装66、未上传/启动服务、未附加/注入进程。即使尝试现有adbd的root能力也须另获授权，因为该命令会重启ADB；若系统拒绝，应停止，不为此解锁或修改系统。

参考：[Frida Android权限与ABI准备](https://frida.re/docs/android/)、[Frida17 Java桥依赖](https://frida.re/docs/bridges/)。
