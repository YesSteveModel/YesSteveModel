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
  - [design](../../architecture/model-management/catalog/overview.md#逐项发布与替换)
  - [design](../../architecture/model-management/catalog/overview.md#扫描进度投影)
  - [design](../../architecture/client-presentation/overview.md#本地目录进度)
  - [implementation](../../architecture/client-presentation/implementation/page-runtime.md#本地目录进度)
  - [design](../../architecture/model-management/client-resources/overview.md#引用撤销与物理关闭)
  - [design](../../architecture/model-management/storage/overview.md#精确身份与原子提交)
  - [design](../../architecture/model-management/storage/overview.md#共享存储与清理)

## DD.single-current-catalog

- Claim: 当前目录只接纳完整验证的模型条目，浏览、选择和使用得到一致内容；热加载保持已有使用有效。
- Rationale: 浏览、选择和实际使用必须对每个当前条目得到一致答案。无需让一个坏模型或长扫描阻挡其他已验证内容可用；但半份条目、混合表示和失败写入仍不能成为有效版本。扫描只有在完整观察根目录后才可确认删除，根目录访问失败不能被解释为模型全部消失。热加载是日常操作，目录变化也不应撤销已经取得并验证的资源。

### BC.catalog-defines-current-content

- Claim: 热加载与来源切换只发布完成验证的条目；有效项可以在同次扫描结束前逐批可用。坏新项不替换当前有效项；扫描无法完整观察某个根时，不据此删除未确认缺失的旧项。当前目录对同一模型给出一致内容，省略或替换不撤销已有合法使用，也不授予处置原始输入的权利。普通来源按本轮首个完整验证并被目录接纳的 `ModelId` 占位；AUTH、全局根、实例根和路径排序均不授予优先级。旧 current 在本轮首次成功接纳前存续，本轮成功项可替换旧同身份项，晚到重复项报告冲突。Builtin 仅保护保留身份，不保护相对名字。

HUD 与模型页面右下角只显示 Local Catalog 的活动扫描进度，单人和多人一致。发现时显示阶段文字；输入固定后显示 `n/N`；最终验证、索引提交和等待 owner 接纳终态时显示最终阶段文字。成功、失败或取消经 owner 接纳后隐藏；关闭按原协议终结工作后隐藏。Runtime FULL 准备、传输和 session 激活不参与这两处进度。页面右下角文字右对齐并使用深灰色。

`N` 按本轮模型输入和 pack 输入各计一项，重复身份也是独立输入；pack 内的模型仍按各自输入计数，不按输出展开重复计数。`n` 按协调者消费的最终 outcome 计数，成功、坏项和重复冲突各推进一次。旧 current 和提前发布不改变分母。空输入不显示 `0/0`；未完成取消项不补计。BUSY reload 不重置本轮，也不排队补扫；旧轮迟到结果不能覆盖新轮。

### BC.retired-content-remains-valid

- Claim: 模型退出当前目录或其只读来源随后被替换、删除、损坏或暂时不可访问后，已经取得并验证的资源仍可安全完成现有使用；未来需要重新读取该来源的局部操作可以失败。一个使用者结束使用不能使其他使用者的模型资源失效。

已验证资源的保留与读取句柄的寿命独立。后续读取不能继承旧路径成功或目录观察的资格；关闭读取阶段也不能撤销其他使用者的已验证结果。

### BC.cache-publishes-only-verified-exact-content

- Claim: 存储只发布验证通过且属于所需表示的完整结果；中断或失败不得把半写入内容伪装成有效模型，也不得破坏已有有效版本。Converted 共享缓存的索引、提交与清理应限于本次 DATA／转换版本 scope；其他版本、来源 scope 或仍有消费者的对象不能由本次扫描回收。

同阶段复用句柄仍应逐次验证接纳长度、descriptor 和内容 hash。相同 channel 不证明 bytes 不可变，也不授予 artifact/header 自证 expected 的资格。
