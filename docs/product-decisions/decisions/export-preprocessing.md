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

- Claim: `/ysm export <model> [extra]` 与 `/ysm export-all [reg]` 仅在当前 integrated server 环境供服务端 level 2 管理员执行。Dedicated server 对已有图和缺图均拒绝；进程存在 client renderer 不能授予导出资格。Builtin 不可导出。
- Claim: 单项与批量导出只接受当前已发布的模型，不读取未发布来源候选。Raw／legacy 转换所得的内部容器仍允许缺图发布，导出时补齐图片；direct 成品缺图时禁止发布，也不能通过单项导出修复。
- Rationale: 完整导出可能需要真实主 target 渲染；统一环境资格使 warm 图片命中和 cold 生成具有一致、可预测的权限与终止边界，避免图片状态决定专服是否开放另一条出口。
- Rationale: 正常导出保证制品完整；异常缺图成品的修复不属于创作流程。统一使用已发布输入可避免导出另建来源搜索与准入路径。

### BC.batch-export-freezes-published-paths

- Claim: `export-all` 针对接纳时已经发布的非 builtin 模型集合，不扫描未准入来源。之后新增模型留到下一批，移除或换内容的未开始项跳过；已开始项不得静默改为另一份模型内容。
- Claim: `reg` 经宿主命令字符串解析后对整条 catalog 路径作区分大小写的 glob 匹配：`*` 为零个或更多 Unicode code points，`?` 恰好一个，两者均可跨 `/`；连续星号等价。其余字符包括点、括号、方括号与反斜线均为字面字符，无转义、正则、路径转换或 Unicode normalization。省略为全集，显式空串与无匹配为成功空选集。
- Claim: 单项失败不阻止其他有效项导出；批次报告逐项结果及成功、失败、跳过、未处理汇总。已成功提交的产物不因其他项失败而撤回。

### BC.export-produces-complete-independent-artifacts

- Claim: 每个成功输出都是完整、可独立重载的当前 `.mxc`，保留模型来源身份、署名、行为和有效作者图及其来源声明。缺图时才使用适用独立图或真实生成图，并标明生成来源；不得以 placeholder 冒充成功。
- Claim: 输出验证通过才替换目标；失败保留来源及旧有效输出。输出始终独立于来源。
