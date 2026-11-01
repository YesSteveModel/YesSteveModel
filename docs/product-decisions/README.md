# Product decisions

Authority: 本树是声明范围内产品目标、需求、业务约束、领域决策、理由和行为契约的 Root Authority；每项产品判断只有一个权威定义。标准、架构与实现消费这些选择，不得独立重定义；当前行为、支持和验证结论由对应下游页面维护，不能反向改写产品选择。Decision-status = Complete 仅表示决策闭合。

Scope: Minecraft 1.20.1 Forge；未来方向及游戏版本/加载器迁移不在当前范围。

## Read

首次进入读取 [shared.md 的 Constraints](shared.md#constraints)，得到全局底线及默认模型的限定例外；其他目标和场景按引用读取。术语不明时查 [terms.md](terms.md)。

- **从需求出发**：从下表选择 REQ，经 Select 选择决策包，按条件展开 Needs，再用 Landing 定位下游。只有完整处理该 REQ 时才读取其全部 Select/Uses。
- **从实现出发**：用领域符号、配置名或下游文档名检索 [reverse-index.md](reverse-index.md)，读取对应决策包，定位 BC 所属的 DD 及其 Rationale，按 Requirement/Motivation 追溯需求与目标。仅查当前实现时在 Landing 停止；仅查业务理由时不加载下游正文。

默认以完整决策包为读取单元，带齐包头、DD/BC、规则优先级、例外和互斥条件。目标节自包含，或显式列出所需包头、共享规则和跨节引用时，可按节读取；不要求读者扫描未读正文发现必要边界。`### BC.*` 默认属于当前 DD；显式 Decision 字段覆盖嵌套归属。

Needs 仅在写明的条件成立时展开，并去重已读 ID，不递归展开整个 REQ 或整棵树。取得目标决定、理由、适用约束和例外及所需落点后停止。未裁决的问题保留未决，不从实现补写产品决定。新增、修改、合并、下沉或删除决策，以及审查分层或校验决策文档时，读取[维护规则](maintenance.md)。

## Select requirement

| 需求线索 | 入口 |
|---|---|
| 始终解析到可安全使用的模型 | [REQ.authoritative-model-lifecycle](requirements/req-authoritative-model-lifecycle.md) |
| 让联机玩家按需要共享模型与表现状态 | [REQ.bounded-model-distribution](requirements/req-bounded-model-distribution.md) |
| 让玩家与创作者控制模型内容 | [REQ.create-and-select-models](requirements/req-create-and-select-models.md) |
| 区分模组与模型许可、了解内容责任和免责声明 | [REQ.understand-content-rights](requirements/req-understand-content-rights.md) |
| 屏蔽干扰游玩的模型或第一人称手臂、调节模型音效 | [REQ.control-local-presentation](requirements/req-control-local-presentation.md) |
| 从玩家状态形成模型动作 | [REQ.evaluate-entity-animation](requirements/req-evaluate-entity-animation.md) |
| 联动既有内容并适配模型表现 | [REQ.integrate-mod-content](requirements/req-integrate-mod-content.md) |
| 以可控成本提供主流平台的基础可用性 | [REQ.platform-availability](requirements/req-platform-availability.md) |
| 以独立制品携带可验证模型 | [REQ.portable-model-artifact](requirements/req-portable-model-artifact.md) |
| 模型能力不能成为游戏故障源 | [REQ.preserve-gameplay-and-availability](requirements/req-preserve-gameplay-and-availability.md) |
| 更新与迁移持续履行已有模型语义 | [REQ.preserve-model-semantics](requirements/req-preserve-model-semantics.md) |
| 保持作品语义与游戏视觉反馈 | [REQ.render-model-consistently](requirements/req-render-model-consistently.md) |

## Records

- ID 前缀定义类型：PG/SCN/CON 见 shared；REQ = 需求；DD = 领域决策；BC = 可观察行为契约。ID 是稳定定位键，文件分包可调整；标题只含 ID，链接使用自动锚点（ID 转小写并去掉句点），不维护额外 HTML 锚点。
- Claim = 决定或承诺；Rationale = 产品选择理由与成本。DD 默认 selected；Disposition = superseded 的方案只解释拒绝理由，不能作为当前行为。Horizon = future 的记录不表示已实现。
- Requirement 指向包内决策的需求来源；Motivation 指向上游目标/场景；Constraints 是明确约束；Needs 是带触发条件的跨包边界。REQ.Select/Uses 提供正向关系，不另写重复边表。
- Landing 只路由：standard 定义格式/wire，design 定义内部契约与取舍，implementation 定位当前机制，verification 定义验证方法与证据解释，governance 定义文档治理规则，status 定义支持/缺口，future 定义后续方向。有链接不证明该决定已落实；映射不到时显式报告缺少落点，不从目录相似推定实现。
- 正文唯一拥有业务事实；Select/Uses 与 reverse-index 只维护导航，不复制结论。分包依据共同裁决边界，不按行数或每个 ID 机械拆文件。
