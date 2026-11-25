# 宿主接入已知问题

正常职责见[宿主接入](../../architecture/integration/overview.md)。

## 可选 handler 注册回滚

`ExtensionEventHandlerLoader` 在事件注册失败后会对原总线上的同一实例调用 `unregister`，并继续接入其余处理器（职责见[失败隔离](../../architecture/integration/overview.md#失败隔离与声明寿命)）。

- **已知局限**：若第三方事件总线自身 `unregister` 失败，只能将撤销异常作为 suppressed 附加到原异常并输出告警，无法保证残留监听器已被清除。
- **未验证缺口**：目前自动化回归仅覆盖 loader、checker 夹具与独立事件总线，完整 Forge 启动扫描与生成 checker 的组合链路尚未端到端验证。
