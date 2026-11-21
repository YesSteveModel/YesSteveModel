# REQ.evaluate-entity-animation

- Claim: 玩家在本地与联机环境中应看到模型按适配的动画、控制器、脚本和可用游戏状态运动，并听到模型声明触发的声音；替换原版动画系统后仍提供应有的动作能力。
- Decision-status: Complete
- Motivation: [PG.model-replacement](../shared.md#pgmodel-replacement); [PG.shared-multiplayer-presentation](../shared.md#pgshared-multiplayer-presentation); [SCN.replacement-breaks-dependent-content](../shared.md#scnreplacement-breaks-dependent-content)
- Constraints: [CON.normal-use-isolation](../shared.md#connormal-use-isolation)

## Select

| 任务涉及 | 决策包 |
|---|---|
| 层级姿态与可覆盖复用的动作基线 | [bone-and-controller](../decisions/bone-and-controller.md) |
| 动画输入来源、整体表现与逻辑因果关系 | [animation-sampling](../decisions/animation-sampling.md) |
| 动画与脚本的声音触发及播放义务 | [model-sound](../decisions/model-sound.md) |
| 实体状态隔离、多 pass 副作用去重与单动画失败 | [animation-isolation](../decisions/animation-isolation.md) |

## Uses

- [DD.local-player-controls-presentation — 本地模型与音效控制](../decisions/local-presentation-control.md#ddlocal-player-controls-presentation)
- [DD.local-failure-degradation — 将失败限制在受影响的模组能力](../decisions/failure-isolation.md#ddlocal-failure-degradation)
