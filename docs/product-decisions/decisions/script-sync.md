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

- Claim: 模型创作者显式区分并声明需要同步的瞬时事件与累积状态。
- Rationale: 随机数等脚本结果是否需要保留，取决于作品自身的动作和状态设计。由创作者决定既可以避免模组猜测任意脚本内存的含义，也不必把全部动画执行变成强同步系统。

### BC.script-sync-goes-through-server

- Claim: `ysm.sync` 在当前本地执行借用结束后恰好入队执行一次。联机模式下，只要原选择与当前使用仍然匹配，LocalPlayer 就独立发起一次尽力上报。服务端只校验连接、操作主体、当前正 intent 和最多 16 个有限 F32 参数，校验通过后发给游戏当前观察者及发送者；LocalPlayer 忽略回显。

远端已接纳当前完整选择、但对应模型尚未安装完成时，客户端应在有限容量内暂存事件，即使 catalog 尚未发布也同样适用。匹配模型安装成功后，客户端按接收顺序依次消费 roaming 更新与暂存事件，保留两者的交错顺序。当前选择失效时丢弃旧事件；已消费的事件不在重装时重复执行。如果匹配安装的模型没有 sync handler，则直接丢弃，不再等待未来重装。

暂存容量不足时直接丢弃新事件并计数，后续合法的 roaming 更新继续接纳。服务端不保存事件历史，客户端也不增加 ACK、超时重试、持久化或网络重放；重新追踪与重新连接后只取得当前状态。

### BC.roaming-carries-accumulated-state

- Claim: 需要累积并供后续观察者获取的同步值由 roaming 变量机制承担：新观察者通过快照拿到当前状态，后续变化发给游戏当前观察者及发送者。这套机制同样服从服务端校验、表现用途和 best-effort 边界。服务端只维护当前选择对应的值，不保存玩家的 roaming 历史；远端在匹配安装前持续接纳当前选择的完整状态与增量。每个已声明的 roaming 变量始终有值，初值为正零，不区分未赋值与零。收到完整状态时将未携带字段恢复为正零，收到增量时只覆盖携带字段。

### BC.only-local-player-roaming-persists

- Claim: 每个客户端都按模型脚本推进当前实体实例的 roaming，但只有 LocalPlayer 的合法写入会按玩家 UUID 与完整发布身份在客户端本地持久保存，并独立发起尽力上报。其他实体的本地写入既不反向同步，也不保证跨实例保留，且可被服务端后续状态修正。

自身选择、选择增量和追踪（track）回显均不恢复、清除或持久写入本地 roaming。Intrinsic `default` 与同源普通模型使用各自独立的偏好记录。持久化失败不撤回已生效的合法本地表现或网络上报机会，但近期更新允许丢失。

所有本地 roaming 查询失败均以临时零基线继续当前表现或主动选择；当前 scope 的后续合法变化仍可运行并尽力上报，但不持久保存。切换模型后会重新尝试查询，期间不自动重试，也不覆盖磁盘上的原有记录。执行显式 clear 时，以用户明确清空的状态建立新 scope 并独立清除持久记录，此后的真实变化可继续保存。偏好查询失败只记录 DEBUG 日志，仅在启用动画调试界面时于聊天栏提醒；写入失败沿用原有反馈策略。

### BC.creator-defines-synchronized-state

- Claim: 累积状态如何设计、如何划分，以及何时选用 roaming 或 `ysm.sync`，均由模型创作者负责。YSM 不会自动识别哪些脚本结果需要保存，也不承诺恢复创作者未声明的状态。
