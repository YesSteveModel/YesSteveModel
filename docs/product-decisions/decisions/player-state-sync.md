# player-state-sync

- Requirement: [REQ.bounded-model-distribution](../requirements/req-bounded-model-distribution.md#reqbounded-model-distribution)
- Select: 观察者状态取得、best-effort、游戏事实与本地输入
- Needs:
  - 观察者主动限制本机表现: [DD.local-player-controls-presentation](local-presentation-control.md#ddlocal-player-controls-presentation)
  - 输入校验: [DD.validate-client-before-effect](trust-boundaries.md#ddvalidate-client-before-effect)
  - 游戏事实保护: [CON.visual-only-scope](../shared.md#convisual-only-scope)
- Landing:
  - [implementation](../../architecture/network/player-state.md)
  - [implementation](../../architecture/animation/implementation/state-inputs-and-sync.md#输入分类)
  - [design](../../architecture/network/overview.md)
  - [design](../../architecture/animation/overview.md)

## DD.tracking-scopes-state-sync

- Claim: 新加入的观察者先取得目标当前的表现快照；此后的完整选择、选择增量和实体增量只向游戏当前观察者及发送者尽力发送，且要求接收方已建立业务会话。
- Rationale: 新观察者需要的是当前状态起点，而不是历史变更。复用游戏的实体追踪关系可以避免把状态发给无关玩家；用初始快照兜底也能同时覆盖握手与追踪建立的先后顺序，代价仅是在发送时读取追踪关系并筛选具备业务资格的接收方。

### BC.tracking-starts-with-current-state

- Claim: 开始观察远端玩家时，客户端应取得当前同步范围内的表现状态；如果业务会话晚于追踪建立，也应取得当前仍在观察的玩家快照。这一过程不要求复制实体全部字段，也不补发历史事件。

### BC.tracked-state-advances-by-deltas

- Claim: 服务端接纳和保存合法状态不依赖当前是否存在观察者；后续变化发给游戏当前观察者及发送者。接收方按当前实体角色消费这些更新：找不到实体时直接丢弃，不为未来实体排队，也不承诺每个游戏 tick 必达一次更新。

## DD.presentation-sync-is-best-effort

- Claim: 服务端与客户端之间以及不同客户端之间的模型表现同步统一采用 best-effort。
- Rationale: 不同观察者的采样时刻、已知状态与网络延迟天然不同，且模组同步只服务于视觉表现，不重新判定玩法；要求各端严格一致会增加等待与同步开销，违背游戏持续运行的优先级。

### BC.sync-drift-is-not-fatal

- Claim: 模型选择和动画状态应尽力保持接近，但允许延迟、遗漏和漂移；同步失败不得导致游戏或模组启动失败，也不得阻塞客户端渲染或服务端 tick。前次选择的迟到 roaming 不得混入当前选择；本地玩家忽略自身的选择、选择增量和追踪（track）回显，但仍消费实体增量。这项约束不承诺逐消息排序或可靠交付。

### BC.send-failure-does-not-rollback-authority

- Claim: 状态通知发送失败不回滚已提交的本地选择或服务端事实。客户端准备完成后先提交选择和生产者 scope，再尝试发送一次；既不等待服务端回显，也不因发送失败恢复旧选择。

## DD.game-state-plus-mod-projection

- Claim: 模组只补充动画所需且观察者实际缺少的状态，不将补充表现输入混作原版玩法事实。
- Rationale: LocalPlayer 和 RemotePlayer 可观察的信息不同，动画还会依赖本地操作。只补充真正缺失的输入才能补足表现，同时避免重复占用带宽和产生双份状态歧义；把补充输入写进原版权威状态则会越过视觉功能的边界。

### BC.gameplay-input-remains-server-owned

- Claim: 绝大多数玩家状态复用游戏自身维护和分发的事实；模组需要补充分发的服务端权威值必须从服务端取得，不接受客户端自报值替代。

### BC.missing-state-means-missing-delivery

- Claim: 补充输入只覆盖观察者实际缺少且动画必需的信息；本地可读不等于远端可用，也不能虚构玩法事实来填补表现输入。

### BC.local-only-input-is-explicitly-reported

- Claim: 动画需要且游戏未提供的本地按键或操作状态可以显式上报，经服务端校验后供其他观察者使用；这些补充表现输入不写回原版玩法状态。
