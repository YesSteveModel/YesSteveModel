# game-visual-signals

- Requirement: [REQ.render-model-consistently](../requirements/req-render-model-consistently.md#reqrender-model-consistently)
- Select: 光照、着火、受击与发光轮廓
- Needs:
  - 状态输入来源: [DD.client-evaluates-visible-state](animation-sampling.md#ddclient-evaluates-visible-state)
- Landing:
  - [implementation](../../architecture/rendering/implementation/vertex-output.md)
  - [status](../../status/known-issues/rendering.md#视觉与接入)
  - [design](../../architecture/rendering/overview.md)

## DD.preserve-game-visual-signals

- Claim: 原版游戏状态对玩家外观的视觉反馈继续作用于替换模型。
- Rationale: 发光、着火、受击和环境亮度是玩家理解战斗与场景状态的信息；换模若移除这些反馈，就会使视觉增强损害原有可玩性。

### BC.game-effects-apply-to-model

- Claim: 玩家的发光轮廓、着火火焰、受击红色覆盖层和不同环境亮度下的模型明暗都必须保留，不能因渲染路径变化而消失或错用其他实体状态。
