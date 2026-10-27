# 持久提交的落实

本页落实[存储区域与所有权](../overview.md#运行作用域与所有权)、[精确提交](../overview.md#精确身份与原子提交)和[共享清理](../overview.md#共享存储与清理)，定位能力、锁及 owner gate 的关系。

## 固定区域与文件边界

`ProcessAssetPaths` 冻结一次探测的 `AssetPaths`；`ModelStorageInfra` 拥有区域与 converted 消费者登记。Getter 不重探测，reload、session 切换或局部 I/O 失败不重选区域。

`CacheDirectoryGuard.Observation` 保存目录变化观察；`DirectoryAccess` 复核后提供受检路径视图。创建、打开、原子替换与删除统一使用 `Path`、`Files` 和 `FileChannel`。操作拒绝已观察到的越界、内部链接和目录变化，适用[目录稳定前提](../../../../product-decisions/decisions/artifact-storage.md#bccache-maintenance-does-not-own-sources)。Writer、validator 借用同一 staging channel。

`FileIdentity` 仅比较可选 `fileKey` 与类型；`DirectoryAccess` 另核验真实路径。创建时间可能退化为修改时间，不能用于该比较。Provider 不提供 `fileKey` 时不因此停用存储，但同路径同类型替换可能不可见。观察不保证发现每次目录变化，不替代内容校验，也不证明不可伪造的物理身份。

`ChannelReadPhase` 只拥有一次已接纳 channel。Callback 借用只读游标，完整 I/O 串行；封口不等待活动 I/O。最后借用归还时关闭，无活动借用则当场关闭。打开后未能构造阶段也关闭 channel。写入、staging 验证、索引锁和消费者登记锁不进入读取阶段；同路径 atomic commit 前先结束本操作的读阶段。

目录观察保存完整祖先链。重新取得路径视图时，门面逐项复核类型和可选身份，并解析末端真实路径以检查整条路径。快照只复用观察记录，不缓存复核成功；后续文件操作仍会复核。多层目录创建在写入缺失尾部前先检查既有前缀，避免进入链接目标创建目录。

锁顺序为 JVM key lock、JDK 文件锁、owner effect gate。全部参与者通过 `FileChannel` / `FileLock` 协调，不再使用 Unix `flock`。等待文件锁时不持 catalog/session monitor；锁及所属 channel 保留到对应操作或消费者登记结束。`SharedCacheLocks` 统一 JVM 内路径锁与文件锁。活跃消费者路径在 JVM 内集中登记；peer 探测在打开文件前跳过本 JVM 的登记，避免 POSIX 锁因另一个 channel 关闭而失效。

## 派生持久工作

`DerivedStorageWork.submit` 原子完成工作数接纳和输入接管，返回 Job 或拒绝。生产进程共用一个后台 worker；队列与运行项合计上限为 16 项。拒绝不转移输入责任。队列空时 worker 退出，下次接纳可启动同一域的 worker，不保留空闲线程。

Worker 复用 `AtomicSharedCache.replaceValidated` 的同 key JVM/OS 锁、同 channel staging、完整验证、force 与 atomic move。旧对象在替换成功前保持原位。Expression staging 验证覆盖 artifact 和每项动画；geometry staging 校验继续验证完整 payload。事务不隔离旧有效文件，也不增加重试日志。

Commit、取消和 shutdown 共用 effect gate。替换成功即固定成功终态，后续清理失败不能重标。取消排队项可立即归还责任；运行项只有结束借用后才清除工作输入。过程诊断只保留终态计数，不保留已终结输入。

## 来源与 converted

`ModelSourceResolver` 接纳 direct 内容后，由 runtime 建立精确读取实例。Worker 向 owner 投递原实例 provenance；owner tick 提交单向失效，尚未开始的读取不能借旧 admission 越过标记。完成资源不依赖该实例继续有效。

`ChunkDataSource.openReadPhase` 为实际准备提供局部视图。File source 用同一 channel 取得 definition、common strings 和声音首块；读取结束后关闭，再编译表达式。Runtime 的精确来源 wrapper 转发该阶段与原 batch，不把批次退化为逐 chunk 打开；读取失败仍回报原精确实例。

`readPayloads` 去重相同 descriptor，返回完整集合或释放未发布引用。File source 在读取前后核对接纳长度，各 payload 仍验证内容。Remote preparation 固定 received 输入引用，先结束 transfer，再消费本地 bytes；独立 chunk 文件各自读取并关闭。媒体前缀资格归[声音准备](../../audio/overview.md#运行作用域与所有权)，不由源 chunk 完整性替代。

`ConvertedSourceIndexStore.replaceScopes` 在 scope 事务锁内重读并合并类别。`ConvertedCacheCoordinator` 在 gate 内登记实际 consumer 锁，再由 Catalog 复核 active/closed；扫描准入不能先于登记。`ModelStorageInfra.prune` 只把本次保留对象分派到各 scope，路径分派不授予删除许可。进程 owner 在 Catalog 与 runtime 停止后关闭登记。

## Remote 与图片

`RemoteModelStore` 只保存已验证 bytes，不保存 fetcher、grants 或 query table。坏读返回 miss/分类失败；当前 exact session 有资格时至多补取一次，验证后原子替换。写失败不撤回有效内存结果。

每个区域的 `PreviewStore` 被请求接纳时固定。普通 `readEncoded` 返回有界 bytes，不声称媒体已验证；`accept` 可返回内存图，`commit` 要求持久成功。Commit 在 cache key 锁下准备 staging，借用 `ServerModelSession.Effect` gate 复核原 member 并 atomic move。

持久成功退出 effect gate 后、commit 返回前，通知对应 `ServerResourceRuntime` 撤字节保留。锁顺序保持 cache key → authority gate；退出 effect 后才取 runtime 锁。Runtime 不持锁回调 storage/session。Move 失败、资格拒绝或仅内存交付均不发持久失效。
