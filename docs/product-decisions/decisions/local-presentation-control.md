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

- Claim: 玩家可以主动限制本机的 YSM 模型替换与音效；本地控制优先于模型希望呈现的效果和联机共同呈现目标，不要求先证明内容有错误。
- Rationale: 模型表现的价值以不妨碍正常游玩为前提，而遮挡与声音干扰取决于观察者的环境和偏好。按自身、其他玩家及指定玩家提供有限范围的控制，可处理主要干扰来源；复用原版表现并在本地裁决，不需要引入内容分类审核或服务端逐项批准。

### BC.blocked-models-restore-vanilla-presentation

- Claim: 客户端必须提供下表中的控制；模型屏蔽撤销相应的 YSM 替换并恢复原版表现，不使玩家实体不可见，也不以 YSM 内置 `default` 代替原版。

| 控制范围 | 生效结果 |
|---|---|
| 自身玩家模型 | 本机自身玩家恢复原版外观及原版第一人称手臂。 |
| 自身第一人称手臂 | 独立撤销第一人称手臂替换并恢复原版手臂，不因此屏蔽第三人称主体模型。 |
| 其他玩家模型 | 本机看到的其他玩家统一恢复原版外观。 |
| 指定玩家模型 | 本机指定玩家恢复原版外观，不要求同时屏蔽所有其他玩家；其切换模型不能绕过针对该玩家的屏蔽。 |

### BC.local-controls-do-not-change-shared-state

- Claim: 本地屏蔽只决定本机呈现，不修改自己或他人的服务端模型选择，不改变其他客户端的表现，也不改变玩家的实体存在、碰撞、攻击判定及其他玩法状态。服务端允许呈现或选择某模型不覆盖本地屏蔽，模型切换和故障恢复也不得绕过仍生效的屏蔽。

### BC.model-audio-volume-is-user-controlled

- Claim: 玩家可以调低模型触发音效的本地播放音量，直至静音；该调节覆盖 YSM 动画或脚本触发的音效，不改变无关原版或其他模组声音的音量，也不改变其他客户端的播放结果。

### BC.restoring-presentation-uses-current-selection

- Claim: 玩家能够撤销本地屏蔽；所有适用屏蔽均解除后，按当前有效模型选择和访问权限恢复 YSM 替换，仍不可用时服从普通故障降级。主动屏蔽不改写模型内容或既有作品语义，也不取消默认模型的启动与故障兜底义务。
