# session-isolation

- Requirement: [REQ.bounded-model-distribution](../requirements/req-bounded-model-distribution.md#reqbounded-model-distribution)
- Select: 会话归属与迟到结果
- Needs:
  - 会话失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [implementation](../../architecture/network/implementation/transport-and-session.md)
  - [design](../../architecture/network/overview.md)

## DD.session-scopes-remote-state

- Claim: 一次联机会话的目录、授权、模型选择和玩家状态不跨会话成为另一连接的事实。
- Rationale: 玩家更换服务器或重新连接后，内容和权限可能已经不同；旧通知或延迟取得的资产不能改变新环境中的选择与表现。

### BC.connection-scopes-session-work

- Claim: 会话结束或连接替换后，旧会话的迟到消息与工作结果不得写入新会话的目录、选择、授权或玩家状态。
