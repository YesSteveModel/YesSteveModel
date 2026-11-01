# 文档入口

- [产品决策](product-decisions/README.md)是其声明范围内产品目标、需求、业务约束、领域决策、理由和行为契约的 Root Authority。
- [标准](standards/README.md)及随文发布的 schema 定义格式、wire、协议和一致性要求。
- [架构](architecture/README.md)定义职责、契约、设计选择及理由。[模型兼容与容器语义冻结](architecture/asset-pipeline/model-compatibility.md)属于架构专题。尚未落地的设计差异归 status。
- [开发指南](development/README.md)定义开发与维护规则；[构建指南](build.md)、[基建指南](development/infrastructure.md)和[扩展模组兼容性检测](extension-compatibility.md)提供构建方法、已有能力的使用契约及接入要求。
- [当前 ABI 版本](abi/current.md)记录版本值与覆盖范围；[ABI 维护规则](abi/maintenance.md)定义版本迭代与兼容门禁，落实产品兼容性决策。
- [复杂度地图](complexity-map/README.md)记录复杂机制因果、协调成本和归约机会。
- [验证](verification/policy.md)记录方法及证据边界，专题包括[资源诊断](verification/resource-diagnostics.md)和[实体动画所有权验证](verification/entity-animation-ownership.md)；[状态](status/support-and-verification.md)以可复现的实现行为和验证结果说明当前支持与缺口。
- [术语表](glossary.md)统一名称与释义。
- [迁移概览](migration-overview.md)说明重构背景与迁移方向。
- 未来方向：
  - [独立 Backend](future/independent-backend.md)
  - [外部模型源](future/external-model-sources.md)
  - [模组动画联动](future/mod-animation-integration.md)
  - [GPU Compute Renderer](future/gpu-compute-renderer.md)。
- [文档治理规则](documentation-rules.md)定义整棵文档树的信息取舍、维护、写作约定与检查要求；架构文档专用规则见[架构文档维护](architecture/documentation-rules.md)。

按问题选择入口：

| 要回答的问题 | 入口 |
|---|---|
| 产品为什么这样工作、应保证什么 | [产品决策](product-decisions/README.md)（Root Authority） |
| 公共格式、Schema、wire 与一致性要求 | [独立标准](standards/README.md) |
| 系统分解、主数据流及子系统设计 | [系统架构](architecture/README.md) / [架构文档维护](architecture/documentation-rules.md) |
| 如何开发、使用和维护 seam 的架构与代码契约 | [开发指南](development/README.md) |
| 启动、作用域、线程交接和关闭 | [运行设计](architecture/runtime/overview.md) / [当前责任映射](architecture/runtime/implementation/lifecycle-and-publication.md) |
| 连接替换、旧安装保留、音频寿命和物理回收 | [按问题定位](architecture/README.md#按问题定位) |
| 已实现、已验证和仍有缺口的范围 | [当前支持状态](status/support-and-verification.md) |
| 机制为何存在、如何归约复杂度 | [复杂度地图](complexity-map/README.md) |
| 如何解释验证范围和结果 | [验证边界](verification/policy.md) |
| 如何编写、维护和检查文档 | [文档治理规则](documentation-rules.md) |
