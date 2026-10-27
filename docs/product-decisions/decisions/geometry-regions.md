# geometry-regions

- Requirement: [REQ.render-model-consistently](../requirements/req-render-model-consistently.md#reqrender-model-consistently)
- Select: 创作结果与输出等价
- Needs:
  - 维护既有作品: [DD.explicit-support-is-stable](model-compatibility.md#ddexplicit-support-is-stable)
  - 骨骼姿态输入: [DD.hierarchical-bone-animation](bone-and-controller.md#ddhierarchical-bone-animation)
- Landing:
  - [implementation](../../architecture/rendering/implementation/bake-and-partition.md#四逻辑分区)
  - [implementation](../../architecture/rendering/implementation/vertex-output.md#变换与面语义)
  - [implementation](../../architecture/rendering/implementation/vertex-output.md)
  - [implementation](../../architecture/animation/implementation/processor-and-bone-output.md)
  - [standard](../../standards/model-schema/assets-and-validation.md#geometry)
  - [status](../../status/known-issues/rendering.md#正确性与失败处理)
  - [status](../../status/known-issues/rendering.md#视觉与接入)
  - [standard](../../standards/conformance.md#视觉一致性验收)
  - [design](../../architecture/rendering/overview.md#几何与可见面)

## DD.authoring-semantics-before-optimization

- Claim: 优化和输出路径变化必须保持受支持的几何、表面、材质与姿态语义。
- Rationale: 创作者依赖 Blockbench 的受支持效果，不能为内部优化重做作品。不同输出路径不能变成需要分别适配的作品格式。

### BC.authoring-surfaces-remain-equivalent

- Claim: 受支持模型的形状、骨骼姿态、UV、正反面和透明语义应与创作结果一致；Minecraft 的环境光照、游戏特效与 shader 差异不自动构成逐像素相同的承诺。

### BC.output-paths-are-visually-equivalent

- Claim: 在相同模型、姿态和宿主绘制条件下，不同受支持输出路径保持几何、正反面、光照方向、透明及材质输入语义等价。

### BC.named-bone-glow-is-overridable

- Claim: 骨骼名以区分大小写的 `ysmGlow` 开头时，该骨骼自身几何的初始 block/sky light 均为 `15`；其他骨骼初始沿用宿主光照。发光属性不向子骨骼继承。
- 动态 glow 写入可持续覆盖命名初值，不再受前缀约束；`0–15` 指定 block/sky light，`-1` 沿用宿主光照。后续绘制和兼容换模保留写入值，新建动画实例才重新应用初值。
- 只消费声明初始属性的第一人称背景保留命名初值。该能力不移除方向光、受击覆盖或 shader 处理，也不承诺 bloom。
