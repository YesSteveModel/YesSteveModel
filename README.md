# Yes Steve Model

## ⚠️ 警告

当前公开版本还未完成，不保证稳定性、数据安全、跨平台行为、API、代码结构或后续版本兼容性。请勿用于生产环境或重要存档，测试前务必备份游戏目录、世界和模型。

所有公开格式、Schema、内部协议和缓存布局均尚未冻结，在正式发布前大概率会有 break change，并且不做向后兼容。

## 项目状态

本项目正在进行大规模重构，目前公开代码主要用于审阅、协作和验证设计。

目前仅协作者可贡献代码，如有贡献意愿可加入 YSM 开发者交流群了解详情。

更多信息见 [迁移概览](docs/migration-overview.md) 

## 与旧版相比

| 类别     | 重点                                                                                                                                                    |
| ------ |---------------------------------------------------------------------------------------------------------------------------------------------------------|
| 新增能力   | 公开的模型资产标准；细粒度资产分发；动态资源管理；更多平台支持。                                                                                        |
| 既有能力改进 | 模型业务回归 Java，native 收缩为能力层；内容身份、连接、资源所有权、失效和恢复边界显式化。                                                              |
| 迁移重点   | 扩展 API |
|        |                                                                                                                                                         |
| 未来方向   | 模型签名、通用外部模型源、GPU Compute Pipeline、独立 Backend。                                                                                |

## 文档

- [完整索引](docs/README.md)
- [构建指南](docs/build.md)
- [术语表](docs/glossary.md) / [文档政策](docs/governance/documentation-policy.md)
- [独立格式标准](docs/standards/README.md)
- [产品决策](docs/product-decisions/README.md)
- [系统架构](docs/architecture/README.md) / [按问题定位](docs/architecture/README.md#按问题定位)
- [当前支持状态](docs/status/support-and-verification.md)

## 许可证

- 除另有声明的内容外，本仓库的原创代码按 [Apache License 2.0](LICENSE) 开源。
- Asset Container Spec、Model Schema、规范性 Proto 快照及一致性要求是独立于 YSM 和 Minecraft 的标准，按 CC0 1.0 Universal 发布。
- 内置模型资产不属于 Apache-2.0；每个资产目录中的 `ysm.json` 是其许可证的权威清单。
- 项目包含直接拷贝或修改的第三方代码以及随包依赖，详见[NOTICE.md](NOTICE.md)。

Apache-2.0 不覆盖上述独立标准、内置资产或第三方作品；对应文件中的单独声明优先。
