# REQ.preserve-gameplay-and-availability

- Claim: 玩家、服主和同环境模组必须能在 YSM 的普通内容处理、网络分发或联动功能运行及出错时继续正常游戏。

平台范围、最低基线、运行依赖和设备可用性边界见[平台适配现状与原则](req-platform-availability.md)。
- Decision-status: Complete
- Motivation: [SCN.ordinary-mod-failure](#scnordinary-mod-failure)
- Constraints: [CON.client-input-untrusted](../shared.md#conclient-input-untrusted); [CON.normal-use-isolation](../shared.md#connormal-use-isolation); [CON.server-trust-has-hard-limits](../shared.md#conserver-trust-has-hard-limits); [CON.visual-only-scope](../shared.md#convisual-only-scope)

## SCN.ordinary-mod-failure

- Claim: 添加有问题的自定义模型、热加载、联网失败和可选联动版本不匹配属于需要隔离失败的日常使用场景；正常模型资产分发也会与游戏业务竞争网络资源。
- Motivation: [PG.preserve-host-gameplay](../shared.md#pgpreserve-host-gameplay)

## Select

| 任务涉及 | 决策包 |
|---|---|
| 普通内容失败、数据保护、启动阶段与连接隔离 | [failure-isolation](../decisions/failure-isolation.md) |
| tick 与 render 阻塞、后台并发、资产分发与 gameplay 消息竞争、MSPT 软指标 | [workload-budget](../decisions/workload-budget.md) |
| 可选联动失败、侵入范围与人工例外 | [integration-risk](../decisions/integration-risk.md) |
| 客户端输入校验、可信服务端的能力底线 | [trust-boundaries](../decisions/trust-boundaries.md) |
