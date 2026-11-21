# 当前网络协议

当前应用层与 Forge transport 版本均引用 `ProtocolVersion.CURRENT`，具体版本归属见[版本清单](../../abi/current.md#保留的协议与环境描述)。全部消息使用统一的 `ysm.network` Proto 包，入口文件包括 [model session](proto/network/model_session.proto)、[common](proto/network/common.proto)、[player state](proto/network/player_state.proto)、[control](proto/network/control.proto) 与 [Minecraft state](proto/network/minecraft_state.proto)。Proto 包名不替代 transport 版本。只要本端或对端的 transport qualifier（不区分大小写）包含 `unstable`、`dev` 或 `snapshot`，两侧的完整原始版本字符串就必须严格相等；只有两侧都不含这些标记时，才交由协议的稳定版策略判断，而当前稳定版策略同样要求精确匹配。

协议尚未冻结，客户端与服务端必须来自同步构建。实现只保留当前版本的读写器，不提供版本协商、探测、双读或对 `0.2.0-unstable` wire 的兼容。

## Frame

每个物理帧的首字节固定为：

```text
messageTag = (messageId << 1) | frameKind

bit 7..1  messageId      # 0..127
bit 0     frameKind      # 0 = simple, 1 = full
```

Simple frame 用于不带 attachment 的短 protobuf 消息：

```text
uint8  messageTag
byte[] rawProtobuf
```

其中 `rawProtobuf` 长度必须小于 30 KiB，不经压缩，且必须由 Proto 解析器精确消费完。

Full frame 用于较大的 protobuf 或携带 attachment 的消息：

```text
uint8  messageTag
uint8  flags                       # bit 0: protobuf 使用 zstd
uint32 decodedProtoSize            # little-endian
uint32 storedProtoSize             # little-endian
uint32 attachmentSize              # little-endian
byte[storedProtoSize] storedProto
byte[attachmentSize] attachment
```

物理帧必须满足以下约束：

- `storedProtoSize + attachmentSize < 30 * 1024`；首字节 tag 与 13-byte 的 full header 不计入 body。
- `decodedProtoSize <= 1 MiB`，且不得超过对应注册消息的上限。
- 遇到未知 flag、负值、加法溢出，或声明长度与实际剩余字节数不符时，必须在分配大块内存、解压或 Proto 解析之前直接拒绝。
- 仅当 protobuf 大于 64 bytes 时，发送方才尝试用 zstd level 10 压缩，且只有在压缩结果严格更小时才置位 bit 0。Attachment 不做协议层压缩。
- zstd 不使用外部 dictionary，解压结果必须精确等于 `decodedProtoSize`，并拒绝尾部残留的压缩数据。未压缩 protobuf 的 `decodedProtoSize` 与 `storedProtoSize` 必须相等。
- Proto 解析器与帧解码器都必须精确消费各自边界内的字节。所有多字节整数均为小端序（little-endian）。

帧传输层不解释 transfer、资源键、成员、序号、偏移、完成状态、授权或发布语义。底层可以绕过 Forge 外层压缩，但不得改变上述 YSM 帧边界。Transport 版本不匹配时，客户端只关闭 YSM session 并退入仅内置默认模式（intrinsic-default-only），不主动断开 Minecraft 连接。

## 消息注册

Model session 相关消息的解码后 Proto 上限均为 1 MiB：

| ID | 方向 | Proto | attachment |
|---:|---|---|---|
| 0 | S2C | `ServerHello` | forbidden |
| 1 | C2S | `SessionResponse` | forbidden |
| 2 | S2C | `SessionFullFragment` | forbidden |
| 3 | S2C | `SessionDeltaFragment` | forbidden |
| 4 | C2S | `MetadataPrefixRequest` | forbidden |
| 5 | C2S | `ModelChunkRequest` | forbidden |
| 6 | C2S | `PresentationPageRequest` | forbidden |
| 7 | C2S | `ResourceTransferCancel` | forbidden |
| 8 | S2C | `ResourceTransferFailure` | forbidden |
| 11 | S2C | `MetadataPrefixFragment` | forbidden |
| 12 | S2C | `ChunkFragment` | required |
| 13 | S2C | `PreviewFragment` | conditional |
| 14 | S2C | `IconFragment` | conditional |
| 15 | S2C | `PackCoverFragment` | conditional |

其余消息的 ID、方向和解码上限如下：

| ID | 方向 | Proto | decoded 上限 |
|---:|---|---|---:|
| 9 | C2S / S2C | `SelectionChange` | 128 KiB |
| 10 | C2S / S2C | `SelectionStateSync` | 128 KiB |
| 16 | C2S / S2C | `EntityStateSync` | 128 KiB |
| 17 | S2C | `EntityTrack` | 128 KiB |
| 18 | S2C | `PlayerFlags` | 64 B |
| 19 | S2C | `SelectionDirective` | 512 B |
| 20 | S2C | `SelectionInvalidated` | 16 B |
| 22 | C2S | `SubmitConfigActionRequest` | 8 KiB |
| 25 | C2S | `SwingHandRequest` | 256 B |
| 26 | S2C | `ProjectileModelState` | 32 KiB |
| 27 | S2C | `VehicleModelState` | 32 KiB |
| 28 | S2C | `ConfigActionEvent` | 8 KiB |
| 29 | S2C | `PreviewAvailable` | 128 B |
| 30 | S2C | `GenPreviewStart` | 16 B |
| 31 | C2S | `MissingPreviewQuery` | 32 B |
| 32 | S2C | `MissingPreviewFragment` | 30 KiB |
| 33 | C2S | `PreviewUploadRequest` | 128 B |
| 34 | S2C | `PreviewUploadAdmission` | 32 B |
| 35 | C2S | `PreviewUploadFragment` | 64 B |
| 36 | S2C | `PreviewRepairResult` | 64 B |
| 37 | S2C | `ServerFlags` | 64 B |

ID 21、23、24 以及 38..127 当前未注册，必须在进入业务 handler 前直接拒绝。ID 35 必须携带 attachment，ID 9、10、16..20、29..34、36 和 37 禁止携带 attachment。每个 ID 的方向、Proto 类型和 attachment 策略均属于 wire 契约的一部分。

Proto 中显式标注 `optional` 的普通单值（ordinary singular）字段区分三种状态：缺失、显式设为默认值、显式设为非默认值；消费方不能只调默认 getter 而丢失存在性信息。Repeated 与 map 用空集合表示无元素，真实 `oneof` 用分支 case 表示选择。在选择增量和实体增量消息中，字段缺失表示不修改，显式设为默认值表示写入默认值；完整选择和 track 消息则必须携带完整状态，不能用 partial message 绕过校验。

当前要求精确版本、同步构建的 unstable profile 不承诺保留或往返（round-trip）未知 Proto 字段。发送方只能发送当前注册 schema 中已知的字段和枚举值；接收方可以跳过未知字段，但绝不能将未知枚举、未知消息 ID 或未知 oneof 分支解释成已知操作。新增任何 wire 表面都应先明确 transport 或 schema 版本策略，不能依赖旧版本对端透传。

## Expression action 与本地求值

协议不注册收藏同步或任意源码下发消息（ID 21 未注册），ID 18 和 19 分别用于玩家状态开关和选择指派。服务端不注册 `/ysm molang execute`，只保留本地的临时 execute 与 watch、普通 sync 以及配置表单（config form）。

模型中的执行程序本身不进入网络线缆。Config action 只携带按钮或表单的语义路由及强类型参数，`SelectionStateSync.sync_event` 只携带有界且有限的 F32 参数。接收端始终在当前实体的本地 module 中按语义路由查找 handler，不把发送端的 `ProgramId`、内存布局或编译产物当作本地地址。

`ExpressionTargetIdentity` 的 `schema_version` 固定为 2。其中 `model_id` 是已校验的模型内容身份，`target_id` 是渲染目标语义，`config_identity` 是有效 `Settings` 的内容摘要，`catalog_identity` 是事件路由与参数契约摘要，`module_identity` 则是按 `ysm:shared-expression-semantics:v1` 对上述四项事实计算出的独立摘要；模型内容本身同时承诺了模型声明的 handler 路由。这一标识不包含当前生效模组、组件类、实体类或实体 profile、后端实现、codegen ABI、内存布局、替代报告、本地目录编号或 `ProgramId`。schema 1 的历史产物语义与任何未知 schema 一律拒绝，不做双读或猜测匹配。

`SubmitConfigActionRequest` 先由服务端依据当前连接、受控主体、模型、target、配置、表单或选项路由以及共享语义身份完成授权，再向观察者转发 `ConfigActionEvent`。接收端核对当前本地 module 的共享语义身份后，才执行对应的本地 handler。布尔值、有限 F32 与单选标签下标不能互换；授权失败、连接或实体过期、或语义不匹配都不会产生转发或执行效果。

`ysm.sync` 在本地执行借用退出后恰好入队一次，随后独立、尽力（best-effort）发送当前选择的瞬时事件。每次事件允许 0 个参数，最多 16 个有限 F32，不携带共享语义摘要。服务端只核对连接、主体和当前正 intent。LocalPlayer 忽略自身回显；远端玩家在模型安装完成前，按[远端安装前暂存与消费顺序](#远端安装前暂存与消费顺序)有界保留这些事件。如果匹配的模型安装没有对应 handler，该事件直接丢弃。该机制没有 ACK、网络重放或持久队列；ConfigAction 仍独立使用上述共享语义身份。

## Connection 与 model session

服务端发现客户端具备 YSM channel 后，发送仅包含精确协议版本与 roaming 策略的 `ServerHello`，客户端通过 `SessionResponse` 接受或拒绝。Wire 层不携带 `session_id`，来源玩家与精确匹配的 Forge `Connection` 构成唯一的连接身份。每条连接至多维护一个 model session：待确认的 Hello 重试必须复用同一份 offer；session 进入活跃状态后，`/ysm ping` 只做无状态连通性探测，不替换 owner，也不重置发布或传输状态。

客户端将 `ACCEPT` 成功提交给当前连接后，C2S 业务消息即获准进入发送门禁；服务端只在同一连接的 model session 处理完该响应并进入活跃状态后才执行它们。这里的业务消息指选择、玩家状态和控制请求（ID 9、10、16、22、25）。业务消息资格不等待 collection publication 完成，也不会因为 catalog 组装、提交或激活失败而撤销；ID 4..7 的目录与资源请求则仍受已提交 publication 及各自接纳条件约束。一旦遇到非法帧、协议不匹配、参数变更的重复 Hello、断连或连接替换，业务资格立即撤销。这一顺序直接复用同一连接的传输顺序和 owner 线程的活跃复核，不额外引入 wire ACK、代际号、重放或待发队列。

Model session 的目录发布（publication）与客户端发起的模型分发各自维护一条连接内作用域的 `uint64` ID 流，要求非零、按无符号整数单调递增且永不复用。首次 publication 必须是 `transfer_id = 1` 的 full；只有建立新连接时才重置 ID 流。合法范围为 `1 ... 0xffff_ffff_ffff_ffff`，仅在发出无符号最大值后才耗尽；耗尽后拒绝新任务并要求重连，不回绕到 0，也不引入代际、历史记录、ACK、重放或全量重同步。

## 公开目录地址

Catalog 模型位置、pack key 与移除项、pack-cover 选择器以及缺图清单位置统一使用完整地址：`global:<entry>`、`local:<entry>` 或 `builtin:<entry>`。模型 entry 不能为空，文件型入口保留扩展名，raw 目录入口保留目录名。目录地址可以直接指向 `global:`、`local:` 或 `builtin:`，末尾不带 `/`。地址语法合法不代表对应对象存在。

`default` 是内置默认（intrinsic default）的唯一规范地址，其内部来源仍是 builtin，且指向不随服务端默认模型配置而改变。输入解析优先识别这一保留地址；如果有同名用户模型，应使用完整地址区分。`builtin:default` 是同源普通 builtin 模型的规范地址，会进入普通 catalog collection，并使用完整规范的 `ModelId`。旧版 `$YSM_*` 占位符地址一律无效。

地址的命名空间前缀区分大小写，以 `:` 结尾，不使用 `//`。路径部分以 `/` 分段，原样保留大小写、空格和 Unicode scalar 序列，不做 trim、大小写折叠、Unicode 归一化、百分号解码或环境变量展开。路径部分拒绝空段、`.`、`..`、反斜杠、冒号、U+0000–U+001F、U+007F–U+009F 以及孤立 surrogate；未知命名空间和旧地址别名一律无效。`%2e%2e` 和 `a..b` 按字面段名处理。完整规范串按无符号 UTF-8 字节序比较，不套用宿主操作系统的路径规则。

单模型命令还额外接受 `id:<完整 ModelId>`。明确指定 `id:` 时不回退为路径；使用路径时先在所有公开位置中消歧，再判断命令资格。`id:default` 无效，`default` 与 `builtin:default` 始终保持角色差异。命令参数中的引号不属于选择器本身，含空格的路径应用双引号包裹整体，其中的 `\"` 与 `\\` 由参数层解码。

公开目录地址仅用于定位当前 publication 中的条目，绝不能直接交给宿主文件系统 API。Catalog 与 grant 的普通业务 key 仍为 `ModelId`，内置默认模型不进入这些 collection。`ModelReference.builtin_default` 只表示内置默认，`model_id` 只表示普通模型，不能因为内容哈希恰好等于默认源就切换分支。Pack 以完整目录地址为 key，不同根目录下相对层级相同的 pack 既不合并，也不跨根回退封面。远端模型的位置和访问策略均以服务端权威为准，客户端本地复用同源内容时既不改写这些字段，也不因此获得本机打开文件的权限。对外诊断信息只包含逻辑位置与失败类别，宿主物理路径和底层异常原文仅记入本机日志。

## Collection publication

`SessionFullFragment` 与 `SessionDeltaFragment` 的每个数据包只承载一种强类型的 collection 操作。接收端按 `transfer_id` 与 `sequence` 组装分片，要求恰好有一个 `final`、序号从 0 开始连续覆盖，并在整批校验通过后一次性发布。内容完全相同的物理重复包视为空操作（no-op）；冲突重复包、序号缺口、多个 `final`、`final` 之后继续收到分片，以及过期或未知 ID，都会让接收方按失败语义中止本次发布。

接收端对单次 publication 设定的上限为：最多 65,536 个数据包、128 MiB 解码后 Proto；catalog 与 grants 各最多 16,384 条记录，pack 与 default-animation 各最多 4,096 条记录。单个数据包依然受 1 MiB Proto 与物理帧 `< 30 KiB` 的上限约束。

Full 发布必须用 `FULL` 操作显式覆盖 catalog、grants、pack presentation 和 default animation 全部四个 collection，即使某个 collection 为空也不能省略。Delta 发布只允许 `CLEAR`、`REMOVE`、`ADD` 三种操作：`ADD` 与 `REMOVE` 的载荷必须非空，其中 `REMOVE` 只携带自然键，`CLEAR` 不带载荷。四个 collection 的出现顺序固定为 catalog、grants、pack、default animation；delta 内的操作顺序固定为 `CLEAR`、`REMOVE`、`ADD`。同一 collection 同一操作的数据包必须连续排列，key 在包内以及跨连续包之间必须严格递增（分别按完整 `ModelId` 的 ASCII 字节序、内容 hash 的无符号字节序或规范完整目录地址的无符号 UTF-8 字节序），任何重复或冲突都会导致拒收。对于 grant 操作，每个发送数据包只携带一个完整 `ModelId` 并保持未压缩的 simple frame；其余 collection 数据包允许启用物理帧压缩。

Collection publication 不包含、不等待、也不协调模型选择。模型选择由 ID 9 的完整选择和 ID 10 的当前选择增量传递，ID 17 则专用于向新观察者下发当前选择和完整实体状态。Metadata 消息必须在同一连接的发送 FIFO 中排在依赖它的状态消息之前。

`CatalogPublication.runtime_content_allowed` 表示服务端为当前接收方计算出的普通内容使用资格，缺省为 `false`，且不授予选择权限。服务端依据当前 public、grant 与 `RESTRICTED_AUTH` 状态求值；资格发生变化时，通过同一 catalog key 的 `ADD` 操作替换原条目。客户端在基于本地内容或缓存发起新的资源获取和新的音频播放之前，必须检查该字段。资格变化不撤销已经接纳的资源获取或已安装的 lease，也不改变 `(ModelId, ContainerId, path, access)` 的内容身份。

## 显式管理补图

`/ysm model gen-preview` 仅对权限等级达到 level 2 的在线玩家开放，且执行方当前精确匹配的连接必须具备活跃的 model session 和已发布目录。服务端只下发 `GenPreviewStart`；客户端在单条连接上独占一个补图批次，重复启动立即返回 `BUSY`，GUI 界面的开关不影响批次运行。

以下三种独立请求共用强类型模型分发的非零无符号单调 ID 流、高水位线，以及 `ResourceTransferCancel` 与 `ResourceTransferFailure`：

| 请求 | 接纳资格与结果 |
|---|---|
| `MissingPreviewQuery` | 要求当前具备 level 2 权限且 session 处于活跃并已发布状态。服务端探测固定的已发布目录，按 `(ModelId, ContainerId)` 无符号字节序排序，只返回缺图项和探测失败项（后者携带 `probe_failure = UNAVAILABLE`）。清单本身不构成后续拉取内容或上传图片的资格凭证 |
| `ModelChunkRequest(GEN_PREVIEW_MAIN)` | 要求当前具备 level 2 权限、目标为当前精确 `R` 且仍处于缺图状态；请求的全部 descriptor 必须属于可信模型视图规划的主 `player` target definition、实际默认纹理与 PBR、common strings 以及声音首块闭包。允许无普通选择 grant，但绝不放宽普通用途、其他 target、非默认纹理或声音尾块的访问 |
| `PreviewUploadRequest` | 要求当前具备 level 2 权限、目标为当前精确 `R`、仍缺图且服务端容量未满。声明的 `encoded_size` 须在 1..16 MiB 之间；通过后服务端以相同 ID 返回 `PreviewUploadAdmission`，客户端随后再上传分片。无需先执行 query 或拉取管理 chunks |

缺图清单的分片序号必须从 0 连续覆盖且仅有一个 `final`（空清单也要发送 `final`），上限为 16,384 条记录、65,536 个数据包、128 MiB 解码后 Proto。每个条目的 `R`、[完整公开模型地址](#公开目录地址)、failure enum 以及跨帧严格排序都必须校验；内容相同的重复包不重复计费，冲突或序号缺口直接失败，不处理不完整的结果集。

上传流程中的请求头、准入响应、数据分片和最终结果共用同一个请求 ID。单张图片最多 16,384 个分片记录，附件按字节偏移从 0 连续覆盖，当且仅当结束偏移等于声明长度时置位 `final`。字节完全相同的重复分片为空操作；缺口、重叠、冲突重复、越界或错误的 `final` 都会使本次请求失败。相同活跃请求头不重复接纳，已退休或未知的旧 ID 不能复活状态。完整图片按既有的 PreviewStore 媒体规则校验，协议不设图片 hash 权威。

`PreviewRepairResult` 的业务结果为 `COMMITTED`、`ALREADY_AVAILABLE`、`STALE` 或 `NOT_FOUND`；权限、容量、结构、媒体与存储层错误则通过既有强类型失败消息返回。只有持久化原子替换真正完成后才返回 `COMMITTED`。撤权只影响后续新请求；原连接断开、原目录成员被移除或更换 `R`、或移除后重新加入，都会撤销尚未提交请求的提交资格。两个独立的已接纳上传允许依次提交，提交阶段不再重复检查权限或缺图状态。提交成功后，服务端尝试向所有当前有效 session（包括上传方）广播 ID 29 `PreviewAvailable`；通知发送失败不影响 `COMMITTED` 结果，也不建立重放历史。

客户端只保留一份固定的完整清单、一个当前处理项和有限的逐项结果，按顺序记录成功、失败和跳过项，关闭时把尚未启动的项计入未处理。处理每一项时先尝试获取有效的编码图片，命中则直接原样上传而不拉取模型本体、不构建 target、也不执行绘制；未命中才经真实主渲染目标生成。服务端只持有单个独立请求的组装缓冲、数据源、配额与提交资格，不维护批次状态、清单凭证、Receiving 状态机、预留槽位或收尾阶段。

## Typed model distribution

`ModelChunkRequest.purpose` 取值为 `ORDINARY` 或 `GEN_PREVIEW_MAIN`。用途区分不影响 descriptor 上限、覆盖规则或 ID 空间；`GEN_PREVIEW_MAIN` 的窄授权边界见[显式管理补图](#显式管理补图)。

每一个 `MetadataPrefixRequest`、`ModelChunkRequest` 或 `PresentationPageRequest` 都对应一个独立的 `data_transfer_id`，并拥有各自的队列接纳、取消以及成功、失败或取消终态。只要完整的 descriptor 列表能放入单个物理帧，就只能发起一次 transfer；只有当 descriptor 超出单帧容量时，metadata、model 或 page 的业务 owner 才按规范的成员顺序将其拆成有限个完整子请求（children）。父级没有 wire ID；各子请求独立接纳，允许前面的子请求成功而后面的返回 `BUSY`。内部聚合、取消和底层数据所有权见[资产传输](../../architecture/network/implementation/asset-transfer.md#typed-request-与-action-owner)。

三类请求的成员键、规范顺序和接收端本地上限如下：

| 业务 | member key 与规范顺序 | 每 transfer 上限 |
|---|---|---|
| metadata prefix | 32-byte `container_id`，按无符号字节严格递增 | 1..16,384 个成员；16,384 条分片记录；128 MiB 保留字节 |
| model chunk | 精确的模型与容器身份 + 规范 chunk 名称，名称按 UTF-8 字节严格递增 | 1..256 个成员；16,384 条分片记录；128 MiB 存储字节；512 MiB 解码字节 |
| presentation page | 强类型 `(preview \| icon \| pack-cover, slot)`，各类型列表内 `slot` 严格递增；preview 成员仅含 `slot`、`model_id`、`container_id`，icon 与 cover 保留 descriptor | 1..256 个成员；16,384 条分片记录；128 MiB 实际存储字节；非 preview 的 descriptor 解码总计 256 MiB；单张 preview 编码后上限 16 MiB、单边上限 4096、总像素上限 16 Mi pixels |

Metadata、model chunk、icon 与 pack cover 的 descriptor 中，每个 hash 固定为 32 bytes，存储大小和解码大小都必须为正且算术运算不得溢出；chunk 名称必须符合规范；pack 层级必须是[完整公开目录地址](#公开目录地址)，并精确匹配当前已发布的 pack 及其 descriptor。Preview 请求不携带名称、hash、大小或编码，只校验 slot、完整规范的 `ModelId`、32-byte `ContainerId` 以及规范顺序。服务端在推进连接本地高水位、或触碰数据源、native 调用、内存分配和保留槽位之前，必须先完成全部结构校验；推进高水位后，再对照当前 session 校验目录身份、权限、允许的展示选择器以及实际数据源事实。整份业务请求全部校验通过后，才创建数据源读取计划。

`IconMember` 复用同一套 descriptor，既可请求模型图标，也可请求已校验 Manifest 中明确引用的作者头像、GUI 前景图和背景图。服务端按当前精确的模型与容器身份核对这份展示白名单及对应 chunk descriptor；普通图像 blob 或运行时纹理不能借该请求类型获取访问权。这一复用不新增 wire 字段、选择权限或运行时内容授权。

Metadata 按容器编号使用分片序号（sequence），model chunk 与 presentation 数据则使用字节偏移（byte offset）。接收端只保留本次请求的强类型成员：内容相同的重复分片为空操作；冲突重复、区间重叠、越界、覆盖缺口、非法 `final` 或未知成员都会导致该子请求失败终止。Presentation 的交付类型（delivery）在 wire 上定义为 `UNSPECIFIED = 0`、`DATA = 1`、`UNAVAILABLE = 2`、`ABSENT = 3`。每个 slot 必须要么是完整的 `DATA` 字节覆盖，要么是 `(offset = 0, final = true, empty attachment)` 的合法业务空结果：preview 仅允许 `ABSENT`，icon 与 cover 仅允许 `UNAVAILABLE`。交付类型不匹配、将 `DATA` 与空结果混合、或出现未知 delivery 值，都会使对应子请求失败。

返回 `ABSENT` 要求服务端已经校验过当前精确成员，确认合法内容未声明内嵌图且对应的独立 preview 缓存未命中；已经声明但损坏的图片、以及实际读取或传输故障都不能伪装成 `ABSENT`。Preview 返回 `DATA` 仅代表服务端发送了完整的编码字节，不保证服务端已验证独立缓存图可解码。Preview 的唯一 `final` 分片动态决定整张图片的编码大小；完整覆盖且客户端解码成功后，图片只按 `ContainerId` 存入独立缓存，不绑定图片 hash、模型身份或服务器隔离。客户端根据发起请求时的远端 Manifest 区分来源：完整独立图遇到确定性的媒体解码拒绝时，按当前 session 缺图处理；内嵌图解码失败则仍视为错误；传输故障、访问受限和运行环境异常一律不得降级为缺图。这一分类不需要新增 wire 字段，也不要求客户端上报坏图。Icon、cover、model 与 metadata 依然必须严格通过 descriptor 字节边界、hash、容器与 Manifest 身份以及格式校验。

各子请求独立参与接纳；容量不足时返回单个 `BUSY`，不同子请求之间不做跨 transfer 的原子接纳或回滚。关于内部入队所有权交接、数据源游标以及锁外关闭机制，见[资产传输](../../architecture/network/implementation/asset-transfer.md#全局-dispatch-owner)。

`PreviewAvailable` 只携带完整规范的 `model_id` 与 32-byte `container_id`，不含附件、图片版本号或授权字段。客户端收到后，只让当前连接中匹配成员的缺图记录，以及通知前发出的请求产生缺图结果的资格失效（包括在途的 `ABSENT` 和 `DATA` 解码拒绝）；未知或已移除身份无效果，非法的模型身份或容器 hash 长度则视为会话级协议错误。已经过时的缺图结果仍须正常走向取消终态。该通知既不修改 catalog，也不自动触发拉图；广播的触发时机与持久化成功由管理写入 owner 负责保证。

## 安全与能力边界

协议的主动对抗模型针对恶意客户端。所有上行（serverbound）帧和请求都必须先完整校验消息 ID、方向、attachment、数量、长度、hash 宽度、整数算术、强类型选择器、规范路径、压缩描述、权限和当前数据源事实，之后才能允许客户端输入驱动数据源打开、大块内存分配、解压、native 计算或长期保留。任何拒绝都不得创建无主资源，也不得波及其他连接或 session。

客户端信任已连接服务端的业务决策，但仍将所有远端数据当作受限输入处理：

- metadata、chunk 和 preview 只进入固定的容器、Proto 和图像解析器，以及本地 Molang 解析求值、BLAKE3、zstd、烘培和渲染流程；preview 的弱关联策略绝不放宽其他内容的校验。模型和远端数据包不能提供 JVM 类、类加载器路径、Java 成员描述符、provider 对象或任意本地缓存路径，远端字符串也不能选择 native 库、插件或反射能力。
- 远端只能提供已校验的 hash、固定枚举的编码选择器或规范的 pack 层级地址，不能提供宿主文件路径。远端缓存的物理根目录隔离与 pack 逻辑来源校验分别见[Storage](../../architecture/model-management/storage/overview.md)与[资产传输](../../architecture/network/implementation/asset-transfer.md#server-admission-与-source-closure)。
- 没有任何协议字段能直接触发进程或 shell 执行、动态类加载、JNDI、命名或表达式查找、外部实体或资源解析、任意 URL 网络访问或任意宿主路径访问。
- 日志一律采用参数化数据记录，不把远端字符串当作模板查找或插值表达式处理。

上述规则划定的是进程能力隔离与常规内容正确性边界，而不是针对恶意服务端的带宽、CPU、累计传输量或内存耗尽防护。协议不额外引入连接聚合上限、超时控制、沙箱、策略引擎或恢复运行时，也不声称能防范依赖未知漏洞的攻击。

## 选择、授权与状态顺序

远端目录允许向客户端展示尚未授权的条目。Metadata、preview 与 icon 请求只要求当前 catalog 成员资格；普通 chunk 与普通模型选择则按当前 catalog、grants 与 `restricted_auth` 综合裁定。接纳后的撤权只阻止新请求，不追溯中断已接纳的传输；相关产品边界见[model-authorization](../../product-decisions/decisions/model-authorization.md)。管理员的强制选择复用同一套普通授权逻辑，不设独立绕过通道。

`SelectionChange.selection` 表达完整选择状态：主体实体 ID、完整发布身份、正的有符号长整型 `intent`、完整的 MAIN roaming 声明字典，以及完整的纹理、动画和 roaming 初值。内置默认 `default` 与普通 builtin 模型属于不同角色。完整状态必须显式携带纹理、动画和 roaming 字段，即使内容为空也不能省略。动画 `oneof` 为 `stopped = true` 或非空名称；纹理名与动画名各不超过 256 UTF-8 bytes。服务端原样保留这些字符串，不做名称存在性校验；客户端可以在本地按需回退纹理。

每次新选择都分配 `1..Long.MAX_VALUE` 范围内的递增 `intent`，显式重选同一目标或执行 clear 同样分配新值；耗尽后本业务会话停止接受新选择，不回绕。服务端在进行任何业务裁决之前，先对结构合法且更新的请求推进已见序号（seen order）。结构非法、过期或重复的请求直接丢弃；业务拒绝时保留已接纳的旧选择，并向发起方下发无条件的内置默认指派（directive）。协议不设选择结果 ACK、selection window 或周期性 FULL。

客户端在提交新选择前，先准备好完整字典、合法初值和持久化资格，再提交本地选择、退休旧生产者并建立新 scope；clear 操作则在发送前按存储 FIFO 顺序提交。随后客户端只尝试发送一次，不等待自身回显，也不因物理发包失败而回滚本地状态。旧模型实例可以暂时保留显示，但绝不能以新 `intent` 上报状态。服务端只维护一份当前已提交选择；model session 只保存连接资格和请求序号，对外提供该选择的只读视图。

Roaming 字典保留 MAIN 编译布局中的名称顺序，最多 64 个唯一名称，每个名称非空且不超过 32 UTF-8 bytes。`RoamingState` 中的 packed `uint32 ids` 与 packed `float values` 一一平行；入口先整批校验等长、ID 唯一、原始编号范围、字典边界和有限 F32，全部通过后再窄化编号并写入。合法的有限浮点值保留原始比特位（raw bits），包括 `-0.0`。完整状态先将声明域全部归为 `+0.0`，再覆盖写入携带的编号；增量状态只修改携带的编号；快照则输出整个字典域。服务端不读取模型编译布局；接收端在安装准备期按名称建立 `wireId` 到 `localId` 的交集映射。即使字典中存在本地未知的名称，也必须先参与整批合法性校验，不能先过滤再校验。

`SelectionStateSync` 携带主体实体、当前 `intent`，以及状态增量或瞬时 sync 事件。正 `intent` 必须归属当前连接上的生产者；`intent = 0` 表示无生产者，此时字典与 roaming 必须为空。服务端权威的实体状态事实经 `EntityStateSync` 发送，客户端上行数据不能覆盖服务端主导的玩法字段。

`EntityTrack` 必须同时携带完整 `selection`、完整实体 `state` 和 `PlayerFlagsState flags`，接收端整体验证通过后一并采用；主体实体 ID 直接取自 `selection`，外层不重复携带。`PlayerFlags` 携带实体 ID 和必填的 `PlayerFlagsState state`，目前仅含 `disabled` 标志，`disabled = false` 是完整的合法值。`ServerFlags.can_switch_model` 只表示当前连接的服务器切模开关，不携带实体、intent 或 catalog 身份。

完整选择、选择增量、实体增量与实体 flags 只发给游戏当前的观察者及发送者自身，接收方按精确连接的有效业务会话筛选并去重；`ServerFlags` 则发给全部业务就绪的连接。开始追踪实体时向新观察者单播完整 `EntityTrack`；握手就绪时，服务端独立下发玩家自身的实体 full、自身 flags 与 server flags，并补发当前已追踪远端玩家的 track。上述状态消息的接纳、发送和接收都不等待 catalog publication 完成，而资源请求仍保留各自的门禁。

LocalPlayer 整条忽略选择、选择增量（含 sync 事件）和 track，但仍正常消费实体增量、独立实体 flags 与 server flags；RemotePlayer 消费全部四类状态。实体角色按当前游戏实体判定，找不到对应实体时直接丢弃消息，不为未来可能出现的实体排队。Directive 与 invalidation 只单播给目标玩家。收到失效通知后，客户端无条件停止当前生产者、移除非法模型使用，再发起普通选择；服务端同步将非法选择归约为无生产者的内置默认空状态。

所有状态通知均保持尽力投递（best-effort），发送失败不回滚，也不排队重放。玩家存档只记录模型选择与授权（grant），不保存 roaming 历史、收藏或管理员绕过标记。SQLite 中的 roaming 数据仍按玩家 UUID、完整发布身份与变量名称保存，格式版本保持 1。

### 远端安装前暂存与消费顺序

客户端串行入口实际收到消息的先后顺序，直接定义 full、delta 与 sync 的相对顺序，不根据发送时间戳推断。客户端先整体验证主体、当前选择、字典和载荷，再更新网络视图或写入暂存队列；找不到当前实体或缺少完整选择时直接丢弃。业务会话失效时拒收并清空队列；但 catalog 尚未发布或资源激活失败不会撤销已有的有效选择。

每个远端当前选择都维护两份数据：最新的 roaming 网络状态，以及尚未消费的 full 基线、状态段与 sync 参数副本。Sync 事件本身不回写网络状态。每个状态段按字段保留最后一次赋值（包括写入 0 或与当前网络状态相同的值）；delta 段额外记录本段的写集合并只输出写过的字段，full 段则输出整个字典域。写集合只表示“本段内改写过哪些字段”，不表示变量缺值，因此不能用最新网络状态的全量快照替代 delta 段。

| 输入位置 | 暂存规则 |
|---|---|
| 首个 sync 之前收到 delta 或同一选择的 full | delta 合并进 full 基线；full 直接替换基线 |
| sync 之后连续收到 delta | 合并进尾部 delta 段，同字段后值覆盖前值 |
| 收到 sync | 复制参数并追加一道顺序边界；参数重复的 sync 也原样保留 |
| sync 之后收到同一选择的 full | 在当前位置建立 full 段，并替换同一无 sync 间隔内先前的状态段 |
| full 段之后收到 delta | 合并进该 full 段，保留完整替换语义 |

状态合并严禁跨越 sync 边界。当匹配的模型安装发布后，客户端先采用基线，再按队列顺序逐项应用状态段或执行 sync。每个 sync 的执行以及借用退出后的延迟动作排空，都必须在下一项开始前完成，期间不得穿插常规动画推进。回放期间新到达的输入一律追加到队列尾部，不能走即时路径越过队首。队列排空后恢复即时接收，且不再用最新网络状态覆盖脚本执行产生的副作用。

每个远端选择最多暂存 64 个未消费 sync，单条连接上所有远端选择合计最多 4096 个。每个 sync 最多携带 16 个有限 F32；由于连续状态段会自动合并，N 个 sync 最多对应 N + 1 个状态段，每段最多 64 个 roaming 字段。超出上限时直接丢弃新到达的 sync 并递增计数；被丢弃的 sync 不建立顺序边界，后续合法 delta 继续合并。消费完成或队列清理时归还额度；不设 TTL、后台重试或服务端事件重放。

候选模型加载失败或尚未提交时，不消费暂存队列。安装提交时必须读取提交当下的基线与队列，不能固化异步准备初期的旧快照。每个 sync 在交给执行入口前先从队列移除，执行失败不重试；没有对应 handler 时直接丢弃。一旦开始消费，剩余队列项即与这一次精确安装绑定：如果安装在中途退休，直接丢弃剩余项；之后重新安装时从最新网络状态起步，绝不重放已消费过的事件。选择被替换、实体退休、业务会话失效或断连时，立即清空旧队列。暂留中的旧安装 A 绝不接收新选择 B 的状态值或事件。
