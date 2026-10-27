# model-authorization

- Requirement: [REQ.bounded-model-distribution](../requirements/req-bounded-model-distribution.md#reqbounded-model-distribution)
- Select: 选择/资产/展示权限、RESTRICTED_AUTH、gen-preview 主目标管理例外、接纳后撤权
- Needs:
  - 区分作品许可与联机权限: [DD.mod-and-model-licenses-are-independent](content-rights.md#ddmod-and-model-licenses-are-independent)
  - 客户端主动屏蔽表现: [DD.local-player-controls-presentation](local-presentation-control.md#ddlocal-player-controls-presentation)
  - 客户端请求校验: [DD.validate-client-before-effect](trust-boundaries.md#ddvalidate-client-before-effect)
  - 独立图片持久效果: [BC.generated-preview-is-an-independent-cache](artifact-storage.md#bcgenerated-preview-is-an-independent-cache)
  - 有限管理工作: [BC.no-main-loop-starvation](workload-budget.md#bcno-main-loop-starvation)
  - 受限内容无法呈现: [DD.default-model-is-reliability-baseline](model-fallback.md#dddefault-model-is-reliability-baseline)
- Landing:
  - [standard](../../standards/protocol-v1/README.md#选择授权与状态顺序)
  - [implementation](../../architecture/network/implementation/asset-transfer.md#server-admission-与-source-closure)
  - [standard](../../standards/protocol-v1/README.md#显式管理补图)
  - [design](../../architecture/network/overview.md)

## DD.server-decides-model-operations

- Constraints: [CON.client-input-untrusted](../shared.md#conclient-input-untrusted)
- Claim: 多人模式中客户端发起模型选择和轮盘动画请求，服务端决定有效结果并分发。
- Rationale: 这些状态由模组新增，游戏本体不会自动同步；它们影响其他玩家看到的内容，所以客户端意图必须经过共同的服务端权限与业务裁决。

### BC.selection-and-wheel-are-authorized

- Claim: LocalPlayer 的模型与轮盘动画请求显式上报，服务端校验主体、当前可用模型、权限和动画后应用并通知相关客户端；玩家无权选择的模型必须拒绝，浏览或已取得其资产都不授予选择权限，客户端也不能伪造其他玩家的有效状态。

## DD.selection-permission-is-separate-from-asset-access

- Claim: 无权选择某模型的玩家默认仍可取得其资产以呈现 RemotePlayer；服务端启用 `RESTRICTED_AUTH` 时，普通访问收缩为展示信息，并接受相应呈现降级。Level 2 管理员显式 gen-preview 可独立取得当前已发布模型的主目标必要依赖，不要求选择 grant。
- Rationale: 看到他人的模型是联机共同呈现的基本需要，不应默认要求观察者也有权选用该模型；服主可以选择更严格的资产访问限制，并承担观察者无法呈现相应模型的可见代价。

### BC.presentation-access-does-not-grant-model-content

- Claim: 当前目录中的 preamble、manifest、i18n 字符串、预览图和图标属于可取得的展示信息，即使玩家无权选择模型且服务端启用 `RESTRICTED_AUTH` 也可用于展示；展示访问本身不授予模型运行资产或选择权限。

### BC.restricted-auth-limits-assets-and-degrades-presentation

- Claim: 服务端按下表裁决玩家的选择和内容访问；模型运行资产包括 geometry、动画、动画控制器、纹理、音频、脚本等实际呈现所需内容。

| 玩家对模型的选择权限 | `RESTRICTED_AUTH` | 自行选择 | 展示信息 | 模型运行资产 |
|---|---|---|---|---|
| 有权 | 关闭或开启 | 允许 | 允许 | 按需允许 |
| 无权 | 关闭（默认） | 拒绝 | 允许 | 按需允许，可用于呈现 RemotePlayer |
| 无权 | 开启 | 拒绝 | 允许 | 拒绝，不能因观察 RemotePlayer 而取得 |

受限访问使 RemotePlayer 的目标资产不可用时，该客户端将其 humanoid 表现降级到 `default`；受影响的 projectile/vehicle 撤销视觉替换并恢复宿主渲染。降级不改写服务端权威选择，也不阻断游戏。缓存复用或本地同源模型激活仍遵守当前内容访问策略，不能凭已有内容取得新授权。

矩阵中的内容访问指普通请求。显式管理例外仅包含实际主 `player` target 的 definition、默认纹理及 PBR、common strings 和声音首块；其它 target、非默认纹理和声音尾块仍拒绝。该用途不授予普通内容访问或模型选择权限。

## DD.request-time-authorization

- Claim: 对一次模型内容传输或独立管理 query、主目标内容、上传请求的授权在服务端接纳该请求时确定；先前命令或清单不代替下一请求授权。
- Rationale: 已接纳传输代表一次有效授权，后续撤权若改变正在传送的同一份结果，会让接收者得到半份内容；把新权限用于后续请求使内容取得具有明确的业务边界。

### BC.accepted-transfer-keeps-admission-result

- Claim: 模型选择权限、管理员级别或 `RESTRICTED_AUTH` 变化后，后续请求按更新后的访问规则裁决；默认允许观察者取得资产时，失去选择权限本身不禁止资产取得。已经接纳的传输保留接纳时的授权结果，不因后续撤权或配置变化重新授权或取消。持久写入另须保持原 connection 和精确目录成员的效果资格；移除重加同一 identity 也不能复活已退休请求。
