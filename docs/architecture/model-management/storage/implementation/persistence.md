# 持久提交的落实

本页落实[固定区域](../overview.md#固定区域与目录观察)、[精确缓存与持久工作](../overview.md#精确缓存与持久工作)和[共享清理](../overview.md#共享存储与清理)，说明存储层中各类能力、锁边界与 owner 门禁的具体实现。

## 业务库身份与打开

Native 存储模块集中定义了各业务库的文件名、`epoch` 与当前 `revision`，以此落实[数据库身份与演进](../overview.md#数据库身份与演进)。每个数据库用 `PRAGMA application_id` 标识业务角色，用单行 `storage_meta.compatibility_epoch` 核对文件名预期的 `epoch`，用 `PRAGMA user_version` 记录该 `epoch` 下的 `schema revision`。

| 业务角色 | 当前文件 | Epoch | 当前 revision |
|---|---|---|---|
| PlayerData | `preferences-c2.sqlite` | 2 | 1 |
| Catalog | `f/catalog.sqlite` | 1 | 1 |
| Remote | `f/remote.sqlite` | 1 | 1 |
| Bake | `f/bake.sqlite` | 1 | 1 |
| FileIdAllocator | `f/ids.sqlite` | 1 | 1 |

当前布局从 revision 1 起计。获得 SQLite 写事务后，打开流程会再次检查目标文件，只有本次新创建的空文件允许初始化：在事务内一次性写入空表结构、角色标识和两个版本号，提交前绝不把连接暴露给上层业务 owner。打开已有库时，必须依次通过角色、epoch、revision 和已知表结构校验；低于当前 revision 的库直接拒绝，同 epoch 下较新的 revision 只开放已知能力并原样保留未知扩展字段。打开失败时保留原文件不动，内部诊断记录业务角色、预期与实际 epoch、revision 以及失败阶段，对外结果只返回稳定的失败类别。

历史布局由独立 epoch 隔离。`PlayerData` 的模型版本 donor 首次继承由 Java 在业务读取时执行，并校验 payload 和目标布局。

新缓存命名空间位于 `CACHE/f`：`converted`、`bake`、`remote`、`preview` 分别对应 `c`、`b`、`r`、`p` 四个扁平文件池，每个池下的 `s` 子目录存放 staging 临时文件。索引表中的位置列存为 SQLite `INTEGER`，Java `long` 与 JNI `long` 按无符号 `uint64` 位模式传递，物理文件名为左侧补零的 16 位小写十六进制字符串。`f/admission.sqlite` 与 `f/consumers.sqlite` 统一保护新文件池；旧业务库、旧文件池和旧 scope 协调库一律不读取、不迁移、不自动删除。`PlayerData` 保持在原目录位置。

## 固定区域与文件边界

`ProcessAssetPaths` 在启动期冻结一次性探测得到的 `AssetPaths`，`ModelStorageInfra` 持有各存储区域与实际 CACHE 根的消费者登记。后续调用 getter 不会重新探测，reload、会话切换或局部 I/O 失败也不会切换存储区域。

`CacheDirectoryGuard.Observation` 保存完整祖先链的目录变化观察记录，`DirectoryAccess` 复核后返回受检路径视图。所有文件创建、打开、原子替换与删除统一通过 Java 标准库的 `Path`、`Files` 和 `FileChannel` 完成，操作前拒绝已观察到的越界、内部符号链接和目录替换（适用[目录稳定前提](../../../../product-decisions/decisions/artifact-storage.md#bccache-maintenance-does-not-own-sources)）。Writer 与 validator 共用同一个 staging channel。

目录身份复核的边界如下：
- `FileIdentity` 只比较可选的 `fileKey` 与文件类型，`DirectoryAccess` 再额外核验真实路径；文件创建时间在某些平台上可能退化为修改时间，不能用于身份比较。文件系统不提供 `fileKey` 时不会因此停用存储，但同路径同类型的替换可能无法被察觉。目录观察不保证捕获每一次并发修改，不能替代内容校验，也不构成不可伪造的物理身份证明。
- 重新取得路径视图时，门面逐级复核祖先链上的类型与可选身份，并解析末端真实路径以检查整条路径。快照只复用观察记录，不缓存“复核成功”结论，后续文件操作仍会重新复核。创建多层目录时先检查已存在的前缀，防止顺着符号链接在外部目录建子目录。

`ChannelReadPhase` 封装一次已接纳的只读 channel：回调只借用只读游标，同一 channel 的 `seek -> read -> validate` 受 I/O 锁保护完整串行执行，生命周期锁只保护接纳与借用计数。封口操作不等待活动 I/O，等最后一个借用归还时关闭 channel（无活动借用则当场关闭）；打开 channel 后若未能构造读取阶段也会立即关闭 channel。文件写入、staging 校验、索引锁和消费者登记锁都不会进入读取阶段；对同一路径执行 atomic commit 之前，必须先结束本操作的读取阶段。

`StorageIndex<R>` 绑定固定区域以及 `Catalog` 或 `Remote` 能力，将文件与 Catalog worker 的有界数据库操作派发给独立的 `DatabaseWork`。`PlayerPreferences` 复用同一 `DatabaseWork` 持有 `DATA` 区的 `PlayerData` 能力；创建和操作前由 Java 复核根目录，native 复核私有目标文件。`ConvertedSourceIndexStore` 和派生物 store 只借用外部显式传入的缓存对象，独立调用方须自行持有并关闭缓存或存储区域，不能把队列为空当成资源已关闭。业务层不直接持有 SQL，文件生成也不占用数据库事务。针对同一键允许并发重复生产，由 SQLite 短事务选出最后发布的完整文件，不引入 JVM 级 key 锁、显式锁文件或 JDK `FileLock`。根级长期消费者保护由 SQLite 协调库提供，同根路径别名与同 JVM 内多服务由 `CacheRootCoordinator` 合并。

## 偏好逻辑顺序与执行

`PlayerPreferences` 持有唯一的 `StorageWriteBuffer`，所有偏好读写和 remote 使用事件都进入同一条 FIFO 队列。主线程独占逻辑接纳顺序、内存覆盖层与观察切片，`DatabaseWork` 只负责后台物理执行与 native 资源管理。Java 侧逐条串行调用既有业务 JNI 执行批内保留项，每一项仍保持独立事务。`SUPERSEDED` 只出现在 Java 归并结果中，native 返回载体维持原有的六种状态。

纯查询排队项只弱持有 `StorageQuery`：worker 执行前先提升为强引用再直接发布独立结果，存储结果队列不反向强持有句柄；副作用操作则保留强结果 owner。受控域显式关闭时先封口入口并 drain 已接纳工作，再关闭 `DatabaseWork`，不依赖客户端采用回调；JVM 退出不触发该流程（详见[玩家偏好](../preferences.md#周期提交与关闭)）。

## 派生持久工作

`DerivedStorageWork.submit` 原子完成任务数配额检查与输入接管，返回 `Job` 或直接拒绝。整个生产进程共用一个后台 worker，队列与运行中任务合计上限为 16 项；拒收时不转移输入所有权。队列清空后 worker 线程自动退出，下次接纳新任务时按需重启，不保留空闲线程。

首次接纳任务时包含后台线程的构造与启动，在线程启动成功前不对外承担工作；如果构造、配置或启动抛错，会回滚本次入队、计数和输入引用并向上传播原异常，后续独立提交仍可重新尝试启动。未被接纳的任务不计入工作结束统计。

后台 worker 执行持久化的固定流程是：先向区域分配器申请整数 ID，经 `AtomicSharedCache.reserve` 独占预留 staging 文件，再在同一个 channel 上完成序列化、完整校验、`force` 刷盘和 atomic move。若发现正式文件或 staging 已被占用则继续分配新 ID；`converted` 与 `bake` 还会额外避让尚未清理的对象归属记录，防止分配器重建后重用旧 token 地址。文件发布后再由短事务提交精确索引，旧索引指向的磁盘文件保持原位不动。动画输入与 AOT 分别作为独立任务提交：动画输入的 staging 校验覆盖全部动画与未绑定程序块，AOT 校验完整制品及独立 expected，几何 staging 继续校验完整 payload。事务不隔离旧有效文件，也不记录重试日志。

提交、取消与关闭共用同一 effect gate：一旦 atomic move 替换成功即锁定为成功结果，后续清理步骤失败不能改标为失败。取消尚在排队的任务可立即归还输入责任；取消正在运行的任务则必须等同步借用结束后才清理工作输入。过程诊断只记录统计计数，不留存已经结束的输入对象。

## 来源与 converted

每次生产生成的 `file ID` 与独立实例 `UUID` 分别代表物理位置和原生产实例。ID 分配器在短事务内单调递增 `uint64`，最大值只分配一次，耗尽后直接拒绝；预留 ID 不代表具备缓存复用资格。SQLite 索引完整保存 `ModelId`、`ContainerId` 以及用途、ABI 和 profile，物理文件名本身不携带身份或校验资格。来源事实与对象归属分开记录：撤销复用资格后，归属记录一直保留到受保护的物理删除结束。

`ModelSourceResolver` 接纳 `direct` 内容后，运行时会为其建立精确读取实例。后台 worker 遇到读取失败时向 owner 投递原实例 provenance，由 owner tick 提交单向失效，此后尚未开始的读取不能借旧资格越过失效标记；已经构建完成的资源则不依赖该读取实例继续存活。

`ChunkDataSource.openReadPhase` 为实际准备提供局部读取视图：文件来源用同一个 channel 依次读取定义、common strings 和声音首块，读取结束后立即关闭 channel，随后再编译表达式。运行时的精确来源包装器透传该读取阶段与原批次，不会把批次退化为逐 chunk 开关文件，读取失败仍准确归属到原精确实例。

`readPayloads` 会对相同的 descriptor 去重，返回完整的 `SharedBuffer` 集合，失败时不交付半成品；读取阶段结束只关闭 channel，不影响已交付的字节。文件来源在读取前后核对接纳时的文件长度，每个 payload 仍单独校验内容。远端准备先固定已接收的输入引用、结束网络传输，再消费本地字节；独立的 chunk 文件各自读取并关闭。媒体前缀资格归[声音准备](../../audio/overview.md#播放接纳与顺序读取)，不由源 chunk 校验替代。

索引发布与启动清理的具体分工如下：

- **`ConvertedSourceIndexStore` 与 `DirectContainerIndex`**：`ConvertedSourceIndexStore.publish` 只发布新生产实例，调用方消费实际提交的 UUID；可用文件若未成功写入索引，仍受消费者保护，但不具备索引损坏反馈资格。`replaceScopes` 在一个 scope 内分批提交无转换关联的来源，每批最多 64 条。批次失败保留此前已提交的数据，仅在所有来源批次成功后才按最新索引分批删除过期键。保留的转换实例继续沿用现有记录。`CONTENT` 损坏反馈按原位置和 token 撤销 `converted` 复用资格，同时保留来源事实、对象归属与物理文件。`DirectContainerIndex` 按当前文件的 CID 和 profile 保存已验证的目录证明集合，不把来源路径当作预期身份。
- **`CacheRootCoordinator`**：在业务缓存消费前取得根消费登记。普通读取、cache-only、延迟 opener 和已接纳生产在对外交付能力前，都会登记保留 `(pool, fileId, token)` 直到 JVM 结束；最后一个服务退出时关闭 native 登记，但不清空该本地保留集合。同根门禁串行化文件取得、来源更新、发布与删除，数据库 worker 不反向获取该门禁。
- **`CachePruner`**：借用连续的独占清理窗口，先读取完整库存、所有持久来源，再将去重后的 CID 分批交给 `Catalog` 和 `Remote`。每库在同一个读快照内返回该批的覆盖信息和引用状态。所有必要数据库都以仅打开已存在库（existing-only）的方式打开，只要有库缺失或任一批读取失败就整轮放弃删除。调用方只标记本轮完整扫描的 scope，其他 scope 保持原样，单 scope 扫描不能提升整个根的覆盖状态。仅当 `Bake` 库尚不存在且尚未交付消费者时，启动 owner 可在排他窗口内初始化根的来源覆盖。回收每个对象时严格按“条件撤销原 token -> 受检删除文件 -> 清除归属记录”推进；中途部分失败不回滚已删文件，也不返回整轮成功，归属清除失败仍可追踪并阻止重建后的分配器重用该 ID（清理策略见[共享清理](../overview.md#共享存储与清理)）。
- **`CacheMaintenance` 与关闭链**：`CacheMaintenance` 在根交付前锁定 `Catalog` 的启动 revision。首次合格扫描按根尝试清理，在窗口内重读当前日期与请求；两池全部成功后，`Catalog` 以条件事务写入完成 UTC 并消费本轮全局 `all` 标记。手动预约命令会推进 revision，防止旧清理轮覆盖新命令。日期提交或失败处理以及事务真正退出后，才恢复 `SHARED` 锁并释放 admission 锁。遇到同步中断时，先取消并等待有界数据库工作真正退出，再恢复线程中断标记。`ModelStorageInfra.pruneNext` 借用根登记并在既有 `DatabaseWork` 上逐根提交，不获取文件变更门禁。显式关闭时先封口新工作，依次结束 Catalog、运行时、派生工作、写缓冲、数据库以及同步借用，最后退出根登记；进程直接退出时由操作系统回收连接和锁占用，尚未落盘的状态允许丢失，磁盘上已完成的事务与原子文件发布仍保证下次启动看到合法状态。

## Remote 与图片

`RemoteModelStore` 只保存已经校验通过的字节数据，不持有 fetcher、授权表或查询表。读取缓存出错统一按未命中（miss）返回，取消、关闭和 OOM 继续向外传播；如果当前会话仍有权限，则至多补取一次，校验通过后原子替换。磁盘写入失败不撤回已经有效的内存结果。

`ContentStorageBinding` 根据接纳时的实际来源选择 `Catalog` 或 `Remote` 及其对应的 `PreviewStore`；几何、`AnimationInputStore` 与 `ExpressionArtifactStore` 共用实际存储区域的 `BakeIndexStore`。动画输入与 AOT 的精确键、磁盘文件和内存引用分别归属各自独立的 store，AOT producer 不持有动画 producer。本地来源替代远端请求后，不会按请求类型改写其实际存储归属。同一个根下的本地与远端 store 分别缓存内存资格并共享该区域的整数文件池；索引能力采用惰性开库，启动维护快照与来源覆盖初始化各有独立的开库责任。

独立预览图的读写与提交流程如下：
- 普通的 `readEncoded` 只返回有界编码字节，不声称已通过媒体解码校验；`accept` 允许交付有效内存图，而管理端 `commit` 要求持久化替换成功。
- `commit` 在事务外准备 staging 文件，进入 `ServerModelSession.Effect` 门禁复核原成员资格、执行 atomic move 并在同一窗口提交 SQLite 图片索引。
- 持久化成功退出 effect gate 后、在 `commit` 返回调用方之前，通知对应的 `ServerResourceRuntime` 撤销服务端字节保留。文件准备阶段不持有 authority 门禁，退出 effect gate 后才获取运行时锁，运行时绝不持锁回调 storage 或 session。如果 atomic move 失败、资格被拒或只是内存交付，都不会发出持久化失效通知。
