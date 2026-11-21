# 宿主接入已知问题

正常职责见[宿主接入](../../architecture/integration/overview.md)。

## 可选 handler 注册回滚

`ExtensionEventHandlerLoader` 在事件注册失败后，会对原总线上的同一实例调用 `unregister`。如果撤销同样失败，撤销异常会作为 suppressed 附加到原注册异常上，日志会明确提示可能仍残留监听器，其余处理器则继续正常接入，见[失败隔离](../../architecture/integration/overview.md#失败隔离与声明寿命)。

`ExtensionEventHandlerLoaderTest` 共 4 项测试全部通过、无跳过：在真实 eventbus 6.0.5 上，非法订阅方法报错前已经注册的监听器经 loader 回滚后不再响应，前后健康的处理器继续正常工作；测试同时覆盖资格拒绝、构造失败与回滚异常。夹具按当前反射顺序挑选出实际发生部分注册的案例，不依赖固定的方法扫描顺序。

目前验证仅覆盖 loader、checker 协议夹具及真实事件总线，尚未覆盖完整 Forge 启动扫描与生成 checker 的组合链路。此外，当第三方事件总线自身撤销失败时，无法保证残留监听已被清除。
