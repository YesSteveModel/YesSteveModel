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

- Claim: 创作者能够把 Blockbench 等工具制作的受支持 geometry、动画、控制器和纹理组织成模型；采用基岩版体系不自动承诺其全部特性。

## DD.custom-content-hot-load

- Claim: 玩家添加和创作者迭代模型后，可以在游戏进程内热加载该内容。
- Rationale: 自定义模型和创作调试需要反复修改与观察；每次变化都重启游戏会显著增加日常创作和使用成本。

### BC.model-edit-can-reload-in-game

- Claim: 已完成验证的新增或更新模型可以通过显式 reload 或既有生命周期触发进入当前可用模型列表与后续使用；文件变化本身不触发扫描。无效内容不破坏已有游戏状态。

### BC.catalog-links-preserve-logical-location

- Claim: 用户可用符号链接或 Windows directory junction 组织 custom/auth 来源。目录、来源索引、pack hierarchy 与发布路径应沿配置根的逻辑层级命名；链接目标可在根外。全局与实例的 custom/auth 同时参与发现。完整公开位置区分来源根，保留文件入口后缀；物理目标和内容身份都不能替代该位置。来源类别取决于逻辑入口，模型身份仍取决于已验证内容。
- Rationale: 创作者可在独立作品目录保存原件，并用链接安排游戏中的层级。raw 入口已能挂载整个作品目录；禁止 raw 内部链接限制输入捕获的复杂度。

| 入口 | 符号链接 | Directory junction |
|---|---|---|
| custom/auth 根、目录分组、raw 入口目录 | 允许 | 允许 |
| 已支持的 zip/legacy/container 文件 | 允许 | 不适用，入口应为文件 |
| 分组中的 pack manifest/cover 文件 | 允许 | 不适用，入口应为文件 |
| raw 内部文件、子目录与 manifest | 禁止 | 禁止 |

全局目录只允许在启动探测期间创建正常缺席的根和自有探针，见[来源保护例外](artifact-storage.md#bclocal-catalog-input-is-never-mutated)。已有链接和 junction 保留；断链不视为缺席，不创建断链目标。实例 custom 自动创建、实例 auth 仅在 dedicated server 自动创建的边界不变。

文件格式按逻辑入口名识别。链接不放宽内容验证、来源权限或缓存写入边界，也不扩展压缩包内部链接语义。多个逻辑入口均可参与候选；同相对位置的不同内容可并存，同内容冲突由 [Catalog 设计](../../architecture/model-management/catalog/overview.md#seam-contract)裁决，不承诺固定赢家或多别名展示。

断链、访问失败、祖先环或 raw 内部链接只拒绝受影响分支或模型；同根健康项继续参与验证与发布。不完整观察不能证明旧项删除，见[目录发布](catalog-publication.md#bccatalog-defines-current-content)。修复输入本身不触发重载。
