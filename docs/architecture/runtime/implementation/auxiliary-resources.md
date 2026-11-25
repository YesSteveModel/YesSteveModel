# 进程辅助保留

本页落实[运行作用域与所有权](../overview.md#运行作用域与所有权)，说明实体或模型 lease 结束后仍保留在进程内的内存归属。

- **进程级静态表**：`StringPool` 强持有驻留字符串与编号至进程结束；粒子参数、页面导航记忆、locator 与扩展注册元数据由各自静态负责方保留，不随模型 lease 清理。
- **可复用执行缓冲与线程池**：`NativeRenderer`、native 模型包装对象与兼容查询容器复用静态或 `ThreadLocal` 缓冲容量。`ResponsiveExecutor` 与 transient 编译器执行容量归属进程，具体任务结束状态与私有 program lease 仍由实体或调用方管理。GUI 页面直接读取线程池最大容量（并发边界见[并发与执行边界](../../../development/infrastructure.md#并发与执行边界)）。
- **弱引用与实体保留**：`RuntimeContentStore` 以弱引用键关联读取 wrapper，避免永久强持有历史内容。动画登记与展示实体保留边界见[实体生命周期](../../animation/entity-lifecycle.md)与[客户端展示](../../client-presentation/overview.md)。
- **堆池缓存**：`ArrayHeapBuffer` 池化路径底层使用 Netty 全局 allocator，业务归还所有权后 allocator 仍可能保留空闲内存块复用于后续分配，进程 RSS 不会随归还立即下降（规则见[Buffer 所有权](../../../development/infrastructure.md#buffer-与所有权)）。
