# 因果缺口与差异

本页维护 AN 子系统的产品偏差、兼容缺口和待验证的运行语义；产品判断仍以[产品决策树](../../product-decisions/README.md)为 Root Authority。

## Q-01

**主循环禁止等待的条款与当帧并行计算存在冲突。** [BC.no-main-loop-starvation](../../product-decisions/decisions/workload-budget.md#bcno-main-loop-starvation) 明确禁止正常模型工作让 tick 或 render 等待异步结果，但[帧执行](../../architecture/rendering/implementation/frame-execution.md)目前接受按需等待。具体来说，`AnimatableEntity.waitForAsyncUpdate` 通过 `EntityExecutionLifecycle.awaitTask` 等待 Future，`LevelRendererMixin.waitAll` 汇合动画任务；native 侧 `renderer::Render` 通过 executor 启动 worker 后也会等待完成。这两处分别是跨调度阶段的动画等待，以及同一次 draw 内的 CPU fork-join——不能因为都是等待就假定它们风险相同，也不能自行豁免后者。

- **当前定位**：跨阶段 Future 等待已违反现有条款，无需重新确认该义务；真正待裁决的是，是否允许满足特定条件的当帧 CPU fork-join，以及允许时的线程、工作量与降级边界。
- **默认处理**：在补充裁决前，一律按现有禁止条款记录偏差，不把“后台执行”视作已经满足主循环预算。
- **改动代价**：移除动画等待需要明确未完成结果的可见策略，并同时保持整体表现、动画逻辑因果和 target 保活；移除 native join 可以退回现有的 inline 路径，但可能降低 CPU 吞吐。如果改成异步提交后一律延迟绘制，则会引入额外的输出槽、过期判定与资源持有，未必能缩小整体状态空间。
- **最小验证**：人为阻塞一个动画 worker，分别观察主循环是否阻塞、是否出现半成品姿态以及动作是否重复；对 native 侧的同一任务对比 inline 与并行输出的结果一致性及低核数耗时。仅凭单元测试不能宣称满足帧预算。

## Q-06

**历史旋转叠加的兼容等级缺少作品依据。** `AnimationProcessor` 中 `blendRotation` 补丁的注释承认过渡混合尚未彻底修复，但现存 parallel controller 仍在启用它。目前既无证据表明原问题已经消失，也缺少对应的作品样本来说明哪些混合结果已成为作者依赖。

待裁决的问题是：受影响的行为属于明确支持的稳定特性，还是[未分类特性的尽力兼容](../../product-decisions/decisions/model-compatibility.md#ddexplicit-support-is-stable)。前者要求等价替换，后者允许在明确收益与影响面后调整。最小证据应包含一个真实的并行动画或过渡样本、当前数值结果与期望结果；拿到这些证据前，不把 AN-08 标为 `Historical`，也不承诺删除后的收益。

## Q-07

**依赖级生成 checker 的维护成本缺少对比依据。** [可选联动降级](../../product-decisions/decisions/failure-isolation.md#ddlocal-failure-degradation)可以直接解释为什么需要 AN-10 的加载前门禁，但不足以单独推出 AN-09 整套依赖分析、probe、manifest、fingerprint 和运行时反射结果协议的必要性。

需要补充的理由包括：实际需要接纳哪些扩展与 API 变动，以及生成式检查相比版本门禁或更窄的适配接口多拦截了哪些风险。应先拿真实扩展和不兼容版本样本对比误拒绝率、漏检率和维护成本，再决定是否收窄检查面。注意，直接删掉 checker 会导致现有 loader 跳过 handler 与自动 Molang 声明；统一 Molang 声明桥接虽然已交付，但没有替代完整 checker 的检测职责，手动前检也不构成收窄 checker 的成本证据。具体模组的运行中 worker 读取以及真实缺失 JAR 和版本组合仍待实机环境验收。

## 其他实现边界

| ID | 观察与约束 | 最小核验与判断边界 |
|---|---|---|
| O-07 实际求值限频 | [动画契约](../../product-decisions/decisions/animation-sampling.md)允许调度频率影响播放与动作次数；远处 RemotePlayer 可以降低骨骼、指令和 controller 的推进频率，并继续按 delta time 插值。 | 限频本身不再构成逐帧契约偏差，但仍须验证整体表现及所有受支持的逻辑和因果语义——尤其是跨区间指令不漏触发、不回退，且在同一播放生命周期内不重复。未完成这些验证前，不能宣称现有实现完全符合契约。 |

在 worker 中直接读取活动游戏对象或第三方 API、以及同帧复用姿态却未刷新 draw context 的问题，均已记录在[动画已知问题](../../status/known-issues/animation.md)和[渲染已知问题](../../status/known-issues/rendering.md)中；本图不会把尚未落地的安全快照或未接通的 `RenderStateModifier` 画成现存解决机制。此外，`GeoReplacedEntityRenderer.setupRotations` 对活动实体的临时修改与恢复也是潜在候选，但其父类语义与异常路径尚未梳理完毕，本轮暂不纳入节点与排名。

尚未验证的性能收益、旧 bug 是否仍可复现、第三方视觉表现以及跨平台一致性都属于证据缺口，不能靠推断补成产品决定。各机制的归约仍须按[归约支点](reduction-pivots.md)核对独立义务。
