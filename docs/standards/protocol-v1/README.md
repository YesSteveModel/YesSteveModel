# 当前网络协议

当前应用与 Forge transport version 均为 `0.5.0-unstable`。全部消息使用统一的 `ysm.network` Proto package；入口为 [model session](proto/network/model_session.proto)、[common](proto/network/common.proto)、[player state](proto/network/player_state.proto)、[control](proto/network/control.proto) 与 [Minecraft state](proto/network/minecraft_state.proto)。Proto package 不替代 transport version。当前或对端 transport qualifier 大小写不敏感地包含 `unstable`、`dev` 或 `snapshot` 时，兼容判断要求两个完整原始版本字符串严格相等；两侧均不含这些标记时才委托 protocol 的稳定版策略，当前该策略仍为 exact。

协议尚未冻结；client 与 server 必须来自同步构建。实现只保留当前 reader/writer，不协商、探测、双读或兼容 `0.2.0-unstable` wire。

## Frame

首字节固定为：

```text
messageTag = (messageId << 1) | frameKind

bit 7..1  messageId      # 0..127
bit 0     frameKind      # 0 = simple, 1 = full
```

Simple frame 用于无 attachment 的短 protobuf：

```text
uint8  messageTag
byte[] rawProtobuf
```

`rawProtobuf` 必须小于 30 KiB，不压缩且必须被 parser 精确消费。

Full frame 用于较大 protobuf 或 attachment：

```text
uint8  messageTag
uint8  flags                       # bit 0: protobuf 使用 zstd
uint32 decodedProtoSize            # little-endian
uint32 storedProtoSize             # little-endian
uint32 attachmentSize              # little-endian
byte[storedProtoSize] storedProto
byte[attachmentSize] attachment
```

约束如下：

- `storedProtoSize + attachmentSize < 30 * 1024`；tag 与 13-byte full header 不计入 body。
- `decodedProtoSize <= 1 MiB`，同时不得超过注册消息上限。
- 未知 flag、负值、加法溢出、声明长度与实际剩余 bytes 不一致均在分配、解压和 Proto parse 前拒绝。
- protobuf 大于 64 bytes 时 producer 才尝试 zstd level 10；只有结果严格更小时设置 bit 0。Attachment 不做协议层压缩。
- zstd 不使用 dictionary，必须精确产生 `decodedProtoSize` 并拒绝尾随压缩数据。Raw protobuf 的 decoded/stored size 必须相同。
- Proto parser 与 frame decoder 都必须精确消费各自边界。所有多字节 wire 整数均为 little-endian。

Frame transport 不解释 transfer、resource key、member、sequence、offset、completion、authorization 或 publication。Forge 外层压缩可以绕过，但不能改变上述 YSM frame 边界。Transport version 不匹配只关闭 YSM session 并进入 intrinsic-default-only，不主动断开 Minecraft connection。

## 消息注册

Model session 的 decoded Proto 上限均为 1 MiB：

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
| 9 | C2S | `SelectModelRequest` | forbidden |
| 10 | S2C | `SelectModelResult` | forbidden |
| 11 | S2C | `MetadataPrefixFragment` | forbidden |
| 12 | S2C | `ChunkFragment` | required |
| 13 | S2C | `PreviewFragment` | conditional |
| 14 | S2C | `IconFragment` | conditional |
| 15 | S2C | `PackCoverFragment` | conditional |

其余消息使用以下 identity、方向和上限：

| ID | 方向 | Proto | decoded 上限 |
|---:|---|---|---:|
| 16 | C2S | `PlayerStateReport` | 128 KiB |
| 17 | S2C | `PlayerStateUpdate` | 128 KiB |
| 18 | S2C | `StarredModelsSnapshot` | 768 KiB |
| 19 | C2S | `UpdateStarredModelRequest` | 1 KiB |
| 21 | S2C | `PrivilegedTransientEvent` | 64 KiB |
| 22 | C2S | `SubmitConfigActionRequest` | 8 KiB |
| 23 | C2S | `EmitMolangSync` | 1 KiB |
| 24 | S2C | `MolangSyncEvent` | 4 KiB |
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

20、37..127 当前未注册，必须在进入业务 handler 前拒绝。ID 35 必须携带 attachment，ID 29..34、36 禁止 attachment。每个 ID 的方向、Proto 类型和 attachment policy 都是 wire contract 的一部分。

Proto 中显式 `optional` 的 ordinary singular 字段区分 absent、present-default 与 present-nondefault；consumer 不能只读取默认 getter 后丢失 presence。Repeated/map 用 empty 表达无元素，real oneof 用 case 表达选择。对 PlayerState DELTA，section 或字段 absent 表示不修改该状态，present-default 表示把该值写为默认值；FULL、section reset 与无效组合仍按下文状态契约验证，不能用 partial message 绕过。

当前 exact-version、同步构建的 unstable profile 不承诺保留或 round-trip 未知 Proto 字段。Producer 只能发送已注册 schema 中的字段和 enum 值；consumer 可以跳过未知字段，但未知 enum、message ID 和 oneof 分支不得映射成已知操作。新增 wire surface 必须提升并裁决 transport/schema version，不能依赖旧 peer 透传。

## Expression action 与本地求值

模型中的执行程序不进入 wire。Config action 只携带 button/form 语义路由和类型化 payload；`EmitMolangSync` / `MolangSyncEvent` 只携带有界有限 F32。接收端始终在当前 entity 的本地 module 中按语义路由选择 handler，不把发送端 ProgramId、布局或产物当作本地地址。

`ExpressionTargetIdentity` 的 `schema_version = 2`。`model_id` 是已验证模型内容身份，`target_id` 是目标语义，`config_identity` 是有效 `Settings` 内容摘要，`catalog_identity` 是事件路由与参数契约摘要；`module_identity` 是这四项事实按 `ysm:shared-expression-semantics:v1` 计算的独立摘要。model content 同时承诺模型声明的 handler 路由。该投影不包含有效模组、组件类、实体 Class/profile、backend、codegen ABI、布局、替代报告、本地 catalog 编号或 ProgramId。schema 1 的 artifact 含义及未知 schema 均拒绝，不双读或猜测匹配。

`SubmitConfigActionRequest` 先由 server 以当前 connection、控制主体、模型、target、config、form/label 路由及共享语义身份授权，再转发 `ConfigActionEvent`。接收端复核当前 module 的共享语义身份后执行本地对应 handler；bool、有限 F32 与 radio label/index 不能互换。授权失败、stale connection/entity 或错语义不产生转发和效果。

`EmitMolangSync` 由已安装 module 产生身份，参数最多 16 个且全部为有限 F32。server 用自身已验证内容重建期望，而不相信发送方摘要；通过 connection、主体、模型和共享语义校验后，`MolangSyncEvent` 广播给可见客户端及发送者。接收端再次比较当前 module 后调用本地 `sync` handler。它仍是 best-effort 瞬时事件，不同步普通变量、Roaming 全量状态或骨骼姿态，也没有 ACK、重放、持久队列或安装握手。

## Connection 与 model session

发现 YSM channel 后，server 发送只含 exact protocol version 与 roaming policy 的 `ServerHello`，client 以 `SessionResponse` 接受或拒绝。Wire 不携带 `session_id`；来源玩家与 exact Forge `Connection` 是唯一连接 identity。每条 connection 至多有一个 model session：pending hello 重试复用同一 offer，session active 后 `/ysm ping` 只做无状态连通性探测，不替换 owner 或重置 publication/transfer 状态。

Client 将 `ACCEPT` 成功提交给当前 exact connection 后，C2S 业务消息即可进入发送门禁；server 只在同一 connection 的 model session 已处理该响应并进入 active 后执行。业务消息包括 Selection、PlayerState、收藏和控制请求，即 ID 9、16、19、22、23、25。该资格不等待 collection publication，也不因其 assembly、commit 或 activation 失败而撤销；ID 4..7 的 catalog/resource 请求仍服从已提交 publication 及各自 admission。非法 frame、协议不匹配、变更后的重复 Hello、断连或 connection replacement 会撤销业务资格。该顺序复用同一 connection 的 transport order 与 owner-thread active 复核，不新增 wire ACK、generation、重放或待发队列。

Model-session publication 与 client 发起的 model distribution 各自拥有 connection-scoped、非零、按unsigned顺序单调且不复用的 `uint64` ID stream。首次 publication 必须是 transfer ID 1 的 full；新 connection 才重置 stream。合法范围是`1 ... 0xffff_ffff_ffff_ffff`，只有发出unsigned maximum后才耗尽；此后拒绝新work并要求重连，不wrap到zero，也不引入generation、history、ACK、replay或resync。

## 公开目录地址

Catalog 模型位置、pack key/移除项、pack-cover selector 和缺图清单位置使用同一完整地址：`global:custom/<entry>`、`global:auth/<entry>`、`local:custom/<entry>`、`local:auth/<entry>` 或 `builtin:<entry>`。模型 entry 非空，文件入口保留扩展名，raw 入口保留目录名。目录地址可指向 `global:custom`、`global:auth`、`local:custom`、`local:auth` 或 `builtin:`，末尾不带 `/`。地址合法不证明对象存在。

`default` 是 intrinsic default 的唯一规范地址，内部来源仍为 builtin。`default` 不随服务端默认模型配置改变指向。输入解析优先识别此保留地址；同名用户模型应使用完整地址。`builtin:default` 与旧 `$YSM_*` 占位符地址无效。

命名空间前缀区分大小写，以 `:` 结束，不使用 `//`。路径部分以 `/` 分段，保留大小写、空格和 Unicode scalar 序列。不 trim、折叠大小写、归一化 Unicode、解码百分号或展开环境变量。路径部分拒绝空段、`.`、`..`、反斜杠、冒号、U+0000–U+001F、U+007F–U+009F 和孤立 surrogate；未知命名空间与旧地址别名无效。`%2e%2e` 和 `a..b` 是字面段名。完整规范串按 unsigned UTF-8 bytes 比较，不使用宿主路径规则。

地址只定位当前 publication，不能直接交给宿主路径 API。Catalog/grant 的业务 key 仍为 `ModelId`。Pack 以完整目录地址为 key；不同根的同相对 hierarchy 不合并，也不跨根回退 cover。远端模型位置与 access 均来自服务端 authority，本地同源内容替代不改写这些字段，也不授予客户端本机打开能力。公开诊断仅包含逻辑位置与失败类别，宿主路径和底层异常原文只进入本机日志。

## Collection publication

`SessionFullFragment` 与 `SessionDeltaFragment` 每个 packet 只包含一个 typed collection operation。接收端按 `transfer_id` 与 `sequence` 组装，要求一个 final、从 0 开始的完整 coverage，并在完整验证后一次发布。相同 physical duplicate 是 no-op；冲突 duplicate、gap、多个 final、final 后数据或旧/未知 ID 使 owner 按其失败语义终结。

接收端限制为每个 publication 至多 65,536 packets、128 MiB decoded Proto；catalog/grant 各至多 16,384 records，pack/default-animation 各至多 4,096 records。单个 packet 仍受 1 MiB Proto 与 frame `< 30 KiB` 限制。

Full 必须用 `FULL` 显式覆盖 catalog、grants、pack presentation、default animation 四个 collection，包括空 collection。Delta 只允许 `CLEAR`、`REMOVE`、`ADD`：`ADD`/`REMOVE` payload 必须非空，`REMOVE` 只携带自然 key，`CLEAR` 无 payload。Collection 顺序固定为 catalog、grants、pack、default animation；delta op 顺序固定为 `CLEAR`、`REMOVE`、`ADD`。同一 collection/op 的 packets 必须连续，key 在 packet 内及跨连续 packet 按 hash unsigned bytes 或规范完整目录地址的 unsigned UTF-8 bytes 严格递增，任何重复或冲突都拒绝。Grant operation 每个 producer packet 只携带一个 32-byte `ModelId`，因此保持未压缩 simple frame；其他 collection packet 可使用物理 frame 压缩。

Collection publication 不包含、等待、提交或协调 selection。Selection 继续只由既有 ID 17 `PlayerStateUpdate.model` / `ModelSelectionState` 同步；ID 9/10 只处理选择请求及 disposition，不是第二条同步 authority。

`CatalogPublication.runtime_content_allowed` 是 server 为本接收方投影的普通内容使用资格，缺省为 false。该字段不授予选择权限。Server 按当前 public/grant、`RESTRICTED_AUTH` 与既有本接收方强制选择例外求值；资格变化通过同一 catalog key 的 `ADD` 替换。Client 在本地内容或 cache 支持的新 acquire 与新音频播放前检查该字段；资格变化不撤销已接纳取得或已安装 lease，也不改变 `(ModelId, ContainerId, path, access)` 的内容身份。

## 显式管理补图

`/ysm model gen-preview` 只允许 level 2 的在线玩家，且执行方当前 exact connection 必须有 active model session 与已发布目录。Server 仅发送 `GenPreviewStart`；client connection 独占一个批次，重复启动立即反馈 BUSY，GUI 开关不影响它。

三种独立请求共享 typed distribution 的非零 unsigned 单调 ID stream、高水位与 `ResourceTransferCancel/Failure`：

| 请求 | 接纳资格与结果 |
|---|---|
| `MissingPreviewQuery` | 当前 level 2、active/published session。探测固定 published catalog；按 `(ModelId, ContainerId)` unsigned 排序，仅返回缺图及探测失败项，后者携带 `probe_failure = UNAVAILABLE`。清单不成为后续内容或上传的资格 |
| `ModelChunkRequest(GEN_PREVIEW_MAIN)` | 当前 level 2、当前 exact R、仍缺图；全部 descriptors 必须属于可信模型 view 规划的主 `player` target definition、实际默认纹理/PBR、common strings、声音首块闭包。可无选择 grant；不放宽普通用途、其它 target、非默认纹理或声音尾块 |
| `PreviewUploadRequest` | 当前 level 2、当前 exact R、仍缺图及有限容量。声明 `encoded_size` 为 1..16 MiB；通过后同 ID 返回 admission 才接收图片。无需先 query 或取得管理 chunks |

清单 sequence 从 0 连续覆盖且唯一 final，空清单也发送 final；上限为 16,384 entries、65,536 packets、128 MiB decoded Proto。Entry 的 R、[完整公开模型地址](#公开目录地址)、failure enum 与跨帧严格排序均须验证；相同 duplicate 不再次计价，冲突/gap 失败，不处理不完整选择集。

Upload header/admission/fragments/result 是同一请求。单图至多 16,384 fragment records；附件按 offset 从 0 连续覆盖，final 当且仅当结束 offset 等于声明长度；相同 bytes 的 duplicate 是 no-op，gap、overlap、冲突 duplicate、越界和错误 final 使本请求失败。相同活动 header 不再次接纳；退休/未知旧 ID 不复活状态。完整图片按既有 PreviewStore 媒体界限验证，无图片 hash authority。

`PreviewRepairResult` 为 `COMMITTED`、`ALREADY_AVAILABLE`、`STALE` 或 `NOT_FOUND`；授权、容量、结构、媒体及存储失败使用既有 typed failure。COMMITTED 只来自持久原子替换。撤权仅影响后续请求；原 connection 结束、原目录成员移除/换 R 或移除重加则撤销尚未提交的效果资格。两个独立已接纳上传可依次提交，提交时不重查权限或缺图。成功后尝试向所有当前有效 session（含上传方）发送 ID 29；通知失败不更改 COMMITTED，也不建立 replay history。

Client 只保留完整固定清单、一个 current item 和有限逐项结果；顺序记录 success/failed/skipped，关闭时未启动项计入 unprocessed。每项先选有效编码图片，hit 不取得 body/target 或绘制并原样上传；miss 才经真实主目标生成。服务端只持独立请求的 assembly/source、额度及提交资格，没有清单资格、批次、Receiving、reservation 或 finish-stage。

## Typed model distribution

`ModelChunkRequest.purpose` 为 `ORDINARY` 或 `GEN_PREVIEW_MAIN`；用途不改变 descriptor/coverage 上限或 ID 空间。后者的窄授权见[显式管理补图](#显式管理补图)。

一个 `MetadataPrefixRequest`、`ModelChunkRequest` 或 `PresentationPageRequest` 对应一个独立 `data_transfer_id`、queue admission、cancel 和 success/failure/cancel terminal。完整 descriptor 能放入一个 physical frame 时必须只使用一个 transfer；只有 descriptor 超帧时，metadata/model/page action owner 才按 canonical member 顺序拆成有限个完整 child requests。Parent 没有 wire ID；children 独立接纳，允许 earlier success 后 later `BUSY`。内部聚合、取消与 backing 所有权见[资产传输](../../architecture/network/implementation/asset-transfer.md#typed-request-与-action-owner)。

请求和 receiver-local limits 如下：

| 业务 | member key 与 canonical order | 每 transfer 上限 |
|---|---|---|
| metadata prefix | 32-byte `container_id`，unsigned bytes 严格递增 | 1..16,384 members；16,384 fragment records；128 MiB retained bytes |
| model chunk | exact model/container identity + normalized chunk name，name UTF-8 bytes 严格递增 | 1..256 members；16,384 fragment records；128 MiB stored；512 MiB decoded |
| presentation page | typed `(preview|icon|pack-cover, slot)`，各 typed list slot 严格递增；preview member 为 `slot/model_id/container_id`，icon/cover 保留 descriptor | 1..256 members；16,384 fragment records；128 MiB actual stored；非 preview descriptor decoded 合计 256 MiB；单 preview 16 MiB encoded、4096 单边、16 Mi pixels |

Metadata、model chunk、icon 与 pack cover descriptor 中每个 hash 固定 32 bytes，stored/decoded size 为正且算术不得溢出；chunk name 必须 canonical；pack hierarchy 必须是[完整公开目录地址](#公开目录地址)，并精确匹配当前发布的 pack 与 descriptor。Preview 不携带 name/hash/size/encoding，只验证 slot、32-byte model/container identity 与 canonical order。Server 在推进 connection-local high-water 和触碰 source/native/allocation/retention 之前完成完整 structural validation；推进 high-water 后再按当前 session 验证 catalog identity、authorization、允许的 presentation selector 与适用 source facts。整份业务请求验证完成后才创建 source plan。

`IconMember` 复用同一 descriptor 表达模型 icon，以及已验证 Manifest 明确引用的作者头像、GUI 前景和背景。服务端按当前 exact model/container 校验该展示白名单及 chunk descriptor；普通 image/blob 或运行纹理不能凭相同请求类型取得展示访问。该用途不增加 wire 字段、选择权限或运行内容授权。

Metadata 使用 per-container sequence；model chunk 与 presentation data 使用 byte offset。Receiver 只保留已请求的 typed member：相同 duplicate 是 no-op，冲突 duplicate、overlap、越界、coverage gap、非法 final 或未知 member 使 child terminal。Presentation delivery 的 wire 值为 UNSPECIFIED=0、DATA=1、UNAVAILABLE=2、ABSENT=3。每个 slot 必须是 exact DATA coverage 或 `(offset=0, final=true, empty attachment)` 的合法领域空结果：preview 仅允许 ABSENT，icon/cover 仅允许 UNAVAILABLE；错类型、混合 data/空结果或未知 delivery 使原 child 失败。

ABSENT 要求服务端已验证当前精确成员、合法内容没有声明内嵌图且对应独立 cache miss；已声明坏图和实际读取/传输失败不能转成 ABSENT。Preview DATA 只表示发送完整编码字节，不表示服务端已验证独立图可解码。Preview 的唯一 final range 动态确定 encoded size；完整 coverage 和客户端媒体解码通过后只按 `ContainerId` 提交独立 cache，不要求图片 hash、模型绑定或服务器隔离。客户端按请求原 remote Manifest 区分来源：完整独立图的确定性媒体拒绝按当前 session 缺图处理，内嵌图拒绝仍为失败；传输、访问和运行环境失败不能降为缺图。该分类不新增 wire 字段或客户端坏图报告。Icon/cover 及 model/metadata 仍须通过 descriptor stored/decode boundary、hash、container/Manifest identity 与格式验证。

每个 child 独立接纳；容量拒绝返回一个 `BUSY`，不同 children 不作跨 transfer 原子接纳或回滚。内部 enqueue 所有权交接、source/cursor 与锁外关闭机制见[资产传输](../../architecture/network/implementation/asset-transfer.md#全局-dispatch-owner)。

`PreviewAvailable` 仅携带 32-byte `model_id` 与 32-byte `container_id`，无附件、图片 revision 或授权字段。接收端只使当前 exact connection 中匹配成员的缺图观察及通知前请求的缺图发布资格失效，包括在途 ABSENT 和 DATA 解码拒绝；未知或已移除身份无效果，非法 hash 宽度为 session-local protocol failure。过时缺图结果仍须取消终态。通知不改 catalog 或主动拉图；提交广播的来源与持久成功由管理写入 owner 承接。

## 安全与能力边界

主动对抗对象是恶意客户端。Serverbound frame/request 必须先验证 ID、方向、attachment、count、length、hash width、算术、typed selector、canonical path、compression descriptor、authorization 与当前 source facts，再允许 client-controlled 值驱动 source 获取、大 allocation、解压、native work 或长期 retention。拒绝不能创建无 owner capability，也不能影响其他 connection/session。

Client 信任已连接 server 的业务选择，但仍把远端内容当作有界数据：

- metadata、chunk 与 preview 只进入固定 container/Proto/image parser、本地 Molang 解析与求值边界、BLAKE3、zstd、bake 和 render operation；preview 的弱关联不放宽其它内容验证。模型与远端 packet 不能提供 JVM class、loader path、Java member descriptor、provider object 或任意本地 cache path，远端字符串也不选择 native library、plugin 或反射 capability；
- 远端只提供已验证 hash、固定 encoding selector 或 canonical pack hierarchy，不能提供 host path；remote cache 的物理写入隔离与 pack 的逻辑来源验证分别见[Storage](../../architecture/model-management/storage/overview.md)和[资产传输](../../architecture/network/implementation/asset-transfer.md#server-admission-与-source-closure)；
- 没有协议字段可直接触发 process/shell execution、动态加载、JNDI/naming/expression lookup、external entity/resource resolution、任意 URL/network resolution或任意宿主路径；
- 日志使用参数化 data，不把远端值作为 lookup/interpolation template。

这是 process-capability isolation 与普通内容正确性边界，不是对恶意 server 的 bandwidth、CPU、累计 transfer 或内存耗尽防护。协议不增加 aggregate connection cap、timeout、sandbox、policy engine、recovery runtime，也不声称能够排除依赖当前或未来的未知漏洞。

## 选择、授权与状态顺序

Remote catalog 可展示未授权 entry；metadata、preview 与 icon 要求当前 catalog membership，普通 chunk 与普通选择按当前 catalog、grants 和 `restricted_auth` 接纳。Admission 后撤权只阻止新请求，不改写 accepted transfer；产品边界见[model-authorization](../../product-decisions/decisions/model-authorization.md)。Server-private forced-selection 的当前行为与未决例外见[Catalog 同步](../../architecture/network/implementation/catalog-sync.md#selection-与-request-admission)。

ID 9 `SelectModelRequest.intent_id` 为 field 4、`uint64`；ID 10 `SelectModelResult.intent_id` 为 field 2、`uint64`。0 无效，server 在任何选择效果前拒绝；每个有效请求的 ACCEPTED、UNAUTHORIZED、NOT_FOUND、INVALID_REQUEST 原样回显该值。Client 在 exact connection 内为每个新选择递增分配非零值，同目标未完成重复点击复用；`2^64-1` 后不得回绕复用，结束本次发送资格并报告失败。Java signed long 仅承载原始 64 bits，负数不是非法 ID。

Client 仅将当前 connection、当前未结束 intent 的 ID 10 归入对应反馈；迟到、旧连接、0 或未知 status 不制造成功。ACCEPTED 只表示 disposition，不是安装完成。该字段不进入 ID 17，不提供排序、可靠投递或第二条 selection authority；ID 17 仍按到达机会应用实际模型状态。

每个 client session 的首个已接纳 `PlayerStateReport` 必须是 FULL；同一 exact model session 保存这个真实 baseline，没有 baseline 的 DELTA 必须拒绝。DELTA 只修改出现字段，后续周期 FULL 是独立机会，不因先前失败立即重发。`PlayerStateReport`、`PlayerStateUpdate` 和 `StarredModelsSnapshot` 不携带 sequence/revision；本地提交与 peer 接纳分开解释，合法消息可以遗漏、重复、延迟或按不同到达顺序应用。发送、接收处理或广播失败都不得创建 ACK、重放、待发、失败恢复或回滚状态。`ModelId` 必须恰好 32 bytes。
