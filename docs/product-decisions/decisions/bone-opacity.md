# bone-opacity

- Requirement: [REQ.render-model-consistently](../requirements/req-render-model-consistently.md#reqrender-model-consistently)
- Select: 骨骼透明度只作用于透明区域
- Needs:
  - 烘焙分区前提: [DD.authoring-semantics-before-optimization](geometry-regions.md#ddauthoring-semantics-before-optimization)
  - 动画属性边界: [BC.child-bones-inherit-pose](bone-and-controller.md#bcchild-bones-inherit-pose)
- Landing:
  - [implementation](../../architecture/animation/implementation/processor-and-bone-output.md)
  - [implementation](../../architecture/rendering/implementation/bake-and-partition.md#四逻辑分区)
  - [design](../../architecture/animation/overview.md)
  - [design](../../architecture/rendering/overview.md)

## DD.bone-opacity-is-translucent-only

- Claim: 仅透明区域受骨骼透明度影响是明确支持的创作限制。
- Rationale: 模型在烘焙时确定分区，使原本不透明的区域在运行期转为透明会显著增加区域处理与透明绘制的复杂度，并降低渲染性能；固定这一作用范围保留既有分区的性能收益。

### BC.bone-opacity-keeps-opaque-regions-opaque

- Claim: 改变骨骼透明度只改变已划入透明区域的内容，不使不透明区域随之渐隐；创作者需要渐隐的内容必须适配这一受支持限制。
