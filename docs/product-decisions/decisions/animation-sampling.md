# animation-sampling

- Requirement: [REQ.evaluate-entity-animation](../requirements/req-evaluate-entity-animation.md#reqevaluate-entity-animation)
- Select: 动画输入来源、整体表现与逻辑因果关系
- Needs:
  - 远端与本地补充输入: [DD.game-state-plus-mod-projection](player-state-sync.md#ddgame-state-plus-mod-projection)
  - 更换采样策略: [DD.explicit-support-is-stable](model-compatibility.md#ddexplicit-support-is-stable)
- Landing:
  - [implementation](../../architecture/animation/implementation/entity-and-frame-state.md#时间与更新频率)
  - [implementation](../../architecture/animation/implementation/state-inputs-and-sync.md#输入分类)
  - [design](../../architecture/animation/overview.md)

## DD.client-evaluates-visible-state

- Claim: 客户端使用可见游戏状态、服务端模组状态、本地输入和模型程序求值动画。
- Rationale: 游戏已经维护大多数权威状态，客户端又独有视角、输入与渲染采样信息；组合这些输入即可生成视觉表现，无需让服务端成为逐骨骼动画执行者。

### BC.server-snapshots-are-animation-input

- Claim: 服务端分发到客户端的状态是非权威本地快照，动画可同时使用被允许的本地输入、脚本结果和视角信息；缺少远端数据不能以伪造服务端玩法状态补齐。

## DD.animation-sampling-is-separate-from-state-updates

- Claim: 动画保持受支持的整体表现、逻辑与因果关系；呈现更新可按对象与成本调整，不承诺逻辑动作次数与帧率无关。
- Rationale: 动画是连续骨骼表现和离散指令的组合。适当降低远处对象更新成本有利于整体可用性，但不能以调度优化破坏创作者依赖的插值、混合、控制覆盖或指令顺序。

### BC.animation-preserves-temporal-semantics

- Claim: 所有受支持动画行为的整体表现、逻辑与因果关系必须保持；线性、step、平滑关键帧插值，跑步与攻击的混合，以及自定义控制器覆盖只是示例，不是封闭清单。网络到达频率不直接限定动画呈现频率，动画也不改变游戏逻辑推进频率。

### BC.instruction-keyframes-preserve-causality

- Claim: 有效动画的指令关键帧按播放顺序执行，不回退、不因降低更新频率漏掉中间指令；在同一动画播放生命周期内不重复执行同一指令帧。新的播放生命周期可再次执行相应指令，不保证不同帧率或调度频率下产生相同次数的播放和动作。
