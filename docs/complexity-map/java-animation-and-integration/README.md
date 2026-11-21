# Java 动画、绘制交接与扩展复杂度总览

本主题涵盖实体私有求值状态、调度与采样、多 pass 输出、动作副作用、模型声音触发、完整音频后端与宿主适配器、controller 回退、可选扩展隔离，以及 Java 到宿主顶点 consumer 的交接。模型资源与声音缓存归 [MN](../java-models-and-network/README.md)，native worker、帧状态、透明重排与音频 decoder 能力归 [NR](../native-capabilities/README.md)。

## 阅读路径

| 问题 | 页面 |
|---|---|
| AN 机制的具体行为、删除失败后果与证据入口 | [机制](mechanisms.md) |
| 动画、绘制交接和扩展的覆盖范围 | [证据、覆盖与根依据](evidence-and-scope.md) |
| 求值、多 pass、副作用与输出桥的因果关系 | [因果图与高扇出候选](causal-graph.md) |
| 调度、姿态、输出路径与扩展检查如何归约 | [归约支点](reduction-pivots.md) |
| 哪些兼容、性能或线程语义尚未明确 | [因果缺口与差异](open-questions.md) |

## 子系统拓扑

| 机制簇 | 主要机制 | 边界 |
|---|---|---|
| 实体状态与调度 | AN-01–04 | 只读资源可共享，可变求值状态归实体私有；异步、限频与多 pass 各自协调 |
| 动作与 controller | AN-05、06、19、20 | 动作能力门禁、模型覆盖与内建动作桥、延迟参数捕获以及声音完整后端所有权 |
| 扩展隔离与 builtin 生成 | AN-08–10、21 | 历史旋转混合补丁、兼容 checker、加载前隔离以及生成式声明与调用计划 |
| 宿主输出交接 | AN-11、12 | Direct 与 fallback 两条 consumer 路径，以及成功后才提交宿主输出 |

本主题维护 AN 内部的所有关系；跨子系统边由[跨子系统关系](../governance/cross-subsystem-relations.md)唯一维护，按需加载规则见[关系登记入口](../governance/relationship-register.md)。
