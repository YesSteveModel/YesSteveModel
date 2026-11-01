# model-distribution

- Requirement: [REQ.bounded-model-distribution](../requirements/req-bounded-model-distribution.md#reqbounded-model-distribution)
- Select: 按需验证与分发、已有内容复用
- Needs:
  - 内容失败阻断目标呈现: [DD.default-model-is-reliability-baseline](model-fallback.md#dddefault-model-is-reliability-baseline)
  - 普通内容验证失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
  - 复用身份: [DD.model-and-representation-identities](model-identity.md#ddmodel-and-representation-identities)
  - 分发或复用权限: [DD.selection-permission-is-separate-from-asset-access](model-authorization.md#ddselection-permission-is-separate-from-asset-access)
  - 网络成本与失败: [DD.main-loop-has-priority](workload-budget.md#ddmain-loop-has-priority)
  - 资产分发与 gameplay 消息竞争: [DD.gameplay-traffic-precedes-asset-distribution](workload-budget.md#ddgameplay-traffic-precedes-asset-distribution)
- Landing:
  - [implementation](../../architecture/network/implementation/catalog-sync.md)
  - [implementation](../../architecture/network/implementation/asset-transfer.md)
  - [implementation](../../architecture/model-management/storage/implementation/persistence.md#remote-与图片)
  - [standard](../../standards/asset-container.md#container-verification)
  - [standard](../../standards/asset-container.md#chunk-table)
  - [design](../../architecture/network/overview.md)
  - [design](../../architecture/model-management/storage/overview.md#精确缓存与持久工作)
  - [design](../../architecture/model-management/sessions/overview.md#目录权威与内容激活)
  - [design](../../architecture/model-management/audio/overview.md#接纳与读取)

## DD.catalog-before-model-body

- Claim: 玩家先取得模型描述，再按需要取得经过验证的目标完整依赖，不为无关模型和目标付出下载成本。
- Rationale: 数千模型的目录远大于一次游戏的实际需求，入服即全量分发被明确排除。模型描述、实际目标与完整资产的成本应分阶段承担；先看描述不能成为信任未检查内容的理由。

### BC.joining-does-not-download-all-models

- Claim: 加入游戏和浏览模型列表不触发所有完整模型下载；元数据可用不能被解释为每个模型已完整物化、烘焙或驻留。

### BC.transfer-only-needed-asset-closure

- Claim: 内容访问获准时，目标准备取得其 geometry、纹理、动画及全部声音首块等实际准入依赖，不因它们位于同一容器就连带传输无关目标；声音尾块只在真实播放需要时取得，未获准的内容不能以补齐依赖为由取得。

### BC.verified-prefix-does-not-imply-ready-content

- Claim: 模型描述可用只证明已检查的描述边界，不证明未取得的资产可解码或所需目标已可呈现。

### BC.chunks-are-independently-addressable

- Claim: 使用方能够定位并取得目标所需内容，无需先读取或传输整个模型中无关目标的资产。

### BC.consumed-chunks-are-verified

- Claim: 所需资产必须通过完整性与适用的解码验证后才能作为有效内容使用；已经接纳模型描述不能豁免后续内容验证。

### BC.late-chunk-failure-degrades-locally

- Claim: 普通模型在后续资产验证失败时拒绝受影响内容并采用 fail-soft 降级；阻断实际呈现时按目标类别回退默认模型或恢复宿主，已经完成初始准入不使后续错误升级为游戏故障。

## DD.reuse-content-before-downloading

- Claim: 满足身份与验证要求的已有资产应优先复用，以减少重复模型分发。
- Rationale: 服务端带宽宝贵，重新连接、切换游戏实例或使用已经安装的同源模型不应反复传输相同需求；复用内容降低分发成本，但不能把缓存存在误认为当前会话仍然授权。

### BC.user-level-remote-content-reuse

- Claim: 客户端在系统用户范围内复用已验证的远程资产缓存，使不同游戏实例或后续会话可以复用满足当前身份与内容要求的资产；缓存不携带跨会话授权。

### BC.local-catalog-activation-avoids-download

- Claim: 当前需求可以由已验证的本地同源模型满足时，通过本地模型目录激活该内容，避免重复下载；模型可见性、资产访问和选择权限仍服从当前会话裁决，本地存在不绕过内容访问限制。
