# 目录传递

本页落实[事实提交与通知](../overview.md#事实提交与通知)。目录 authority、资格和 activation 的设计归[模型会话](../../model-management/sessions/overview.md)。

`ServerModelSession` 先提交 catalog、grants、内容策略与 selection facts，再从 committed snapshot 构造 `CatalogPublication`。Typed full/delta packet 只交给一个 `ResourceDispatchWorker`；编码或 enqueue 失败不回写领域 snapshot。后续 delta 仍基于 server current snapshot，不为漏发创建 history。

`SessionCollectionPublication` 将完整 operation 验证并应用到不可见 candidate，然后一次替换 collection。Wire/domain 无效和 retained baseline 不兼容交由模型会话分类，不能以部分 operation 发布结果。目录只携带当前身份、位置、access 和内容使用资格；metadata、Manifest 与展示内容另行按需取得。

Collection commit 后交给独立 activation owner；前缀 miss 通过[typed action](asset-transfer.md#typed-request-与-action-owner)取得，经[分层验证](../../asset-pipeline/implementation/container-and-validation.md)和[存储提交](../../model-management/storage/overview.md)才可形成 representation。目录发布不等待或协调独立的 ID 17 selection sender。

## Selection 与 request admission

`ClientCatalogSnapshot` 提供当前新使用资格；资源 manager 在新 acquire、Ready/cache 查询与新声音 access 前消费该资格。已接纳 attempt/lease 的保留与拒绝后的恢复由[模型会话](../../model-management/sessions/overview.md)定义。

当前 server-private forced-selection 只为绑定 `ModelId` 放行普通 chunk，不写 grants、不授权其他模型；现有普通内容资格纳入接收方投影。清除 mode 只阻止新使用。该模式相对 `RESTRICTED_AUTH` 的产品例外仍待[Q-09](../../../complexity-map/java-models-and-network/open-questions.md#q-09)裁决。
