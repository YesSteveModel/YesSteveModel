# 模型兼容与容器语义冻结

历史格式的兼容范围、兼容等级及媒体失败边界由[模型兼容决策](../../product-decisions/decisions/model-compatibility.md)定义。

## 兼容目标

Raw 目录、压缩包及历史 `v1`、`v2`、`v3` 格式均在入口经单向转换进入当前 Asset Container，再交由统一的模型管理与运行时消费；转换不引入旧版加密、缓存、会话或 native 模型管理。方案取舍见[资产管线设计](overview.md)，实现流程见[转换实现](implementation/conversion-and-export.md)。

当 raw 或历史来源的默认轮盘列表为空时，转换器自动补齐 `extra0` 到 `extra7`（名称留空），不依赖 metadata 或分类列表是否存在；非空声明原样保留。转换产物保存完整列表供界面与服务端共享，运行时不对已有当前容器再补齐轮盘。

## 容器语义冻结

后续运行、缓存重建与格式迁移均以导出容器内已定义的语义为输入。“语义冻结”指运行语义以导出快照为准，容器格式版本仍可按 [Model Schema](../../standards/model-schema/README.md) 演进，完整约束见[导出快照契约](../../product-decisions/decisions/model-compatibility.md#bcexported-container-is-runtime-snapshot)。

两份语义相同的模型仍各自保持独立来源身份，见[模型身份](../model-management/overview.md#身份与来源)；当前实现差异见[格式问题](../../status/known-issues/format-and-schema.md)。
