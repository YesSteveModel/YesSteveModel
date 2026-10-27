# 模型 Schema 0.1.1-unstable

模型 Schema 定义 `.mxc` 模型的逻辑内容与 Protobuf wire，不定义 raw 目录、旧容器、转换缓存、网络投影或 baked 数据。当前标准尚未冻结。

## 容器绑定

一个模型必须使用 [Asset Container 0.1.0-unstable](../asset-container.md)，并满足：

| 项目 | 规则 |
|---|---|
| schema id | `mixel/character` |
| property 0 | schema version；生产者必须写字面值 `0.1.1-unstable` |
| property 1 | 非空白 producer/vendor 标识；当前 producer 写入 `模组名称(ID) v版本` |
| property 3 | producer 的模组签名指纹；未签名时为 `(unsigned)` |
| 必需 chunk | `manifest` |
| 可选命名图像 | `thumb-button`、`thumb-icon` |
| 编号 blob | `blob-N`，`N` 从 1 开始；definition、字符串、图像与声音块共用该命名空间 |

符合本标准的 consumer 必须接受字面值 `0.1.1-unstable`。Schema 准入先应用共享开发版本门禁：当前或候选 qualifier 大小写不敏感地包含 `unstable`、`dev` 或 `snapshot` 时，必须按完整原始版本字符串严格相等。两侧均不含这些标记时，当前实现拒绝 major 或 minor 任一大于支持值的版本，patch 不参与稳定版准入。拒绝提示从 property 1 取得导出模组名称与版本，不依赖 Manifest 可解析。其他实现可以额外接受别的拼写，但这些输入不具备可移植的一致性保证。

`manifest` 是非空 Proto3 `Manifest` payload。Proto 语法遵循 [Proto3 Language Guide](https://protobuf.dev/programming-guides/proto3/)，二进制表示遵循 [Protocol Buffer Encoding](https://protobuf.dev/programming-guides/encoding/)；随本标准发布的 `.proto` 快照进一步冻结本 schema 的实际 wire。生产者直接存储 Manifest；consumer 仍须先完成容器级 payload 验证再解析。容器 summary 可以复制模型显示名，但不是 schema 的权威数据。

## Normative Proto 快照

以下文件是 wire 布局、字段编号、字段类型、枚举数值和 import 关系的唯一规范来源；Markdown 不重复这些信息：

- [Manifest 入口](proto/manifest/manifest.proto)
- [ModelData 入口](proto/asset/model/ModelData.proto)
- [StringData 入口](proto/asset/strings/StringData.proto)

`proto/` 包含三个模型入口的完整 import closure。修改 wire 必须先裁决版本，再同步更新 Proto、语义规则和 fixtures。

所有 repeated numeric/bool primitive 必须显式声明 `[packed = true]`，不能只依赖 Proto3 的默认 packed 行为。Native `struct_pb` profile 只支持这种 wire shape；声明本 schema 的 producer 不得为这些字段发送 unpacked occurrence。当前 profile 没有 repeated enum；引入前必须另行审计 `struct_pb` 的生成与解码能力，并在允许后显式声明 packed。Repeated string、bytes、message 和 map 仍使用各自的 length-delimited 表示，不添加 `packed` option。

当前实现 profile 把未标 `optional` 的 ordinary singular 字段视为完整 message 的必需值；producer 必须显式赋值，consumer 必须拒绝缺失，不能用 partial parse 或通用默认注入绕过。显式 `optional` 字段允许缺省，并区分 absent、present-default 与 present-nondefault；各状态的业务含义由相邻语义页定义。Repeated/map 用 empty 表达无元素，real oneof 用 case 表达分支，不按 ordinary singular 规则机械增加 `optional`。

Presence 审计只采用正向证据：历史 wire 的显式 optional/nullable、source adapter 的明确省略分支，或 FULL/DELTA 契约中已经定义的 absence。C++ 普通成员和嵌套聚合初始化会把缺省输入物化为默认对象，因此未见 nullable 不能推出 non-null。实现缺口见[格式与 Schema 问题](../../status/known-issues/format-and-schema.md)。

当前 unstable profile 不承诺保留或 round-trip 未知 Proto 字段。声明某个 schema version 的 producer 只能写该版本快照已知的字段和 enum 值；consumer 可以跳过未知字段，但不得把未知 enum 解释为已知策略。新增字段或 enum 值必须先裁决版本，再同步快照、语义和 fixtures。

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

- Manifest 是可先行读取的 descriptor，声明身份、展示信息、render target 和资源引用。
- Blob 是按正整数 ID 命名的不可变 payload，可存放 definition、字符串、图像或声音块。
- 命名图像用于模型列表的 thumbnail 与 icon；格式和尺寸由 chunk descriptor 描述。

M2 完整模型的全部引用必须形成闭包：每个正 blob ID（含声音块 ID）都解析到 `blob-N`，引用双方对 payload 类型和用途一致，资源通过容器 hash 与 schema 校验。

## 文档划分

- [Manifest 与身份](manifest-and-identity.md)：descriptor、target、metadata、modelHash 和 preview 语义。
- [资产与验证](assets-and-validation.md)：definition、几何、动画、纹理、字符串、音频 profile 和验证顺序。
- [格式一致性](../conformance.md)：conformance profile 与 fixture 要求。
- [当前支持状态](../../status/support-and-verification.md)：实现边界和已知缺口。

## 标准边界

Schema 包含 player、projectile、vehicle target，以及几何、动画、控制器、PBR texture descriptor、展示图像、语言条目和 Molang user function 的 wire 表面。所有 execution-bearing 字段由 `mixel.common.Program` 信封承载：`ExpressionValue.num` 是有限 `float` literal，`ExpressionValue.program` 是单条 Molang source；`InstructionKeyFrame.programs`、`AnimationReference.condition`、`Transition.condition`、`State.on_entry`/`on_exit`、`UserFunction.body`、`ConfigForms.read_program`/`write_program` 与 `ConfigLabel.action_program` 携带各自位置的 Molang source。本工程只写、也只读 `format = 0` + `source`；`Program` 的 `bytecode`（field 2）保留给已移除的引擎，这里既不生产也不消费。

Source 只由客户端在加载与运行时用本地 Molang 引擎解析和求值。模型 wire 不携带编译产物、绑定或宿主 symbol；Schema 冻结这些 source 字段的归属、编号与类型，Molang 语言自身的语法、绑定与求值语义不由本页定义。JVM class 与宿主 binding 不属于模型载荷；尚未冻结的渲染语义不属于本标准。

Source 在加载时按字段角色解析为 expression：`ExpressionValue` 未选择 oneof case 时退化为常数 `0`，`Program.source` 解析失败时记录诊断并退化为常数 `0`，不阻断其余动画或模型。常量 fallback 只保证 reader 不因单个表达式失败而拒绝整个模型；表达式本身的正确性仍属于 authoring 与模型兼容义务。

模型音频使用 `Common.sounds` 的 Opus-only wire：descriptor 内嵌首个 OpusHead Ogg 页，并以有序 `chunk_ids` 引用其余 `blob-N` bytes；两部分拼接后才是一个完整 logical stream。Raw 与 legacy producer 都必须先投影为该表示，current export 只复制已经获选的表示。M1 证明 descriptor、首页和块定位结构；M2 还要求按序读取全部块并完成 codec EOF 验证。运行时按需取得和播放不改变 Schema 的内容有效性定义。
