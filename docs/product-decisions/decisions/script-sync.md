# script-sync

- Requirement: [REQ.bounded-model-distribution](../requirements/req-bounded-model-distribution.md#reqbounded-model-distribution)
- Select: roaming 累积状态、ysm.sync 瞬时事件
- Needs:
  - 同步范围与漂移: [DD.tracking-scopes-state-sync](player-state-sync.md#ddtracking-scopes-state-sync)
  - 服务端校验: [DD.validate-client-before-effect](trust-boundaries.md#ddvalidate-client-before-effect)
- Landing:
  - [implementation](../../architecture/animation/implementation/state-inputs-and-sync.md#roaming)
  - [implementation](../../architecture/animation/implementation/state-inputs-and-sync.md)
  - [design](../../architecture/animation/overview.md)

## DD.script-selects-sync-values

- Claim: 模型创作者显式区分并声明需要协调的瞬时事件与累积状态。
- Rationale: 随机数等脚本结果是否需要保留，取决于作品的动作和状态设计；由创作者决定可以避免模组猜测任意脚本内存的含义，也不必把全部动画执行变成强同步系统。

### BC.script-sync-goes-through-server

- Claim: 联机时 LocalPlayer 通过 `ysm.sync` 上报瞬时事件及其数据，服务端验证后向相关客户端尽力转发；该函数不承担累积状态保存或向新追踪者补发旧事件的义务。

### BC.roaming-carries-accumulated-state

- Claim: 模型需要累积并供后续观察者取得的同步值由 roaming 变量机制承担，沿追踪关系提供当前状态与后续变化；它仍服从服务端校验、表现用途和 best-effort 边界。

### BC.only-local-player-roaming-persists

- Claim: 每个客户端都按模型脚本推进当前实体实例的 roaming，但只有 LocalPlayer 的写入被本机保留并上报；其他实体的本地写入不反向同步、不保证跨实例保留，并可被服务端后续状态修正。

### BC.creator-defines-synchronized-state

- Claim: 累积状态的设计、划分及 roaming 与 `ysm.sync` 的选择由模型创作者负责；YSM 不自动识别哪些脚本结果需要保存，也不承诺恢复创作者未声明的状态。
