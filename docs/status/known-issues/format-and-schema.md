# 格式、Schema 与编解码已知问题

格式与版本以[一致性要求](../../standards/conformance.md)及其标准入口为准。

## 一致性与转换

- Asset Container 与 Model Schema 缺跨实现 portable binary golden；同一实现自产自读不能证明互操作。
- Canonicalizer 已覆盖冻结 capture、无符号 UTF-8 排序和 little-endian records，仍缺跨 source adapter 的公开 golden。
- Verification 的 hash/signLength 与精确 preamble 已接线，但 opaque `sign` 不提供作者、授权或抗篡改保证。固定字符串 ASCII、图像 payload 长度及 metadata 一致性仍有严格校验偏差。
- Raw 几何转换会误写 visible bounds offset，可能使转换后的模型范围信息错误。
- Direct 成品与 raw/builtin/legacy 内部来源的缺图准入矩阵已有 Java 局部测试，仍缺完整产品入口与跨平台媒体组合验收。规则见[来源准入](../../architecture/asset-pipeline/implementation/conversion-and-export.md#preview-取得与显式-export)。

## Codec、导入与平台

- `ZTX` 是未冻结的私有图像格式。PNG、JPEG、WebP、AVIF 与各平台的 encode/decode 组合尚未系统验证。
- Legacy v1/v2/v3 单向导入已在 Windows x64 完成 197 个真实 source 的 staging、生产 reader 重开和发布，包含声音归约；remote、实际游戏、headless 转换、其他平台与听测仍缺端到端验收。转换边界见[历史输入投影](../../architecture/asset-pipeline/implementation/conversion-and-export.md#历史输入的单向投影)。
- 模型音频的 raw/current、legacy projector、JNI 解码和 builtin 物化已有局部自动化；真实语料不能替代 remote session、Minecraft/OpenAL 设备、headless、跨平台或听测证据。表示规则见[音频 profile](../../standards/model-schema/assets-and-validation.md#common-stringssettings-与音频)。
- 当前源码在 MSVC 19.51 fresh Release 构建时因 GNU-style `asm volatile` 失败。既有 Release binary 的 bake/JNI 测试不能替代当前源码生成的新 native artifact。
- Codec 与 archive 尚未覆盖全部产品平台；存在构建目标或库文件不能证明产品入口可用。
