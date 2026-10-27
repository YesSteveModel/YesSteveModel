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

- Claim: 玩家开始观察相关实体时能够取得其当前表现状态，之后持续尽力跟进变化。
- Rationale: 新观察者需要当前起点而非无关历史；只向需要观察的玩家提供表现状态能避免全服广播的持续带宽成本。

### BC.tracking-starts-with-current-state

- Claim: 开始观察远端玩家时取得当前同步范围内的表现状态；不要求复制实体全部字段或补发无关历史事件。

### BC.tracked-state-advances-by-deltas

- Claim: 观察期间继续尽力取得相关表现变化，避免为无关状态重复付出传输成本；不承诺每个游戏 tick 必达一次更新。

## DD.presentation-sync-is-best-effort

- Claim: 服务端与客户端、不同客户端之间的模型表现同步采用 best-effort。
- Rationale: 不同观察者的采样时刻、已知状态与网络延迟天然不同，模组同步用于视觉表现而非重新裁决玩法；要求它们严格一致会增加等待和同步成本，违背游戏持续运行的优先级。

### BC.sync-drift-is-not-fatal

- Claim: 模型选择和动画状态应尽力接近，但允许延迟、遗漏和漂移；同步失败不使游戏或模组 startup 失败，也不阻塞客户端渲染或服务端 tick。

### BC.send-failure-does-not-rollback-authority

- Claim: 状态通知失败不回滚已裁决的服务端游戏事实，也不把客户端表现快照提升为补偿性的玩法权威。

## DD.game-state-plus-mod-projection

- Claim: 模组只补充动画所需且观察者实际缺少的状态，不将补充表现输入混作原版玩法事实。
- Rationale: LocalPlayer 和 RemotePlayer 可观察的信息不同，动画还会依赖本地操作；只补实际缺失的输入才能补足表现并避免重复带宽和双份状态歧义，把补充输入写进原版权威状态则会越过视觉功能边界。

### BC.gameplay-input-remains-server-owned

- Claim: 绝大多数玩家状态使用游戏维护和分发的事实；模组需要补充分发的服务端权威值从服务端取得，不接受客户端自报值替代它。

### BC.missing-state-means-missing-delivery

- Claim: 补充输入只针对观察者实际缺少且动画需要的信息；本地可读不等于远端可用，不用虚构玩法事实填补表现输入。

### BC.local-only-input-is-explicitly-reported

- Claim: 动画需要且游戏未提供的本地按键或操作状态可以显式报告，经服务端校验后供其他观察者使用；补充表现输入不写回原版玩法状态。
