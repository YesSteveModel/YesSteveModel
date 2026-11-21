# authoring-input-and-reload

- Requirement: [REQ.create-and-select-models](../requirements/req-create-and-select-models.md#reqcreate-and-select-models)
- Select: Blockbench 创作体系、逻辑来源链接、添加模型与显式热加载
- Needs:
  - 加载用户来源: [DD.user-model-sources-are-readonly](artifact-storage.md#dduser-model-sources-are-readonly)
  - 热加载发布: [DD.single-current-catalog](catalog-publication.md#ddsingle-current-catalog)
  - 修改已支持作品: [DD.explicit-support-is-stable](model-compatibility.md#ddexplicit-support-is-stable)
  - 内容无效: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [standard](../../standards/model-schema/assets-and-validation.md)
  - [implementation](../../architecture/model-management/catalog/overview.md#实现与验证入口)
  - [design](../../architecture/model-management/catalog/overview.md#seam-contract)
  - [design](../../architecture/model-management/catalog/overview.md#逻辑位置与接纳)

## DD.bedrock-creator-ecosystem

- Claim: 模型采用基岩版的体素几何、Molang、动画与动画控制器创作体系。
- Rationale: 体素美术风格契合 Minecraft，基岩版已有设计经验和创作者生态，Blockbench 提供成熟建模工具；复用这些基础降低创作、学习与维护成本。

### BC.supported-bedrock-content-is-authorable

- Claim: 创作者能够把 Blockbench 等工具制作的受支持几何体、动画、控制器和纹理组织成模型；采用基岩版体系不自动承诺支持其全部特性。

## DD.custom-content-hot-load

- Claim: 玩家添加和创作者迭代模型后，可以在游戏进程内热加载该内容。
- Rationale: 自定义模型和创作调试需要反复修改与观察；每次变化都重启游戏会显著增加日常创作和使用成本。

### BC.model-edit-can-reload-in-game

- Claim: 新增或更新的模型在通过验证后，可以通过显式 reload 或既有生命周期触发进入当前可用模型列表并供后续使用；文件变动本身不会自动触发扫描。无效内容不得破坏已有的游戏状态。

### BC.catalog-links-preserve-logical-location

- Claim: 用户可以使用符号链接或 Windows directory junction 组织 models 来源。目录结构、来源索引、pack 层级（pack hierarchy）与发布路径都沿配置根下的逻辑层级命名，链接目标可以位于根目录之外。全局与实例两处的 models 目录同时参与发现。完整的公开位置需要区分来源根并保留文件入口后缀，既不能用物理目标路径替代，也不能用内容身份替代。来源类别由逻辑入口决定，模型身份仍由已验证的内容决定。
- Rationale: 创作者可以在独立的作品目录中保存原件，并用链接安排游戏中的目录层级。raw 入口已经能够挂载整个作品目录；禁止 raw 内部链接可限制输入捕获的复杂度。

| 入口 | 符号链接 | Directory junction |
|---|---|---|
| models 根、目录分组、raw 入口目录 | 允许 | 允许 |
| 已支持的 zip、legacy 或 container 文件 | 允许 | 不适用，入口应为文件 |
| 分组中的 pack manifest 或 cover 文件 | 允许 | 不适用，入口应为文件 |
| raw 内部文件、子目录与 manifest | 禁止 | 禁止 |

全局目录只允许在启动探测期间创建正常缺失的根目录和自有探针，详见[来源保护例外](artifact-storage.md#bclocal-catalog-input-is-never-mutated)。已有的符号链接和 junction 保持不变；断链不视为缺失，也不为其创建目标。实例 models 目录在客户端与服务端均可创建；旧版 custom 与 auth 目录既不参与发现，也不自动迁移。

文件格式按逻辑入口名识别。使用链接不会放宽内容验证、来源权限或缓存写入边界，也不会扩展压缩包内部的链接语义。多个逻辑入口均可进入候选集合：同一相对位置下的不同内容允许并存，而相同内容的冲突处理规则由 [Catalog 设计](../../architecture/model-management/catalog/overview.md#seam-contract)定义，不承诺固定胜出方，也不保证多别名同时展示。

遇到断链、访问失败、祖先环或 raw 内部链接时，只拒绝受影响的分支或模型，同一根目录下的其他健康项继续参与验证与发布。目录观察不完整时，不能据此判定旧项已被删除，详见[目录发布](catalog-publication.md#bccatalog-defines-current-content)。修复本地输入本身不会触发重载。
