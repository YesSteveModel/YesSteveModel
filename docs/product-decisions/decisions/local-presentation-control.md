# local-presentation-control

- Requirement: [REQ.control-local-presentation](../requirements/req-control-local-presentation.md)
- Select: 本地模型屏蔽、第一人称手臂与模型音效控制
- Needs:
  - 多人模式模型选择与访问: [DD.selection-permission-is-separate-from-asset-access](model-authorization.md#ddselection-permission-is-separate-from-asset-access)
  - 恢复替换后模型不可用: [DD.default-model-is-reliability-baseline](model-fallback.md#dddefault-model-is-reliability-baseline)
  - 模型声音触发: [DD.model-sounds-are-observable-content](model-sound.md#ddmodel-sounds-are-observable-content)
- Landing:
  - [design](../../architecture/rendering/overview.md#本地呈现边界)

## DD.local-player-controls-presentation

- Claim: 玩家可以主动限制本机的 YSM 模型替换与音效。本地控制优先于模型自身的呈现意图和联机共享展示目标，且无需先证明内容存在错误。
- Rationale: 模型表现的前提是不妨碍正常游玩，而视线遮挡和声音干扰因观察者的环境与偏好而异。按自身、其他玩家及指定玩家提供有限范围的控制，就能覆盖主要干扰来源；直接复用原版表现并由客户端本地决定，无需引入内容分类审核或服务端逐项批准。

### BC.blocked-models-restore-vanilla-presentation

- Claim: 客户端必须提供下表列出的控制项。启用模型屏蔽后，客户端撤销对应的 YSM 替换并恢复原版表现，既不会让玩家实体隐身，也不会用 YSM 内置的 `default` 代替原版。

| 控制范围 | 生效结果 |
|---|---|
| 自身玩家模型 | 本机自身玩家恢复原版外观及原版第一人称手臂。 |
| 自身第一人称手臂 | 独立撤销第一人称手臂替换并恢复原版手臂，不因此屏蔽第三人称主体模型。 |
| 其他玩家模型 | 本机看到的其他玩家统一恢复原版外观。 |
| 指定玩家模型 | 本机指定玩家恢复原版外观，无需同时屏蔽所有其他玩家；该玩家切换模型不能绕过针对其本人的屏蔽。 |

### BC.local-controls-do-not-change-shared-state

- Claim: 本地屏蔽只影响本机呈现，既不修改自己或他人的服务端模型选择，也不改变其他客户端的画面，更不影响玩家的实体存在、碰撞、攻击判定及其他玩法状态。服务端允许呈现或选择某模型不会覆盖本地屏蔽，模型切换和故障恢复也不得绕过仍在生效的屏蔽。

### BC.model-audio-volume-is-user-controlled

- Claim: 玩家可以调低模型触发音效的本地播放音量，直至完全静音。这项调节作用于 YSM 动画或脚本触发的音效，不改变无关原版或其他模组声音的音量，也不影响其他客户端的播放结果。

### BC.restoring-presentation-uses-current-selection

- Claim: 玩家随时可以撤销本地屏蔽。当所有适用屏蔽都解除后，客户端按当前有效的模型选择和访问权限恢复 YSM 替换；如果模型仍不可用，则按普通故障降级处理。主动屏蔽不会改写模型内容或既有作品语义，也不取消默认模型的启动与故障兜底义务。
