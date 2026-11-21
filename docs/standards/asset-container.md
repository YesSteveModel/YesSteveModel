# Asset Container 规范

Asset Container 是一层与具体资产类型无关的二进制封装格式，负责描述 schema、chunk、存储编码、字节对齐与完整性校验，不涉及模型业务、缓存、网络传输或资源所有权。本规范描述当前的线缆版本（wire version），具体版本值见[版本清单](../abi/current.md#五个全局-abi)中的 `AbiVersion.CONTAINER`。当前格式尚未冻结。

文中的“必须”“不得”“应”“可以”分别表示规范要求、禁止、建议和可选行为。除特别说明外，多字节整数均采用小端序（little-endian），字节区间均为左闭右开。

## 使用 profile

同一份通过校验的 metadata 可以按两种 profile 使用：

| Profile | 当前文件包含的内容 | 用途 |
|---|---|---|
| 完整自包含文件 | verification payload 与所有普通 chunk payload | 持久文件、导入和完整离线校验 |
| verified preamble | 只到 verification payload 结束 | 传输描述、建立索引，由外部数据源提供普通 chunk |

Preamble 不是另一种二进制格式，而是完整文件开头一段已通过校验的结构前缀。chunk table 本身不记录内联或外置标志，也不包含路径、URL 或缓存键。当前规范不支持“部分普通 chunk 内联、部分外置”的混合文件，也不允许在完整文件末尾附加任意尾随数据。

## 基本类型与限制

| 项目 | 当前要求 |
|---|---:|
| 完整文件最大长度 | `134217728` bytes（128 MiB） |
| summary 最大长度 | `16384` bytes |
| base header 长度 | `64` bytes |
| qualifier 固定宽度 | `13` bytes |
| schema id 固定宽度 | `32` bytes |
| schema property 最大数量 | `32767` |
| chunk 最大数量 | `32767`，包含 verification chunk |
| chunk 最大对齐（alignment） | `2^8 = 256` bytes |
| BLAKE3 digest 长度 | `32` bytes |

固定字符串和短字符串必须使用 ASCII。生产者必须拒绝无法无损编码为 ASCII、包含内嵌 NUL 或超过字段宽度的输入，不得静默替换字符。固定字符串末尾以 `0x00` 填充至字段宽度，且第一个填充字节之后必须全为零。短字符串由一个 `uint8` 长度字节和紧随其后的内容字节组成，允许长度为 `0..255`；长度字节按无符号整数解码，`0x80` 表示 128 bytes，`0xff` 表示 255 bytes。写入方在写入前必须拒绝 256 bytes 的输入，读取方必须按字段边界拒绝缺失、截断或跨越 property 区域、chunk table 区域的 payload。上层 schema 可以另行规定更小的上限，例如 `ModelId` 最大为 194 bytes。

Summary 是 UTF-8 文本，允许为空，不得包含 `0x00`，并以单个 `0x00` 字节结尾。它仅作人类可读提示，不属于机器可读的 metadata，也不得参与身份判定。

## 总体布局

```text
magic
summary + 0x00
header
schema properties
chunk table
verification payload
aligned ordinary chunk payloads    # 仅完整自包含文件
```

Magic 必须逐字节等于：

```text
EF BB BF 59 53 47 50 32 0A 0A
```

其中可见的 ASCII 部分为 `YSGP2\n\n`。`YSGP2` 是沿用的历史文件签名，只用于字节级识别；其中的 `2` 既不代表本规范的主版本号，也不表示任何项目对本规范的所有权。

各区域的派生偏移如下：

```text
headerOffset        = magicSize + summarySize + 1
propertyDataOffset  = headerOffset + headerSize
chunkTableOffset    = propertyDataOffset + propertyDataSize
chunkDataOffset     = chunkTableOffset + chunkTableSize
```

计算偏移时所有加法都必须检查溢出，且所有 metadata 区域都必须落在当前输入范围内。对于完整文件，还必须检查所有普通 chunk payload 的区间合法；对于 preamble，则只要求输入恰好结束在 verification payload 末尾。

## Header

Base header 从 `headerOffset` 开始：

| 相对偏移 | 长度 | 字段 | 类型 | 当前语义 |
|---:|---:|---|---|---|
| 0 | 2 | `headerSize` | `uint16` | header 总长度，至少 64 |
| 2 | 1 | `majorVersion` | `uint8` | 当前 wire version 的主版本号（major） |
| 3 | 2 | `minorVersion` | `uint16` | 当前 wire version 的次版本号（minor） |
| 5 | 2 | `patchVersion` | `uint16` | 当前 wire version 的修订号（patch） |
| 7 | 13 | `qualifier` | 定长 ASCII | 当前 wire version 的限定后缀（qualifier）；无后缀时为空 |
| 20 | 32 | `schema` | 定长 ASCII | 上层 schema id |
| 52 | 2 | `schemaPropertyCount` | `int16` | 必须非负 |
| 54 | 2 | `chunkCount` | `int16` | 必须为 `1..32767` |
| 56 | 4 | `propertyDataSize` | `int32` | 必须非负 |
| 60 | 4 | `chunkTableSize` | `int32` | 必须非负 |

当 `headerSize > 64` 时，多出的字节视为未知的 header 扩展。兼容的读取方应跳过这些扩展字节，但它们仍须计入 verification input。

读取方需将四个版本字段组合为规范格式 `major.minor.patch[-qualifier]`，再执行版本检查。当前 profile 要求与当前 wire version 精确匹配，任一字段不符都必须拒绝。按通用门禁规则，只要当前版本或候选版本的 qualifier（不区分大小写）包含 `unstable`、`dev` 或 `snapshot`，两侧的完整原始版本字符串就必须严格相等；只有两侧都不含这些标记时，才交由该 profile 的稳定版策略判断。当前 profile 的稳定版策略同样是精确匹配，不做 minor、patch 或 qualifier 级别的兼容推断。

## Schema properties

Schema property 区域必须恰好包含 `schemaPropertyCount` 个条目，且末尾不得有剩余字节：

| 字段 | 类型 | 约束 |
|---|---|---|
| `type` | `int16` | 非负，且在容器内唯一 |
| `value` | 短 ASCII 字符串 | 由上层 schema 解释 |

未知的 property type 可以保留或忽略。property 的排列顺序没有语义，上层 schema 不得依赖它们的序列化顺序。

## Chunk table

Chunk table 必须恰好包含 `chunkCount` 个条目，且末尾不得有剩余字节。每个条目依次为：

| 字段 | 类型 | 语义 |
|---|---|---|
| `type` | 短 ASCII 字符串 | 容器内唯一的 chunk 名称 |
| `encoding` | 短 ASCII 字符串 | 存储编码或媒体格式；空串表示直接存储 payload |
| `size` | `int32` | stored representation 的字节长度，必须非负 |
| `decodeSize` | `int32` | 解码后的字节长度，或 schema 定义的辅助 metadata |
| `flags` | `int32` | 由上层 schema 定义 |
| `alignmentShift` | `uint8` | payload 对齐值为 `1 << alignmentShift`，范围 `0..8` |
| `hash` | 32 bytes | 普通 chunk 的 BLAKE3-256；verification 条目全零 |

`type` 在容器内必须唯一。`verification` 是保留名称，必须且只能出现一次，并且必须是第一个条目。

在完整文件中，所有普通 chunk 都按 chunk table 的顺序内联存放。Preamble 中不包含普通 payload，但 chunk table 仍然描述完整的逻辑资产，外部数据源必须按照同一份 descriptor 提供对应的 stored representation。

## Offset 与 alignment

完整文件按以下公式逐项派生每个 chunk 的偏移：

```text
cursor      = chunkDataOffset
alignment   = 1 << alignmentShift
paddingSize = (alignment - cursor % alignment) % alignment
offset      = cursor + paddingSize
cursor      = offset + size
```

Padding 字节必须实际存在且全为零；它们不属于 payload，也不计入普通 chunk 的 hash。每个 chunk 的 offset 必须满足声明的对齐要求，payload 区间不得重叠或越界。最后一个 payload 的结束位置必须恰好等于完整文件总长度，不允许存在尾随数据。

Verification chunk 的 `alignmentShift` 必须为 0，因此其 offset 恒等于 `chunkDataOffset`。Preamble 的总长度固定为：

```text
verification.offset + verification.size
```

## Stored representation、logical content 与 hash

规范严格区分存储形态（stored representation）与逻辑内容（logical content）：

- **`encoding = "zstd"`**：stored representation 由 [RFC 8878](https://www.rfc-editor.org/rfc/rfc8878.html) 定义的 Zstandard standard frame 与 skippable frame 组成。`size` 是整个压缩流的长度，`decodeSize` 是全部 standard frame 解压后的总字节数，chunk hash 覆盖解压后的 logical content。规范的生产者写入单个不依赖外部 dictionary 的 standard frame；读取方接受一个或多个 standard 或 skippable frame（但至少包含一个 standard frame），并必须拒绝依赖外部 dictionary 的输入，以及未被这些 frame 消费的尾随垃圾字节。
- **空 encoding**：存储字节直接就是 logical content，`decodeSize` 通常为 0，hash 直接覆盖存储字节。
- **schema 定义的直接媒体 encoding（例如图像格式）**：存储字节本身就是逻辑 payload，`decodeSize` 可由 schema 复用为尺寸等辅助 metadata，hash 覆盖存储字节。

所有 BLAKE3 摘要均遵循 [BLAKE3: one function, fast everywhere, 2020-01-09](https://github.com/BLAKE3-team/BLAKE3-specs/blob/ea51a3ac997288bf690ee82ac9cfc8b3e0e60f2a/blake3.pdf)，采用无密钥模式的默认 32-byte 输出，不使用 keyed 或 derive-key 模式，也不加额外的 domain separator。生产者必须在 chunk table 中写入与上述 hash 域一致的原始 32 bytes。读取方必须先检查 stored size，再完成必要的解码、校验精确的 `decodeSize` 和 hash，全部通过后才能向调用方暴露 logical content。对外部数据源同样适用这一顺序，不能因为 metadata 已通过校验就跳过 payload hash 检查。

遇到未知的 encoding 时，只能转交给明确支持它的上层 chunk handler，不得猜测编码格式，也不得把未知 encoding 默认当作 zstd 处理。

## Container verification

Verification input 覆盖 `[0, chunkDataOffset)` 这一完整前缀，依次包含 magic、summary 及其终止符、完整 header、schema properties 和整个 chunk table，不包括 verification payload、对齐 padding 或任何普通 payload。

本版本定义的 verification profile 如下：

| Encoding | Payload | 状态 |
|---|---|---|
| `BLAKE3` | `hash + signLength + sign` | 支持 |
| `ED25519` | 未定义完整 payload 与信任策略 | 仅保留名称，必须按不支持拒绝 |

`BLAKE3` payload 的字节布局固定为：

```text
byte[32] hash                  # verification input 的 BLAKE3-256
uint16   signLength            # little-endian
byte[signLength] sign
```

Payload 总长度必须精确等于 `34 + signLength`，旧的 32-byte payload、缺少长度字段或带有尾随字节的输入一律拒绝。其中的 `hash` 同时作为 `ContainerId`。`sign` 是可为空的不透明字节串（opaque bytes），当前规范不定义签名算法、key id、作者、信任或授权语义。在长度不变的前提下修改 `sign` 不会改变 `ContainerId`；但如果改变 `signLength`，chunk table 中的 `size` 会随之变化，进而改变 verification input 和 `ContainerId`。

Verification 在 chunk table 中的条目还必须满足：`size = 34 + signLength`、`decodeSize = 0`、`flags = 0`、`alignmentShift = 0`，且 `hash` 全为零。未知的 verification encoding 必须拒绝。

需要注意的是，BLAKE3 verification 仅用于检测意外损坏。能够重写文件的攻击者同样可以重算 digest，因此它不提供作者身份、授权或抗篡改保证。

## 验证顺序

Metadata 读取方必须按以下顺序校验，任一步失败即中止（fail closed）：

1. 检查输入总大小、magic 和 summary 终止符。
2. 解析 base header，检查版本、计数、长度及所有派生偏移。
3. 精确消费 schema property 区域，拒绝重复的 `type`。
4. 精确消费 chunk table，拒绝重复的 chunk `type` 和非法的 `alignmentShift`。
5. 检查首项 `verification` 条目的结构约束。
6. 校验 verification payload 长度、verification input 与 `ContainerId`；如果调用方提供了期望的 `ContainerId`，再做精确比对。
7. 根据调用方声明的 profile，确认输入恰好结束在 preamble 末尾，或继续执行完整文件校验。
8. 对于完整文件，逐个 chunk 检查零 padding、stored size、解码长度和 hash，并拒绝任何截断或尾随数据。

仅完成第 1–6 步只能证明 metadata 或 preamble 本身完整，不能证明普通资产存在、可解码或符合上层 schema。

### 模型目录准入 profile

对于完整的内联模型文件，目录扫描阶段可以只执行结构校验：核验 preamble digest、描述表布局，以及该文件的精确总长度。目录读取方随后再验证 Manifest 和展示相关的引用闭包，或复用同一 `ContainerId`、同一验证 profile 下的持久化校验结果。这一阶段不证明尚未读取的 chunk 完整；每次实际消费 chunk 时，仍须校验 descriptor、stored size、decodeSize 和 hash。完整离线校验则会执行上面的全部步骤。

direct、converted、builtin 与 export 产物都使用完整的内联容器。只有经过验证的远端发布方（remote owner）才会构造“metadata prefix 与外置 chunk”的形态；单独的 prefix、sidecar 或混合布局不得伪装成完整的本地模型。独立图片与非容器派生文件不受此限制。

## 生产者要求

生产者必须先确定全部 schema properties、chunk descriptors 和 `signLength`，再生成 verification payload。当前生产者可以写入空 `sign`，但必须保留 `signLength` 字段。写入完整文件时，必须按 chunk table 顺序输出全部 payload 和零 padding；导出 preamble 时，必须在 verification payload 结束后立即终止。生产者不得输出非 ASCII 字符串、混合内联与外置 payload，或在末尾附加私有尾随数据。

上层 schema 负责定义具体的 chunk type、非 zstd encoding、`decodeSize`、`flags` 以及资源引用语义。当前模型 schema 见[模型 Schema](model-schema/README.md)，测试要求见[格式一致性](conformance.md)。
