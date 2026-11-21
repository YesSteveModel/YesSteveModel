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

- Claim: 无论处于正常加载、转换、热加载，还是遇到内容损坏或访问失败，YSM 都不得修改、覆盖或删除 local catalog 的用户来源目录与原始文件。指向用户来源的符号链接入口及其目标受同等保护；允许创建缺失根目录的政策，不授予修复断链或创建链接目标的权利。更新内存中的模型目录，或通过转换生成独立缓存产物，都不会改变这一输入保护边界。

新增的全局目录允许进行有限的启动探测：客户端和服务端均可创建正常缺失的 DATA、models 和 CACHE 目录，并通过独占方式创建、写入、读回再删除 YSM 自有的随机探针文件。在启用全局目录前应完成探针清理；只要读回、删除、原子替换或 SQLite 数据库与协调能力检查中任意一项失败，就停用整组全局目录。探测过程不得覆盖既有文件，也不修复断链、不替换链接、不修改权限；探测失败后，只保留正常创建的目录以及已记录明确诊断的自有清理失败对象。此外，客户端和服务端均可创建正常缺失的实例 models 目录。

### BC.cache-maintenance-does-not-own-sources

- Claim: 模型管理的写入、替换、清理和淘汰仅限于自身管理的缓存，不得延伸到用户来源。访问失败不能证明内容损坏；替换有效缓存失败时，必须保留原有的有效目标。每次持久化工作在接纳时即固定实际的内容来源和缓存区域，后续来源切换不会重定向已接纳的工作。全局目录已启用时，全局来源和纯远端产物写入全局 CACHE，实例来源和 builtin 产物写入实例 CACHE；全局目录停用时，全部产物均写入实例 CACHE。各类缓存的坏读处置和清理权限由[存储设计](../../architecture/model-management/storage/overview.md)分别定义。

在单次存储操作期间，调用环境需保证缓存目录结构稳定。YSM 必须拒绝已经观察到的越界路径、缓存内部链接和目录变化，但不承诺防御检查与实际路径操作之间由外部替换目录引发的竞态。这一前提不会放宽来源只读、实际 payload 消费验证、失败保旧或活跃消费者保护。

只读文件句柄仅在单次实际读取阶段内复用。阶段一旦结束、失败或被取消，就不再接纳新的读取，待最后一个活跃借用归还后立即关闭句柄；长期保留已验证内容不会延长空闲句柄的生命周期，后续新的读取必须重新取得来源资格。发生普通动画失败时，批次 owner 可以结束当前失败阶段，并为已有的 fallback 或其他健康动画开启新的读取阶段，而成功阶段依然保持同源只打开一次。关闭失败、取消和 OOM 不走这条恢复分支。在替换同一路径的目标文件之前，当前操作必须先结束自身的读取阶段；写入、staging 验证和文件锁则继续由各自的 owner 负责关闭。

缓存索引存放在固定区域的私有 SQLite 数据库中，缓存文件必须先完整生成再发布索引；数据库发生故障时，不得借此删除用户来源，也不得在运行中途切换缓存根目录。发现 converted 产物损坏时，只撤销原生产实例的复用资格，物理文件等待消费者保护允许后再清理；同一 CID 的新生产实例不继承旧的损坏反馈。

缓存的正式产物与暂存文件均使用 uint64 短文件 ID，converted 和 bake 目录保持固定层级深度，完整身份信息保留在索引中。新布局不读取、不迁移、也不自动删除旧布局文件，玩家主数据仍保持原有边界。

同一实际 CACHE 根目录下的 converted 与 bake 缓存，只能在当前唯一使用进程的连续排他窗口内执行清理，期间其他进程需等待缓存接纳。本进程的活跃使用、延迟读取以及已接纳的工作始终受保护。Bake 的来源保留范围涵盖 Catalog、direct、builtin 与 Remote；无引用回收覆盖全部 ABI 与 schema 版本，不能以“当前未加载”或“缺少 converted 文件”作为无引用的依据。

每个缓存根目录在首次合格的启动扫描完成后，如果没有成功清理日期，或距离上次整轮成功已满 72 小时，就会尝试触发清理。local 与 global 独立计时，清理失败或跳过时都不推进日期。管理命令只安排在下次启动时执行，其中 global 的一次性 `all` 请求会持久化保留，直到执行成功。正常清理时，local 根会清理非当前 schema 的产物，global 根仅在接纳 `all` 请求时才增加这一版本清理；不再支持 ABI 的 bake 产物在两个根下均可回收。所有清理条件都必须服从本地消费者保护和来源只读边界，remote 与 preview 缓存不因此获得额外的删除权限。具体的锁获取、引用检查、提交及失败交接流程由[共享清理](../../architecture/model-management/storage/overview.md#共享存储与清理)定义。

### BC.generated-preview-is-an-independent-cache

- Claim: 按需生成或通过显式管理生成的 preview 保存在本次内容对应的独立缓存区域中，正常浏览和运行期间不得回写或替换来源容器。可正常解码的替换图既可用于展示，也可进入后续 export；不过，缓存中存在图片并不授予模型使用权，也不放宽对模型及声明图片的完整性验证。管理补图只有在当前权限下完成持久提交才算成功，仅内存可用不算成功；一旦持久提交完成，后续的反馈或通知失败也不会改写这一持久结果。
