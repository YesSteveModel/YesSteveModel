# 资产与验证

本页说明模型的各类 payload、跨 chunk 引用关系以及分层验证顺序。Wire 布局以 [ModelData Proto](proto/asset/model/ModelData.proto)、[StringData Proto](proto/asset/strings/StringData.proto) 及其 import 闭包为准。

## Chunk namespace 与引用

| Chunk type | Payload | 要求 |
|---|---|---|
| `manifest` | `Manifest` | 必需且非空 |
| `blob-N` | 由引用方决定 | `N` 为从 1 开始的十进制正整数 |
| `thumb-button` | 命名图像 | 是否存在由 thumbnail source 决定 |
| `thumb-icon` | 命名图像 | 是否存在由 icon source 决定 |

Blob ID 为 0 表示未设置；正整数 ID 必须唯一对应到 `blob-N`。Definition、字符串、图像和声音块共用同一个正整数 ID 空间。同一个 blob 允许被多处引用，但所有引用方对它的 payload 类型、图像 metadata 和用途的预期必须一致，不同角色不得共用同一个 ID（声音别名按后文的共享规则处理）。M1 读取方必须仅凭 Manifest 就拒绝跨角色冲突，无需提前读取实际 payload。

规范生产者直接存储未压缩的 Manifest，对 definition 与字符串数据可以使用 zstd 压缩，对媒体图像则直接存储对应格式的原始字节。本 Schema 未分配任何 flags bit，所有 chunk 的 `flags` 必须为 0。对齐规则完全沿用 Asset Container 标准，不附加模型专属限制。

## Image 与 PBR texture

[Image Proto](proto/common/image.proto) 中的 descriptor 通过 `blob_id` 引用数据块，并声明图像格式、宽、高和帧数。宽高必须非零；当前可移植 profile 只定义静态图像，`frame_count` 必须为 1，`frame_count = 0` 没有冻结语义。

Blob 图像的格式和尺寸取自 descriptor，其容器 chunk 的 `encoding` 可以为空；如果不为空，则必须与 descriptor 中的格式不区分大小写地保持一致。命名图像（`thumb-button` 与 `thumb-icon`）的格式直接取自 chunk `encoding`，宽高编码在容器 `decodeSize` 的高、低 16 位中，且两者都必须非零。

可移植 profile 支持以下图像格式 token：

| Token | Payload 语义 |
|---|---|
| `RGBA` | RGBA8；每通道 8 bit，像素按 R、G、B、A 排列且无行填充；从左到右、从上到下扫描，首像素位于左上角；总长度必须精确等于无溢出的 `width * height * 4` |
| `PNG` | 符合 [PNG Specification, Third Edition, W3C Recommendation 2025-06-24](https://www.w3.org/TR/2025/REC-png-3-20250624/) 的单个静态 PNG 数据流 |
| `JPEG` | 符合 [ISO/IEC 10918-1:1994, Edition 1](https://www.iso.org/standard/18902.html) 的 JPEG 编码图像；JFIF、Exif 包装与 metadata 子集尚未冻结 |
| `WEBP` | 符合 [WebP Container Specification](https://developers.google.com/speed/webp/docs/riff_container) 的完整 RIFF WebP 文件；只允许包含单个静态 VP8 或 VP8L 图像 |
| `AVIF` | 符合 [AV1 Image File Format v1.2.0](https://aomediacodec.github.io/av1-avif/v1.2.0.html) 的完整 AVIF 文件；只允许包含单个主静态图像 |

外部格式 payload 解码后，必须产出与 descriptor 宽高一致的 RGBA8 图像。读取方不得用文件扩展名替代格式 token，也不得猜测未知格式。颜色空间、ICC 配置、方向 metadata、alpha 转换以及尾随媒体字节策略目前尚未形成跨 codec 的稳定 YSM profile；生产者应输出已应用方向且不依赖外部颜色状态的静态图像，跨实现一致性测试则必须固定最终的 RGBA8 像素结果。

`ZTX` 是当前实现可见的私有扩展 token，其字节序、颜色空间、严格长度、版本演进和 fixtures 均未冻结。实现可以显式接受并上报 vendor extension 状态，但不得将含 `ZTX` 的模型标为 M2。

在 PBR 纹理组中，`uv` 必须存在，`normal` 和 `specular` 允许缺省。三者分别对应基础纹理、法线贴图和高光贴图；具体通道编码与 shader 解释规则尚未冻结，不能仅凭字段名承诺跨渲染器的逐像素等价。

## ModelData 与引用闭包

每个 target 的 definition blob 解析为 `ModelData`。其中 player target 至少必须包含名为 `main` 和 `arm` 的 GeoModel，projectile 和 vehicle target 至少必须包含 `main`，且所有 map 的 key 都必须非空。

`GeoModel` 作为 `ModelData` map value 中的字节做二次编码，`GeoModel` 内部的 `cubes` 又是序列化后的 `Cubes`。读取方必须逐层解析校验，不能把这两层字节当作不透明数据跳过。本 Schema 不定义省略 cubes 的独立索引消息。

## Geometry

几何 wire 结构见 [GeoModel Proto](proto/asset/model/data/geo_model.proto)，必须满足以下约束：

- 骨骼数量不超过 65536；骨骼名允许为空字符串，但在整个模型内必须唯一，重复的空名或非空名一律拒绝。
- 骨骼名触发发光的初值与覆盖规则见[命名初值与动态覆盖契约](../../product-decisions/decisions/geometry-regions.md#bcnamed-bone-glow-is-overridable)。
- `parent` 缺省或为空表示根骨骼，不指向空名骨骼；非空 `parent` 必须存在。父子关系图不能有环，且每根骨骼都能沿着父链到达某根根骨骼。
- `pivot` 与 `rotate` 各含三个有限数。`pivot` 采用模型像素单位，读取方在应用平移时换算为 1/16 模型单位；`rotate` 采用弧度。
- `debug` 缺省等同于 `false`，`cube_count` 缺省等同于 0。骨骼按列表顺序连续拥有 `cube_count` 个 cube，所有骨骼的 `cube_count` 之和必须恰好等于 `Cubes` 中的记录总数。
- 每个 cube 的 `face_count` 必须在 0 到 6 之间。
- `position` 是 xyz 坐标序列，长度能被 3 整除且最多包含 8 个顶点；数值已处于 YSM 模型坐标系，1 单位对应一个 Minecraft 方块。
- `uv` 是归一化的 uv 坐标序列，长度能被 2 整除。Schema 不强行将数值截断到 `[0, 1]`，以保留纹理寻址行为。
- 每个 face 恰好包含 4 个 position 索引、4 个 UV 索引和 1 个三分量法线（normal），所有索引都必须落在各自数组范围内。
- 索引顺序即该四边形（quad）的顶点顺序，读取方必须原样保留。Normal 是显式声明的面法线方向，当前 Schema 不强制其为单位长度，也不要求从顶点绕序（winding）重建。
- 所有的 position、UV、normal 和变换浮点值都必须是有限数（非 NaN、非无穷大）。

`GeoProperties` 只描述纹理参考宽高，实际纹理尺寸仍以 Image descriptor 为准。遇到数组长度、索引越界或骨骼层级非法时，读取方必须拒绝，不得自行修补模型。

## Physics

[`ModelData.physics`](proto/asset/model/ModelData.proto) 仅用于 `player` target。节点显式按骨名绑定同一 definition 的 `main` 几何，空名可绑定唯一的空名骨。关节端点引用同一物理定义中的节点。省略整个字段时，definition 仍表示纯动画模型。

- 形状位置、尺寸、关节挂点和平移限位使用模型像素。角度使用弧度，重力矢量使用世界坐标系的 m/s²。矢量按 XYZ 存储，有向盒使用 XYZW 单位四元数。关节两端各自携带局部挂点和右手正交单位基。
- 动态节点必须绑定唯一的动态骨并声明质量。身体代理和锚点只绑定纯动画骨，其自身与祖先都不能绑定动态节点。身体代理可以在同一骨上声明多个，并支持碰撞集合和接触材质。锚点只提供骨骼动画目标。节点 ID 和关节 ID 各自唯一。
- 碰撞集合使用掩码的低 20 位。掩码缺省表示集合 1，显式 `0` 表示空集合，显式 `1` 表示集合 1。关节禁碰缺省开启。重力缺省取世界向下的 `(0, -9.81, 0)` m/s²。刚体摩擦和恢复系数分别缺省为 0.5 和 0，动态体的线速度阻尼和角速度阻尼分别缺省为 0.04 和 0.1 s⁻¹，重力缩放缺省为 1，显隐停用缺省关闭。
- 关节允许闭环，每条关节至少有一端为动态节点。自连接和悬空端点无效。每个动态连通部分必须经有效力学关节到达至少一个锚点或代理。固定关节、球铰和铰链锁定平移，均计入连通性。通用关节在六轴都自由或只有恒零作用时仍可单独禁碰，锚定检查跳过该边。扭转和两个摆动分量的可达角度均位于 `[−π, +π]`，覆盖整段的硬限位和软限位都恒无作用。刚度和阻尼均为零的软限位或回正弹簧也恒无作用。全角限位可与有效的独立回正弹簧并用，此时关节可由回正弹簧提供锚定。
- 固定关节锁定六轴。球铰锁定平移，可选圆锥限位或逐轴摆动限位，扭转独立配置。铰链只开放局部 Z 轴旋转。通用关节开放六轴。可动轴可以选用装配姿态或动画姿态作为唯一回正目标。已声明的软限位需要有限边界与一组独立的弹簧参数。

### 静态安全边界

每份物理定义最多包含 128 个节点、256 条关节和 512 个碰撞图元。每个动态节点和身体代理包含 1 至 32 个图元。绑定的 `main` 骨树须满足骨名唯一、父骨可解析、无环及有限三分量变换，已绑定节点的合成绑定姿态也须有限。

Raw 展开、导出暂存校验与 M2 定义读取使用相同的成品 `float32` 边界。角度先从度转为弧度。允许为零的字段量化为零后按零处理，集合槽位在整数域检查。

| 字段 | 有效区间 |
| --- | --- |
| 动态节点质量 | `[1e−4, 1e6]` kg |
| 球和胶囊半径、盒各边长 | `[1e−3, 1e4]` 模型像素 |
| 胶囊中心线长度 | `[0, 1e4]` 模型像素，零长度按球解释 |
| 图元中心与端点、关节局部挂点、平移限位 | 各分量 `[−1e4, 1e4]` 模型像素 |
| 世界重力、逐体重力缩放 | 各分量 `[−1e4, 1e4]`，世界重力单位为 m/s² |
| 摩擦、线速度和角速度阻尼、阻尼比 | `[0, 1e4]`，速度阻尼单位为 s⁻¹ |
| 恢复系数 | `[0, 1]` |
| 启用的弹簧频率 | `[1e−3, 1e4]` Hz |
| 刚度和阻尼系数 | `[0, 1e9]` |
| 铰链与各角轴限位、圆锥半角 | 轴限位 `[−π, π]`，半角 `[0, π]` |

各范围均含端点，数值必须有限，限位下界不得大于上界。球铰和通用关节的逐轴硬摆动限位须与半径 179° 的闭圆盘相交。取 Y、Z 两轴各自硬区间内最接近零的角度，其他限位类型取零，再用双精度比较两角的平方和与 `rad(179°)²`。

`preset_tag` 保留已展开节点或关节的制作来源，读取方按实际字段解释物理行为。未知形状分支或策略值不能取得物理定义的内容验证资格。

### Raw 物理输入

`ysm.json` 中的 `files.player.physics` 接受包内路径字符串或 `null`。空白路径、文件缺失及无效定义均使本次转换失败。未引用的 `physics.json` 会被忽略。引用文件的原始字节以 `physics` 角色参与未命名模型的来源摘要，显式命名模型沿用已有命名规则。导出物保存已展开定义，可脱离 raw 来源读取。

物理 JSON 使用 UTF-8。顶层可声明 `gravity`（世界 XYZ，单位 m/s²）、`collections`（槽位字符串到显示名的映射，范围 `"1"` 到 `"20"`）、`nodes`、`constraints` 与 `presets` 数组。几何输入采用 YSM 右手模型坐标系，线性量用模型像素，角度用度。欧拉旋转以 XYZ 分量输入，按 `Rz × Ry × Rx` 生成旋转。

| 输入 | 字段 |
| --- | --- |
| 节点 | `id`、`bone`、`type`（`dynamic`、`proxy`、`anchor`）。刚体可设置 `collections`（`[]` 表示空集合）、`friction` 和 `bounciness`。动态节点还可设置 `mass`（编译时缺省填入 1 kg）、`linear_damping`、`angular_damping`、`gravity_scale` 与 `disable_when_hidden`。其余缺省值沿用上节制品语义。 |
| 形状 | `shapes` 的 `type` 选用 `sphere`（`center`、`radius`）、`capsule`（`point_a`、`point_b`、`radius`）或 `box`（`center`、`size` 的三边全长、`rotation_deg`）。形状坐标相对绑定骨 pivot。球心、盒中心和盒旋转缺省均为 `[0, 0, 0]`。 |
| 关节 | `id`、`node_a`、`node_b`、`type`（`fixed`、`point`、`hinge`、`generic`）和可选 `disable_collisions`。共享 `frame` 的 `pivot_offset` 相对 B 端绑定骨 pivot，以模型轴表示。朝向可用 `rotation_deg` 或成对的非零 `axis_x` 与 `axis_y`。归一化后点积的绝对值须不超过 `1e−3`，编译器将其构造成右手正交单位基。省略朝向时对齐模型轴。分别指定两端时，同时提供 `frame_a` 与 `frame_b`，各端以自身骨的局部坐标表达挂点与朝向。 |
| 轴与圆锥 | 铰链的 `limit`、`spring` 和 `soft_limit_spring` 配置局部 Z。球铰和通用关节都使用 `angular_x`、`angular_y`、`angular_z`，通用关节另有 `linear_x`、`linear_y`、`linear_z`。球铰可使用 `cone` 配置摆动限位，字段包括 `half_angle_deg`、`limit_kind` 和可选 `soft_limit_spring`。轴对象的 `limit.kind` 选用 `free`、`locked`、`hard` 或 `soft`，有界角轴使用 `min_deg`、`max_deg`，有界平移轴使用 `min`、`max`。 |
| 弹簧 | `spring.target` 缺省为 `off`，可选 `rest_pose` 或 `animation`。启用的回正和独立的 `soft_limit_spring` 均支持默认的 `"frequency_damping"` 模式，需给出 `frequency`，`damping_ratio` 缺省为 0.5。可选的 `"stiffness_damping"` 模式中，`stiffness`、`damping` 分别缺省为 10、0.5。软限位弹簧无回正目标字段。 |

角轴的全角硬限位或软限位写作 `min_deg: -180` 与 `max_deg: 180`。全角软限位仍需声明 `soft_limit_spring`，该弹簧仅在越界时作用，因此全角区间内始终无力学作用。需要回正时可独立配置 `spring`。

`hair_chain` 和 `skirt_panel` 使用唯一 `id`、有序 `bones` 与 `body`。提供 `anchor_bone` 会生成锚点，提供 `anchor` 会引用已有锚点 ID。可选 `joint` 定义共用关节，`overrides` 按骨名提供 `body` 或 `joint` 局部覆写。共用层和覆写层各自按其关节类型检查可用字段。逐骨换类型时，先移除继承值中该类型不支持的字段，再应用本骨覆写。禁碰与坐标架可跨类型继承，球铰和通用关节互换时也可继承 `angular_x`、`angular_y` 和 `angular_z`。输出节点和关节使用 `<id>/body/<序号>` 与 `<id>/joint/<序号>`，生成锚点使用 `<id>/anchor`，三类预设均保留 `<type>:<id>` 来源标记。

- `hair_chain` 串联各节，缺省采用 `point` 和 35° 硬圆锥，并为整体摆动设置 `linear_damping: 1.5` s⁻¹。X 轴沿当前节指向下一节的绑定姿态 pivot，末节沿用上一节方向。默认以当前节绑定骨的局部 +Z 轴为参考正交化。当 X 与参考轴的点积绝对值达到 0.999 时，改用绑定骨局部 +Y 轴作为 Y 的参考。单节或重合 pivot 需要预设或逐节的 `axis`，也可完整指定关节朝向。
- 可选 `root_spring` 和 `tip_spring` 使用同一目标与参数模式，插值填入每节 Y、Z 的回正弹簧。单节取根值，缺省梢值与根值相同。逐节可改用 `generic` 或双端局部坐标架。只覆写回正弹簧时保留已有圆锥。逐节显式声明 Y 或 Z 限位时替换圆锥，`limit.kind: "free"` 也适用。改用圆锥会清除继承的 Y、Z 限位及其软限位弹簧。同层同时声明圆锥与非自由 Y、Z 限位会报错。
- `skirt_panel` 将每片直接挂到同一锚点，缺省为 `hinge`，可用 `generic`。需跟随关键帧时，显式指定回正目标为 `animation`。发束和裙片均默认 `disable_when_hidden: true`，可逐骨关闭。
- `lateral_link` 通过 `node_a`、`node_b` 连接已有节点，生成 `<id>/joint`。省略坐标架时，共享挂点取两端在绑定姿态下的形状中心中点。复合体使用各图元几何中心的算术平均，锚点使用绑定骨 pivot。缺省允许满足碰撞集合条件的两端接触，并给三条线性轴添加 ±1 模型像素软限位及装配姿态回正，两组弹簧初始为 3 Hz、阻尼比 0.8。可在 `joint` 中逐轴覆写或开启禁碰。

在任一预设中将继承的软限位改为其他类型时，会清除该轴继承的软限位弹簧和失效的边界字段，独立回正弹簧继续有效。

本节固定当前物理创作键名和预设缺省。生产者按上述顺序展开完整预设后校验并写入 `ModelData.physics`，读取方按已展开的数值和约束解释制品。[独立成品与拒绝 fixtures](../conformance.md#物理资产-fixtures)

## Animation 与 controller

动画与控制器的 wire 定义见 [Animation Proto](proto/asset/model/data/animation.proto) 和 [Controller Proto](proto/asset/model/data/animation_controller.proto)。

- 动画名称在文件内唯一；动画长度、关键帧起始时间、混合点以及字面量表达式分量都必须是有限数。
- `blend_weight` 允许缺省，缺省表示没有权重表达式，不得注入通用默认程序。
- 历史格式和 raw 源中的 `start_delay`、`loop_delay` 与 `override_previous_animation` 在当前业务中没有消费方，也不属于当前 Model Schema，转换器会静默丢弃且不产生警告。如果未来需要支持，必须分配新字段并提升 schema version，只对新版本显式携带的值赋予语义，不能让旧的当前容器在运行时升级后突然出现新行为。
- 动画长度与所有 `start_tick` 均以 20 ticks/second 为时间单位。
- 骨骼动画通过骨骼名绑定到几何体，引用应能解析到对应骨骼。Rotation、position 和 scale 分别保存各自轨道，每个分量都是 `ExpressionValue`——即有限 `float` 字面量（`num`）或 `common.Program`（`program.source` 承载 Molang **源字符串**）；`post` 省略时取值等同于 `pre`。
- Loop、easing 与 `oneof` 分支分配以 Proto 定义为准；未知字段和未知枚举按[模型 Schema 的 unstable profile](README.md#normative-proto-快照)处理。
- 指令关键帧（instruction keyframe）在指定 tick 执行 `common.Program programs`：生产者在转换和组装时，将该帧的有序语句拼成单条源码（逐条追加，若末尾非空白字符不是 `;` 则补 `;`，再补 `\n`）。读取方原样保留帧内语句顺序，不做排序或去重，并将整条源码作为一个执行体解析。
- 声音关键帧的 `data` 是合法 UTF-8 的不透明事件选择器，而不是音频或流的外键；它可以是模型声音名、Minecraft 命名空间声音 ID、空值或其他读取方可解释的字符串。同一关键帧序列中的原始顺序可被观察，相同 tick 的记录不得排序或去重。
- Controller 与 state 名称在各自作用域内唯一；`default_state` 为 `optional string`，默认状态与状态转移目标都必须能解析到存在的状态。State 的 `animations` 为 `repeated AnimationReference{ string name = 1; common.Program condition = 2; }`，转移条件为 `common.Program`，`on_entry` 与 `on_exit` 为 `optional common.Program`（多条语句同样按上述规则拼接成单条源码）。State 不再包含 `blend_via_shortest_path`；field 7 未使用，field 8 为 `sound_effects`。

关键帧向量分量的 Bedrock 与 Molang 求值细节、旋转顺序以及 controller 调度尚未冻结为跨渲染器的互操作标准，M2 只证明结构和引用有效。

`common.Program.source` 的字段定义与源码载荷形态不随 Molang 求值语义变化。YSM 在开发期内的语义适用范围由[有界产品例外](../../product-decisions/decisions/model-compatibility.md#bcdevelopment-molang-semantics-use-current-contract)限定，具体规则见[静态语言](../../architecture/animation/molang-static-language.md)与[运行设计](../../architecture/animation/molang-runtime.md)。旧表达式的派生编译产物按[内容身份与缓存规则](../../architecture/animation/implementation/molang-runtime.md)判为失配并重建，不会把旧生成代码套用到新的源码语义上。

## Common strings、settings 与音频

Common 中的 `strings_blob_id` 引用可解析的 [StringData](proto/asset/strings/StringData.proto)。用户函数名必须非空且唯一，函数体由 `UserFunction.body.source` 承载。Settings 中的所有图像引用均遵循普通 blob 图像规则。配置表单（config form）通过 `read_program.source` 承载读数表达式、通过 `write_program.source` 承载 `<value>=t.value` 形式的写回表达式；选项列表为 `repeated ConfigLabel{ string name = 1; common.Program action_program = 2; }`，其中 `action_program.source` 是该选项的动作表达式；纯动作的单选表单（radio form）以 `"0"` 或 `"return;"` 占位。Label 列表保留声明顺序，下标同时对应显示文本、动作与单选读数，不得按名称重排。如果表单缺少与自身类型匹配的表达式，该交互就不能成为可执行动作。UI metadata 只描述交互表面，Schema 不为未说明字段赋予额外运行时行为。

[Sound Proto](proto/common/sound.proto) 与 Manifest Common 共同定义音频 descriptor，controller 与按钮配置中也可以引用声音名称。普通当前生产者与历史兼容生产者遵循同一音频 profile：

- 声音 `name` 必须是非空且合法的 UTF-8 字符串，在同一 Common 内唯一。多个名称可以共享同一首页和有序块列表，但对媒体的解释必须完全一致。
- `encoding` 只能是精确值 `OGG_OPUS`；`channels` 只允许 1 或 2；`sample_rate` 必须为 48000。`samples` 是应用 pre-skip 和末尾裁剪后单次非循环播放的正 per-channel PCM 帧数，最大为 `9223372036854775807`；Opus 自身的 input-rate hint 不参与解释。
- `first_page` 必须是完整且 CRC 有效的单 packet BOS 页：sequence 为 0、granule 为 0，packet 是 mapping family 0 的 OpusHead，声道数与 descriptor 一致。该页同时固定 pre-skip 与 output gain。
- OpusTags 只保留最小合法骨架以及大小写不敏感的 `R128_TRACK_GAIN` 和 `R128_ALBUM_GAIN`，移除普通注释、封面、ReplayGain 标签和可丢弃的尾部扩展。每种 R128 键至多出现一次，其值是最多 6 个字符、可带前导 `+` 或 `-` 的 ASCII 十进制 `int16`。重复、畸形或越界的 R128 值，以及最低位为 1 的未知尾部扩展，都会使媒体无效。
- `chunk_ids` 必须非空，元素为互不相同的 `1..4294967295` blob ID，排列顺序即容器字节顺序。每个 ID 必须解析到直接存储的 `blob-N`（chunk `encoding` 为空、`decodeSize = 0`、`flags = 0`，hash 覆盖逻辑字节）。每张生成的 Ogg 页不超过 57344 bytes，后续块的大小同样不超过 57344 bytes。首块由完整的 OpusTags 页，加上穿过 pre-skip 直至产生首个有效 PCM 所需的最短完整音频页前缀组成；因此首块可能因包含多张页而超过普通块上限，但不得附带无关的后续音频。
- 将 `first_page` 与引用的全部块按序拼接后，必须构成完整、有限、单逻辑流的 Ogg Opus 容器。不能将单个块独立当作媒体解码，也不能替换、打乱顺序或混用来自另一个容器表示的块。
- M1 在不读取媒体块的前提下验证 descriptor、首页与块定位结构。Prefix 验证只读取首块并要求生产解码器产出有效输出；M2 完整验证按序逐块送入同一解码器直至通过校验的 EOF，而不必为了验证额外拼接整段音频。运行时按需播放只需拉取该声音自身的块闭包，不要求浏览目录或创建渲染目标时预读全部音频。

未知的 Proto 字段仍按 unstable profile 跳过且不承诺往返保留，不得借此激活未声明的音频能力。Raw 源中无法识别的音频和历史上本就不生效的旧字节按[兼容边界](../../architecture/asset-pipeline/model-compatibility.md)处理，但不会因此放宽对受支持内容的严格校验。

## 分层验证与发布

读取方必须按以下顺序校验，任一步失败即中止（fail closed）：

1. 按 Asset Container 规范校验容器 metadata、版本与 verification。
2. 校验并解析 Manifest 逻辑 payload，检查 schema id、[schema version](README.md#容器绑定) 与 vendor。
3. 检查 `modelHash`、player target、target 唯一性、纹理与命名图像来源声明的不变量，以及 Manifest 中所有 blob 引用的角色相容性。
4. 建立当前请求所需的 blob、声音块、图像、definition 与字符串引用闭包，拒绝 0 值、缺失项、错序复用或类型冲突。
5. 校验各 payload 的存储长度、解码长度、逻辑 hash 与媒体格式；音频还须满足本页的 descriptor 与完整 Ogg profile 约束。
6. 解析 `ModelData`、`StringData`、`GeoModel`、动画与控制器，执行本页的骨骼层级、引用和有限数校验。若 definition 携带 `physics`，在读取 definition 时校验[物理资产](#physics)。校验失败属于该模型的内容错误，其他模型可以继续加载。执行字段保留 `common.Program.source`，字面量分量保留 `ExpressionValue.num`；当前源码 profile 只使用 `format = 0`，不生产也不消费 `Program.bytecode`。M2 不证明源码可执行，源码解析、静态检查与失败归属见[运行时准入](../../architecture/animation/implementation/molang-runtime.md)。

通过第 3 步后，即可发布明确标记为 M1 的目录 metadata、model offer 或同步对象，但不能宣称模型完整可用。具体选择或加载请求只能在自身引用闭包通过第 4–6 步后使用，渲染还要求该 target 的 definition、纹理与几何全部通过校验。只有整个模型的全部引用都完成验证，才能声明达到 M2。
