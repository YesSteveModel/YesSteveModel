# 目录传递

本页落实[事实提交与通知](../overview.md#事实提交与通知)。目录权威、资格判断和激活逻辑归[模型会话](../../model-management/sessions/overview.md)。

- **服务端发布**：`ServerModelSession` 先提交目录、授权、内容策略与选择事实，再基于已提交 snapshot 构造 `CatalogPublication`。全量与增量目录 packet 统一交唯一 `ResourceDispatchWorker` 发送；编码或入队失败不回写领域 snapshot，后续增量仍基于服务端当前 snapshot 生成。
- **客户端提交**：`SessionCollectionPublication` 将完整操作校验并应用至不可见候选集，通过后一次性替换当前目录集合；协议、领域或基线冲突由模型会话统一分类处理，不发布半应用状态。目录仅传递模型身份、位置、访问权限与内容使用资格，元数据、Manifest 与展示内容按需拉取。
- **激活解耦**：目录集合提交后交独立激活负责方处理，前缀缺失项经[类型化请求](asset-transfer.md#typed-request-与-action-owner)拉取并经[分层验证](../../asset-pipeline/implementation/container-and-validation.md)与[存储提交](../../model-management/storage/overview.md)形成可用模型表示。目录发布与 ID 17 选择发送方各自独立推进。

## Selection 与 request admission

`ClientCatalogSnapshot` 决定客户端当前的新使用资格；资源管理器在发起新申请、查询 Ready 或本地 cache 及申请新声音访问前，都会先核对这份资格。已接纳请求与 lease 的保留及拒绝恢复规则见[模型会话](../../model-management/sessions/overview.md)。

管理员强制选择与玩家自行选择统一走普通 grant 机制。接收方内容使用资格由公开属性、grant 授权与 `RESTRICTED_AUTH` 共同决定；配置分类调整与撤权按[模型会话](../../model-management/sessions/overview.md#配置与目录发布)提交，已接纳在途传输保留原授权。
