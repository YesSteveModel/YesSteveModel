# 证据、覆盖与根依据

分类、证据等级、节点与计数口径统一见[建模、证据与计数口径](../governance/modeling-and-evidence.md)。

## 覆盖

| 区域 | 已建图内容 | 未闭合 / 未检查 |
|---|---|---|
| Native 计算 | Bake 布局、cache、extract、计划缓存、worker、透明与空洞；NR-04–10、16 | 各 intrinsic 等价、性能策略净收益、真实 GPU/driver 效果 |
| Native 边界 | JNI、buffer/handle、allocator、音频、archive、legacy；NR-01–03、11–15、17 | 当前 allocator 故障复现、全部平台/codec 组合、导入真实语料与实际音频设备 |
| 平台/尚未接通 | 已检查 loader、构建入口和 baseline | 未逐项审计第三方 patch、profiling 和所有游戏 hook；GPU renderer、外部模型源 provider API 及未来统一 adapter 不计为实现 |

音频 fixture 已覆盖当前 Windows 构建的真实 JNI 与精确 PCM；跨 codec 实现一致性仍未验证。

## 证据入口

[机制正文](mechanisms.md)逐项维护 ABI、资源 owner、删除失败和证据强度；[因果图](causal-graph.md)唯一维护 NR 内部边，跨域边进入[跨子系统关系](../governance/cross-subsystem-relations.md)。

## 根依据

本主题消费的根键定义与权威链接见[根依据总表](../governance/root-register.md)。

## 详细覆盖边界

NR-14 的生产消费者包括 `OpusDecoder` JNI handle、Java `PlaybackAudioStream` 增量 worker、legacy 时间轴投影及播放 runtime；真实 OpenAL 设备与长期 native allocation 回收仍未验证。

Native 构建及 Java loader 已检查到平台入口、SIMD baseline 与依赖交付边界；未把各平台 CMake 分支按数量计成机制，也未执行平台构建。[平台基线](../../product-decisions/decisions/platform-baselines.md)是要求，当前 Windows/ISA/macOS 等差距不反向放宽它。

未展开第三方 codec 算法、每条 intrinsic、所有第三方 patch、profiling 内部和全部 OS 实机行为。`.inc` 中的 extract 实现按实时源码核验；索引缺边不作不可达证明。
