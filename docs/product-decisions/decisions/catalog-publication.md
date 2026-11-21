# catalog-publication

- Requirement: [REQ.authoritative-model-lifecycle](../requirements/req-authoritative-model-lifecycle.md#reqauthoritative-model-lifecycle)
- Select: 目录与内容完整发布、热加载与已有使用
- Needs:
  - 判定精确内容与本地替代: [DD.model-and-representation-identities](model-identity.md#ddmodel-and-representation-identities)
  - 扫描或隔离本地来源: [DD.user-model-sources-are-readonly](artifact-storage.md#dduser-model-sources-are-readonly)
  - 候选构造失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [implementation](../../architecture/model-management/catalog/overview.md#实现与验证入口)
  - [implementation](../../architecture/model-management/client-resources/implementation/resource-ownership.md)
  - [implementation](../../architecture/model-management/storage/implementation/persistence.md)
  - [design](../../architecture/model-management/catalog/overview.md#seam-contract)
  - [design](../../architecture/model-management/catalog/overview.md#扫描进度投影)
  - [design](../../architecture/client-presentation/overview.md#本地目录进度)
  - [implementation](../../architecture/client-presentation/implementation/page-runtime.md#本地目录进度)
  - [design](../../architecture/model-management/client-resources/overview.md#引用撤销与物理关闭)
  - [design](../../architecture/model-management/storage/overview.md#精确缓存与持久工作)
  - [design](../../architecture/model-management/storage/overview.md#共享存储与清理)

## DD.single-current-catalog

- Claim: 当前目录只接纳通过来源验证的完整模型条目，确保浏览、选择和实际使用看到一致的内容；热加载期间，已有使用继续保持有效。
- Rationale: 浏览、选择和实际使用必须对每个当前条目看到一致的结果。个别坏模型或耗时的扫描不必阻挡其他已验证内容投入使用，但残缺条目、混合表示和失败写入绝不能成为有效版本。扫描只有在完整观察根目录后才能确认旧项已删除，不能把根目录访问失败当成模型全部消失。热加载是日常操作，目录变化也不应撤销已经取得并验证的资源。

### BC.catalog-defines-current-content

- Claim: 热加载与来源切换只发布通过验证的条目，有效项可以在本次扫描结束前分批可用。损坏的新项不会覆盖当前有效项；如果扫描无法完整观察某个根目录，就不能据此删除未确认缺失的旧项。当前目录对同一模型给出一致内容；条目被省略或替换时，不会撤销已有的合法使用，也不授予处置原始输入的权利。普通来源按本轮最先通过来源验证并被目录接纳的 `ModelId` 占位，AUTH、全局根、实例根和路径排序均不授予优先级。本轮首次成功接纳之前，旧的 current 条目继续有效；本轮成功项可以替换旧的同身份条目，之后到达的重复项则报告冲突。Builtin 只保护保留身份，不保护相对名称。

HUD 与模型页面右下角只显示 Local Catalog 的活跃扫描进度，单人和多人环境保持一致：发现阶段显示阶段文字，输入集合固定后显示 `n/N`，进入最终验证、索引提交以及等待 owner 确认最终结果时显示最终阶段文字。无论扫描成功、失败还是被取消，待 owner 确认结果后即隐藏进度；关闭时按原协议结束工作后再隐藏。Runtime FULL 准备、网络传输和 session 激活不计入这两处进度。模型页面右下角的进度文字右对齐，使用深灰色。

分母 `N` 按本轮发现的模型输入和 pack 输入各计一项，重复身份也作为独立输入计入；pack 内的模型按各自输入计数，不按输出展开重复计数。分子 `n` 按协调者收到的最终结果（outcome）计数，成功、坏项和重复冲突各自推进一次。旧的 current 条目和提前发布的条目不改变分母。空输入时不显示 `0/0`，因取消而未完成的项不补计。处于 BUSY 状态的重复 reload 既不重置本轮进度，也不排队补扫；旧轮次的迟到结果不得覆盖新轮次。

### BC.retired-content-remains-valid

- Claim: 模型退出当前目录或其只读来源随后被替换、删除、损坏或暂时不可访问后，已经取得并验证的资源仍可安全完成现有使用；未来需要重新读取该来源的局部操作可以失败。一个使用者结束使用不能使其他使用者的模型资源失效。

已验证资源的保留周期与文件读取句柄的生命周期相互独立。后续读取不能沿用旧路径曾经成功或目录曾经可见的资格，关闭读取阶段也不能撤销其他使用者手头已验证的结果。

### BC.cache-publishes-only-verified-exact-content

- Claim: 存储只发布验证通过且属于所需表示的完整结果；中断或失败不得把半写入内容伪装成有效模型，也不得破坏已有有效版本。Converted 索引与提交会固定本次来源和转换 scope；按来源做过期清理时，扫描不能替其他 scope 证明条目缺失。独立的 schema 淘汰及消费者保护遵守[缓存维护政策](artifact-storage.md#bccache-maintenance-does-not-own-sources)，不受本次扫描版本限制。

同一读取阶段内复用句柄时，每次读取仍须校验接纳长度、descriptor 和内容 hash。复用同一个文件通道（channel）不证明底层字节未被修改，也不允许用制品自身或文件头来充当预期值的依据。

容器校验按实际读取对象划分，direct、converted 和 remote 遵守同一规则。读取 preamble 时验证容器结构与 ContainerId；读取 chunk 时验证该 chunk 的边界、长度、编码和 hash，Manifest 也遵守此规则。仅查索引时不打开容器；读取 metadata 时不遍历未消费的 chunk，也不提前解码 preview 或检查整文件完整性。Direct 成品的内嵌 preview 声明在读取 Manifest 时检查，图片完整性则在读取图片时验证。同表示、同验证 profile 下的不可变 metadata 证明可以复用；新打开的可变文件仍须验证当次读取的 preamble。完整离线验证与新产物完整发布各自保留原有的验证责任。

Converted 实例及其来源关系按 DATA 目录、转换配置和验证 profile 分别隔离。只有新生产流程可以建立实例，扫描完成时只删除过期关系。收到确定的 CONTENT 错误反馈时，只撤销原实例的复用资格，不立即删除物理文件，也不广播撤销已有引用；ACCESS 错误、取消与 OOM 均不具备撤销复用资格的权限。消费者保护贯穿扫描、生产以及全部后续磁盘使用，直到最后一个使用者结束后才释放。
