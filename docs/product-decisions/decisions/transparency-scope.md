# transparency-scope

- Requirement: [REQ.render-model-consistently](../requirements/req-render-model-consistently.md#reqrender-model-consistently)
- Select: 单模型透明保证与 render layers first 调节
- Needs:
  - 考虑扩大宿主管线侵入: [DD.intrusive-change-needs-adjudication](integration-risk.md#ddintrusive-change-needs-adjudication)
- Landing:
  - [implementation](../../architecture/rendering/implementation/vertex-output.md#顶点输出区间与-translucent-排序)
  - [status](../../status/known-issues/rendering.md#视觉与接入)
  - [design](../../architecture/rendering/overview.md)

## DD.transparency-stops-at-model-boundary

- Claim: YSM 保障单个模型内部的透明语义，跨对象透明则遵循宿主管线的组织边界。
- Rationale: 不同模型、实体与世界之间的透明排序属于上层渲染管线职责；强行接管既会越过局部视觉替换范围，也会因高侵入而增加原版及其他模组呈现被破坏的风险与维护成本。

### BC.cross-object-transparency-follows-host

- Claim: 单个模型内受支持的透明关系必须保持；不同模型、实体与世界透明内容之间的整体排序和混合不由 YSM 保证，也不为补齐这些场景擅自改造上层渲染管线。

## DD.attachment-order-is-creator-tunable

- Claim: 针对附着层与模型本体的透明穿插，YSM 只向创作者提供可调节绘制先后顺序的选项。
- Rationale: 手持物、鞘翅、披风等来自独立绘制内容，无法仅靠本体模型内部的透明规则保证整体正确；提供顺序调节可以覆盖部分创作需要，同时把成本与管线侵入控制在有限范围内。

### BC.render-layers-first-is-limited-control

- Claim: 创作者可通过 `render layers first` 配置让附着层先于本体绘制，但该调节不保证手持物、鞘翅、披风等与本体之间任意透明交叠的正确性。
