# 进程辅助保留

本页落实[运行作用域与所有权](../overview.md#运行作用域与所有权)，解释为何实体或模型 lease 结束不意味着进程保留全部消失。

`StringPool` 强持有字符串和编号直到进程结束。粒子参数、页面导航记忆、locator 与扩展注册元数据由各自静态 owner 保留，不随模型 lease 统一清理。

`NativeRenderer`、native model wrapper 和兼容查询容器复用静态或 ThreadLocal 容量。`ResponsiveExecutor` 与 transient compiler 的执行容量也不归单个实体；任务终态及私有 program lease 仍归实体或调用 owner。页面直接读取 pool 的实际最大容量，不另建动画池；daemon、优先级、排队与使用限制见[并发与执行边界](../../../infrastructure.md#并发与执行边界)。

`RuntimeContentStore` 以弱 key 关联精确读取 wrapper，不永久强持有全部历史内容。动画登记和展示实体的保留边界分别见[实体生命周期](../../animation/entity-lifecycle.md)与[客户端展示](../../client-presentation/overview.md)。

`ArrayHeapBuffer` 的 pooled 路径使用 Netty 全局 allocator。业务 ownership 归还后，allocator 仍可保留空闲 backing，不能据此推定 RSS 同步下降。底层规则见[Buffer 所有权](../../../infrastructure.md#buffer-与所有权)。
