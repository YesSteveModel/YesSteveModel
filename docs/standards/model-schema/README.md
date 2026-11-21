# 模型 Schema

模型 Schema 定义 `.mxc` 模型的逻辑内容与 Protobuf wire 格式，不涉及 raw 目录、旧容器、转换缓存、网络派生态或 baked 产物。当前的 schema version 引用[版本清单](../../abi/current.md#五个全局-abi)中的 `AbiVersion.MODEL_SCHEMA`。当前标准尚未冻结。

## 容器绑定

合法的模型文件必须使用当前版本的 [Asset Container](../asset-container.md)，并满足以下绑定规则：

| 项目 | 规则 |
|---|---|
| schema id | `mixel/character` |
| property 0 | schema version；生产者必须写入当前 schema version 的完整原始字符串 |
| property 1 | 非空白的生产者标识（vendor）；当前生产者写入 `模组名称(ID) v版本` |
| property 2 | 与 Manifest 完全一致的规范 `ModelId` 文本；规则见[Manifest 与身份](manifest-and-identity.md#properties-与-modelhash) |
| property 3 | 生产者的模组签名指纹；未签名时写入 `(unsigned)` |
| 必需 chunk | `manifest` |
| 可选命名图像 | `thumb-button`、`thumb-icon` |
| 编号 blob | `blob-N`，`N` 为从 1 开始的正整数；definition、字符串、图像与声音块共用这一编号空间 |

符合本标准的读取方必须接受当前 schema version 的完整原始字符串。版本检查首先套用共享的开发版规则：只要当前版本或候选版本的 qualifier（不区分大小写）包含 `unstable`、`dev` 或 `snapshot`，两侧的完整原始版本字符串就必须严格相等。只有两侧都不含这些标记时，才按稳定版策略判断；当前实现拒绝 major 或 minor 任一大于支持值的版本，patch 不参与稳定版检查。版本拒绝提示直接从 property 1 读取导出模组的名称与版本，不要求 Manifest 可解析。其他实现可以额外宽容别的版本拼写，但那些输入不具备可移植的一致性保证。

`manifest` chunk 存放非空的 Proto3 `Manifest` payload。Proto 语法遵循 [Proto3 Language Guide](https://protobuf.dev/programming-guides/proto3/)，二进制编码遵循 [Protocol Buffer Encoding](https://protobuf.dev/programming-guides/encoding/)，并由随本标准发布的 `.proto` 快照进一步固定实际 wire 布局。生产者直接存放未压缩的 Manifest；读取方仍须先通过容器级 payload 校验再执行 Proto 解析。容器 summary 可以复制模型显示名供人工辨认，但不属于 schema 权威数据。

## Normative Proto 快照

以下三个入口文件连同它们的 import 闭包，是 wire 布局、字段编号、字段类型、枚举数值和依赖关系的唯一规范来源，Markdown 正文不再重复这些定义：

- [Manifest 入口](proto/manifest/manifest.proto)
- [ModelData 入口](proto/asset/model/ModelData.proto)
- [StringData 入口](proto/asset/strings/StringData.proto)

`proto/` 目录包含这三个入口的完整依赖文件。任何 wire 修改都必须先确定版本策略，再同步更新 Proto 文件、语义文档和 fixtures。

所有 repeated numeric 或 bool 基础类型字段都必须显式声明 `[packed = true]`，不能只依赖 Proto3 的默认 packed 行为。Native 侧的 `struct_pb` profile 只支持这种编码形态，因此声明本 schema 的生产者不得发送 unpacked 编码。当前 profile 中没有 repeated enum；如果未来引入，必须先审计 `struct_pb` 的生成与解码能力，并在确认允许后显式声明 packed。Repeated string、bytes、message 和 map 仍使用各自的 length-delimited 表示，不添加 `packed` 选项。

当前实现 profile 将未标注 `optional` 的普通单值（ordinary singular）字段视为完整消息的必填项：生产者必须显式赋值，读取方必须拒绝缺失，不能靠 partial parse 或通用默认注入绕过校验。显式标注 `optional` 的字段允许缺省，并区分三种状态：缺失、显式设为默认值、显式设为非默认值，具体业务含义由相邻语义页说明。Repeated 和 map 用空集合表示无元素，真实 `oneof` 用分支 case 表示选择，不按普通单值字段的规则机械添加 `optional`。

字段存在性（presence）审计只依据正向证据：历史 wire 中的显式 optional 或 nullable 声明、source adapter 中明确的省略分支，或 FULL 与 DELTA 契约里已经定义的缺省语义。由于 C++ 普通成员和嵌套聚合初始化会把缺省输入自动填充为默认对象，因此没有看到 nullable 标注不能反推出“必须非空”。相关实现缺口见[格式与 Schema 问题](../../status/known-issues/format-and-schema.md)。

当前 unstable profile 不承诺保留或往返（round-trip）未知的 Proto 字段。声明特定 schema version 的生产者只能写入该版本快照已知的字段和枚举值；读取方可以跳过未知字段，但不得把未知枚举解释为已知策略。新增字段或枚举值必须先裁决版本，再同步更新快照、语义文档与 fixtures。

## 逻辑组成

```text
Manifest
├── render_targets[] ──> blob-N: ModelData
│   └── textures{} ────> blob-N: Image payload
├── common_assets
│   ├── strings_blob_id -> blob-N: StringData
│   └── sounds[] ───────> first OpusHead page + ordered blob-N chunks
└── info
    ├── metadata / language / settings / properties
    └── image references -> blob-N
```

- **Manifest**：可先行读取的顶层描述，声明模型身份、展示信息、渲染目标（render target）以及全部资源引用。
- **Blob**：按正整数 ID 命名的不可变 payload，用于存放 definition、字符串、图像或声音块。
- **命名图像**：专用于模型列表的缩略图（thumbnail）与图标（icon），格式和尺寸直接记录在容器的 chunk descriptor 中。

达到 M2 的完整模型必须满足引用完整闭合：每个正整数 blob ID（包含声音块 ID）都必须解析到存在的 `blob-N`，所有引用方对 payload 类型和用途的预期一致，且每个资源都通过容器 hash 与 schema 校验。

## 文档划分

- [Manifest 与身份](manifest-and-identity.md)：descriptor、target、展示信息、modelHash 与 preview 语义。
- [资产与验证](assets-and-validation.md)：definition、几何、动画、纹理、字符串、音频 profile 与分层验证顺序。
- [格式一致性](../conformance.md)：conformance profile 与 fixture 要求。
- [当前支持状态](../../status/support-and-verification.md)：当前实现边界与已知缺口。

## 标准边界

本 Schema 涵盖 player、projectile 和 vehicle 三类 target，以及几何、动画、控制器、PBR 纹理描述、展示图像、语言条目和 Molang 用户函数的 wire 结构。所有承载可执行逻辑的字段都由 `mixel.common.Program` 封装：

- `ExpressionValue.num` 保存有限 `float` 字面量，`ExpressionValue.program` 保存单条 Molang 源码。
- `InstructionKeyFrame.programs`、`AnimationReference.condition`、`Transition.condition`、`State.on_entry` 与 `State.on_exit`、`UserFunction.body`、`ConfigForms.read_program` 与 `write_program`，以及 `ConfigLabel.action_program` 各自保存对应位置的 Molang 源码。

当前工程只写入、也只读取 `format = 0` 加 `source` 文本。`Program` 的 `bytecode`（field 2）是为已移除引擎保留的历史字段，这里既不生产也不消费。

Molang 源码仅在客户端加载与运行时由本地引擎解析和求值，模型 wire 不携带任何编译产物、绑定关系或宿主符号。本 Schema 只冻结这些源码字段的归属、编号和类型，不定义 Molang 语言本身的语法、绑定与求值语义；JVM 类和宿主绑定不属于模型载荷，尚未冻结的渲染语义也不属于本标准。

加载时，读取方按字段角色将源码解析为表达式：当 `ExpressionValue` 未选择任何 oneof 分支时退化为常数 `0`；当 `Program.source` 解析失败时记录诊断并同样退化为常数 `0`，不阻断其余动画或整个模型。常量回退仅用于避免单条错误表达式导致整个模型被拒，表达式自身的正确性仍属于模型创作与兼容义务。

模型音频采用 `Common.sounds` 的 Opus-only wire 形态：descriptor 内嵌首个 OpusHead Ogg 页，并通过有序的 `chunk_ids` 引用后续 `blob-N` 字节块，两部分按序拼接后才构成完整的 Ogg 逻辑流。Raw 与旧格式（legacy）生产者都必须先转换为该表示，当前导出则直接复制已选定的表示。M1 只校验 descriptor、首页和块定位结构；M2 要求按顺序读完所有块并通过 codec EOF 校验。运行时按需拉取和播放属于加载策略，不改变 Schema 对内容有效性的定义。
