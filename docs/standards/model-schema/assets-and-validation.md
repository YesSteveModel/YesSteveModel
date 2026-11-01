# 资产与验证

本页定义模型 payload、跨 chunk 引用和验证规则。Wire 布局以 [ModelData Proto](proto/asset/model/ModelData.proto)、[StringData Proto](proto/asset/strings/StringData.proto) 和它们的 import closure 为准。

## Chunk namespace 与引用

| Chunk type | Payload | 要求 |
|---|---|---|
| `manifest` | `Manifest` | 必需、非空 |
| `blob-N` | 由引用处确定 | `N` 为从 1 开始的十进制正整数 |
| `thumb-button` | 命名图像 | 由 thumbnail source 决定 presence |
| `thumb-icon` | 命名图像 | 由 icon source 决定 presence |

Blob ID 0 表示未设置。正 ID 必须唯一对应 `blob-N`；definition、字符串、图像和声音块共用一个 ID 空间。一个 blob 可以被多处引用，但所有引用必须对 payload 类型、图像 metadata 和用途相容；不同 payload 角色不得共用同一 ID，声音别名按下文共享规则处理。M1 必须仅凭 Manifest 拒绝跨角色冲突，无需读取 payload。

Canonical producer 直接存储 Manifest；definition 与 string data 可以使用 zstd；媒体图像直接存储其格式 payload。本 schema 未分配 flags bit，所有 chunk 的 flags 必须为 0。Alignment 完全遵循容器标准，不增加模型专属限制。

## Image 与 PBR texture

[Image Proto](proto/common/image.proto) 中的 descriptor 引用 blob，并声明格式、宽、高和帧数。宽高必须非零；当前 portable profile 只定义静态图像，`frame_count` 必须为 1。`frame_count = 0` 没有冻结语义。

Blob image 的格式和尺寸来自 descriptor。其 chunk encoding 可以为空；非空时必须与 descriptor format 大小写不敏感地一致。命名图像的 format 来自 chunk encoding，宽高编码在容器 `decodeSize` 的高、低 16 位，且都必须非零。

Portable profile 定义以下格式：

| Token | Payload 语义 |
|---|---|
| `RGBA` | RGBA8；每通道 8 bit，像素为 R/G/B/A 顺序，无行填充；从左到右、从上到下，首像素位于左上；长度必须精确为 checked `width * height * 4` |
| `PNG` | 一个符合 [PNG Specification, Third Edition, W3C Recommendation 2025-06-24](https://www.w3.org/TR/2025/REC-png-3-20250624/) 的静态 PNG datastream |
| `JPEG` | 一个符合 [ISO/IEC 10918-1:1994, Edition 1](https://www.iso.org/standard/18902.html) 的 JPEG coded image；JFIF/Exif 包装与 metadata 子集尚未冻结 |
| `WEBP` | 一个符合 [WebP Container Specification](https://developers.google.com/speed/webp/docs/riff_container) 的完整 RIFF WebP file；只允许一个静态 VP8 或 VP8L image |
| `AVIF` | 一个符合 [AV1 Image File Format v1.2.0](https://aomediacodec.github.io/av1-avif/v1.2.0.html) 的完整 AVIF file；只允许一个 primary static image |

外部格式 payload 解码后必须产生与 descriptor 宽高一致的 RGBA8 图像。Consumer 不得用文件扩展名替代 token，也不得猜测未知格式。颜色空间、ICC、方向 metadata、alpha 转换和尾随媒体字节策略尚未形成跨 codec 的稳定 YSM profile；producer 应输出已应用方向、无需外部颜色状态即可消费的静态图像，跨实现一致性测试必须固定最终 RGBA8 结果。

`ZTX` 是当前实现可见的私有扩展 token，端序、颜色、严格长度、版本升级和 fixtures 尚未冻结。实现可显式接受并报告 vendor extension 状态，不得标为 M2。

PBR texture set 的 `uv` 必须存在，`normal` 和 `specular` 可缺省。三者分别是 base texture、法线扩展和高光扩展的资源角色；通道编码与 shader 解释尚未冻结，不能仅凭字段名承诺跨 renderer 的逐像素等价。

## ModelData 与引用闭包

每个 target 的 definition blob 解析为 `ModelData`。Player 至少包含名为 `main` 和 `arm` 的 GeoModel；projectile 和 vehicle 至少包含 `main`。所有 map key 必须非空。

GeoModel 作为 ModelData map value 中的 bytes 二次编码；GeoModel 的 `cubes` 又是序列化的 `Cubes`。Consumer 必须逐层解析，不能把两层 bytes 当作任意 opaque data。本 Schema 不定义删减 cubes 的独立 index message。

## Geometry

对应 wire 见 [GeoModel Proto](proto/asset/model/data/geo_model.proto)。必须满足：

- Bone 数量不超过 65536；name 可为空字符串，但全模型唯一。重复的空名和非空名均应拒绝。
- Bone name 的发光命名语义见[命名初值与动态覆盖契约](../../product-decisions/decisions/geometry-regions.md#bcnamed-bone-glow-is-overridable)。
- parent 缺省或为空表示 root，不引用空名 bone；非空 parent 必须存在。parent graph 无环，且每个 bone 可到达某个 root。
- pivot 与 rotate 各有三个有限数。Pivot 使用模型像素单位，consumer 在应用平移时换算为 1/16 模型单位；rotate 使用弧度。
- debug 缺省等同于 `false`；`cube_count` 缺省等同于零。Bone 按列表顺序连续拥有 `cube_count` 个 cube；总和必须恰好等于 Cubes 中的记录数。
- 每个 cube 的 `face_count` 在 0 到 6 之间。
- position 是 xyz 序列，长度可被 3 整除且最多八个顶点；其值已经处于 YSM 模型坐标，1 单位对应一个 Minecraft block。
- UV 是归一化的 uv 序列，长度可被 2 整除。Schema 不要求数值截断到 `[0, 1]`，以保留纹理寻址行为。
- 每个 face 恰有四个 position index、四个 UV index 和一个三分量 normal；索引必须在各自数组范围内。
- Index 顺序就是该 quad 的顶点顺序，consumer 必须保留。Normal 是 face 的显式方向，当前 schema 不强制单位长度，也不要求从 winding 重建。
- 所有 position、UV、normal 和 transform float 都必须是有限数。

`GeoProperties` 描述纹理参考宽高；实际 texture blob 尺寸仍以 Image descriptor 为准。Consumer 不得在数组长度、索引或 hierarchy 非法时自行修复模型。

## Animation 与 controller

Wire 见 [Animation Proto](proto/asset/model/data/animation.proto) 和 [Controller Proto](proto/asset/model/data/animation_controller.proto)。

- Animation name 在文件内唯一；length、keyframe start、blend point 和 literal expression 分量必须有限。
- `blend_weight` 可缺省；absence 表示没有权重表达式，不注入一条通用默认程序。
- Historical/raw `start_delay`、`loop_delay` 与 `override_previous_animation` 没有当前业务 consumer，也不属于当前 Model Schema；converter 静默丢弃且不生成 warning。未来支持必须分配新字段并提升 schema version，只对新版本显式携带的值赋予语义，不能让旧 current container 因 runtime 升级突然获得新行为。
- Length 与所有 `start_tick` 使用 20 ticks/second 的时间单位。
- Bone animation 通过 bone name 绑定 geometry；引用应可解析。Rotation、position、scale 分别保存对应轨道；每个分量是 `ExpressionValue`，即有限 `float` literal（`num`）或 `common.Program`（`program.source` 承载 Molang **源字符串**），`post` 省略时等于 `pre`。
- Loop、easing 与 oneof 分配以 Proto 为准；未知字段和 enum 按[模型 Schema 的 unstable profile](README.md#normative-proto-快照)处理。
- Instruction keyframe 在指定 tick 执行 `common.Program programs`：生产者在投影/组装时把该帧的有序 statement 拼成一条 source（先追加语句，末尾非空白字符不是 `;` 时补 `;`，再补 `\n`）；同一 keyframe 内 statement 顺序原样保留，reader 不对其中 statement 排序或去重，而是把这条 source 整体解析为一个执行体。
- Sound keyframe 的 `data` 是 well-formed UTF-8 opaque event selector，不是 sound/stream foreign key；它可以是模型声音名、Minecraft namespaced sound id、空值或其他 consumer 可解释值。其 repeated source order 可观察，相同 tick 的记录不得排序或去重。
- Controller、state name 在各自作用域唯一；`default_state` 是 `optional string`，default state 和 transition destination 必须可解析。State 的 animations 是 `repeated AnimationReference{ string name = 1; common.Program condition = 2; }`，transition condition 是 `common.Program`，on-entry 与 on-exit 是 `optional common.Program`（statement 先按上一条的算法拼接成单条 source）。State 不再有 `blend_via_shortest_path`；field 7 未使用，field 8 是 `sound_effects`。

关键帧向量分量的 Bedrock/Molang 求值、旋转顺序和 controller 调度尚未冻结为 renderer 互操作标准。M2 只证明结构和引用有效。

`common.Program.source` 的字段与源字符串载荷形状不随 Molang 求值语义变化。YSM 的开发期适用范围由[有界产品例外](../../product-decisions/decisions/model-compatibility.md#bcdevelopment-molang-semantics-use-current-contract)限定；具体规则见[静态语言](../../architecture/animation/molang-static-language.md)和[运行设计](../../architecture/animation/molang-runtime.md)。旧表达式派生物按[内容身份与缓存规则](../../architecture/animation/implementation/molang-runtime.md)判为失配并重建，不把旧生成代码解释为新的源语义。

## Common strings、settings 与音频

Common 的 `strings_blob_id` 引用可解析的 [StringData](proto/asset/strings/StringData.proto)；user function name 非空且唯一，body 是 `common.Program`，函数体 source 取自 `UserFunction.body.source`。Settings 中所有 image reference 遵循普通 blob image 规则。Config form 用 `read_program.source` 承载读数表达式、`write_program.source` 承载 `<value>=t.value` 的写回表达式，label 是 `repeated ConfigLabel{ string name = 1; common.Program action_program = 2; }`，`action_program.source` 是该 label 的 action 表达式；纯 action 的 radio form 以 `"0"` / `"return;"` 占位。Label 列表保留声明顺序，列表下标同时定位显示文本、动作和单选读数；不得按名称排序。缺少与 form 类型相符的表达式时，该交互不能成为可执行 action。UI metadata 只描述交互表面，schema 不赋予未说明字段额外运行时行为。

[Sound Proto](proto/common/sound.proto) 和 Manifest Common 定义 sound descriptor，controller 与按钮也可出现 sound 名称。普通 current producer 与 legacy compatibility producer 使用同一 profile：

- Sound `name` 必须是非空、well-formed UTF-8，并在同一 Common 中唯一。多个名称可以共享同一首页与有序块定位，但媒体解释必须完全一致。
- `encoding` 只能是精确 token `OGG_OPUS`；`channels` 只允许 1 或 2，`sample_rate` 必须为 48000。`samples` 是应用 pre-skip 与末尾裁剪后一次非循环内容的正 per-channel PCM frame 数，最大为 `9223372036854775807`；Opus input-rate hint 不参与解释。
- `first_page` 必须是完整且 CRC 有效的单 packet BOS 页：sequence 为 0、granule 为 0，packet 是 mapping family 0 的 OpusHead，声道与 descriptor 相同。它同时冻结 pre-skip 与 output gain。
- OpusTags 只保留最小合法骨架以及大小写不敏感的 `R128_TRACK_GAIN` / `R128_ALBUM_GAIN`，删除普通 comment、封面、ReplayGain 标签和可丢弃 trailing extension。每种 R128 键至多一个；值是最多 6 个字符、可带前导 `+`/`-` 的 ASCII 十进制 `int16`。重复、畸形或越界值，以及最低位为 1 的未知 trailing extension，均使媒体无效。
- `chunk_ids` 非空，元素为互异的 `1..4294967295` blob ID，其顺序就是容器字节顺序。每个 ID 必须解析到 direct `blob-N`：chunk `encoding` 为空、`decodeSize = 0`、`flags = 0`，hash 覆盖 logical bytes。每张生成 page 不超过 57344 bytes；后续块同样不超过 57344 bytes。首块由完整 OpusTags pages 和穿过 pre-skip 直至首个有效 PCM 的最短完整 audio-page 前缀组成，因此可因包含多张 page 超过普通块上限，但不得附带无关后续音频。
- `first_page` 后顺序拼接全部引用块必须形成一个完整、有限、单 logical stream 的 Ogg Opus 容器；不能把块独立解释为媒体，也不能更换、错序或混用另一 representation 的块。
- M1 在不读取媒体块时验证 descriptor、首页和块定位。Prefix 验证只读取首块并要求生产 decoder 输出；M2 complete 验证按序逐块 feed 到同一 decoder 并到达已验证 EOF，不为验证额外拼接整条媒体。按需播放只取得自身声音闭包，不要求 catalog 浏览或 render-target 创建预读全部声音。

未知 Proto 字段仍按 unstable profile 跳过且不承诺 round-trip，不能据此激活未声明的音频能力。Raw source 中无法识别的音频和历史上始终 inert 的 legacy bytes 服从[兼容边界](../../architecture/asset-pipeline/model-compatibility.md)，不放宽已支持内容的严格验证。

## 分层验证与发布

Consumer 必须 fail closed：

1. 按 Asset Container 规则验证 metadata、版本和 verification。
2. 验证并解析 Manifest logical payload，检查 schema id、字面版本 `0.1.1-unstable` 和 vendor。
3. 检查 modelHash、player target、target 唯一性、texture 与命名图像 source 不变量，以及 Manifest 的 blob 引用角色相容性。
4. 建立请求范围内的 blob、sound chunk、image、definition 和 strings 引用闭包，拒绝 0、缺失、错序复用或类型冲突。
5. 验证 payload 的 stored size、解码长度、logical hash 和媒体格式；声音还须满足本页的 descriptor 与完整 Ogg profile。
6. 解析 ModelData、StringData、GeoModel、animation 与 controller，执行本页的 hierarchy、引用和有限数校验。Execution 字段保留 `common.Program.source`，literal 分量保留 `ExpressionValue.num`；当前 source profile 只使用 `format = 0`，不生产或消费 `Program.bytecode`。M2 不证明 source 可执行；源解析、静态闭合与失败归属见[运行时准入](../../architecture/animation/implementation/molang-runtime.md)。

完成第 3 步可以发布明确标记为 M1 的 catalog metadata、model offer 或同步 subject，但不能宣称完整可用。某个 selector 或加载请求只能在自身引用闭包通过第 4–6 步后使用；渲染还要求该 target 的 definition、texture 和 geometry 全部验证。只有整个模型的全部引用通过，才能声明 M2。
