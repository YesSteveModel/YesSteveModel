# Product decisions

Authority: 本目录树是声明范围内产品目标、需求、业务约束、领域决策、理由和行为契约的唯一权威来源（Root Authority），每项产品判断只在此定义一次。标准、架构与实现必须遵循这些决策，不得自行重定义；当前行为、支持状态和验证结论由对应的下游页面维护，不能反向改写产品决策。Decision-status = Complete 仅表示产品决策已完备，不代表已经实现或通过验证。

Scope: Minecraft 1.20.1 Forge；未来方向以及游戏版本或加载器迁移不在当前范围内。

## Read

首次阅读请先查看 [shared.md 的 Constraints](shared.md#constraints)，了解全局底线以及默认模型的限定例外；其余产品目标和场景按引用查阅。遇到不熟悉的术语时查阅 [terms.md](terms.md)。

- **从需求出发**：从下表选择 REQ，经 Select 找到对应决策包，按条件展开 Needs，再用 Landing 定位下游文档。只有在完整处理该 REQ 时，才需要通读其全部 Select 与 Uses。
- **从实现出发**：用领域符号、配置名或下游文档名检索 [reverse-index.md](reverse-index.md)，打开对应决策包，定位 BC 所属的 DD 及其 Rationale，再按 Requirement 和 Motivation 向上追溯需求与目标。如果只需核对当前实现，查到 Landing 即可停止；如果只查业务理由，无需加载下游正文。

默认以完整决策包为单位阅读，一并读完包头、DD 与 BC、规则优先级、例外和互斥条件。如果目标小节本身自包含，或已显式列出所需的包头、共享规则和跨节引用，也可以按节读取，不必通读未引用的正文来排查隐含边界。`### BC.*` 默认归属于当前 DD，显式声明 Decision 字段时则以该字段为准。

Needs 仅在写明的条件成立时展开，并跳过已读过的 ID，不要递归展开整个 REQ 或整棵文档树。取得目标决策、理由、适用约束、例外及所需落点后即可停止。尚未定案的问题保持未决状态，不要根据现有实现补写产品决策。需要新增、修改、合并、下沉或删除决策，或者审查分层与校验决策文档时，请查阅[维护规则](maintenance.md)。

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

- ID 前缀代表记录类型：PG、SCN、CON 定义见 shared；REQ 表示需求；DD 表示领域决策；BC 表示可观察行为契约。ID 是稳定定位键，文件分包可以调整。标题只放 ID，跨文档链接使用自动锚点（ID 转小写并去掉句点），不额外维护 HTML 锚点。
- Claim 记录决定或承诺，Rationale 记录产品选择理由与成本考量。DD 默认处于 selected 状态；标记为 Disposition = superseded 的方案只用来解释当初为何拒绝，不能作为当前行为依据；标记为 Horizon = future 的记录不表示已经实现。
- Requirement 指向包内决策的需求来源；Motivation 指向上游产品目标或场景；Constraints 列出明确约束；Needs 记录带触发条件的跨包边界。REQ 的 Select 与 Uses 已提供正向关系，不再另写重复边表。
- Landing 只承担路由作用：standard 定义格式与 wire 协议，design 定义内部契约与取舍，implementation 定位当前机制，verification 定义验证方法与证据解释，governance 定义文档治理规则，status 记录支持状态与已知缺口，future 说明后续方向。存在落点链接并不证明该决策已经落实；如果映射不到下游，必须显式报告缺少落点，不能仅凭目录名称相似就推定已经实现。
- 正文是业务事实的唯一拥有者；Select、Uses 与 reverse-index 只负责导航，不重复抄写结论。分包按共同的决策边界划分，不按行数或每个 ID 机械拆分文件。
