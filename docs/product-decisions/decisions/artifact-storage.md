# artifact-storage

- Requirement: [REQ.portable-model-artifact](../requirements/req-portable-model-artifact.md#reqportable-model-artifact)
- Select: 用户来源只读、独立预览、显式补图持久提交与缓存处置边界
- Needs:
  - 判断精确身份与本地例外: [DD.model-and-representation-identities](model-identity.md#ddmodel-and-representation-identities)
  - 兼容旧输入: [DD.semantic-freeze-at-export](model-compatibility.md#ddsemantic-freeze-at-export)
  - 失败写入: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [implementation](../../architecture/model-management/storage/implementation/persistence.md)
  - [implementation](../../architecture/model-management/catalog/overview.md#实现与验证入口)
  - [design](../../architecture/model-management/storage/overview.md#用户来源与精确读取)
  - [implementation](../../architecture/asset-pipeline/implementation/conversion-and-export.md)
  - [standard](../../standards/asset-container.md#stored-representationlogical-content-与-hash)
  - [standard](../../standards/protocol-v1/README.md#显式管理补图)
  - [design](../../architecture/model-management/storage/overview.md)
  - [design](../../architecture/model-management/overview.md#身份与来源)
  - [design](../../architecture/model-management/storage/overview.md#独立图片与提交资格)
  - [design](../../architecture/asset-pipeline/overview.md)

## DD.user-model-sources-are-readonly

- Claim: Local catalog 的用户来源目录与原始文件是只读输入；除下述启动探测例外，模型管理只能修改或删除自己管理的缓存。
- Rationale: 用户来源可能是作品的唯一原件，内容错误或暂时不可访问都不赋予模组处置它的权利；缓存则是可重建的派生物。区分输入与缓存，使加载、转换和失败处理不会把局部错误升级为作品丢失；访问失败也不能证明缓存已经损坏。

### BC.local-catalog-input-is-never-mutated

- Claim: 无论正常加载、转换、热加载、内容损坏还是访问失败，YSM 都不得修改、覆盖或删除 local catalog 的用户来源目录与原始文件。来源链接入口及目标受同一保护；缺席根的创建政策不授予修复断链或创建链接目标的权利。更新内存中的模型目录或转换生成独立产物不改变这一输入保护边界。

新增全局目录允许有限的启动探测：客户端和服务端均可创建正常缺席的 DATA、custom、auth、CACHE，并独占创建、写入、读回、删除 YSM 自有随机探针。启用前应完成探针清理；读回、删除、原子替换或 OS 锁能力失败均停用整组全局目录。探测不覆盖既有文件，不修复断链、不替换链接、不改变权限；失败后只保留正常创建的目录及有明确诊断的自有清理失败对象。实例来源的既有创建政策不变。

### BC.cache-maintenance-does-not-own-sources

- Claim: 模型管理的写入、替换、清理和淘汰仅可作用于其管理的缓存，不得延伸到用户来源。访问失败不证明内容损坏；有效替换失败应保留旧有效目标。每次持久工作固定实际内容来源与缓存区域；来源切换不重定向已接纳工作。全局来源及纯远端的产物使用已启用的全局 CACHE，实例来源及 builtin 的产物使用实例 CACHE。全局停用时全部使用实例 CACHE。各类缓存的坏读处置和清理权限由[存储设计](../../architecture/model-management/storage/overview.md)分别定义。

缓存目录结构在一次存储操作期间由调用环境保持稳定。YSM 应拒绝已经观察到的越界路径、缓存内部链接及目录变化；不承诺抵抗检查与路径操作之间的外部目录替换竞态。该前提不放宽来源只读、缓存完整验证、失败保旧或活跃消费者保护。

只读文件按实际读取阶段复用。阶段结束、失败或取消后，不再接纳读取；最后活动借用归还后关闭句柄。长期内容保留不延长空闲句柄寿命。新读取应重新取得来源资格。普通动画失败后，批次 owner 可结束失败阶段，为既有 fallback 或健康动画建立新阶段；成功阶段仍按同源一次打开。关闭失败、取消与 OOM 不进入该恢复分支。本操作替换同路径前应结束自己的读取阶段。写入、staging 验证与锁沿原 owner 关闭。

### BC.generated-preview-is-an-independent-cache

- Claim: 按实际需求或显式管理生成的 preview 在本次内容的缓存区域独立保存，正常浏览和运行不得回写或替换来源容器。可解码替换图可以展示并进入后续 export；图片存在不授予模型使用权，也不放宽模型及声明图片的完整性验证。管理补图只有在当前权限下完成持久提交才成功，内存可用不代表成功，后续反馈或通知失败不改写持久结果。
