# model-authorization

- Requirement: [REQ.bounded-model-distribution](../requirements/req-bounded-model-distribution.md#reqbounded-model-distribution)
- Select: 配置 auth 分类、选择与资产及展示权限、RESTRICTED_AUTH、gen-preview 主目标管理例外、接纳后撤权
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
  - [design](../../architecture/model-management/sessions/overview.md#配置与目录发布)

## DD.server-decides-model-operations

- Constraints: [CON.client-input-untrusted](../shared.md#conclient-input-untrusted)
- Claim: 多人模式中，客户端在准备完成后本地提交选择，服务端再独立判定远端可见的选择并分发给其他玩家。轮盘动画属于当前选择的表现增量。
- Rationale: 这些状态由模组新增，游戏本体不会自动同步。由于它们会改变其他玩家看到的内容，客户端的意图必须统一经过服务端的权限与业务校验。

### BC.selection-and-wheel-are-authorized

- Claim: LocalPlayer 显式上报完整选择和表现增量。服务端依次校验请求结构、操作主体、当前目录中的模型成员、grant 以及切换开关；intrinsic default 单独豁免切换开关，普通 builtin 不豁免。服务端不读取模型内部字段布局，也不判定纹理名或动画名是否属于该模型。请求因业务规则被拒绝时，服务端保留原先已接纳的选择，并无条件向该客户端指派 intrinsic default；对于结构非法、过时或重复的请求，服务端直接丢弃。浏览列表或取得资产不会授予选择权限。

## DD.selection-permission-is-separate-from-asset-access

- Claim: 无权选择某模型的玩家默认仍可取得其资产，用于正常呈现 RemotePlayer。当服务端启用 `RESTRICTED_AUTH` 时，无权玩家的普通访问范围缩小为展示信息，并接受由此带来的呈现降级。Level 2 管理员执行显式 gen-preview 时，可以独立取得当前已发布模型主目标的必要依赖，不要求持有该模型的选择 grant。
- Rationale: 看到他人的模型是联机共享呈现的基本需要，不应默认要求观察者也拥有选用权。服主可以选择更严格的资产访问限制，并承担观察者无法呈现受限模型的视觉代价。

### BC.restricted-model-list-owns-access-policy

- Claim: 服务端配置 `RESTRICTED_MODEL_LIST` 是普通用户模型区分 public 与 auth 的唯一依据，缺省为空列表。条目用 `path:` 匹配完整公开地址，用 `id:` 匹配完整 ModelId，命中任意规则即归为 auth。Builtin 保持独立可用，intrinsic 不参与分类。来源目录不授予任何权限，配置分类本身也不会授予或回收玩家的 grant。
- Claim: 配置成功加载或重载后，服务端对当前完整目录统一重新分类；新模型在首次对外可见前应用当前有效策略。候选列表读取或校验失败时，服务端报告错误并保留最近一次有效策略；启动时若无有效配置，则只发布 builtin。服务端不会将失败当作空列表处理，也不会自动覆盖磁盘上的非法配置。
- Claim: glob 对完整字符串区分大小写匹配：`*` 匹配零个或多个 Unicode 码点（code point），`?` 恰好匹配一个，两者均可跨 `/`。其余字符均按字面值匹配，不支持转义、正则、字符组、去首尾空白或 Unicode 规范化（normalization）。前缀必须精确填写且不可省略；出现空模板、错误类型或孤立 surrogate 时，整份列表都会被拒绝。
- Claim: 移除规则或路径不再匹配后，模型恢复为 public；ID 规则按完整身份匹配，不会自动继承其他版本的权限。模型从 public 改为 auth 时，没有 grant 的当前选择按普通撤权规则重新处理；已经接纳的传输保持原有授权不变。模型 auth 设置与取消指令以及 reload 的默认 auth 参数不再提供。
- Claim: 管理员强制玩家选择 auth 模型前，应先显式授予普通 grant，该 grant 同时也允许玩家自行选择。强制选择不会隐式授予权限，也不提供 `ignore_auth` 参数或独立的持久绕过字段。

### BC.presentation-access-does-not-grant-model-content

- Claim: 当前目录中的 preamble、manifest、多语言字符串、预览图和图标都属于可获取的展示信息。即使玩家无权选择该模型且服务端启用了 `RESTRICTED_AUTH`，仍可获取这些信息用于展示，但展示访问本身不授予模型运行资产权限或选择权限。

### BC.restricted-auth-limits-assets-and-degrades-presentation

- Claim: 服务端按下表判定玩家的选择权和内容访问权。模型运行资产包括几何、动画、动画控制器、纹理、音频和脚本等实际呈现所需内容。

| 玩家对模型的选择权限 | `RESTRICTED_AUTH` | 自行选择 | 展示信息 | 模型运行资产 |
|---|---|---|---|---|
| 有权 | 关闭或开启 | 允许 | 允许 | 按需允许 |
| 无权 | 关闭（默认） | 拒绝 | 允许 | 按需允许，可用于呈现 RemotePlayer |
| 无权 | 开启 | 拒绝 | 允许 | 拒绝，不能因观察 RemotePlayer 而取得 |

受限访问导致 RemotePlayer 的目标资产不可用时，该客户端将其 humanoid 表现降级到 `default`，受影响的 projectile 与 vehicle 则撤销视觉替换并恢复宿主渲染。此类降级不改写服务端权威选择，也不阻断游戏。缓存复用或本地同源模型激活同样受当前内容访问策略约束，不能凭本地已有内容取得新授权。

上表中的内容访问仅指普通请求。显式管理例外只包含实际主 `player` target 的 definition、默认纹理及 PBR、common strings 和声音首块；其他 target、非默认纹理和声音尾块依然拒绝。该用途不授予普通内容访问权限或模型选择权限。

## DD.request-time-authorization

- Claim: 一次模型内容传输，以及独立的管理查询、主目标内容获取和上传请求，均在服务端接纳该请求时确定授权；先前的命令或清单不能代替后续请求的授权检查。
- Rationale: 已接纳的传输代表一次已经生效的授权。如果后续撤权中途改变正在发送的同一份结果，接收方就会拿到残缺内容；将新权限作用于后续请求，才能让内容获取拥有清晰的业务边界。

### BC.accepted-transfer-keeps-admission-result

- Claim: 模型选择权限、管理员级别或 `RESTRICTED_AUTH` 发生变化后，后续请求按更新后的访问规则判定；默认允许观察者取得资产时，失去选择权限本身不禁止取得资产。已经接纳的传输保留接纳时的授权结果，不因后续撤权或配置变化而重新授权或取消。涉及持久写入时，还必须保持同一个连接（connection）和目录中精确匹配的同一成员；即使将同一 identity 移除后再重新加入，也不能让已结束的请求重新生效。
