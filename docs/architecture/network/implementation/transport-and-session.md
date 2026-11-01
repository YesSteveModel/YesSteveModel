# 连接与消息准入

本页定位[业务资格](../overview.md#连接业务资格与撤销)和[连接撤销交接](../overview.md#连接撤销交接)的当前落实关系。

`NetworkHandler` 唯一注册消息方向、类型、decoded 上限、attachment policy 和 handler。`FrameCodec` 在 dispatch 前验证物理帧；transport 不持有业务 assembly。`ClientSessionRuntime` 与 `SessionProtocolHandler` 以 exact `Connection` 路由和退休，延后执行再次验证原连接。

`BusinessSession` 是 connection-owned 的业务资格投影，保存连接与已协商 policy。ACCEPT 成功交给 transport 后才发布；相同 pending Hello 复用实例。业务 sender 读取此投影，不读取 catalog `ACTIVE`。

客户端目录状态由模型会话维护，见[会话设计](../../model-management/sessions/overview.md)。Channel 缺失或显式 Local 不建立远端会话；显式 Local 至多 decline 同一 Hello 一次。Negotiating ping 重发同一 pending Hello，active ping 只探测连通性，不替换 owner 或重置 ID。Server 仅在同一 session 成功从 `PENDING` 变为 `DECLINED` 时接受 DECLINE，late/duplicate response 不改变已结束的协商。

Collection assembly、commit、activation 失败走 catalog/resource 关闭路径；版本、非法 frame、冲突 Hello、disconnect 或 replacement 同时撤销 `BusinessSession`。因此 intrinsic-default-only 状态本身不能判断业务可用性。

新连接与 disconnect 共用清理入口：先摘 current connection，再将旧 transfer、assembly、activation 与投影交回各自 owner 结束。Game server stopping 关闭该服务的 accepted transfers 和 dispatch，进程 Catalog/worker 归[运行模型](../../runtime/overview.md)。
