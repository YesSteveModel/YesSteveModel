# 持久提交的落实

本页落实[固定区域](../overview.md#固定区域与目录观察)、[精确缓存与持久工作](../overview.md#精确缓存与持久工作)和[共享清理](../overview.md#共享存储与清理)，说明存储层中各类能力、锁边界与 owner 门禁的具体实现。

## 业务库身份与打开

Native 存储模块集中定义各业务库文件名、`epoch` 与当前 `revision`（设计见[数据库身份与演进](../overview.md#数据库身份与演进)），通过 `PRAGMA application_id` 标识角色、单行 `storage_meta.compatibility_epoch` 核对 `epoch`、`PRAGMA user_version` 记录 `schema revision`。

| 业务角色 | 当前文件 | Epoch | 当前 revision |
|---|---|---|---|
| PlayerData | `preferences-c2.sqlite` | 2 | 1 |
| Catalog | `f/catalog.sqlite` | 1 | 1 |
| Remote | `f/remote.sqlite` | 1 | 1 |
| Bake | `f/bake.sqlite` | 1 | 1 |
| FileIdAllocator | `f/ids.sqlite` | 1 | 1 |

打开与版本校验规则：

- **新库初始化**：当前布局从 revision 1 起计。进入 SQLite 写事务后复核目标文件，仅本次新建空文件允许初始化：在事务内一次性写入空表结构、角色与版本号，提交后再向业务 owner 暴露连接。
- **已有库校验**：依次校验角色、epoch、revision 与已知表结构；低于当前 revision 直接拒绝，同 epoch 更高 revision 仅开放已知能力并保留未知扩展字段。打开失败保留原文件不动，内部诊断记录角色、预期与实际版本及阶段，对外返回稳定失败类别。
- **历史隔离与继承**：历史布局以独立 epoch 隔离；`PlayerData` 的模型版本 donor 首次继承由 Java 在业务读取时执行并校验。

新缓存命名空间位于 `CACHE/f`：

- `converted`、`bake`、`remote`、`preview` 对应扁平文件池 `c`、`b`、`r`、`p`，各池下 `s` 子目录存放 staging 文件。
- 索引位置列为 SQLite `INTEGER`，Java/JNI `long` 按无符号 `uint64` 位模式传递，物理文件名为左侧补零的 16 位小写十六进制串。
- `f/admission.sqlite` 与 `f/consumers.sqlite` 统一保护新文件池；旧业务库、旧文件池与旧 scope 协调库不读取、不迁移、不自动删除。`PlayerData` 维持原目录位置。

## 固定区域与文件边界

- **进程路径固定**：`ProcessAssetPaths` 在启动期冻结一次性探测的 `AssetPaths`，`ModelStorageInfra` 持有各存储区域与实际 CACHE 根的消费者登记，后续调用不重探或切换区域。
- **受检目录访问**：`CacheDirectoryGuard.Observation` 保存完整祖先链观察，`DirectoryAccess` 复核后返回受检路径视图。文件创建、打开、原子替换与删除统一走 Java `Path`、`Files`、`FileChannel`，操作前拒绝已观察到的越界、内部符号链接与目录替换（适用[目录稳定前提](../../../../product-decisions/decisions/artifact-storage.md#bccache-maintenance-does-not-own-sources)）。Writer 与 validator 共用同一 staging channel。
- **目录复核精度**：
  - `FileIdentity` 比较可选 `fileKey` 与文件类型，`DirectoryAccess` 额外核验真实路径（跨平台文件创建时间可能退化为修改时间，不参与比较）。无 `fileKey` 的文件系统仍照常工作，但同路径同类型替换可能不可见；目录观察属防误用门禁，不替代内容校验。
  - 每次取路径视图逐级复核祖先链并解析末端真实路径，不缓存“复核成功”结论；建多层目录前先检已存在前缀，防止沿符号链接写到外部目录。
- **`ChannelReadPhase`**：封装单次已接纳只读 channel。I/O 锁串行保护同一 channel 的 `seek -> read -> validate`，生命周期锁保护接纳与借用计数。封口不阻塞等待活动 I/O，由最后借用归还时关闭 channel（无活动借用则立即关闭）；构造失败立即关 channel。文件写入、staging 校验、索引锁与消费者登记锁均不进入读取阶段；对同一路径执行 atomic commit 前先结束本操作读取阶段。
- **数据库访问与并发写入**：`StorageIndex<R>` 绑定固定区域与 `Catalog`/`Remote` 能力，将有界数据库操作派发给 `DatabaseWork`；`PlayerPreferences` 复用同一 `DatabaseWork` 持 `DATA` 区 `PlayerData` 能力。`ConvertedSourceIndexStore` 与派生物 store 仅借用外部显式传入缓存，独立调用方须自行关闭。业务层不持 SQL，文件生成不占数据库事务；同键允许并发重复生产，由 SQLite 短事务决定最终索引，不使用 JVM key 锁、显式锁文件或 `FileLock`。根级消费者保护由 SQLite 协调库与 `CacheRootCoordinator` 提供。

## 偏好逻辑顺序与执行

- `PlayerPreferences` 持有唯一 `StorageWriteBuffer`，全部偏好读写与 remote 使用事件入同一 FIFO 队列。主线程独占逻辑接纳顺序、内存覆盖层与观察切片，`DatabaseWork` 后台逐条串行调用既有业务 JNI（每项独立事务）。`SUPERSEDED` 仅见于 Java 归并结果，native 仍维持六种返回状态。
- 纯查询排队项弱持有 `StorageQuery`，worker 执行前提升强引用并发布结果；副作用操作保留强结果 owner。受控域显式关闭先封口并 drain 已接纳工作再关 `DatabaseWork`；JVM 退出不触发该流程（见[玩家偏好](../preferences.md#周期提交与关闭)）。

## 派生持久工作

- **接纳与线程模型**：`DerivedStorageWork.submit` 原子检查任务配额（队列与运行中合计上限 16）并接管输入，拒收不转移所有权。整个生产进程共用单个后台 worker，队列空后线程自动退出、新任务到来时按需重启。首次接纳含线程构造与启动，抛错时回滚入队、计数与输入引用并原样传播异常。
- **持久化流程**：向区域分配器申请 `uint64` ID → `AtomicSharedCache.reserve` 独占预留 staging → 同 channel 序列化、完整校验、`force` 刷盘与 atomic move。正式文件或 staging 已被占用则分配新 ID；`converted` 与 `bake` 额外避让未清理的对象归属记录，防分配器重建后复用旧 token 地址。文件发布后短事务提交精确索引，旧磁盘文件保持原位。
- **分项校验**：动画输入（覆盖全部动画与未绑定程序块）、AOT（完整制品与独立 expected）与几何分别作为独立任务提交校验。
- **Effect gate**：提交、取消与关闭共用同一门禁——atomic move 成功即锁定成功，后续清理失败不改判。取消排队任务立即归还输入，取消运行中任务待同步借用结束后清理。过程诊断仅记计数，不留存已结束输入对象。

## 来源与 converted

`file ID` 与实例 `UUID` 分别标识物理位置与原生产实例：ID 分配器短事务单调递增 `uint64`，耗尽后拒绝；SQLite 索引完整保存 `ModelId`、`ContainerId`、用途、ABI 与 profile，物理文件名不携带身份。来源事实与对象归属分离，撤销复用资格后归属记录保留至受保护物理删除完成。

- **Direct 读取与失效**：`ModelSourceResolver` 接纳 `direct` 内容后建立精确读取实例；后台读取失败向 owner 投递原 provenance，由 owner tick 提交单向失效，未开始读取不再沿旧资格执行，已构建资源独立存活。
- **局部读取批次**：`ChunkDataSource.openReadPhase` 为当次准备提供局部读取视图，文件来源用同一 channel 依次读定义、common strings 与声音首块后即关 channel，再编译表达式。精确来源包装器透传该读取阶段，保留批次与失败归属。
- **`readPayloads`**：对相同 descriptor 去重并返回完整 `SharedBuffer` 集合，失败不交付半成品；读取阶段结束仅关 channel，不影响已交付字节。文件来源前后核对文件长度并逐 payload 校验；远端准备先固定已接收输入并结束传输再读本地字节。媒体前缀资格归[声音准备](../../audio/overview.md#播放接纳与顺序读取)。

索引发布与启动清理分工如下：

- **`ConvertedSourceIndexStore` 与 `DirectContainerIndex`**：
  - `publish` 仅发布新生产实例，调用方消费实际提交 UUID；未成功写索引的文件仍受消费者保护但不具索引损坏反馈资格。
  - `replaceScopes` 在单 scope 内每批最多 64 条提交无转换关联来源，失败保留已提交批次，全批成功后分批删过期键；保留的转换实例沿用原记录。
  - `CONTENT` 损坏反馈按原位置与 token 撤销 `converted` 复用资格，保留来源事实、对象归属与物理文件。
  - `DirectContainerIndex` 按当前文件 CID 与 profile 保存已验证目录证明集合。
- **`CacheRootCoordinator`**：业务缓存消费前取得根消费登记。普通读取、cache-only、延迟 opener 与已接纳生产交付能力前登记 `(pool, fileId, token)` 至 JVM 结束；最后服务退出关闭 native 登记，不清空本地保留集合。同根门禁串行化文件取得、来源更新、发布与删除，数据库 worker 不反向获取该门禁。
- **`CachePruner`**：借用连续独占清理窗口，读取库存与持久来源后将去重 CID 分批送交 `Catalog` 与 `Remote` 各自在单读快照内查覆盖与引用状态。必要库均以 existing-only 打开，任一缺失或批次失败则整轮放弃删除。仅标记本轮完整扫描的 scope；仅当 `Bake` 库尚不存在且未交付消费者时，启动 owner 可在排他窗口初始化根来源覆盖。对象回收严格按“条件撤销原 token → 受检删文件 → 清除归属记录”推进；中途失败不回滚已删文件也不报整轮成功（策略见[共享清理](../overview.md#共享存储与清理)）。
- **`CacheMaintenance` 与关闭链**：
  - 根交付前锁定 `Catalog` 启动 revision，首次合格扫描按根尝试清理并在窗口内重读日期与请求；两池均成功后条件写入完成 UTC 并消费全局 `all` 标记，手动预约推进 revision。事务真正退出后才恢复 `SHARED` 锁并释放 admission 锁；同步中断时先取消并等有界数据库工作退出再恢复中断标记。
  - `ModelStorageInfra.pruneNext` 借用根登记在既有 `DatabaseWork` 逐根提交，不获取文件变更门禁。
  - 显式关闭顺序：封口新工作 → 依次结束 Catalog、运行时、派生工作、写缓冲、数据库与同步借用 → 退出根登记。进程直接退出由 OS 回收连接与锁，已落盘事务与原子发布保证下次启动状态合法。

## Remote 与图片

- **`RemoteModelStore`**：仅保存已校验字节，不持 fetcher、授权表或查询表。读缓存出错按 miss 返回，取消、关闭与 OOM 向上传播；会话有权限时至多补取一次并原子替换，落盘失败不撤回内存结果。
- **`ContentStorageBinding`**：按接纳实际来源选定 `Catalog` 或 `Remote` 及其 `PreviewStore`；几何、`AnimationInputStore` 与 `ExpressionArtifactStore` 共用实际存储区的 `BakeIndexStore`。动画输入与 AOT 精确键、文件与内存引用各自独立，本地替代远端不改写实际存储归属。同根本地与远端 store 各自缓存内存资格、共用整数文件池，索引惰性开库。
- **独立预览图读写与提交**：
  - 普通 `readEncoded` 返回有界编码字节（未做媒体解码校验）；`accept` 允许交付有效内存图，管理端 `commit` 要求持久化替换成功。
  - `commit` 在事务外准备 staging 文件，进 `ServerModelSession.Effect` 门禁复核原成员资格、执行 atomic move 并在同窗口提交 SQLite 图片索引。
  - 持久化成功退出 effect gate 后、返回调用方前，再获取运行时锁通知 `ServerResourceRuntime` 撤销服务端字节保留；运行时不在持锁时回调 storage 或 session。若 atomic move 失败、资格被拒或仅为内存交付，则不发失效通知。
