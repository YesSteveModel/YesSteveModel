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

- Claim: ModelId 是完整的规范（canonical）文本身份，形式为 `hash:` 来源摘要、`owner/name` 或 `owner/name@SemVer`。命名身份本身不证明相同内容或同一作者。业务身份与精确容器表示属于不同的等价关系。
- Rationale: 玩家选择、授权和跨领域引用需要稳定标识同一个模型来源，而远端内容获取则必须证明拿到的是所要求的精确内容。允许已验证的本地同源内容替代重新封装后的远端表示可以节省带宽，但不能因此混用不同表示的资产，或把视觉相同但来源不同的模型当作同一个。

### BC.model-id-routes-domain-identity

- Claim: ModelId 是选择、授权和跨领域引用的完整业务身份。普通默认源使用自身的 ModelId，intrinsic default 保持独立的发布角色。视觉相同不会导致身份合并。

### BC.remote-content-requires-exact-representation

- Claim: 远端取得或从缓存复用的内容必须属于所需模型，并与所需容器表示精确匹配，严禁拼接不同容器的资产。

### BC.verified-local-representation-may-substitute

- Claim: 只有通过完整验证且 `hash:` ModelId 相同的本地模型可以满足同源需求，此时无需与远端容器字节相同；命名身份则始终要求精确匹配 ContainerId。本地内容与远端要求的真实身份不能互相冒充。本地替代保留各自的来源及派生产物归属，服务端发布的位置、身份与权限也保持独立。
