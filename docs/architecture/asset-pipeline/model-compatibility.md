# 模型兼容与容器语义冻结

历史格式的兼容范围、兼容等级及媒体失败边界由[模型兼容决策](../../product-decisions/decisions/model-compatibility.md)定义。

## 兼容目标

Raw 目录、压缩包以及历史 `v1`、`v2`、`v3` 格式都在入口经单向转换进入当前 Asset Container，再交由统一的模型管理与运行时消费。转换过程不恢复旧版加密机制、缓存体系、会话状态或 native 侧模型管理。转换方案的取舍见[资产管线设计](overview.md)，具体执行与校验流程见[转换实现](implementation/conversion-and-export.md)。如果运行时本身存在缺陷，应直接修复根因，不能靠在导入阶段拒绝模型来掩盖问题。

当 raw 或历史来源的默认轮盘列表为空时，转换器会自动补齐 `extra0` 到 `extra7`（名称留空）。这一补齐不依赖 metadata 文件或分类列表是否存在；如果原模型声明了非空默认列表，则保留原声明。转换产物会保存完整列表供界面与服务端共享，运行时不会再为已有的当前容器补齐轮盘。

## 容器语义冻结

后续运行、缓存重建与格式迁移都以导出容器内已定义的语义为输入。需要注意，“语义冻结”只表示运行语义以导出快照为准，并不承诺容器格式版本号永久不变。完整约束见[导出快照契约](../../product-decisions/decisions/model-compatibility.md#bcexported-container-is-runtime-snapshot)，具体字段与版本规范由 [Model Schema](../../standards/model-schema/README.md) 定义。

即使两份模型语义完全相同，也不会因此合并它们的来源身份，详见[模型身份](../model-management/overview.md#身份与来源)；当前实现与规范之间的差距见[格式问题](../../status/known-issues/format-and-schema.md)。
