# 玩家偏好与周期提交

上级契约：[模型存储](overview.md#seam-contract)。

## 职责与边界

`PlayerPreferences` 提供客户端玩家偏好读写与跨版本首次继承，并持有全进程唯一 `StorageWriteBuffer`，把 roaming 状态、本地模型选择（Local selection）、收藏、清除（clear）、首次填充及远端使用事件排入同一逻辑序列，借用[数据库执行域](overview.md#数据库执行)异步落盘。

键定位：
- **Roaming 状态**：`UUID + 发布身份`（明确空值也是已存在记录；intrinsic 与同源普通默认分开存取）。
- **收藏**：仅普通 `ModelId`。
- **Local selection**：按 UUID 保存完整发布身份与纹理意图（记录缺失不代表显式选 intrinsic）。

玩家权限、模型可用性、游戏效果与网络同步均归会话 owner，本模块仅承担独立存储读写。

## Seam Contract

| 消费者问题 | 契约 |
|---|---|
| 读取、更新与按版本首次继承 UUID 偏好 | [偏好读写与首次继承](#偏好读写与首次继承) |
| 合并高频状态更新、观察提交结果并安全关闭缓冲 | [周期提交与关闭](#周期提交与关闭) |

### 偏好读写与首次继承

**边界与权威**

Java 校验业务值、字段布局与选择 donor；native 按完整键执行查询、整值写事务与原子首次填充。玩家偏好为主数据，不能从缓存索引重建。

**前置条件与输入**

区域 owner 提供固定 `DATA` 根目录与数据库执行域；`PlayerPreferences` 懒创建 `PlayerData`。SQLite 中 roaming 保持命名数组格式 1，偏好 scope 与写队列使用 `NamedRoamingSnapshot` 按字段名保值（最多 64 唯一字段，名非空且 ≤ 32 UTF-8 字节，值为有限 `F32`）。存储入口整份校验，持久读取与首次继承另核目标布局（遇未知字段直接恢复失败并保留原记录）。写入方交出只读名称/值数组，门面校验后直接持有，后续任一方需修改须自行复制。

**结果与保证**

结果区分 `Missing`、完整值、已提交、失败与取消；普通更新只替换单个完整键；收藏分页包含已取消成员以推进 keyset 游标，读到空页结束。

首次继承仅在目标键 `Missing` 时触发：
- 仅版本化命名模型参与：同 UUID、同 family、版本严格更低中按 SemVer 优先级再按完整版本字符串 ASCII 序取最大 donor（分页观察不锁 donor）。
- 明确空记录可作 donor；未版本化、`hash:` 与 intrinsic 不参与；无 donor 时建明确空记录。
- 仅校验最终选中的最高版本 donor：数据非法或布局不兼容直接恢复失败，不降级试更老版本。
- Native 单短事务“缺失则插，并发已写则返回已有”，Java 复核返回值，不改原 donor 或收藏。

**所有权与时序**

所有线程向同一 FIFO 入口提交不可变操作：
- **逻辑接纳与内存覆盖**：返回 `QUEUED` 仅表拿到队列配额；主线程 `tick` 消费确定逻辑顺序后副作用 `Operation.admission()` 才变为 `ACCEPTED`。主线程按完整键维护最新整值内存覆盖层，读操作在接纳点固定观察视图，仅覆盖层无记录时查库；旧操作完成只撤自身代表的覆盖；分页跨页不保证一致性快照。
- **查询与副作用句柄**：纯读返回 `StorageQuery`，排队项弱持有句柄，worker 执行前提升强引用（已关闭/丢弃则跳过），结果发布与 `close` 共用同步点，配额归还不依赖再次轮询（进 JNI 的在 JNI 结束后归还）。写入/clear/首次填充/flush 返回强 owner `Operation`；`restoreRoaming` 开始前可取消，开始后完成原落盘。
- **客户端接线**：`ClientDemandCoordinator` 在本地提交边界向新 scope 移交完整声明、初值与持久资格；`PlayerPreferenceSession` 管理 scope 恢复，owner 切换先撤旧采用资格再关句柄。Local selection、收藏与本地 roaming 恢复在客户端主线程 tick 采用，动画与渲染线程不轮询句柄。
- **启动预热**：[启动预热](../../runtime/overview.md#启动模型一次性预热)在显式 `LOCAL` 模式且尚无玩家时调用 `readSelection`，复用同一逻辑读取顺序。

**失败与拒收**

普通写入后台 worker 落盘前仍会读并校验磁盘原记录：原记录不可解析或读失败阻止持久覆盖（内存逻辑覆盖仍可见）；布局不兼容合法记录仍允许显式 clear。结果保留原始失败信息，不伪装 `Missing` 或成功。

客户端查询失败临时降级：
- **Roaming**：以临时空值继续并标该 scope 不可持久化（运行期求值与上报照常，不落盘直至换模重查；显式 clear 建明确空 scope 并独立尝试持久清除后可恢复持久化）。
- **收藏与选择**：收藏任一页失败整份降级为空；Local selection 失败临时用普通 `builtin:default`，不写回磁盘。
- **日志**：仅记 DEBUG，仅在 `DebugAnimationScreen.isEnabled()` 开启时向聊天栏提醒。

当前生效选择 clear：本地提交边界先退休旧生产者、建明确空 scope，发包前向 FIFO 提交 clear；接纳后立即写明确空覆盖并截断写入归并窗口（入口拒收不建空覆盖，后续落盘失败不撤内存空覆盖）。

**证据与依赖**

依赖[数据库执行](overview.md#数据库执行)与[固定区域](overview.md#固定区域与目录观察)；测试见 `PlayerPreferencesTest` 与 `SharedStorageProcessTest`。

### 周期提交与关闭

**边界与权威**

主线程是逻辑顺序与内存覆盖层的唯一写入者：Roaming 按“固定区域 + UUID + 发布身份”、Local selection 与收藏按完整键、usage 按“固定 remote 能力 + `ContainerId`”保最大 UTC 秒级时间戳；其他线程仅向 FIFO 提交。

**前置条件与输入**

客户端由 `ClientModelService.tick`、专用服务端由服务端 tick 推进；批次计时用单调纳秒时钟，usage UTC 秒取事件发生时刻向下取整。

**结果与保证**

- **成批与截断**：首批待提交项进入满 1 秒打包成批（新写入不重置计时器）；遇读取、clear、restore 或显式 flush 提前截断。
- **有界容量**：单批至多 64 项且 ≤ 1 MiB；队列 + 覆盖层 + 观察切片 + 在途合计至多 256 项且 ≤ 1 MiB，饱和返回 `FULL`（不限磁盘总量）。
- **写入归并**：同键中间无观察操作的连续写入归并为最后整值，旧操作报 `SUPERSEDED`；读取、clear、restore、flush 截断归并窗口。
- **独立事务**：worker 逐条调既有业务 JNI，批内允许部分成功；usage 更新取原记录与新事件最大值，UTC 回拨不倒退。

**所有权与时序**

已逻辑接纳的内存覆盖即使落盘失败或返回 unknown 仍继续可见，普通 tick 不自动重试。

显式 `flush` 在接纳点截取前缀，仅对当时“已失败、确定未提交且仍是最新覆盖”条目安排一次重试（在途项随后失败不重试，unknown 与提交后清理失败不重放）。同键新写入不抵消历史 unknown（持续留存至关闭报告并占容量），`flush` 也追踪前缀内已被覆盖但仍在途的旧写入（若成 unknown 仍需报告未解决）；已知历史失败可被前缀内已确认落盘的新值抵消。

受控域显式关闭：停生产者 → `closeAndDrain`（封口并结束未接纳排队项 → 排空已接纳批次与 flush，未尝试项得一次正常落盘机会、已失败不自动重试，专用 drain 不依赖普通 tick，产关闭报告）→ 区域 owner 关数据库执行域与 native 资源。

[JVM 进程退出](../../runtime/overview.md#jvm-进程退出)不触发 `flush` 或 `closeAndDrain`，允许丢失未落盘近期内存状态；落盘的每条数据仍受完整校验与原子事务保护。

**失败与拒收**

提交入口返回 `QUEUED`/`FULL`/`CLOSED`，副作用主线程接纳后报 `ACCEPTED`。落盘失败不撤回内存值或改写玩家/模型/网络状态。Usage 仅记录交付历史，本模块不执行 remote 缓存清理。

**证据与依赖**

依赖[偏好读写](#偏好读写与首次继承)与[数据库执行](overview.md#数据库执行)；测试见 `StorageWriteBufferTest`。

## 设计取舍

- **Java 分页筛选 donor + native 短事务原子首次填充**：避免跨语言重复实现 SemVer 比较，也避免业务校验期间长占 SQLite 写事务。
- **接受 JVM 退出丢失近期未落盘状态**：省去退出排空钩子、持久写前队列、跨库事务与强制刷盘复杂度。
- **共享写缓冲 + 单键独立事务**：控制总在途请求量并降低单键失败耦合；代价是批次无统一原子提交点，调用方逐项消费反馈。

## 实现与验证入口

该存储能力可在无游戏宿主环境下通过 `PlayerPreferences`、`StorageWriteBuffer` 与 `DatabaseWork` 直接调用。验证策略与平台覆盖边界见[验证政策](../../../verification/policy.md)和[构建入口](../../../build.md#java-代码格式)。
