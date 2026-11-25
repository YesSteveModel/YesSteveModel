# 连接与消息准入

本页定位[业务资格](../overview.md#连接业务资格与撤销)和[连接撤销交接](../overview.md#连接撤销交接)的代码落实。

- **帧分发与连接路由**：`NetworkHandler` 唯一负责注册消息方向、类型、解码上限、附件策略（attachment policy）与处理器。`FrameCodec` 在分发前完成物理帧校验。`ClientSessionRuntime` 与 `SessionProtocolHandler` 按同一个 `Connection` 路由并退役会话，跨线程延后执行时复核原连接有效性。
- **业务资格视图**：`BusinessSession` 归属当前连接并保存已协商策略，仅在 `ACCEPT` 成功交给传输层后发布（同一等待中 Hello 复用原实例）；业务发送方直接读取该视图，不要求目录已 `ACTIVE`。
- **协商与 Ping**：通道缺失或显式 Local 模式下客户端不建远端会话，显式 Local 对同一 Hello 至多回一次 `DECLINE`。协商期 ping 重发等待中的 Hello，活跃期 ping 仅探测连通性。服务端仅在会话从 `PENDING` 转为 `DECLINED` 时接受 `DECLINE`，迟到或重复响应不改写已结束状态。客户端目录状态归[会话设计](../../model-management/sessions/overview.md)。
- **故障边界区分**：目录组装、提交或激活失败走目录与资源关闭路径，但保留 `BusinessSession`；版本不匹配、非法帧、冲突 Hello、断连或连接替换同时撤销 `BusinessSession`。因此内置默认（intrinsic-default-only）状态与业务资格正交。
- **清理入口**：新连接替换与断开连接共用同一清理入口：先摘除活跃连接，再由各负责方分别结束旧传输、组装、激活与视图状态。游戏服务端停止时关闭该服务已接纳的传输与 dispatch；进程级 Catalog 与 worker 寿命归[运行模型](../../runtime/overview.md)。
