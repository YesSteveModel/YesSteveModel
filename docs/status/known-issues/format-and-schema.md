# 格式、Schema 与编解码已知问题

格式与版本规范以[一致性要求](../../standards/conformance.md)为准。

## 一致性与转换

### 已知缺陷

- Raw 几何转换误写 visible bounds offset，可能导致转换后模型的包围盒范围信息出错。
- 固定字符串 ASCII 校验、图像 payload 长度以及 metadata 一致性仍存在严格校验偏差。

### 未实现范围

- 不支持 ED25519 verification；当前已接线的 `hash`、`signLength` 与不透明 `sign` 不提供作者身份、授权或抗篡改保证。

### 未验证缺口

- Asset Container、Model Schema 与 Canonicalizer 缺少跨实现可移植二进制 golden（同实现自产自读不能证明跨实现互操作）。
- Direct 与内部来源的缺图准入矩阵缺少完整产品入口与跨平台媒体组合的端到端验收（规则见[来源准入](../../architecture/asset-pipeline/implementation/conversion-and-export.md#preview-取得与显式-export)）。

## Codec、导入与平台

### 已知缺陷

- 当前 native 源码在 MSVC fresh Release 构建时会因 GNU 风格的 `asm volatile` 报错失败。

### 未实现范围

- `ZTX` 为未冻结的私有图像格式；不支持 JPEG XL。
- Codec 与归档支持尚未覆盖全部目标平台。

### 未验证缺口

- PNG、JPEG、WebP、AVIF 在各目标平台上的编解码完整组合。
- Legacy v1/v2/v3 单向导入及模型音频（见[音频 profile](../../standards/model-schema/assets-and-validation.md#common-stringssettings-与音频)）在远端传输、实际游戏与 OpenAL 设备、headless 转换、其他平台与听测上的端到端验收。
