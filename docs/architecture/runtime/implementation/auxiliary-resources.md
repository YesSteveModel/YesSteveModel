# 进程辅助保留

本页落实[运行作用域与所有权](../overview.md#运行作用域与所有权)，说明为什么实体或模型 lease 结束后，部分进程级内存保留并不会随之清空。

`StringPool` 会强持有驻留的字符串与编号直到进程结束。粒子参数、页面导航记忆、locator 以及扩展注册元数据也都由各自的静态负责方保留，不随模型 lease 统一清理。

`NativeRenderer`、native 模型包装对象以及兼容查询容器会复用静态或 `ThreadLocal` 缓冲容量。`ResponsiveExecutor` 与 transient 编译器的执行容量同样归属进程而非单个实体，但具体任务的结束状态与私有 program lease 仍由对应实体或调用方负责。GUI 页面直接读取线程池的实际最大容量，不另建独立的动画池；daemon、优先级、排队与使用边界见[并发与执行边界](../../../development/infrastructure.md#并发与执行边界)。

`RuntimeContentStore` 通过弱引用键关联对应的读取 wrapper，避免永久强持有全部历史内容。动画登记和展示实体的保留边界分别见[实体生命周期](../../animation/entity-lifecycle.md)与[客户端展示](../../client-presentation/overview.md)。

`ArrayHeapBuffer` 的池化路径底层使用 Netty 的全局 allocator。即使业务层已经归还了所有权，allocator 仍可能继续缓存空闲内存块以便复用，因此不能据此假定进程 RSS 会立即下降。底层规则见[Buffer 所有权](../../../development/infrastructure.md#buffer-与所有权)。
