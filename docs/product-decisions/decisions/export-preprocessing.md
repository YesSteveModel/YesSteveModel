# export-preprocessing

- Requirement: [REQ.portable-model-artifact](../requirements/req-portable-model-artifact.md#reqportable-model-artifact)
- Select: Raw 资产预处理与集成环境完整导出
- Needs:
  - 读取、转换或排除原始文件: [DD.user-model-sources-are-readonly](artifact-storage.md#dduser-model-sources-are-readonly)
  - 已有导出物的语义: [DD.semantic-freeze-at-export](model-compatibility.md#ddsemantic-freeze-at-export)
  - 普通无效输入: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
  - 保留来源创作者声明: [DD.visible-creator-attribution](selection-and-attribution.md#ddvisible-creator-attribution)
  - 导出输入的已发布范围: [DD.single-current-catalog](catalog-publication.md#ddsingle-current-catalog)
  - 有限工作与主循环优先: [DD.main-loop-has-priority](workload-budget.md#ddmain-loop-has-priority)
- Landing:
  - [standard](../../standards/model-schema/assets-and-validation.md#分层验证与发布)
  - [design](../../architecture/asset-pipeline/model-compatibility.md#容器语义冻结)
  - [implementation](../../architecture/asset-pipeline/implementation/conversion-and-export.md#preview-取得与显式-export)
  - [implementation](../../architecture/asset-pipeline/overview.md#导出批次与提交)
  - [design](../../architecture/model-management/catalog/overview.md#逻辑位置与接纳)
  - [design](../../architecture/asset-pipeline/overview.md)
  - [design](../../architecture/runtime/overview.md)
  - [design](../../architecture/model-management/storage/overview.md#独立图片与提交资格)

## DD.export-preprocesses-assets

- Claim: Raw 导出在形成模型制品之前完成资产预处理。
- Rationale: 错误、歧义和无关文件越晚发现，越容易在所有使用者处重复付出代价；提前校验、排除无关内容并适当压缩资产可减小制品与分发成本，也使冻结的内容成为明确的运行输入。

### BC.export-validates-declared-assets

- Claim: 导出检查资产、引用与模型定义的正确性，并消除受支持规则能唯一消除的歧义；无法唯一解释的内容不得被当作有效模型静默导出。

### BC.export-removes-unrelated-cost

- Claim: Raw 内与模型无关的文件不进入制品，不能让每次模型分享和使用都为无关内容付费。

## DD.export-runs-in-integrated-environment

- Claim: `/ysm export <model> [extra]` 与 `/ysm export-all [reg]` 仅允许服务端 level 2 管理员在当前集成服务端（integrated server）环境中执行。独立服务端（dedicated server）无论模型是否已有预览图均拒绝执行；仅凭进程中存在客户端渲染器也不能授予导出资格。Builtin 模型不可导出。
- Claim: 单项与批量导出只接受当前目录中已发布的模型，不读取未发布的来源候选。由 raw 或 legacy 转换得到的内部容器仍允许在缺图状态下发布，并在导出时补齐图片；而 direct 成品缺图时禁止发布，也不能通过单项导出修复。
- Rationale: 完整导出可能需要通过真实主 target 渲染来生成预览图；统一环境资格可确保命中已有图片和冷生成两种情况具备一致、可预测的权限与终止边界，避免因图片状态不同而让独立服务端多出另一条导出通道。
- Rationale: 正常导出保证制品完整，而修复异常缺图的成品不属于创作流程。统一从已发布目录读取输入，也能避免导出功能另起一套来源搜索与验证路径。

### BC.batch-export-freezes-published-paths

- Claim: `export-all` 只处理命令接纳时已经发布的非 builtin 模型集合，不扫描未通过验证的来源。批次启动后新增的模型留待下一批处理，尚未开始便被移除或更换内容的项直接跳过；已经开始的项不得静默切换为另一份模型内容。
- Claim: `reg` 经宿主命令字符串解析后，对整条 catalog 路径做区分大小写的 glob 匹配：`*` 匹配零个或多个 Unicode 码点，`?` 匹配恰好一个，两者均可跨越 `/` 匹配，连续星号与单个星号等价。其余所有字符（包括点号、圆括号、方括号与反斜线）均按字面字符处理，不支持转义、正则、路径转换或 Unicode 规范化。省略参数时匹配全集；传入显式空串或无匹配项时，视为成功但选集为空。
- Claim: 单项失败不阻止其他有效项导出；批次报告逐项结果以及成功、失败、跳过、未处理的汇总。已成功提交的产物不因其他项失败而撤回。

### BC.export-produces-complete-independent-artifacts

- Claim: 每个成功导出的产物都是完整、可独立重载的当前版本 `.mxc` 文件，保留原有的模型来源身份、署名、行为，以及有效的作者图及其来源声明。仅在缺少作者图时才采用适用的独立缓存图或真实生成的预览图，并标明其生成来源；不得用占位图（placeholder）冒充成功。
- Claim: 输出产物必须通过验证才能替换目标文件；导出失败时保留原始来源及原有的有效输出。导出产物始终独立于原始来源。
