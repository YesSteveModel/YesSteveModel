# Manifest 与身份

Manifest 的 wire 布局以 [manifest.proto](proto/manifest/manifest.proto) 及其 import closure 为准。本页只定义跨字段不变量和消费语义。

## Manifest 与 RenderTarget

Manifest 包含 render targets、公共资源描述和模型信息。它必须至少包含一个合法 player target。

每个 target 必须满足：

- `target_id` 非空且在 Manifest 内唯一。
- `kind` 是 Proto 已分配且本标准支持的 player、projectile 或 vehicle；unspecified、reserved 和未知值不得成为可加载 target。
- 恰有一个 `target_id = "player"` 且 kind 为 player；其他 target 不得声明 player kind。
- Player target 的 textures map 非空；projectile/vehicle target 可以用空 map 表达 unavailable replacement。
- 每个实际存在的 texture entry 的 key 非空，并继续满足 image descriptor、blob identity 与引用闭包要求；空 map 不产生 image reference 或 placeholder。
- `blob_id` 为正数并引用合法 `ModelData`。

Player target 使用固定 id `player`。空纹理 projectile/vehicle 仍保留 target identity、kind、`match`、`ModelData`、settings 与 Manifest 顺序，但不能成为可用的 YSM replacement；普通运行时恢复宿主呈现。该语义只由 target kind 决定，不依赖 raw、legacy 或 direct-container provenance。`match` 保存重复字符串序列；schema 不赋予列表顺序以优先级、覆盖或匹配算法，这些属于模型管理策略。

`ModelSettings` 是渲染提示，`ModelStats` 是展示统计。统计值不得替代解析后的 geometry 校验。

## Info 与展示信息

Info 聚合语言文件、选择界面设置、metadata、properties、export 信息，以及 thumbnail/icon source。对应 wire 结构见 [Info Proto](proto/manifest/info/info.proto)。

模组与模型许可独立、模型许可声明保留和展示的产品边界由 [DD.mod-and-model-licenses-are-independent](../../product-decisions/decisions/content-rights.md#ddmod-and-model-licenses-are-independent) 定义；展示字段不承担权利真实性验证。

未显式指定许可证类型时的解释与展示见 [BC.unspecified-model-license-is-all-rights-reserved](../../product-decisions/decisions/content-rights.md#bcunspecified-model-license-is-all-rights-reserved)；metadata 合法缺省不取消该默认规则。

已有来源创作者声明在处理、分享和展示中的保护义务见 [DD.visible-creator-attribution](../../product-decisions/decisions/selection-and-attribution.md#ddvisible-creator-attribution)；下述 metadata 合法缺省不免除已有署名的保留义务。

- locale、同一语言文件内的翻译 key、作者联系人 key、链接 key，以及各类 UI id 应在各自作用域唯一，避免实现相关覆盖。
- Metadata 的名称、提示、许可证、作者、链接和头像用于展示；metadata 整体缺省表示没有作者提供的展示信息，单个 author 的 avatar 缺省表示没有头像引用。两者均以显式 `optional` 表达，已有头像按普通 blob image 验证。
- ExportInfo 是可缺省的 producer provenance；absence 表示没有这份来源说明。Schema 不以它判断兼容性。
- Thumbnail source 与 icon source 各自缺省时都与 `PREVIEW_SOURCE_UNSPECIFIED` 同义，必须满足下文相同的命名图像约束。
- Properties 与 Settings 是 ordinary required message；每个 current producer 都必须物化。Settings 中的默认纹理、preview animation、extra animation 和 GUI 图像属于模型行为或展示提示；GUI foreground/background 缺省表示没有对应图像引用，具体 UI 编排不是 wire 兼容规则。

这些字节可能参与某个 producer 的来源身份计算，因此本标准不承诺编辑展示 metadata 后 modelHash 保持不变。

## Exact tuple 与 representation 激活

Exact tuple 是 `(ModelId, ContainerId)`，分别标识模型与精确容器表示；产品含义见[模型身份](../../product-decisions/decisions/model-identity.md)。Java 对象及所有权见[Catalog 与来源](../../architecture/model-management/catalog/overview.md)。

Remote cache、网络下载和其他远端来源只能以 exact tuple 满足 publication。本地来源是唯一例外：完整打开容器、校验 Manifest 并证明 `ModelId` 相同后，可以用不同 `ContainerId` 的本地 representation 满足 publication。该例外由 local discovery/index 来源证明，不能根据磁盘路径或 cache 落盘位置推断。

Publication 保留 server 声明的 exact tuple、path 与 access；Ready representation 保留实际文件的 exact tuple。本地非精确复用不得改写任一方的容器身份，也不得反向覆盖 publication path 或 access。

Network `CatalogPublication` 只传输 `model_id`、`container_id`、hierarchy path 与 access。名称、description、icon、preview、preamble 和 Manifest 必须从已验证的 Ready representation 派生，不能作为 publication 中的第二份展示或 metadata authority。

Manifest 必须是 verification 之后逻辑上的第一个 ordinary chunk，使用 direct storage 且非空；verification 与 Manifest 之间只能存在格式要求的 alignment padding，不能夹入其他 ordinary chunk。Metadata prefix 是精确区间 `[0, manifest.offset + manifest.size)`，包含 header、table、verification payload 与合法 padding。Remote miss 传输并验证这一段连续 bytes：consumer 按 expected `ContainerId` 验证容器 metadata，按 expected `ModelId` 验证 Manifest `model_id`，并验证 Manifest 引用与 descriptors 闭合；全部成功后才能提交 cache 或构造 Ready content。

## Properties 与 modelHash

`model_id` 是恰好 32 bytes 的 `ModelId`。它作为持久化、网络、索引和授权身份使用；不得截断、用模型路径替代，或仅保存文本前缀。

M1 verified preamble 的 schema property type 2 必须保存同一 `ModelId` 的 64 个小写十六进制字符。Producer 必须从 Manifest `model_id` 派生该 property，不能接受第二份调用方身份；轻量 identity consumer 必须拒绝 property 缺失、长度错误、大写或非十六进制字符，完整 consumer 还必须将其与 Manifest `model_id` 精确交叉验证。该 property 只允许 consumer 在不读取 Manifest 的情况下发现 exact tuple，不取代 Manifest 的模型身份语义。

ModelHash 与以下值不同：

| 身份 | 覆盖对象 | 用途 |
|---|---|---|
| `ModelId` / modelHash | 普通 source adapter 的原始源文件闭包，或下述 legacy v3 兼容身份 | 持久、网络、索引和授权身份 |
| chunk hash | 单个 chunk 的 logical content | payload 完整性 |
| container verification | preamble/descriptor bytes | metadata 完整性 |
| `ContainerId` / representation identity | 某次精确验证的容器表示 | 仅限网络与模型管理内部的精确传输、存储和私有派生缓存；不属于本 schema 字段，不向其他子系统或业务层暴露 |

除下述 legacy v3 兼容例外外，`ModelId` 的 canonical input 是转换器实际读取的原始源文件闭包。每个 source adapter 输出唯一 record：

```text
record = role, portablePath, originalBytes
```

- `role` 是稳定 ASCII 语义角色。
- `portablePath` 使用 UTF-8 与 `/`，不得为空、为绝对路径、包含空段、`.`、`..`、反斜杠或 NUL。
- `originalBytes` 是源中实际读取的原始 bytes；解析、标准化、解码、预处理和生成结果不参与身份。
- 实际读取的用户预览图参与身份；未读取成员和动态生成的等价 Manifest 不参与身份。
- 重复 `(role, portablePath)` 必须拒绝。

Records 先按 `role`、再按 `portablePath` 的无符号 UTF-8 bytes 排序，多字节整数均为 little-endian：

```text
uint32 recordCount
repeat recordCount times:
    uint32 roleLength
    byte[roleLength] roleUtf8
    uint32 pathLength
    byte[pathLength] portablePathUtf8
    uint64 contentLength
    byte[contentLength] originalBytes
```

`ModelId` 是以上 bytes 的无密钥 BLAKE3-256 默认 32-byte 输出，不使用 Base64、hex、keyed hash、derive-key 或版本盐。文件夹、zip、7z 和可解包的 legacy bundle 产生同一 record 集合时必须得到同一 `ModelId`。完整 Asset Container 只能验证 Manifest 中的 `ModelId`，不能从容器反推原始源闭包。

Legacy v3 conversion adapter 是唯一例外。其 original source-file closure 可能已不可用，因此只使用通过历史结构校验的 `ModelContainer.info.hash`：该字段必须是恰好 32 个 ASCII 十六进制字符，按文本顺序大小写不敏感地解码为 16 bytes，并在尾部追加 16 个零字节形成 `ModelId`。首对字符固定对应 `ModelId[0]`，不得经过 host-endian 整数解释；canonical hex 是小写 `info.hash` 后接 32 个字符 `0`。路径、envelope bytes、加密随机量、解析结果和 source digest 均不参与该映射，也不得在字段缺失或非法时改用 BLAKE3 fallback。

该映射只保留历史格式的 128-bit、无密钥兼容身份，不增加熵或抗碰撞强度，也不能单独证明 exact content、作者身份、真实性、授权或抗主动篡改。需要区分精确容器表示时仍使用 `ContainerId`；consumer 仍须按本页规则交叉验证 Manifest `model_id` 与 property type 2。

重编码或重封装工具必须保留既有 `ModelId`；直接创建新原始来源的工具必须按上述算法生成身份，并避免把同一身份指向不同内容。

`free` 是模型声明的使用属性，不替代许可证判断。`origin_ver` 是 producer-defined 来源版本字符串；Schema 不用它判断格式兼容或身份。Producer 可以用代表版本保留历史行为级别，因此该字段不保证还原实际导出工具版本。导出模组名称、ID 与版本由容器 property 1 记录，签名由 property 3 单独记录。

M1 consumer 在模型进入 catalog、同步或缓存身份域前必须验证 `model_id` 长度。容器 verification 或 Manifest payload hash 不能替代此检查。

## Preview 与命名图像

Thumbnail source 与 icon source 分别约束 `thumb-button` 和 `thumb-icon`：

| 名称 | 对应命名图像要求 |
|---|---|
| unspecified | 必须不存在 |
| raw | 必须存在 |
| generated | 必须存在 |

两个字段独立判断，未知枚举值必须拒绝。两个命名图像都必须声明受支持格式和非零宽高，并按 [资产与验证](assets-and-validation.md) 校验 payload。

上述规则只定义容器本身的 schema 合法性：unspecified 且无命名图像始终可以达到 M1/M2。Local Catalog 可以在 schema 验证之后按实际来源应用更严格的产品准入——direct 成品要求有效内嵌 thumbnail，而 raw/builtin/legacy 的内部转换结果允许缺图。独立 `ContainerId` preview cache 不改变 Manifest、命名 chunk presence 或 conformance profile，也不能替代 direct 成品的内嵌要求。

Settings 中的 sound 名称和 controller 中的 sound effect 名称只是动作引用表面，不证明存在可播放音频资产；当前音频规则见 [模型 Schema](README.md#标准边界)。
