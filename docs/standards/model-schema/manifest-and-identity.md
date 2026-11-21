# Manifest 与身份

Manifest 的 wire 布局以 [manifest.proto](proto/manifest/manifest.proto) 及其 import 闭包为准。本页只说明跨字段的不变量和消费语义。

## Manifest 与 RenderTarget

Manifest 汇总渲染目标（render targets）、公共资源描述与模型展示信息，其中至少必须包含一个合法的 player target。

每个 target 都必须满足以下约束：

- `target_id` 非空，且在同一个 Manifest 内唯一。
- `kind` 必须是 Proto 已分配且本标准支持的 `player`、`projectile` 或 `vehicle`；unspecified、reserved 和未知枚举值都不能作为可加载 target。
- 有且仅有一个 `target_id = "player"` 且 `kind` 为 `player` 的条目；其他 target 不得声明为 `player`。
- Player target 的 `textures` map 必须非空；projectile 和 vehicle target 允许使用空 map，表示该目标暂不可作为模型替换。
- 每个实际存在的纹理条目都必须拥有非空 key，并满足图像 descriptor、blob 身份和引用闭包约束；空 map 不产生图像引用，也不生成占位图。
- `blob_id` 必须为正整数，并指向合法的 `ModelData`。

空纹理的 projectile 或 vehicle target 仍保留自身的 target identity、kind、`match`、`ModelData`、settings 以及在 Manifest 中的排列顺序，但不能成为可用的 YSM 替换外观，普通运行时会恢复宿主呈现。该规则只取决于 target 类型，与模型来自 raw、legacy 还是 direct 容器无关。`match` 保存字符串列表；Schema 不赋予列表顺序以优先级、覆盖或匹配算法，这些规则由模型管理策略决定。

`ModelSettings` 仅作渲染提示，`ModelStats` 仅作展示统计，统计值不能替代解析后的几何校验。

## Info 与展示信息

Info 聚合语言文件、选择界面设置、metadata、properties、导出信息以及缩略图与图标来源声明，对应的 wire 结构见 [Info Proto](proto/manifest/info/info.proto)。

关于模组与模型许可彼此独立，以及模型许可声明的保留与展示边界，见 [DD.mod-and-model-licenses-are-independent](../../product-decisions/decisions/content-rights.md#ddmod-and-model-licenses-are-independent)；展示字段不承担权利真实性审核。未显式指定许可证类型时的默认解释与展示规则见 [BC.unspecified-model-license-is-all-rights-reserved](../../product-decisions/decisions/content-rights.md#bcunspecified-model-license-is-all-rights-reserved)，metadata 合法缺省不取消该默认规则。已有来源创作者声明在处理、分享与展示中的保护义务见 [DD.visible-creator-attribution](../../product-decisions/decisions/selection-and-attribution.md#ddvisible-creator-attribution)，metadata 合法缺省同样不免除保留已有署名的义务。

具体字段规则如下：

- locale、同一语言文件内的翻译 key、作者联系方式 key、链接 key 以及各类 UI id 应在各自作用域内保持唯一，避免因实现差异发生覆盖。
- Metadata 中的名称、提示、许可证、作者、链接和头像用于界面展示。metadata 整体缺省表示作者未提供展示信息，单个作者的头像（avatar）缺省表示没有头像引用；两者均以显式 `optional` 表达，已存在的头像按普通 blob 图像规则校验。
- `ExportInfo` 是可选的生产者来源说明，缺省表示未附带来源信息；Schema 不以它判断格式兼容性。
- Thumbnail source 与 icon source 各自缺省时，语义均等同于 `PREVIEW_SOURCE_UNSPECIFIED`，并须满足下文同样的命名图像约束。
- `Properties` 与 `Settings` 是必填的普通消息，每个当前生产者都必须提供。Settings 中的默认纹理、预览动画、额外动画与 GUI 图像属于模型行为或展示提示；GUI 的前景图和背景图缺省表示没有对应图像引用，具体的 UI 编排规则不属于 wire 兼容范围。

由于这些字段的原始字节可能参与某些生产者的来源身份计算，本标准不保证编辑展示 metadata 后 `modelHash` 保持不变。

## Exact tuple 与 representation 激活

精确元组（exact tuple）定义为 `(ModelId, ContainerId)`，分别标识逻辑模型与某一精确的容器表示；其产品含义见[模型身份](../../product-decisions/decisions/model-identity.md)，Java 对象模型与所有权见[Catalog 与来源](../../architecture/model-management/catalog/overview.md)。

远端缓存、网络下载及其他远端来源只能以精确元组满足服务端的 publication。本地来源的 `hash:` 身份是唯一例外：在完整打开容器、校验 Manifest 并确认 `ModelId` 一致后，允许用不同 `ContainerId` 的本地表示满足该 publication；命名身份则始终要求精确元组匹配。这一例外必须由本地发现或索引记录的来源信息证明，不能根据磁盘路径或缓存落盘位置自行推断。

Publication 保留服务端声明的精确元组、逻辑路径与访问策略，Ready 表示则保留实际文件的精确元组。本地非精确复用既不得改写任一方的容器身份，也不得反向覆盖 publication 的路径或访问策略。

网络层的 `CatalogPublication` 只传输 `model_id`、`container_id`、层级路径与访问策略。名称、描述、图标、预览图、preamble 和 Manifest 都必须从已校验的 Ready 表示中派生，不能在 publication 中维护第二份展示信息或 metadata 权威来源。

Manifest 必须是 verification 之后逻辑上的第一个普通 chunk，采用直接存储且非空；verification 与 Manifest 之间只允许存在格式规定的对齐 padding，不能夹杂其他普通 chunk。Metadata prefix 是精确区间 `[0, manifest.offset + manifest.size)`，包含 header、chunk table、verification payload 与合法 padding。远端缓存未命中时，客户端拉取并校验这一段连续字节：先按期望的 `ContainerId` 校验容器 metadata，再按期望的 `ModelId` 校验 Manifest 的 `model_id`，并确认 Manifest 的全部引用与 descriptor 一致且完整；全部通过后才能提交缓存或构造 Ready 内容。

## Properties 与 modelHash

`model_id` 是 Proto `string`，保存完整的规范 ASCII `ModelId`，最大长度 194 bytes。持久化存储、网络传输、索引、授权、收藏和 roaming 命名空间统一使用这一完整值，不截断、不去除空白，也不修改大小写。

普通模型身份共有三种合法形式：`hash:` 后接 64 个小写十六进制字符、`owner/name` 以及 `owner/name@SemVer`。其中 `owner` 与 `name` 均须满足 `[a-zA-Z_-][0-9A-Za-z_-]{0,31}`；SemVer 长度为 1–128 ASCII bytes，遵循 SemVer 2.0.0，数字段按任意精度比较。Build metadata 参与身份相等判定，但不参与版本优先级排序。命名声明本身不证明作者身份，也不保证内容相同。

`default` 是独立的内置默认（intrinsic）发布角色，不能按普通 `ModelId` 解析。普通默认源依然持有自己的真实身份，不能因为内容与默认源一致就切换角色。

M1 verified preamble 的 schema property type 2 必须保存与 Manifest `model_id` 完全一致的规范 ASCII 文本。生产者直接从 Manifest 派生该 property，不接受调用方另行传入的第二份身份。只读 preamble 的轻量消费者应拒绝缺失或非法的身份；完整消费者还必须将 Manifest 与 property 逐字节交叉比对。该 property 只用于在不读取 Manifest 时快速识别精确元组，不取代 Manifest 自身的模型身份语义。

`ModelId`（即 modelHash）与容器内其他摘要各司其职：

| 身份 | 覆盖对象 | 用途 |
|---|---|---|
| `ModelId` / modelHash | 显式命名声明，或未声明时的来源文件闭包摘要、legacy v3 历史映射 | 持久化、网络同步、索引与授权身份 |
| chunk hash | 单个 chunk 的逻辑内容 | payload 完整性校验 |
| container verification | preamble 与 descriptor 字节 | 容器 metadata 完整性校验 |
| `ContainerId` / representation identity | 某次精确校验通过的容器表示 | 仅限网络与模型管理内部用于精确传输、存储和私有派生缓存；不属于本 schema 字段，不向其他子系统或业务层暴露 |

在 raw 格式的 `ysm.json` 中，根字段 `owner`、`name`、`sem-ver` 用于声明命名身份。`owner` 与 `name` 必须成对出现，`sem-ver` 只能伴随完整的 `owner` 与 `name` 一起声明。凡是声明的字段都必须是非空字符串，遇到 null、类型错误、残缺声明或语法非法一律拒绝。只有当这三个字段都未声明时，才回退到下述内容摘要算法；历史 `info.json` 统一沿用内容摘要算法。

未命名的普通 source adapter 以转换器实际读取的原始源文件集合为规范输入，每个文件产出一条唯一记录：

```text
record = role, portablePath, originalBytes
```

- `role` 是稳定的 ASCII 语义角色。
- `portablePath` 使用 UTF-8 编码并以 `/` 分隔，不得为空、为绝对路径，或包含空段、`.`、`..`、反斜杠与 NUL。
- `originalBytes` 是从源中实际读取的原始字节；解析、标准化、解码、预处理以及派生结果均不参与计算。
- 实际读取到的用户预览图参与计算；未被读取的成员以及动态生成的等价 Manifest 不参与计算。
- 出现重复的 `(role, portablePath)` 时必须拒绝。

所有记录先按 `role`、再按 `portablePath` 的无符号 UTF-8 字节序排序，多字节整数均采用小端序，按以下布局拼接：

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

转换器对上述字节序列计算无密钥 BLAKE3-256 的默认 32-byte 输出（不加盐，也不改动算法），再拼成 `hash:` 加 64 个小写十六进制字符作为 `ModelId`。只要文件夹、zip、7z 或可解包的 legacy bundle 产出相同的记录集合，就必须得到相同的 `ModelId`。对于已经封装好的完整 Asset Container，只能校验 Manifest 中的 `ModelId`，无法从容器反向推导出原始源文件集合。

Legacy v3 转换适配器是唯一例外。由于原始源文件集合可能已不可获取，它只使用通过历史结构校验的 `ModelContainer.info.hash`：该字段必须恰好为 32 个 ASCII 十六进制字符，按文本顺序不区分大小写地解码为 16 bytes，再在末尾追加 16 个零字节构成 32-byte 原始摘要。首对字符固定对应摘要的首字节，不得经过宿主字节序的整数转换。最终的 `ModelId` 文本为 `hash:` 接小写的 `info.hash` 再接 32 个 `0`。路径、信封字节、加密随机量、解析结果和源摘要都不参与这一映射；如果该字段缺失或非法，必须拒绝，不得退回到 BLAKE3 计算。

这一映射只为兼容历史格式的 128-bit 无密钥身份，不提升熵值或抗碰撞强度，也不能单独证明精确内容、作者身份、真实性、授权或抗主动篡改。需要区分精确容器表示时仍应使用 `ContainerId`，读取方仍须按本页规则交叉校验 Manifest `model_id` 与 property type 2。

重编码或重封装工具必须原样保留已有的 `ModelId`；从新原始来源构建模型的工具则必须按上述规则生成身份，并避免将同一身份指向不同内容。

`free` 是模型声明的使用属性，不能替代许可证判断。`origin_ver` 是由生产者定义的来源版本字符串，Schema 不用它判断格式兼容性或身份；生产者可能用代表版本保留历史行为级别，因此该字段不保证还原实际导出工具的版本。导出模组的名称、ID 与版本统一记录在容器 property 1，签名指纹则单独记录在 property 3。

M1 读取方在将模型纳入 catalog、网络同步或缓存身份域之前，必须验证完整的 `model_id` 语法与长度。容器 verification 或 Manifest payload hash 不能替代这项检查。

## Preview 与命名图像

Thumbnail source 与 icon source 分别约束命名图像 `thumb-button` 与 `thumb-icon` 是否应当存在：

| 名称 | 对应命名图像要求 |
|---|---|
| unspecified | 必须不存在 |
| raw | 必须存在 |
| generated | 必须存在 |

两个字段各自独立判断，遇到未知枚举值必须拒绝。已存在的命名图像必须声明受支持的格式和非零宽高，并按[资产与验证](assets-and-validation.md)校验 payload。

上述规则仅定义容器本身的 schema 合法性：声明 unspecified 且不含命名图像的容器始终可以达到 M1 或 M2。本地 Catalog 会按模型来源施加更严格的产品准入规则：direct 成品的 Manifest 应声明内嵌缩略图，而 raw、builtin 与 legacy 的内部转换结果允许缺图。读取方在解析 Manifest 时检查声明、descriptor 和引用，在实际读取图片时才校验 payload，目录扫描阶段不提前解码图片。独立的 `ContainerId` preview 缓存既不改变 Manifest、命名 chunk 的存在性或 conformance profile，也不能替代 direct 成品的内嵌缩略图声明。

Settings 中的 sound 名称以及 controller 中的 sound effect 名称只代表动作引用，不证明模型内存在可播放的音频资产；当前音频规则见[模型 Schema](README.md#标准边界)。
