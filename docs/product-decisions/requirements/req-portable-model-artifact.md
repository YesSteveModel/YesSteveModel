# REQ.portable-model-artifact

- Claim: 创作者和玩家必须能用独立制品分享完整模型定义，使用方可以取得所需内容而不依赖原作者的 raw 文件目录。
- Decision-status: Complete
- Motivation: [PG.creator-controlled-content](../shared.md#pgcreator-controlled-content); [PG.shared-multiplayer-presentation](../shared.md#pgshared-multiplayer-presentation)

## Select

| 任务涉及 | 决策包 |
|---|---|
| 单文件制品与完整模型定义 | [container-definition](../decisions/container-definition.md) |
| Raw 资产预处理、集成环境单项与固定集合完整导出 | [export-preprocessing](../decisions/export-preprocessing.md) |
| 整体视觉保真、输入兼容与处理成本 | [image-policy](../decisions/image-policy.md) |
| 用户来源只读、独立 preview 持久提交与缓存处置边界 | [artifact-storage](../decisions/artifact-storage.md) |

## Uses

- [DD.catalog-before-model-body — 按需取得经过验证的目标依赖](../decisions/model-distribution.md#ddcatalog-before-model-body)
- [DD.single-current-catalog — 只发布完整有效内容](../decisions/catalog-publication.md#ddsingle-current-catalog)
