# 一致性

本页定义格式与协议的一致性等级和 fixture 要求。通过较低等级不代表通过较高等级。

## Conformance profile

| Profile | 必须证明 | 不证明 |
|---|---|---|
| C1 Container Metadata | magic、summary、header、properties、chunk table、version、verification | ordinary payload 存在或可用 |
| C2 Verified Preamble | C1，且输入恰好结束于 verification payload | 外部 ordinary payload 可取得 |
| C3 Complete Container | C1，所有 inline payload、padding、decode size、hash 与精确 EOF | 上层 schema 语义 |
| M1 Model Metadata | C1 或 C2、Manifest payload、schema/version/vendor、modelHash、target、按 kind 裁决的 texture cardinality、preview 不变量与 blob 引用角色相容；列出所有 unsupported extension | 引用 payload 可加载或 extension 可消费 |
| M2 Complete Model | M1、完整引用闭包、全部资源与结构校验，且不依赖本标准未冻结的 extension | renderer 输出或平台设备行为 |

实现必须在结果中携带实际 profile。Metadata-first、延迟加载和外部 payload source 是执行策略，不能把 M1 标成 M2。

## 必需 golden fixtures

格式冻结前必须提交可跨实现复用的二进制 fixture；运行时临时生成的 round-trip 不能替代它们。最小集合为：

1. 一个 M1 metadata-only descriptor 与对应 verified preamble；其普通引用可以尚未物化，但必须明确列出。
2. 一个最小 M2 player 模型，包含 player definition、`main`/`arm` GeoModel 和至少一个 UV texture。
3. 一个含直接媒体 payload、非零 alignment 和多个 blob 的 C3/M2 文件。
4. 一个含 zstd definition/string blob 的 C3/M2 文件。
5. 与同一完整文件对应的 C2 preamble 和逐 chunk 外部 payload。
6. 每个 fixture 的 container version、preamble 长度、chunk offset/size/decodeSize/hash、modelHash、profile 和期望解析摘要。
7. 固定 BLAKE3 空输入/短输入向量、zstd logical-content hash 向量，以及含 packed scalar 和嵌套 GeoModel/Cubes bytes 的 Proto fixture；Model Schema 中每个 repeated numeric/bool primitive 必须在 source 与 normative snapshot 显式声明 `[packed = true]`，fixture 必须验证 length-delimited packed wire。
8. 显式 `optional` 字段的 absent、present-default 与 present-nondefault，empty repeated/map，以及 real oneof 的各合法 case。

至少两个独立实现必须能消费同一组 portable fixtures。Golden bytes 只能通过显式格式版本裁决替换，不得由被测 serializer 每次重建后自我比较。

## 必需负例

测试至少覆盖：

- 错 magic、无 summary 终止符、过长或非法 UTF-8 summary、非法或非 ASCII 固定/短字符串。
- 不支持的 major/minor、非法 header size、负计数/长度、offset 加法或对齐溢出。
- property/chunk 重名、区域剩余字节、verification 非首项、非法 alignment。
- verification 旧 32-byte payload、signature length/encoding/hash 错误、普通 chunk hash 错误、zstd dictionary 依赖、decodeSize 错误、截断或非 zstd 尾随垃圾。
- alignment padding 中含非零字节、payload 截断、C3 尾随数据、C2 多余数据。
- schema id、字面版本、vendor 错误，Manifest 缺失、空或非法 Proto3。
- modelHash 非 32 bytes、player target 缺失或错配、target id 重复、player texture 为空、已有 texture entry 的 key 或 image/reference 不变量损坏。
- preview source 与 thumbnail presence 冲突，blob/image/definition/string/sound 引用缺失或类型冲突。
- RGBA 长度或尺寸溢出、外部图像 probe 与 descriptor 不一致、多帧输入进入 portable profile。
- bone name、parent、cycle、vector、finite-number、cube-count，以及 cube 数组长度或索引错误。
- 不满足 Opus-only profile 的声音和 ZTX extension 不得被误报为 M2；ED25519 与未知 image token 不得被静默降级。

每个负例应只破坏一个不变量，并断言失败层级；不能只断言“最终某处抛错”。

## 边界 fixture

实现存在语言、进程、JNI、IPC 或其他边界时，必须用正式 producer 生成的 bytes 经真实 packing 和消费入口验证，不能只让同一侧 parser 自产自读。至少证明：

- Model/asset fixture 使用 production writer/container 的精确 bytes，并由基于 imports-complete descriptor set 的独立 Protobuf 实现解码；比较逻辑值时，独立实现的重序列化结果不能反过来替代原 golden bytes。
- ModelData 中的 GeoModel bytes 能二次解析，GeoModel 中的 Cubes bytes 能继续解析。
- Packed scalar 正确互操作；Model Schema producer 不得发送 unpacked repeated numeric/bool primitive occurrence，consumer 是否额外接受这种等价 Proto wire 不属于当前 profile。未知 Proto 字段可以跳过且不要求重编码保留，未知 enum 不得解释为已知策略；新增字段或 enum 值只能随已裁决的新版本 fixture 出现。
- 非法 hierarchy、cube count、数组长度和索引在进入 renderer 前 fail closed。
- 每种媒体格式分别验证 decode/encode direction、平台和最终 RGBA8 结果。

若实现以不可变 generated message 作为发布值，还必须另行验证 published repeated/map 的直接、iterator、entry 与 callback mutation route 均不可写；owned bytes 在 input backing 修改、cursor 移动或 input owner 关闭后仍保持值与重序列化稳定，且每次 accessor 返回只读、归零、cursor 独立的 view。这些测试证明实现内的 publication/ownership，不替代上面的跨实现 wire fixtures。当前 strict profile 还必须覆盖未标 `optional` 的 ordinary singular 缺失时 build/parse 失败，以及所有显式 `optional` 字段的三态；不能用 partial message 规避。

当前产品已具备什么、缺什么测试，由 [当前支持与验证](../status/support-and-verification.md) 记录；状态不能反向修改本标准。

## 当前网络协议

| Profile | 必须证明 |
|---|---|
| P1 Frame | 单字节 `messageTag`、simple/full、little-endian header、Protobuf/zstd、attachment、大小限制和未知输入拒绝 |
| P2 Model Session | P1、[当前协议](protocol-v1/README.md)的精确 Hello 版本、每 exact connection 单一 session、四 collection typed full/delta、七种 typed fragment、三种 model-distribution request、业务本地 bounds/completion、授权与 selection 边界 |
| P3 Gameplay State | P2、真实 FULL baseline、generation-free 表现消息、best-effort 失败边界、控制消息方向与连接隔离 |

P1 golden 至少包含同一消息的 simple frame、raw full frame、zstd full frame 与带 attachment 的 full frame，并明确 message tag、方向、三个 little-endian size、decoded Proto 和 attachment。负例至少覆盖未知/reserved ID、未知 flag、错误方向、截断、声明/实际长度不一致、zstd 尾随数据、解压长度和 strict 30 KiB / 1 MiB 边界。

P2 必须覆盖以下 current contract：

- Session 与 identity：Local、channel absent、negotiating、active、intrinsic-default-only 与 closed 路径；pending Hello 重试复用同一 offer，active ping 不替换 owner；wire 不携带 connection/session ID。Publication 与 model distribution 的 nonzero `uint64` stream 在 exact connection 内按 unsigned 顺序单调且不复用，覆盖首次 full ID 1、maximum 后耗尽、terminal ID 不重开及 replacement connection 重置。
- Collection publication：`SessionFullFragment` / `SessionDeltaFragment` 只承载 catalog、grants、pack presentation 与 default animation；full 显式覆盖四个 collection，delta 只使用合法 `CLEAR`、`REMOVE`、`ADD` 与 canonical collection/op/key order。测试必须覆盖 packet/record/byte bounds、sequence coverage、duplicate/conflict/final、missing remove no-op、baseline-only drift 整体丢弃，以及 wire/domain invalid fail closed。Collection transaction 不包含或等待 selection；既有 ID 17 `PlayerStateUpdate.model` / `ModelSelectionState` 保持唯一 selection 同步路径，`SelectModelResult` 只返回 disposition。
- Typed model distribution：`MetadataPrefixRequest`、`ModelChunkRequest` 与 `PresentationPageRequest` 各用一个 `data_transfer_id` 表达一个独立 admission/cancel/terminal。完整 descriptor 能放入一帧时只能建立一个 transfer；仅 descriptor 超帧时由原业务 owner 按 canonical order 拆成有限 children。Children 独立接纳，允许 partial admission；parent 只聚合 outcome，不拥有 child backing，也不产生 wire parent/member identity。测试必须覆盖 metadata prefix 的 per-container sequence，chunk 与 preview/icon/pack-cover 的 typed byte-range/delivery、各自 member/count/byte bounds、duplicate/overlap/gap/final、required/optional/cache-hit、失败或取消 siblings、迟到 outcome、exact cache 保留及 all-required 后唯一 publication。Preview fixture 还必须证明 request 只携带 slot/model/container、字段 4..8 reserved、结束范围动态确定、16 MiB 单图与 128 MiB 实收聚合限制；icon/cover 的 descriptor/hash 路径保持不变。
- Admission、authority 与 dispatch：Server 在 source/native/allocation/retention 前完成完整 structural validation，再检查 current catalog、exact descriptor、grants 与 forced-selection authorization；ambient memory 或 queue snapshot 不能参与 admission。业务 transfer owner 必须在任何 source acquisition 前建立，一个 child 的完整 packet 集合只经过一次 global dispatch `enqueue()` frontier；构造失败、hard-cap reject 或 enqueue exception 不转移 ownership，accepted source/cursor/retry/close 只归 dispatch owner。Bytes、verified chunk lease 与 file range 都必须覆盖有界读取、同 cursor retry、完整重组和唯一 terminal。Server collection authority 先于 full/delta 编码与 enqueue 提交，发送失败不回滚 current state，后续 delta 使用 current baseline。
- Activation 与 cache：覆盖 authority-before-activation、unchanged exact tuple reuse、replacement stale-completion guard、active/local/cache/server activation、same-`ModelId` local non-exact reuse 不联网、remote exact requirement、访问错误 provenance、single-file atomic cache 与 physical-root containment。Local scan 的冲突赢家按首个完成验证并由 owner 接纳观察，不要求稳定 completion order。Metadata/preview/icon 只要求 current catalog membership；普通 chunk 按 grants 或 current forced selection admission，admission 后撤权不追溯改变 accepted work。Preview 另覆盖内嵌优先、`ContainerId` cache 次之、`UNAVAILABLE`、服务端不 bake、可解码替换图接受与坏图不删除；这些观察不得放宽模型、icon 或 cover 验证。
- Exact-connection closure：begin 与 disconnect 复用同一 session 清理 seam；旧 connection 的 collection fragment、resource failure/outcome、activation、selection、player/entity state 与通知不能作用于 replacement，也不能关闭其 work。合法独立 cache commit 和不携带旧权限的完整 Ready 可以跨 session 保留；query table、miss/失败资格和 request state 必须随 session 释放。Dispatch disconnect 返回后不再发送旧 packet；connection close 不删除持久 cache、本地完整 catalog、forced selection 或共享 runtime。Baseline drift 与 intrinsic-invalid 的本地系统消息仍须覆盖 per-connection 去重、重连重置、旧 owner 拒绝、固定 translation key、发送失败不传播/不重试/不改变 authority，以及不相关失败不消费通知额度。

P3 必须覆盖无真实 FULL baseline 的 `PlayerStateReport` DELTA 被拒绝、FULL 成功应用后建立 baseline，以及 report/update/starred snapshot 的 descriptor 不含代际字段。客户端本地提交失败、服务端接收/处理失败和逐观察者广播失败都要证明本次机会结束后无重试、待处理、失败、待广播或恢复状态；还要覆盖允许的遗漏、重复、延迟与旧合法内容覆盖、exact-connection replacement 和迟到输入。真实 transport 测试必须使用保存的 Proto 与 frame golden，不能用同一套运行时代码同时生成期望值；同构建 full/delta/resource/player-state、真正 version mismatch 与 secondary-login 单 session 需要分别提供真实 Forge 双端 observation。

## 视觉一致性验收

视觉验收消费[创作结果与输出等价](../product-decisions/decisions/geometry-regions.md)、[动画因果关系](../product-decisions/decisions/animation-sampling.md)、[附着内容](../product-decisions/decisions/attachments.md)、[游戏视觉反馈](../product-decisions/decisions/game-visual-signals.md)、[分类降级与恢复](../product-decisions/decisions/model-fallback.md)、[骨骼透明度](../product-decisions/decisions/bone-opacity.md)、[透明保证范围](../product-decisions/decisions/transparency-scope.md)与[联动能力](../product-decisions/decisions/mod-integration.md)。具体剔除及观察范围由[渲染设计](../architecture/rendering/overview.md#几何与可见面)定义，场景表不增加产品支持范围。固定游戏、联动和光照条件，比较整体视觉效果与对应语义，不设跨环境逐像素相同的硬门槛。

| 验收类别 | 必须覆盖的内容 |
|---|---|
| 几何与姿态 | 模型形状、UV、骨骼姿态继承、完整与不完整几何、正尺寸与三轴负尺寸剔除 |
| 材质 | 四区域、透明表现、骨骼透明度的作用范围，以及受支持的 PBR、发光能力 |
| 附着层 | 主副手持物、鞘翅、披风、肩部鹦鹉、零缩放隐藏和 `render layers first` 的配置效果 |
| 状态反馈 | 环境明暗、着火火焰、受击覆盖和玩家发光轮廓 |
| 降级与恢复 | humanoid 默认模型回退、projectile/vehicle 宿主恢复、普通降级后仅由显式 retry/reload 或相关条件变化恢复资格，以及默认兜底失败在开发期的排除与 startup 提前暴露 |

AI 验证承担整体视觉效果与基础特性检查；半透明排序、PBR、发光等高级特性由人工验收。基础检查通过不能代表高级效果已验收；采用何种验证工具不改变作品语义和兼容责任。

动画调度检查包括不同 delta time 与更新频率下的整体表现和逻辑关系、跨多个指令帧的推进序列、短动画重新播放及多 pass 观察。插值、混合和控制覆盖只是示例；不能把相同墙钟时间下动作总次数相同作为统一 oracle，应验证相应播放生命周期的有序、无遗漏与不重复。
