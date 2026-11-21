# 证据、覆盖与根依据

分类、证据等级、节点与计数口径统一见[建模、证据与计数口径](../governance/modeling-and-evidence.md)。

## 覆盖

| 区域 | 已建图内容 | 未覆盖与未验证 |
|---|---|---|
| Native 计算 | 烘焙布局、缓存、帧提取、渲染计划缓存、并行 worker、透明重排与空洞清理（NR-04–10、16） | 各指令集 intrinsic 的结果等价性、性能策略的净收益以及真实 GPU 与驱动上的表现 |
| Native 边界 | JNI 绑定、buffer 与 handle 交接、allocator 补偿、音频解码、archive 读取与 legacy 导入（NR-01–03、11–15、17） | 当前 allocator 故障的复现条件、全部平台与 codec 组合、真实导入语料以及真实音频设备表现 |
| 平台与未接通路径 | 已核查加载器、构建入口和平台基线 | 未逐项审计第三方 patch、profiling 代码和所有游戏 hook；GPU 渲染器、外部模型源 provider API 及未来统一适配层不计入当前已实现范围 |

音频 fixture 已在当前 Windows 构建下验证真实 JNI 调用与精确 PCM 输出，但跨 codec 实现的一致性仍未验证。

## 证据入口

[机制正文](mechanisms.md)逐项记录 ABI 约束、资源所有权、直接删除引发的失败以及证据强度；[因果图](causal-graph.md)维护 NR 内部边，跨域边统一维护在[跨子系统关系](../governance/cross-subsystem-relations.md)。

## 根依据

本主题引用的根键定义与权威链接统一维护在[根依据总表](../governance/root-register.md)。

## 详细覆盖边界

NR-14 的生产消费者包括 `OpusDecoder` 的 JNI handle、Java 侧 `PlaybackAudioStream` 增量 worker、legacy 时间轴转换以及播放 runtime；真实 OpenAL 设备表现和长期运行下的 native 内存回收仍未验证。

Native 构建与 Java 加载器已检查到各平台入口、SIMD 基线与依赖交付边界；本图未把各个平台的 CMake 分支按数量拆成独立机制，也未执行全平台构建。[平台基线](../../product-decisions/decisions/platform-baselines.md)是硬性要求，当前在 Windows、指令集或 macOS 上的实现差距不能反向放宽该基线。

本图未展开第三方 codec 内部算法、每条 SIMD intrinsic、所有第三方 patch、profiling 内部细节以及全部操作系统的实机表现。`.inc` 文件中的提取实现按实时源码核验；静态代码索引里缺少调用边不能当作“不可达”的证明。
