# 目录传递

本页落实[事实提交与通知](../overview.md#事实提交与通知)。目录权威、资格判断和激活逻辑归[模型会话](../../model-management/sessions/overview.md)。

`ServerModelSession` 先提交目录、授权、内容策略与选择事实，再基于已提交的 snapshot 构造 `CatalogPublication`。全量或增量的类型化目录 packet 只交给唯一的 `ResourceDispatchWorker` 发送；即使编码或入队失败，也不会反过来回写领域 snapshot。后续增量仍然基于服务端当前 snapshot 生成，不会为漏发消息保留历史版本。

接收端由 `SessionCollectionPublication` 把完整操作校验并应用到一个不可见的候选集上，通过后再一次性替换当前目录集合。协议层或领域层无效，以及与已保留基线不兼容的情况，都交由模型会话统一分类处理，绝不把只应用了一半的操作发布出去。目录本身只传递当前模型身份、位置、访问权限与内容使用资格；元数据、Manifest 和展示内容另行按需拉取。

目录集合提交后交给独立的激活负责方处理。前缀缺失项通过[类型化请求](asset-transfer.md#typed-request-与-action-owner)拉取，依次经过[分层验证](../../asset-pipeline/implementation/container-and-validation.md)和[存储提交](../../model-management/storage/overview.md)后才能形成可用的模型表示。目录发布与独立的 ID 17 选择发送方互不等待、互不协调。

## Selection 与 request admission

`ClientCatalogSnapshot` 决定客户端当前的新使用资格；资源管理器在发起新的资源申请、查询 Ready 或本地 cache，以及申请新声音访问前，都会先核对这份资格。已经接纳的请求与 lease 如何保留，以及被拒绝后如何恢复，由[模型会话](../../model-management/sessions/overview.md)定义。

管理员强制选择与玩家自行选择统一走普通 grant 机制。接收方的内容使用资格只根据公开属性、grant 授权和 `RESTRICTED_AUTH` 综合决定；配置分类调整和撤权按[模型会话](../../model-management/sessions/overview.md#配置与目录发布)提交，已经接纳的在途传输保留其原有授权。
