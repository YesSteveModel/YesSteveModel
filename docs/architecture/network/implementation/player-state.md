# 玩家状态落实

本页定位[玩家状态契约](../player-state.md#权威模型)的写入者、报告者与真实终态。

## 权威模型

`LocalPlayerStateReporter` 持有当前 `BusinessSession`、权威模型上下文与报告观测。`PlayerStateHandler` 从已认证 sender 和捕获的 exact `Connection` 定位 `ServerModelSession`；排队到 owner thread 后通过 `ServerModelService` registry 重验，再验证 active、policy、主体与内容。真实 FULL baseline 保存在该 session，没有另一套报告 registry。

`ClientDemandCoordinator` 为选择发送和实体 scope 分别应用连续意图。`SelectModelResult` 只交给当前 intent；ID 17 权威更新不按本地 intent 丢弃，匹配 FULL 安装由 entity binding 反馈。

## 客户端报告机会

Reporter 在原 installation barrier 内捕获 Roaming 值、presence 与 token，并在同一安装的有效窗口投影、编码和尝试发送。退休优先则整个机会失效，不能从 replacement 重新采样。

Reporter 在宿主调用前提交观测进度；发送失败不回写 dirty。服务端完整应用 FULL 才登记 baseline，广播失败不撤销 baseline。Tracker 与周期机会独立形成当前 FULL，不能从本地提交结果推断送达。

## 服务端应用与下行投影

`ControlHandler` 的服务端入口不引用 client capability 类型；下行转交 CLIENT-only `ClientControlHandler`。这保持 dedicated server 的类验证边界，使服务端选择路径可更新持久 capability 并广播权威 FULL。

表现输入如何进入求值见[动画输入](../../animation/implementation/state-inputs-and-sync.md)。完整多人和失败交错的证据边界见[网络问题](../../../status/known-issues/network.md)。
