# visual-gameplay-boundary

- Requirement: [REQ.render-model-consistently](../requirements/req-render-model-consistently.md#reqrender-model-consistently)
- Select: 模型体型、碰撞与非视觉玩法边界
- Needs:
  - 涉及外部模组状态与呈现: [BC.integration-does-not-redefine-gameplay](mod-integration.md#bcintegration-does-not-redefine-gameplay)
  - 确定授权范围: [CON.visual-only-scope](../shared.md#convisual-only-scope)
- Landing:
  - [design](../../architecture/rendering/overview.md#设计目标)

## DD.rendering-only-replacement

- Constraints: [CON.visual-only-scope](../shared.md#convisual-only-scope)
- Claim: 模型与动画系统的替换以视觉表现为目标，不以外观输入重新定义玩法规则。
- Rationale: 系统替换的价值在于提供更丰富的模型效果，并已接受依赖旧系统的相关内容可能失效；但这一兼容代价不能进一步成为按模型体型、动作或外观修改碰撞与战斗规则的理由。

### BC.visual-size-does-not-change-physics

- Claim: 即使模型在视觉上大于或小于实体，碰撞箱仍遵循原版逻辑，攻击距离、跳跃高度、武器伤害等非视觉玩法参数均不因模型或动画而改变。
