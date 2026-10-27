# attachments

- Requirement: [REQ.render-model-consistently](../requirements/req-render-model-consistently.md#reqrender-model-consistently)
- Select: 原版附着层、定位组零缩放与自有几何替换
- Needs:
  - 姿态继承: [DD.hierarchical-bone-animation](bone-and-controller.md#ddhierarchical-bone-animation)
  - 明确支持的自定义技巧: [DD.explicit-support-is-stable](model-compatibility.md#ddexplicit-support-is-stable)
  - 附着与本体透明穿插: [DD.attachment-order-is-creator-tunable](transparency-scope.md#ddattachment-order-is-creator-tunable)
- Landing:
  - [implementation](../../architecture/rendering/implementation/frame-execution.md#层级遍历与可见性)
  - [status](../../status/known-issues/rendering.md#视觉与接入)
  - [design](../../architecture/rendering/overview.md)

## DD.preserve-vanilla-attachments

- Claim: YSM 显式重建原版附着内容，以补回系统替换造成的表现损失。
- Rationale: 原版附着绘制依赖已被取代的模型与动画，旧流程因此失效；重新实现持物、鞘翅、披风和肩部鹦鹉是在降低已接受的替换破坏，保留玩家本来就应有的内容。

### BC.vanilla-attachments-remain-visible

- Claim: 玩家主副手物品、背上的鞘翅或披风、肩部鹦鹉等内容应继续按游戏状态显示，并跟随对应模型部位的动画姿态；模型明确接管的附着外观遵守自定义替换契约。

## DD.locator-hiding-is-supported-customization

- Claim: 支持模型脚本识别物品等状态、隐藏对应定位组并用模型自有几何替换附着外观。
- Rationale: 创作者已经从脚本和定位能力发展出合理的手持物、披风等自定义方式，这直接扩展模型表现价值且保持视觉边界，因此应成为需要维护的创作能力。

### BC.zero-scale-locator-hides-attachment

- Claim: 模型将相应定位组缩放为零时，可以隐藏由该组定位的原版附着绘制并显示自己的替代几何；不能强行补画被模型明确接管的重复外观。

### BC.custom-attachment-keeps-game-item

- Claim: 自定义手持物、披风等外观不替换真实物品、装备或实体状态，不改变其功能、判定或对其他内容的作用。
