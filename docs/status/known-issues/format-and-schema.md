# 格式、Schema 与编解码已知问题

格式与版本要求以[一致性要求](../../standards/conformance.md)及各标准入口为准。

## 一致性与转换

- Asset Container 与 Model Schema 仍缺少跨实现的可移植二进制 golden；同一实现自产自读不能证明跨实现互操作。
- Canonicalizer 已覆盖冻结捕获、无符号 UTF-8 排序和小端序记录，但仍缺少跨 source adapter 的公开 golden。
- Verification 的 `hash`、`signLength` 与精确 preamble 已接线，但其中不透明的 `sign` 不提供作者身份、授权或抗篡改保证。固定字符串的 ASCII 校验、图像 payload 长度以及 metadata 一致性仍存在严格校验偏差。
- Raw 几何转换会误写 visible bounds offset，可能导致转换后模型的包围盒范围信息出错。
- Direct 成品与 raw、builtin、legacy 内部来源的缺图准入矩阵已有 Java 局部测试，但仍缺少完整产品入口与跨平台媒体组合的端到端验收。规则见[来源准入](../../architecture/asset-pipeline/implementation/conversion-and-export.md#preview-取得与显式-export)。

## Codec、导入与平台

- `ZTX` 是未冻结的私有图像格式。PNG、JPEG、WebP、AVIF 在各平台上的编解码组合尚未完成系统验证。
- Legacy v1/v2/v3 单向导入已在 Windows x64 上完成 197 个真实源的暂存、生产读取器重开与发布（含声音归约），但远端传输、实际游戏、headless 转换、其他平台与听测仍缺少端到端验收。转换边界见[历史输入投影](../../architecture/asset-pipeline/implementation/conversion-and-export.md#历史输入的单向投影)。
- 模型音频的 raw 与当前格式、legacy 转换器、JNI 解码与 builtin 物化已有局部自动化证据；真实语料测试不能替代远端会话、Minecraft 与 OpenAL 设备、headless、跨平台或听测证据。表示规则见[音频 profile](../../standards/model-schema/assets-and-validation.md#common-stringssettings-与音频)。
- 当前源码在 MSVC 19.51 fresh Release 构建时会因 GNU 风格的 `asm volatile` 报错失败。基于既有 Release 二进制的 bake 与 JNI 测试不能替代由当前源码编出的新 native 产物。
- Codec 与归档支持尚未覆盖全部目标平台；存在构建目标或库文件不代表对应产品入口已可用。
