# REQ.render-model-consistently

- Claim: 替换后的模型必须按受支持的 geometry、纹理与动画语义呈现，并保留玩家理解游戏状态所需的原版视觉内容。
- Decision-status: Complete
- Motivation: [PG.model-replacement](../shared.md#pgmodel-replacement); [SCN.replacement-breaks-dependent-content](../shared.md#scnreplacement-breaks-dependent-content)
- Constraints: [CON.normal-use-isolation](../shared.md#connormal-use-isolation); [CON.visual-only-scope](../shared.md#convisual-only-scope)

## Select

| 任务涉及 | 决策包 |
|---|---|
| 模型体型、碰撞与非视觉玩法边界 | [visual-gameplay-boundary](../decisions/visual-gameplay-boundary.md) |
| 原版附着层、定位组零缩放与自有几何替换 | [attachments](../decisions/attachments.md) |
| 光照、着火、受击与发光轮廓 | [game-visual-signals](../decisions/game-visual-signals.md) |
| 创作结果与输出等价 | [geometry-regions](../decisions/geometry-regions.md) |
| 单模型透明保证与 render layers first 调节 | [transparency-scope](../decisions/transparency-scope.md) |
| 骨骼透明度只作用于透明区域 | [bone-opacity](../decisions/bone-opacity.md) |

## Uses

- [DD.local-player-controls-presentation — 本地呈现控制的优先级](../decisions/local-presentation-control.md#ddlocal-player-controls-presentation)
- [DD.default-model-is-reliability-baseline — 默认模型承担故障兜底责任](../decisions/model-fallback.md#dddefault-model-is-reliability-baseline)
- [DD.degraded-presentation-can-recover — 普通降级保留恢复机会](../decisions/model-fallback.md#dddegraded-presentation-can-recover)
- [BC.render-failure-does-not-corrupt-scene — 宿主后续绘制完整性](../decisions/failure-isolation.md#bcrender-failure-does-not-corrupt-scene)
