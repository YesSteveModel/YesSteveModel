# 文档入口

- [产品决策](product-decisions/README.md)是其声明范围内产品目标、需求、业务约束、领域决策、理由与行为契约的 Root Authority。
- [标准](standards/README.md)及随文发布的 Schema 定义公开格式、wire 编码、通信协议与一致性要求。
- [架构](architecture/README.md)说明模块职责、跨边界契约、设计取舍及理由。[模型兼容与容器语义冻结](architecture/asset-pipeline/model-compatibility.md)属于架构专题；尚未落地的设计差异记录在状态页中。
- [开发指南](development/README.md)约定开发与维护流程；[构建指南](build.md)、[基建指南](development/infrastructure.md)和[扩展模组兼容性检测](extension-compatibility.md)提供构建方式、现有基础设施的使用契约以及第三方接入要求。
- [当前 ABI 版本](abi/current.md)列出各全局 ABI 的版本定义与覆盖范围；[ABI 维护规则](abi/maintenance.md)说明版本迭代方式与兼容门禁，用于落实产品兼容性决策。
- [复杂度地图](complexity-map/README.md)梳理复杂协调机制的因果关系、维护成本与简化机会。
- [验证](verification/policy.md)说明验证方法及结论边界，专题包含[资源诊断](verification/resource-diagnostics.md)与[实体动画所有权验证](verification/entity-animation-ownership.md)；[状态](status/support-and-verification.md)基于可复现的实现行为和验证结果，说明当前已支持的能力与已知缺口。
- [术语表](glossary.md)统一项目专有名称和释义。
- [迁移概览](migration-overview.md)说明重构背景与演进方向。
- 未来方向：
  - [独立 Backend](future/independent-backend.md)
  - [外部模型源](future/external-model-sources.md)
  - [模组动画联动](future/mod-animation-integration.md)
  - [GPU Compute Renderer](future/gpu-compute-renderer.md)
- [文档治理规则](documentation-rules.md)定义整棵文档树的内容取舍、维护流程、写作约定与检查要求；架构文档的专用规则另见[架构文档维护](architecture/documentation-rules.md)。

按问题选择入口：

| 要回答的问题 | 入口 |
|---|---|
| 产品为什么这样工作、应该保证什么 | [产品决策](product-decisions/README.md)（Root Authority） |
| 公共格式、Schema、wire 与一致性要求 | [独立标准](standards/README.md) |
| 系统划分、主数据流及子系统设计 | [系统架构](architecture/README.md) / [架构文档维护](architecture/documentation-rules.md) |
| 如何开发、使用和维护跨边界的架构与代码契约 | [开发指南](development/README.md) |
| 启动、作用域、线程交接与关闭 | [运行设计](architecture/runtime/overview.md) / [当前责任映射](architecture/runtime/implementation/lifecycle-and-publication.md) |
| 连接替换、旧安装保留、音频寿命和物理回收 | [按问题定位](architecture/README.md#按问题定位) |
| 已实现、已验证和仍有缺口的范围 | [当前支持状态](status/support-and-verification.md) |
| 某个复杂机制为何存在、如何降低复杂度 | [复杂度地图](complexity-map/README.md) |
| 如何解读验证范围和结果 | [验证边界](verification/policy.md) |
| 如何编写、维护和检查文档 | [文档治理规则](documentation-rules.md) |
