# 因果缺口与差异

本页维护 AN 子系统的产品偏差、兼容缺口和待验证运行语义；产品判断仍以[产品决策树](../../product-decisions/README.md)为 Root Authority。

## Q-01

**主循环禁止等待的范围与当帧并行计算冲突。** [BC.no-main-loop-starvation](../../product-decisions/decisions/workload-budget.md#bcno-main-loop-starvation) 明确禁止正常模型工作让 tick/render 等待异步结果；[帧执行](../../architecture/rendering/implementation/frame-execution.md)却接受按需等待。实时 `AnimatableEntity.waitForAsyncUpdate` 经 `EntityExecutionLifecycle.awaitTask` 等待 Future，`LevelRendererMixin.waitAll` 汇合动画任务；native `renderer::Render` 经 executor 启动 worker 后也等待完成。上述分别是跨调度阶段的动画等待和同一次 draw 内的 CPU fork/join，不能因为都是 wait 就假定具有相同风险，也不能自行给后者豁免。

- 跨阶段 Future 等待已违反现有条款，不需要重新批准该义务。待裁决的是：是否明确允许满足特定条件的当帧 CPU fork/join；若允许，例外的线程、工作量与降级边界是什么。
- 默认解释：在新增裁决前按现有禁止条款记录偏差，不把“后台执行”当作符合主循环预算。
- 代价：移除动画等待需选择未完成结果的可见处置并保持整体表现、动画逻辑因果和 target 保活；移除 native join 可退回当前已有 inline 路径，但可能降低 CPU 吞吐。异步提交后一律延迟 draw 则会新增输出槽、过期判定与资源持有，未必减少总状态空间。
- 最小验证：阻塞一个动画 worker，并分别比较主循环是否等待、是否使用未完成 pose、动作是否重复；native 对同一任务比较 inline 与并行输出及低核数耗时。只凭单元测试不能宣称帧预算已经满足。

## Q-06

**历史旋转叠加的兼容等级需要作品依据。** `AnimationProcessor` 的 `blendRotation` 补丁注释承认过渡混合尚未完全修复，但现存 parallel controller 仍使用它。没有证据证明原问题消失，也没有对应样本说明哪些混合结果已成为作品依赖。

待裁决：具体受影响行为属于明确支持，还是[未分类特性的 best-effort](../../product-decisions/decisions/model-compatibility.md#ddexplicit-support-is-stable)。前者要求等价替换，后者允许在明确收益和影响范围下调整。最小证据是一个真实并行动画/过渡样本、当前结果及期望结果；在此之前不将 AN-08 标 Historical，也不承诺删除收益。

## Q-07

**依赖级生成 checker 的维护成本缺少比较依据。** [可选联动降级](../../product-decisions/decisions/failure-isolation.md#ddlocal-failure-degradation)能直接解释 AN-10 的加载前门禁。它不能单独推出 AN-09 完整依赖分析、probe、manifest、fingerprint 与 runtime 反射结果协议的必要性。

待补理由：需要接纳哪些真实扩展/API 变动，生成检查比版本门禁或较窄适配接口多保护了什么。先用实际扩展和不兼容版本样本比较误拒绝、漏检和维护工作；再决定是否收窄检查面。直接删 checker 会让现有 loader 跳过 handler/auto Molang 声明。统一 Molang 声明桥接已交付，但没有替代完整 checker 的检测职责；manual 前检也不构成 checker 收窄的成本证据。具体模组的 live worker 读取与真实缺 JAR/版本组合仍待环境验收。

## 其他实现边界

| ID | 观察与约束 | 最小核验 / 判断边界 |
|---|---|---|
| O-07 实际求值限频 | [动画契约](../../product-decisions/decisions/animation-sampling.md)允许调度频率影响播放与动作次数；远处 RemotePlayer 可以降低骨骼、指令与 controller 推进频率，仍按 delta time 插值。 | 限频本身不再构成逐帧契约偏差；仍须验证整体表现和所有受支持逻辑/因果语义，尤其跨区间指令不漏、不回退、不在同一播放生命周期重复。未运行这些验证，不能据此宣称现有实现完全符合。 |

Live 游戏/第三方 API 的 worker 读取、同帧 pose 复用但 draw context 未刷新的问题已在[动画](../../status/known-issues/animation.md)和[渲染](../../status/known-issues/rendering.md)保留；本图不把安全快照或未接通的 `RenderStateModifier` 画成现存解决机制。`GeoReplacedEntityRenderer.setupRotations` 的 live entity 临时修改/恢复也是候选，但其 superclass 语义和异常路径尚未展开，未纳入本轮节点与排名。

未检验的性能收益、旧 bug 是否复现、第三方视觉行为与跨平台 conformance 是证据缺口，不通过推断补成产品决定。各机制的归约仍以[全局反事实检查](reduction-pivots.md)保留独立义务。
