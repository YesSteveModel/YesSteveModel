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

- Claim: YSM 保证单模型透明语义，并遵循宿主管线对跨对象内容的组织边界。
- Rationale: 跨模型、实体与世界的透明关系属于上层渲染管线；强行接管会越过局部视觉替换范围，并以高侵入增加原版与其他模组呈现被破坏的风险和维护成本。

### BC.cross-object-transparency-follows-host

- Claim: 单模型受支持的透明关系必须保持；不同模型、实体与世界透明内容之间的整体排序和混合不由 YSM 保证，不为补齐这些场景擅自改变上层管线。

## DD.attachment-order-is-creator-tunable

- Claim: 附着层与本体的透明穿插只提供创作者可调的绘制先后选项。
- Rationale: 手持物、鞘翅、披风等来自独立绘制内容，不能由本体模型内部的透明规则保证整体正确；提供顺序调节能覆盖部分创作需要，成本与侵入范围也保持有限。

### BC.render-layers-first-is-limited-control

- Claim: 创作者可通过 `render layers first` 配置让附着层先于本体绘制；该调节不保证手持物、鞘翅、披风等与本体之间任意透明交叠的正确性。
