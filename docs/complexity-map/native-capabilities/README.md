# Native 能力层复杂度总览

本主题覆盖 JNI 绑定与内存协议、音频解析与解码、按能力区分的烘焙与缓存、逐帧状态、并行 worker 与顶点输出、归档（archive）读取以及 legacy 导入器。Java 侧领域权威、模型资源 lease 与音频保留归 [MN](../java-models-and-network/README.md)，资产转换与页面策略归 [AP](../java-assets-and-presentation/README.md)，宿主适配与动画集成归 [AN](../java-animation-and-integration/README.md)。

## 阅读路径

| 问题 | 页面 |
|---|---|
| NR 机制的行为、删除失败与证据入口 | [机制](mechanisms.md) |
| Native 计算、边界和平台覆盖到哪里 | [证据、覆盖与根依据](evidence-and-scope.md) |
| JNI、布局、worker、借用和 legacy 的因果关系 | [因果图与高扇出候选](causal-graph.md) |
| 缓存、归档与补偿机制可以如何归约 | [归约支点](reduction-pivots.md) |
| Allocator、缓存身份等哪些前提尚未确认 | [因果缺口与差异](open-questions.md) |

## 子系统拓扑

| 机制簇 | 主要机制 | 边界 |
|---|---|---|
| JNI、ABI 与音频 | NR-01–03、14–15 | 一次性绑定、范围与所有权交接、音频解析与解码、错误收口以及 allocator 补偿 |
| Bake、cache 与帧状态 | NR-04–07 | SIMD 热布局、序列化缓存、借用帧 view 与渲染计划失效 |
| 并行输出 | NR-08–10、16 | 固定输出区间、任务完成协议、透明重排与预留容量空洞清理 |
| Archive 与 legacy | NR-11–13、17 | 借用提取缓存、有界输入读取、类型化结果交接与图片表示提前归约 |

本主题负责维护 NR 内部关系；跨域关系由[跨子系统关系](../governance/cross-subsystem-relations.md)统一维护，加载规则见[关系登记入口](../governance/relationship-register.md)。
