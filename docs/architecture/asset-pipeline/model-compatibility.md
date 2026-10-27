# 模型兼容与容器语义冻结

兼容范围、等级及媒体失败边界由[模型兼容决策](../../product-decisions/decisions/model-compatibility.md)定义。

## 兼容目标

Raw 与 legacy v1/v2/v3 经单向转换进入当前 Asset Container，再由统一模型管理与运行时消费；不恢复旧加密、缓存、会话或 native 模型管理。转换选择与代价见[资产管线设计](overview.md)，执行与验证见[转换实现](implementation/conversion-and-export.md)。导入拒绝不能代替运行时缺陷的根因修复。

Raw 与 legacy 的默认轮盘列表为空时，转换器补齐 `extra0` 到 `extra7`，名称为空。补齐不取决于 metadata 文件或分类列表是否存在；非空默认列表保留声明。转换结果保存完整列表，界面与服务端共享该列表；运行时不为已有当前容器补齐。

## 容器语义冻结

后续运行、缓存重建和迁移以导出容器的已定义语义为输入；冻结不表示格式版本稳定。完整约束见[导出快照契约](../../product-decisions/decisions/model-compatibility.md#bcexported-container-is-runtime-snapshot)，字段与版本由 [Model Schema](../../standards/model-schema/README.md)定义。

语义相同不合并来源身份，见[模型身份](../model-management/overview.md#身份与来源)；实现差距见[格式问题](../../status/known-issues/format-and-schema.md)。
