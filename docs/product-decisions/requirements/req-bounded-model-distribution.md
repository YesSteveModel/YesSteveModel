# REQ.bounded-model-distribution

- Claim: 服务端或联机主机添加的模型按权限规则供游戏内玩家选择和呈现，玩家能看到自己与附近其他玩家的模型或约定的降级表现，并以可承受的资源成本尽力同步选择与动画状态。
- Decision-status: Complete
- Motivation: [SCN.large-model-catalog](#scnlarge-model-catalog)
- Constraints: [CON.client-input-untrusted](../shared.md#conclient-input-untrusted); [CON.normal-use-isolation](../shared.md#connormal-use-isolation)

## SCN.large-model-catalog

- Claim: 服务端已有数千模型的使用规模，单个玩家一次游戏实际需要的模型和 render target 只是目录的一部分。
- Motivation: [PG.shared-multiplayer-presentation](../shared.md#pgshared-multiplayer-presentation)

## Select

| 任务涉及 | 决策包 |
|---|---|
| 按需验证与分发、已有内容复用 | [model-distribution](../decisions/model-distribution.md) |
| 选择/资产/展示权限、RESTRICTED_AUTH、主目标管理例外、独立请求与接纳后撤权 | [model-authorization](../decisions/model-authorization.md) |
| 会话归属与迟到结果 | [session-isolation](../decisions/session-isolation.md) |
| 观察者状态取得、best-effort、游戏事实与本地输入 | [player-state-sync](../decisions/player-state-sync.md) |
| roaming 累积状态、ysm.sync 瞬时事件 | [script-sync](../decisions/script-sync.md) |
| unstable/stable 协议兼容边界与 Minecraft 连接保留 | [session-failure](../decisions/session-failure.md) |

## Uses

- [DD.gameplay-traffic-precedes-asset-distribution — 资产分发与 gameplay 消息竞争](../decisions/workload-budget.md#ddgameplay-traffic-precedes-asset-distribution)
- [DD.local-player-controls-presentation — 观察者控制本机呈现](../decisions/local-presentation-control.md#ddlocal-player-controls-presentation)
- [DD.mod-and-model-licenses-are-independent — 联机权限与作品许可的区别](../decisions/content-rights.md#ddmod-and-model-licenses-are-independent)
- [DD.visible-creator-attribution — 分享保留来源署名](../decisions/selection-and-attribution.md#ddvisible-creator-attribution)
