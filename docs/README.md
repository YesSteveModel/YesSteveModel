# 文档入口

按问题选择入口，无需顺序通读整棵文档树。

| 要回答的问题 | 入口 |
|---|---|
| 产品为什么这样工作、应保证什么 | [产品决策](product-decisions/README.md)（Root Authority） |
| 公共格式、Schema、wire 与一致性要求 | [独立标准](standards/README.md) |
| 系统分解、主数据流及子系统设计 | [系统架构](architecture/README.md) |
| 启动、作用域、线程交接和关闭 | [运行设计](architecture/runtime/overview.md) / [当前责任映射](architecture/runtime/implementation/lifecycle-and-publication.md) |
| 连接替换、旧安装保留、音频寿命和物理回收 | [按问题定位](architecture/README.md#按问题定位) |
| 已实现、已验证和仍有缺口的范围 | [当前支持状态](status/support-and-verification.md) |
| 机制为何存在、如何归约复杂度 | [复杂度地图](complexity-map/README.md) |

专题入口：

- [构建指南](build.md)
- [基建指南](infrastructure.md)
- [当前 ABI 版本](abi/current.md) / [ABI 维护规则](abi/maintenance.md)
- [迁移概览](migration-overview.md)
- [模型兼容与容器语义冻结](architecture/asset-pipeline/model-compatibility.md)
- [扩展模组兼容性检测](extension-compatibility.md)
- [术语表](glossary.md)
- [文档政策](governance/documentation-policy.md) / [架构文档维护](governance/architecture-docs.md)
- [资源诊断](verification/resource-diagnostics.md)
- 未来方向：[独立 Backend](future/independent-backend.md)、[外部模型源](future/external-model-sources.md)、[模组动画联动](future/mod-animation-integration.md)、[GPU Compute Renderer](future/gpu-compute-renderer.md)

## 文档检查

从仓库根目录运行 `python docs/tools/check_docs.py`。该入口执行链接、锚点、可达性、目录分层和复杂度关系检查；维护要求见[文档政策](governance/documentation-policy.md)。
