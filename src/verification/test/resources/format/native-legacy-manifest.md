# Native legacy Manifest vector

本向量来自 `ysm-ng/modules/test/src/legacy_historical_decoder_test.cc` 的 `ProjectsCompleteDeterministicPayloadAndPreservesBusinessAnomalies`。2026-11-06 在 Windows x64 Release 调用真实 `conversion::Convert`，原样导出 `result.payloads[0].bytes`。导出探针不属于生产或测试入口。

- SHA-256：`ddabc5c0366d088820def2ba04584c32f8360e8125a11b0c3c185ba09b55e288`。
- 预期 ModelId：`hash:000102030405060708090a0b0c0d0e0f00000000000000000000000000000000`。
- Java 的 `LegacyIdentityInteropTest` 读取这些原始 native bytes，再验证正式映射、Manifest 与 property 2。该文件只包含 Manifest；它不证明完整资产引用闭包可加载。

格式未冻结。修改本向量前须显式复核身份契约；运行测试不得通过 Java serializer 重建期望 bytes。
