# model-identity

- Requirement: [REQ.authoritative-model-lifecycle](../requirements/req-authoritative-model-lifecycle.md#reqauthoritative-model-lifecycle)
- Select: 来源与业务身份、同源复用与精确内容
- Needs:
  - 处理模型制品: [DD.single-file-model-container](container-definition.md#ddsingle-file-model-container)
  - 按需验证表示: [DD.catalog-before-model-body](model-distribution.md#ddcatalog-before-model-body)
- Landing:
  - [standard](../../standards/model-schema/manifest-and-identity.md#exact-tuple-与-representation-激活)
  - [implementation](../../architecture/model-management/overview.md#实现与验证入口)
  - [design](../../architecture/model-management/overview.md#身份与来源)

## DD.model-and-representation-identities

- Claim: ModelId 同时表示来源身份与业务身份；来源相同与某份制品字节相同是不同的等价关系。
- Rationale: 玩家选择、授权和跨领域引用需要稳定识别同一模型来源，远端内容取得则必须证明是要求的精确内容。允许已验证的本地同源内容替代重新封装后的远端表示可以节省带宽；不能因此混用不同表示资产或把视觉相同的不同来源当作同一模型。

### BC.model-id-routes-domain-identity

- Claim: ModelId 标识模型来源，也是选择、授权和跨领域引用的业务身份；视觉相同不使两个来源自动成为同一模型。

### BC.remote-content-requires-exact-representation

- Claim: 远端取得或缓存复用的内容必须属于所需模型，并精确匹配所需容器表示；不能拼合不同容器的资产。

### BC.verified-local-representation-may-substitute

- Claim: 完整验证且 ModelId 相同的本地模型可以满足同源需求，无需与远端容器字节相同；本地内容与远端要求的真实身份不能互相冒充。本地替代保留实际来源及派生产物归属；服务端发布的位置、身份与权限保持独立。
