# Java 动画、绘制交接与扩展复杂度总览

本主题负责实体私有求值状态、调度与采样、多 pass 输出、动作副作用、模型声音触发/完整后端与 host adapter、controller 回退、可选扩展隔离以及 Java 到宿主顶点 consumer 的交接。模型资源与声音保留归 [MN](../java-models-and-network/README.md)，native worker、帧 state、透明重排和音频 decoder 能力归 [NR](../native-capabilities/README.md)。

## 阅读路径

| 问题 | 页面 |
|---|---|
| AN 机制的行为、删除失败与证据入口 | [机制](mechanisms.md) |
| 动画、绘制交接和扩展覆盖到哪里 | [证据、覆盖与根依据](evidence-and-scope.md) |
| 求值、多 pass、副作用和输出桥的因果关系 | [因果图与高扇出候选](causal-graph.md) |
| 调度、pose、输出路径和扩展检查如何归约 | [归约支点](reduction-pivots.md) |
| 哪些兼容、性能或线程语义仍未闭合 | [因果缺口与差异](open-questions.md) |

## 子系统拓扑

| 机制簇 | 主要机制 | 边界 |
|---|---|---|
| 实体状态与调度 | AN-01–04 | 只读资源可共享，可变求值状态实体私有；异步、限频与多 pass 分别协调 |
| 动作与 controller | AN-05/06/19/20 | 动作能力、模型覆盖/内建动作桥、deferred capture 与声音完整后端 ownership |
| 扩展隔离与 builtin 生成 | AN-08–10/13/21 | 历史混合、checker、加载前隔离、生成式声明/call plan |
| 宿主输出交接 | AN-11/12 | Direct/fallback 两条 consumer 路径和成功后提交 |

本主题拥有 AN 内部关系；跨域边由[跨子系统关系](../governance/cross-subsystem-relations.md)唯一维护，加载规则见[关系登记入口](../governance/relationship-register.md)。
