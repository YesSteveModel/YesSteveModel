# 一致性

本页规定格式与协议的一致性等级及 fixture 要求。各等级逐级递进，通过较低等级不代表同时满足较高等级。

## Conformance profile

| Profile | 必须证明 | 不证明 |
|---|---|---|
| C1 Container Metadata | magic、summary、header、properties、chunk table、版本与 verification 全部合法 | 普通 payload 是否存在或可用 |
| C2 Verified Preamble | 满足 C1，且输入恰好结束在 verification payload 末尾 | 外部普通 payload 是否可取得 |
| C3 Complete Container | 满足 C1，且所有内联 payload、零 padding、decode size、hash 与精确 EOF 均合法 | 上层 schema 语义是否正确 |
| M1 Model Metadata | 满足 C1 或 C2；Manifest payload、schema、版本、vendor、modelHash、target、按 target 类型要求的纹理数量、preview 不变量以及 blob 引用角色均合法；同时列出所有不支持的扩展 | 引用到的 payload 可加载，或扩展可消费 |
| M2 Complete Model | 满足 M1、完整引用闭包、全部资源与结构校验，且不依赖本标准未冻结的扩展 | 渲染器视觉输出或平台设备行为 |

实现必须在校验结果中明确标注实际达到的 profile。Metadata-first、延迟加载和外部 payload 数据源都只是执行策略，不得把只达到 M1 的结果标成 M2。

## 必需 golden fixtures

在格式冻结前，必须提交可供多个实现复用的二进制 fixture，运行时临时生成的自测 round-trip 不能替代它们。最小集合包括：

1. 一份 M1 metadata-only 描述及对应的 verified preamble；其中引用的普通 chunk 可以尚未提供实体数据，但必须明确列出。
2. 一个最小 M2 player 模型，包含 player definition、`main` 与 `arm` 两个 GeoModel，以及至少一张 UV 纹理。
3. 一个包含直接媒体 payload、非零对齐（alignment）和多个 blob 的 C3/M2 文件。
4. 一个包含 zstd 压缩的 definition 和字符串 blob 的 C3/M2 文件。
5. 与同一份完整文件对应的 C2 preamble，以及按 chunk 拆分的外部 payload。
6. 每个 fixture 的预期解析摘要，涵盖容器版本、preamble 长度、各 chunk 的 offset、size、decodeSize、hash，以及 modelHash 和目标 profile。
7. 固定的 BLAKE3 空输入和短输入向量、zstd 逻辑内容 hash 向量，以及包含 packed scalar 和嵌套 GeoModel、Cubes bytes 的 Proto fixture。Model Schema 中每个 repeated numeric 或 bool 基础类型字段都必须在源码与规范性快照中显式声明 `[packed = true]`，fixture 必须验证其采用 length-delimited packed wire 格式。
8. 显式 `optional` 字段的三种状态（缺失、显式设为默认值、显式设为非默认值），空的 repeated 与 map，以及真实 `oneof` 的各个合法分支。

至少两个独立实现必须能消费同一组可移植 fixture。Golden 字节只能随着显式的格式版本裁决一起替换，不能在测试时由被测序列化器每次重建后再与自己比对。

## 必需负例

负例测试至少应覆盖以下破坏场景：

- 错误的 magic、缺少 summary 终止符、超长或非法 UTF-8 summary，以及非法或含非 ASCII 字符的固定字符串和短字符串。
- 不支持的 major 或 minor 版本、非法 header size、负计数或负长度，以及 offset 加法或对齐计算溢出。
- property 或 chunk 重名、区域末尾残留多余字节、verification 不在第一项，以及非法的 alignmentShift。
- verification 使用旧版 32-byte payload，或签名长度、encoding、hash 错误；普通 chunk hash 错误；zstd 依赖外部 dictionary、`decodeSize` 不符、数据截断或含非 zstd 尾随垃圾字节。
- alignment padding 中出现非零字节、payload 截断、C3 完整文件存在尾随数据，或 C2 preamble 存在多余字节。
- schema id、字面版本或 vendor 错误，以及 Manifest 缺失、为空或为非法 Proto3。
- `ModelId` 非规范格式、超过 194 bytes、命名声明（owner 与 name）不完整或 SemVer 非法；player target 缺失或错配、target id 重复；player 纹理为空，或已有纹理条目的 key、图像及引用不变量损坏。
- preview source 声明与命名缩略图是否存在相冲突；blob、图像、definition、字符串或声音引用缺失，或同一 blob 被赋予冲突的角色类型。
- RGBA 字节长度不符或尺寸乘法溢出、外部图像探测结果与 descriptor 不一致，以及多帧图像进入可移植 profile。
- 骨骼名、父骨骼引用、父子环、向量长度、有限数约束、cube 数量，以及 cube 数组长度或索引越界错误。
- 不符合 Opus-only profile 的声音和 ZTX 扩展不得被误报为 M2；ED25519 verification 与未知图像 token 不得被静默降级。

每个负例应只破坏单个不变量，并明确断言在预期的层级失败，不能只断言“最终某处抛出了异常”。

## 边界 fixture

当实现跨越语言、进程、JNI、IPC 或其他边界时，必须使用正式生产者输出的字节，经过真实的打包与消费入口验证，不能只让同一侧解析器自产自读。测试至少要证明：

- 模型与资产 fixture 使用正式写入器和容器生成的精确字节，并能由基于完整 import 闭包的独立 Protobuf 实现解码。比对逻辑值时，独立实现重新序列化的结果不能反过来替代原始 golden 字节。
- `ModelData` 中的 `GeoModel` 字节能完成二次解析，`GeoModel` 中的 `Cubes` 字节也能继续解析。
- Packed scalar 能跨实现正确互操作。声明本 Model Schema 的生产者不得为 repeated numeric 或 bool 基础类型字段发送 unpacked 编码；读取方是否额外宽容这种等价的 Proto 编码不属于当前 profile。未知的 Proto 字段可以跳过，不要求重编码保留；未知 enum 值不得解释为已知策略；新增字段或 enum 值只能随经过裁决的新版本 fixture 一起出现。
- 非法的骨骼层级、cube 数量、数组长度和索引在进入渲染器前即中止并拒绝（fail closed）。
- 每种媒体格式按解码与编码方向、运行平台以及最终的 RGBA8 像素结果分别验证。

若实现将不可变的生成消息直接作为发布值，还需覆盖以下内部所有权与严格校验要求（用于验证内部隔离，不替代上述跨实现 wire fixture）：

- 已发布的 repeated 和 map 通过直接调用、迭代器、entry 及回调等所有修改路径均不可写。
- 自有字节在输入底层缓冲区被改写、游标移动或输入拥有方关闭后，仍保持内容与重序列化结果稳定。
- 每次 accessor 调用都返回只读、位置归零且游标独立的视图。
- 当前严格 profile 必须覆盖未标注 `optional` 的普通单值字段缺失时 build 或 parse 失败，以及所有显式 `optional` 字段的三态行为，不得通过 partial message 规避校验。

当前产品已经具备的能力和尚缺的测试，统一记录在[当前支持与验证](../status/support-and-verification.md)；实现状态不能反向修改本标准。

## 当前网络协议

| Profile | 必须证明 |
|---|---|
| P1 Frame | 单字节 `messageTag`、simple 与 full 帧、小端序 header、Protobuf 与 zstd 编码、attachment、大小上限，以及对未知输入的拒绝 |
| P2 Model Session | 满足 P1，且符合[当前协议](protocol-v1/README.md)的精确 Hello 版本、每条连接单一 session、四个 collection 的强类型 full 与 delta、七种强类型 fragment、三种模型分发请求、业务本地边界与完成条件、授权以及选择边界 |
| P3 Gameplay State | 满足 P2，且覆盖真实 FULL 基线、不带代际字段的表现消息、尽力投递（best-effort）失败边界、控制消息方向与连接隔离 |

P1 的 golden 至少要覆盖同一条消息的四种封装形态：simple frame、未压缩 full frame、zstd 压缩 full frame，以及带 attachment 的 full frame，并写明 message tag、方向、三个小端序长度、解码后的 Proto 内容和 attachment。负例至少覆盖未知或保留的 ID、未知 flag、错误方向、截断、声明长度与实际长度不符、zstd 尾随数据、解压长度越界，以及严格的 30 KiB 与 1 MiB 边界。

P2 必须覆盖以下当前契约：

- **Session 与连接身份**
  - 覆盖 Local、无 YSM channel、协商中（negotiating）、活跃（active）、仅内置默认（intrinsic-default-only）和已关闭（closed）路径。
  - 待确认的 Hello 重试必须复用同一份 offer；session 活跃后的 ping 不替换 owner；wire 层不携带 connection 或 session ID。
  - Publication 与模型分发各自维护连接内的非零 `uint64` ID 流，按无符号整数单调递增且不复用，需覆盖首次 full 使用 ID 1、达到最大值后耗尽、已终止 ID 不重开，以及连接替换后重置。
- **Collection publication**
  - `SessionFullFragment` 与 `SessionDeltaFragment` 只承载 catalog、grants、pack presentation 与 default animation；full 必须显式覆盖全部四个 collection，delta 只允许合法的 `CLEAR`、`REMOVE`、`ADD` 并遵守规范的 collection、操作与 key 顺序。
  - 覆盖分包数、记录数和字节数上限、序号连续覆盖、重复包、冲突包与 final 标记、删除不存在项的空操作（no-op）、仅基线漂移时的整体丢弃，以及协议或领域非法时的 fail closed。
  - Collection 事务既不包含也不等待模型选择；ID 17 的 `PlayerStateUpdate.model` 与 `ModelSelectionState` 仍是唯一的选择同步路径，`SelectModelResult` 只返回处理结果。
- **强类型模型分发**
  - `MetadataPrefixRequest`、`ModelChunkRequest` 与 `PresentationPageRequest` 各自用一个 `data_transfer_id` 对应一次独立的接纳、取消和终止状态。
  - 只要完整 descriptor 能装入单个物理帧，就只能建立一个 transfer；只有 descriptor 超出单帧上限时，原业务 owner 才按规范顺序拆分为有限个子请求（children）。各子请求独立接纳，允许部分成功、部分被拒；父级只聚合结果，不拥有子请求的底层数据，也不在 wire 层产生父子或成员身份。
  - 覆盖 metadata prefix 按容器编号的分包序号，chunk 与 preview、icon、pack-cover 的强类型字节范围和交付类型，各自的成员数、分包数和字节上限，重复、重叠、缺口和 final 标记，必需项、可选项与缓存命中，兄弟任务失败或取消、迟到结果、精确缓存保留，以及所有必需项完成后仅发布一次。
  - Preview fixture 还需验证请求只携带 slot、model 与 container、字段 4..8 为保留字段、结束偏移由唯一 final 动态确定，以及单图 16 MiB 与实收聚合 128 MiB 的限制；icon 与 cover 继续走 descriptor 和 hash 校验路径。
- **接纳、权威与分发**
  - 服务端在接触数据源、native 调用、大块内存分配或长期保留前，必须先完成全部结构校验，再检查当前 catalog、精确 descriptor、授权（grants）与当前内容策略，不能依据模糊的内存状态或队列快照做接纳判断。
  - 业务 transfer owner 必须在获取任何数据源之前建立，单个子请求的完整发包集合只经过一次全局 dispatch 的 `enqueue()` 交接点；构造失败、硬上限拒绝或 `enqueue()` 异常都不转移所有权，一旦接纳，数据源、读取游标、重试和关闭责任全归 dispatch owner。
  - 字节缓冲、已校验 chunk lease 和文件区间三种数据源都必须覆盖有界读取、同游标重试、完整重组和唯一终态。
  - 服务端对 collection 权威状态的提交必须早于 full 或 delta 的编码与入队；发送失败不回滚当前权威状态，后续 delta 以最新权威状态为基线。
- **激活与缓存**
  - 覆盖先确立权威再激活、精确元组未变时直接复用、替换场景下拦截过期完成结果，以及活跃实例、本地源、缓存和服务端四种激活路径。
  - 本地同 `ModelId` 的非精确复用不得发起网络请求，远端路径则严格要求精确元组；同时验证访问错误来源标记、单文件原子缓存提交和物理根目录边界隔离。本地扫描遇到同身份冲突时，由最先完成校验并被 owner 接纳的一方胜出，不要求固定的完成顺序。
  - Metadata、preview 和 icon 只要求当前 catalog 成员资格；普通 chunk 还受 public、grants 与当前内容策略约束，且接纳后的撤权不追溯修改已接纳任务。
  - Preview 还需验证内嵌图优先、`ContainerId` 缓存次之、缺失时返回相应空结果、服务端不执行烘培（bake）、可解码替换图被接受且坏图不触发删除；这些宽松规则不得外溢到模型本体、icon 或 cover 的校验中。
- **精确连接的关闭与隔离**
  - 启动新连接与断开连接复用同一条 session 清理路径；旧连接的 collection fragment、资源失败或结果、激活、选择、玩家与实体状态及通知，都不能作用于替换后的新连接，也不能关闭新连接的任务。
  - 合法且独立的缓存提交，以及不携带旧权限的完整 Ready 结果可以跨 session 保留；查询表、未命中与失败记录及请求状态必须随 session 释放。
  - Dispatch 的断开调用返回后，不得再发送旧连接的任何数据包；连接关闭不删除持久缓存、本地完整 catalog、强制选择或共享运行时。
  - 基线漂移与内置默认无效触发的本地系统消息，仍需覆盖单连接去重、重连重置、拒绝旧 owner、固定 translation key、发送失败不传播不重试不改权威，以及无关失败不消耗通知额度。

P3 必须覆盖以下要求：

- 在尚未建立真实 FULL 基线前拒绝 `PlayerStateReport` DELTA；FULL 成功应用后建立基线；report、update 与 starred snapshot 的 descriptor 不包含代际字段。
- 针对客户端本地提交失败、服务端接收或处理失败、以及逐观察者广播失败，都要证明本次机会结束后不留存重试队列、待处理项、失败标记、待广播缓冲或恢复状态。
- 覆盖协议允许的遗漏、重复、延迟与旧合法内容覆盖、连接替换以及迟到输入。
- 真实 transport 测试必须使用保存好的 Proto 与 frame golden，不能用同一套运行时代码既产出数据又生成预期值。
- 同构建的 full、delta、资源和玩家状态链路、真正的版本不匹配，以及二次登录的单 session 约束，需要分别提供真实 Forge 双端观测证据。

## 完整身份与 short ASCII 向量

身份 fixture 覆盖 `hash:` 摘要身份、命名身份、版本号、大小写以及 build metadata。SemVer 中的数字段必须按任意精度处理，不得截断为 `int` 或 `long`。合法的最长身份由 32-byte owner、`/`、32-byte name、`@` 和 128-byte SemVer 拼接而成，总长为 194 bytes。

短 ASCII fixture 独立写出长度为 0、127、128、194、255 的值，并覆盖前缀字节 `0x00`、`0x7f`、`0x80`、`0xc2`、`0xff`，验证完整读取器与 preamble 读取器得到一致的后续 property、chunk table 偏移、verification 结果和 `ContainerId`。负例覆盖写入方 256-byte 超长输入、缺少长度字节、截断、跨区域越界、非 ASCII 字符和内嵌 NUL。跨语言生产者的实际输出还必须证明 Manifest 与 property 2 写入了完全相同的规范文本。

## 视觉一致性验收

视觉验收直接对应产品决策中的各项语义，包括[创作结果与输出等价](../product-decisions/decisions/geometry-regions.md)、[动画因果关系](../product-decisions/decisions/animation-sampling.md)、[附着内容](../product-decisions/decisions/attachments.md)、[游戏视觉反馈](../product-decisions/decisions/game-visual-signals.md)、[分类降级与恢复](../product-decisions/decisions/model-fallback.md)、[骨骼透明度](../product-decisions/decisions/bone-opacity.md)、[透明保证范围](../product-decisions/decisions/transparency-scope.md)与[联动能力](../product-decisions/decisions/mod-integration.md)。具体的面剔除规则与观察范围由[渲染设计](../architecture/rendering/overview.md#几何与可见面)定义，下表不扩大产品支持范围。验收时固定游戏版本、联动组合与光照条件，比对整体视觉表现和对应语义，不设跨环境逐像素完全一致的硬门槛。

| 验收类别 | 必须覆盖的内容 |
|---|---|
| 几何与姿态 | 模型形状、UV、骨骼姿态继承、完整与不完整几何、正尺寸与三轴负尺寸的面剔除 |
| 材质 | 四个渲染区域、透明表现、骨骼透明度的作用范围，以及受支持的 PBR 与发光效果 |
| 附着层 | 主副手持物、鞘翅、披风、肩部鹦鹉、零缩放隐藏，以及 `render layers first` 配置的效果 |
| 状态反馈 | 环境明暗、着火火焰、受击覆盖色和玩家发光轮廓 |
| 降级与恢复 | 类人（humanoid）默认模型回退、投射物与载具恢复宿主模型、普通降级后仅由显式重试、重载或相关条件变化恢复资格，以及默认兜底失败在开发期的排除与启动时提前暴露 |

AI 验证负责整体视觉效果与基础特性检查，半透明排序、PBR 和发光等高级特性由人工验收。基础检查通过不代表高级效果已验收；无论采用何种验证手段，都不改变作品语义和兼容责任。

动画调度检查涵盖不同 delta time 与更新频率下的整体表现和因果关系、跨多个指令帧的推进序列、短动画重新播放，以及多 pass 渲染下的观察。插值、混合和控制覆盖只是具体示例；验收不能简单把“相同墙钟时间内动作触发总次数相同”当作统一判定标准（oracle），而应检查对应播放生命周期内事件是否按序、无遗漏且不重复。
