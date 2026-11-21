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

- Claim: 玩家先获取模型描述，再按需获取并验证目标的完整依赖，不为无关模型和目标付出下载成本。
- Rationale: 动辄数千模型的目录远大于单次游玩的实际需求，入服即全量分发被明确排除。模型描述、具体目标与完整资产的开销应分阶段承担；先拿到描述也不能成为跳过后续内容校验的理由。

### BC.joining-does-not-download-all-models

- Claim: 加入游戏和浏览模型列表不会触发全部完整模型的下载。目录元数据可用只代表能够展示列表信息，并不代表每个模型都已完成加载、烘焙或常驻内存。

### BC.transfer-only-needed-asset-closure

- Claim: 内容访问获准后，目标准备只获取该目标实际必需的依赖——包括几何、纹理、动画以及全部声音首块，不会因为其他目标位于同一容器就连带传输。声音尾块只在真实播放需要时获取；未获准访问的内容不能以补齐依赖为由顺带取得。

### BC.verified-prefix-does-not-imply-ready-content

- Claim: 通过模型描述校验只保证前缀描述边界有效，不能据此假定尚未取得的资产块可正常解码，也不代表所需目标已具备呈现条件。

### BC.chunks-are-independently-addressable

- Claim: 使用方能够直接定位并取得目标所需的数据块，无需先读取或传输同一模型中无关目标的资产。

### BC.consumed-chunks-are-verified

- Claim: 所需资产块必须通过完整性校验和适用的解码验证后才能投入使用；已经接纳模型描述不能豁免后续内容的校验。

### BC.late-chunk-failure-degrades-locally

- Claim: 普通模型在后续资产验证失败时，应拒绝受影响的内容并采取 fail-soft 降级；错误阻断实际呈现时，按目标类别回退到默认模型或恢复宿主。此前已完成初始接纳，不应让后续错误升级为游戏故障。

## DD.reuse-content-before-downloading

- Claim: 满足身份与验证要求的已有资产应优先复用，以减少重复的模型分发。
- Rationale: 服务端带宽宝贵，重新连接、切换游戏实例或使用已经安装的同源模型时，不应反复传输相同内容。复用已有内容可以降低分发成本，但本地缓存存在不能被当作当前会话仍然授权的凭据。

### BC.user-level-remote-content-reuse

- Claim: 客户端在系统用户范围内复用已验证的远程资产缓存，让不同游戏实例或后续会话都能复用满足当前身份与内容要求的资产；缓存本身不携带跨会话授权。

### BC.local-catalog-activation-avoids-download

- Claim: 当前需求可以由已验证的本地同源模型满足时，客户端直接通过本地模型目录激活该内容，避免重复下载。模型可见性、资产访问权和选择权限仍由当前会话判定，本地已有内容不能绕过访问限制。
