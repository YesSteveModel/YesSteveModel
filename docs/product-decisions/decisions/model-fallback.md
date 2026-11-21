# model-fallback

- Requirement: [REQ.authoritative-model-lifecycle](../requirements/req-authoritative-model-lifecycle.md#reqauthoritative-model-lifecycle)
- Select: 分类降级、默认启动基线与恢复机会
- Needs:
  - 本地屏蔽生效时的最终呈现: [DD.local-player-controls-presentation](local-presentation-control.md#ddlocal-player-controls-presentation)
  - 默认初始化或兜底自身失败: [CON.default-failure-exception](../shared.md#condefault-failure-exception)
  - 普通失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
  - 因访问限制降级或重试: [DD.selection-permission-is-separate-from-asset-access](model-authorization.md#ddselection-permission-is-separate-from-asset-access)
  - 仅缺必要动画: [DD.model-controller-overrides-builtins](bone-and-controller.md#ddmodel-controller-overrides-builtins)
- Landing:
  - [design](../../architecture/animation/entity-lifecycle.md#失败记忆与恢复)
  - [implementation](../../architecture/animation/implementation/entity-and-frame-state.md#绑定与切换)
  - [implementation](../../architecture/model-management/client-resources/default-model.md#实现与验证入口)
  - [design](../../architecture/model-management/client-resources/overview.md#取得失败与纹理回退)
  - [design](../../architecture/model-management/sessions/overview.md#失败与恢复资格)
  - [design](../../architecture/animation/overview.md)
  - [design](../../architecture/model-management/client-resources/default-model.md)

## DD.default-model-is-reliability-baseline

- Claim: 普通严重错误按目标类别降级：humanoid 使用内置 default，projectile 与 vehicle 恢复宿主；默认内容在启动期就应建立可靠基线。
- Rationale: 外部内容可能变动，也可能尚未取得，无法承担兜底职责。因此默认内容随模组 jar 提供，并在每次启动时全量准备完毕，付出确定的启动开销以免在故障现场再次依赖外部加载；投射物与载具则保留各自的宿主表现，不套用默认人形。默认内容自身的失败适用共享的指定例外，不能扩展为普通内容失败许可。

### BC.severe-model-failure-selects-default

- Claim: 当普通模型的严重错误阻断 humanoid 目标的模型逻辑或渲染时，受影响的实体降级为默认模型；这一降级不授权改写玩家的玩法状态或服务端权威选择。

### BC.unavailable-replacement-restores-host

- Claim: 当 projectile 或 vehicle 渲染失败时，直接撤销其视觉替换并恢复宿主绘制；恢复过程不得重复绘制、破坏其他内容或改写玩法状态。

### BC.default-startup-is-required

- Claim: 每次启动均以模组 jar 内嵌资源作为默认模型的唯一内容来源。准备过程允许读写磁盘并复用有效的派生缓存，不要求每次都重新编译，但在初始化完成前，必须完成本侧职责所需的全部默认内容准备与可用性验证。遇到阻断性错误属于默认初始化失败，不得发布尚未准备完成的兜底。

### BC.client-default-startup-closure

- Claim: 客户端必须在启动阶段准备好全部默认可用的 render target 及其所需内容，不能把首次获取或准备留到运行期故障现场。

### BC.server-default-startup-closure

- Claim: 服务端在启动阶段只需准备好自身职责所需的默认只读内容，不承担仅供客户端呈现且服务端没有消费方的派生资源开销。

### BC.default-independent-of-ordinary-lifecycle

- Claim: 普通模型热加载、目录切换、网络中断和缓存回收都不得替换、修改或撤走默认模型资产；各实体自身的动画播放状态不属于这些只读资产。

### BC.default-has-no-disk-dependency

- Claim: 默认内容准备完成后，默认模型的加载与绑定不得再依赖磁盘，所需内容必须已常驻内存。初始化完成后，在进程存续期间不会因磁盘或网络不可用而丢失默认内容。

### BC.default-fallback-failure-is-exceptional

- Constraints: [CON.default-failure-exception](../shared.md#condefault-failure-exception)
- Decision: [DD.default-model-is-reliability-baseline](model-fallback.md#dddefault-model-is-reliability-baseline)
- Claim: Humanoid 回退 `default` 失败时，不要求继续恢复宿主。默认兜底的内容与执行路径错误必须在开发期严格排除，并尽量在启动阶段提前暴露；如果运行期仍发生此类故障，则按默认基线例外处理。

## DD.degraded-presentation-can-recover

- Claim: 普通降级保留恢复自定义表现的机会，不会永久停用后续替换。
- Rationale: 切换模型或与失败相关的条件发生变化后，故障可能已经消除；但重新尝试必须有明确的新依据，不能让已知失败持续消耗主循环资源。

### BC.degraded-replacement-can-recover

- Claim: 普通降级后，只有显式触发 retry 或 reload，或者与失败相关的内容、权限、连接或宿主能力发生变化，才恢复发起昂贵重试的资格；单纯切走再切回、时间流逝或逐帧检查都不构成新依据。条件未变化时，不得反复执行已知会失败的昂贵工作。重试仍服从当前访问权限，再次失败时保留已建立的默认模型或宿主表现，既不保证恢复成功，也不得阻塞或拖慢游戏主循环。
