# 连接与消息准入

本页定位[业务资格](../overview.md#连接业务资格与撤销)和[连接撤销交接](../overview.md#连接撤销交接)的代码落实。

`NetworkHandler` 唯一负责注册消息方向、类型、解码上限、附件策略（attachment policy）与处理器。`FrameCodec` 在分发消息前完成物理帧校验，传输层本身不持有业务组装状态。`ClientSessionRuntime` 与 `SessionProtocolHandler` 按同一个 `Connection` 路由并退役会话，跨线程延后执行时会再次核对原连接是否仍有效。

`BusinessSession` 是归属当前连接的业务资格视图，保存对应连接与已协商策略。只有在 `ACCEPT` 成功交给传输层之后才会发布；同一个等待中的 Hello 会复用原有实例。业务发送方直接读取该视图，不以目录是否 `ACTIVE` 作为发送前提。

客户端目录状态由模型会话维护，见[会话设计](../../model-management/sessions/overview.md)。当通道缺失或处于显式 Local 模式时，客户端不建立远端会话；显式 Local 模式对同一个 Hello 最多回复一次 `DECLINE`。协商阶段的 ping 会重发当前等待中的 Hello，而活跃阶段的 ping 只探测连通性，不会替换负责方或重置 ID 流。服务端只有在对应会话成功从 `PENDING` 转为 `DECLINED` 时才接受 `DECLINE`，迟到或重复的响应不会改写已经结束的协商状态。

目录集合的组装、提交或激活失败走目录与资源关闭路径，但保留业务资格；版本不匹配、非法帧、冲突 Hello、断连或连接替换则会同时撤销 `BusinessSession`。因此，仅凭客户端处于内置默认（intrinsic-default-only）状态，不能判断当前业务是否可用。

新连接替换与断开连接共用同一清理入口：先摘除当前的活跃连接，再将旧的传输、组装、激活与视图状态分别交回各自负责方结束。游戏服务端停止时关闭该服务下已接纳的传输和 dispatch；进程级的 Catalog 与 worker 寿命归[运行模型](../../runtime/overview.md)。
