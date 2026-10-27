# Java 模型、资源与网络复杂度总览

本主题负责 Catalog 发布、精确内容存储、Ready/Pending 生命周期、声音表示保留、失败恢复、session 隔离、typed 传输、dispatch、player projection、表达式跨端语义身份与派生物协调。输入转换和页面展示归 [AP](../java-assets-and-presentation/README.md)，实体动画与声音触发归 [AN](../java-animation-and-integration/README.md)，native bake、逐帧计算与音频 decoder 能力归 [NR](../native-capabilities/README.md)。

## 阅读路径

| 问题 | 页面 |
|---|---|
| MN 机制的行为、删除失败与证据入口 | [机制](mechanisms.md) |
| 模型、资源和网络各覆盖到哪里 | [证据、覆盖与根依据](evidence-and-scope.md) |
| 局部因果、协调关系与高扇出候选 | [因果图与高扇出候选](causal-graph.md) |
| 表示、publication 和缓存可以如何归约 | [归约支点](reduction-pivots.md) |
| 强制选择、共享 writer 等哪些判断未闭合 | [因果缺口与差异](open-questions.md) |

## 子系统拓扑

| 机制簇 | 主要机制 | 边界 |
|---|---|---|
| Catalog 与精确内容 | MN-01–03、05、24–25 | 完整发布、表示替代、精确读取、converted 消费者与声音取得/保留 |
| Ready/Pending 与恢复 | MN-06–10、17–20、25 | lease/LRU、有限工作、default、desired/installed、Flight、host publication、失败资格与音频表示退休 |
| Session 与传输 | MN-11–15 | exact session 与有界缺图观察、typed assembly、超帧 parent、accepted dispatch 与 forced-selection 例外 |
| 状态与存储边界 | MN-16、22–23、26–27 | player projection、物理路径约束、共享派生物 writer、表达式 shared semantics 与本地 specialization identity |

本主题拥有 MN 内部关系；跨域边由[跨子系统关系](../governance/cross-subsystem-relations.md)唯一维护，加载规则见[关系登记入口](../governance/relationship-register.md)。
