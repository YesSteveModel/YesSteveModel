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
  - [implementation](../../architecture/model-management/client-resources/default-model.md#实现入口)
  - [design](../../architecture/model-management/client-resources/overview.md#取得失败与纹理回退)
  - [design](../../architecture/model-management/sessions/overview.md#失败与恢复资格)
  - [design](../../architecture/animation/overview.md)
  - [design](../../architecture/model-management/client-resources/default-model.md)

## DD.default-model-is-reliability-baseline

- Claim: 普通严重错误按目标降级：humanoid 使用内置 default，projectile/vehicle 恢复宿主；默认内容在启动期建立可靠基线。
- Rationale: 可变或未取得的外部内容不能承担兜底。默认内容由模组随 jar 提供并每次启动全量建立，付出确定的初始化成本以免在故障现场再次依赖内容加载；投射物与载具保留各自宿主表现，不套用默认人形。默认自身失败适用共享的指定例外，不能扩展为普通内容失败许可。

### BC.severe-model-failure-selects-default

- Claim: 普通模型的严重错误阻断 humanoid 目标的模型逻辑或渲染时，受影响的使用进入默认模型降级；这不授权改写玩家的玩法状态或服务端权威选择。

### BC.unavailable-replacement-restores-host

- Claim: Projectile、vehicle 渲染失败直接撤销其视觉替换并恢复宿主绘制，恢复不得重复绘制、破坏其他内容或改写玩法状态。

### BC.default-startup-is-required

- Claim: 每次启动以模组 jar 内嵌资源作为默认模型的唯一内容来源。物化过程允许读写磁盘及复用有效派生缓存，不要求每次重新编译。初始化完成前仍须完成本侧职责所需的全部默认内容物化与可用性验证。阻断性错误属于默认初始化失败，不能发布未完成兜底。

### BC.client-default-startup-closure

- Claim: 客户端在 startup 完成全部默认可用 render target 及其所需内容，不能把首次内容取得或准备留到运行期故障现场。

### BC.server-default-startup-closure

- Claim: 服务端在 startup 完成其职责所需的默认只读内容，不承担仅供客户端呈现且服务端没有消费者的派生资源成本。

### BC.default-independent-of-ordinary-lifecycle

- Claim: 普通模型热加载、目录切换、网络中断和缓存回收不得替换、修改或撤走默认模型资产；实体各自的动画播放状态不属于这些只读资产。

### BC.default-has-no-disk-dependency

- Claim: 默认内容物化完成后，默认模型的加载与绑定不得依赖磁盘，所需内容应已驻留。初始化完成后，进程存续期间不因磁盘和网络不可用而丢失默认内容。

### BC.default-fallback-failure-is-exceptional

- Constraints: [CON.default-failure-exception](../shared.md#condefault-failure-exception)
- Decision: [DD.default-model-is-reliability-baseline](model-fallback.md#dddefault-model-is-reliability-baseline)
- Claim: Humanoid 回退 `default` 失败时不要求继续恢复宿主；默认兜底的内容与执行路径失败必须在开发期严格排除，并尽量在 startup 提前暴露，运行期仍发生时按默认基线例外处理。

## DD.degraded-presentation-can-recover

- Claim: 普通降级保留恢复自定义表现的机会，不永久停用后续替换。
- Rationale: 切模或与失败相关的条件变化可能消除故障；重新尝试应有收益依据，不能让已知失败持续消耗主循环。

### BC.degraded-replacement-can-recover

- Claim: 普通降级后，只有显式 retry/reload，或与失败相关的内容、权限、连接或宿主能力发生变化，才恢复昂贵尝试的资格；单纯切走再切回、时间经过或逐帧观察都不构成新依据。条件未变化时不得反复执行已知失败的昂贵工作。重试服从当前访问权限，失败时保留已建立的默认模型或宿主表现，不保证恢复成功，也不得阻塞或拖慢游戏主循环。
