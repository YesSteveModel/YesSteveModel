# bone-and-controller

- Requirement: [REQ.evaluate-entity-animation](../requirements/req-evaluate-entity-animation.md#reqevaluate-entity-animation)
- Select: 层级姿态与可覆盖复用的动作基线
- Needs:
  - 默认动画可用性: [DD.default-model-is-reliability-baseline](model-fallback.md#dddefault-model-is-reliability-baseline)
  - 区分默认动画 best-effort 与明确支持的求值语义: [DD.explicit-support-is-stable](model-compatibility.md#ddexplicit-support-is-stable)
- Landing:
  - [implementation](../../architecture/animation/controllers-and-playback.md)
  - [implementation](../../architecture/animation/implementation/processor-and-bone-output.md)
  - [design](../../architecture/animation/overview.md)

## DD.hierarchical-bone-animation

- Claim: 动画通过骨骼的旋转、缩放和位移驱动由 BoneTree 组织的体素 geometry。
- Rationale: 模型沿用基岩版的骨骼创作体系，父子部件需要共同运动；父姿态传递给子骨骼才能让创作者按层级组织完整动作。

### BC.child-bones-inherit-pose

- Claim: 子骨骼的可见位置和朝向受到父骨骼旋转、缩放与位移影响；该姿态继承承诺不自动扩展为颜色、发光等其他属性继承。

## DD.model-controller-overrides-builtins

- Claim: YSM 提供基础动作与可复用默认动画，模型可覆盖对应控制逻辑、借用缺失动画，并由创作者负责艺术适配。
- Rationale: 系统替换后需要补回原版动作反馈；要求每个作品重做全部控制和动画会增加创作成本。自定义覆盖和缺动画借用保留作品外观与控制权，但体型、骨骼与艺术意图各异，通用动画不能保证任意适配。默认动画向后兼容采用 best-effort，层级求值和明确支持的控制语义仍须正确。

### BC.controller-override-is-explicit

- Claim: 模型声明的自定义控制器覆盖对应内置控制逻辑，未覆盖部分继续使用内置逻辑；两种来源遵守同一模型动画语义。

### BC.vanilla-actions-remain-visible

- Claim: 模组为走路、跑步、游泳、攻击、受击等原版动作提供对应控制与动画基线，使适配这些动画的模型继续呈现动作反馈；碰撞、伤害、攻击距离和移动能力不由动画改变。

### BC.missing-animation-is-not-model-failure

- Claim: 普通模型缺少走路、跑步等必要动画时，继续使用该模型并借用默认模型的对应动画；这是允许的输入和正常补全，不切换整个模型，也不归类为严重模型错误。

### BC.default-animation-interface-is-stable

- Claim: 默认动画保持通用性，并尽力维护向后兼容；已有作品复用默认动画的具体适配和表现属于 best-effort，不形成任意模型下的永久艺术适配保证。

### BC.animation-reuse-does-not-guarantee-artistic-fit

- Claim: YSM 不为任意模型保证默认动画的视觉适配，也不代创作者定义最小骨骼或必要动作集合；此边界不豁免动画复用、姿态求值和已支持语义的正确性与兼容义务。
