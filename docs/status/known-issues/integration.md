# 宿主接入已知问题

正常职责见[宿主接入](../../architecture/integration/overview.md)。

## 可选 handler 注册回滚

`ExtensionEventHandlerLoader` 捕获 event bus 注册失败后只记录日志，没有撤销该 handler 已安装的 listener。若 bus 在异常前发生部分注册，handler 会残留部分行为，与[失败隔离](../../architecture/integration/overview.md#失败隔离与声明寿命)要求的撤销半注册不一致。

调用方缺少回滚已由源码确认；当前 bus 是否会部分注册后失败、残留是否可被触发，尚未实证。既有扩展资格与注册准备测试不覆盖此路径，不能把日志中的“跳过 handler”视为无残留保证。
