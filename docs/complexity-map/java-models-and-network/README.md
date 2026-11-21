# Java 模型、资源与网络复杂度总览

本主题覆盖 Catalog 发布、精确内容存储、Ready 与 Pending 生命周期、声音数据保留、失败恢复、会话隔离、按类型区分的资产传输与分发、玩家状态视图、表达式跨端语义身份以及派生缓存协调。输入转换与页面展示归 [AP](../java-assets-and-presentation/README.md)，实体动画与声音触发归 [AN](../java-animation-and-integration/README.md)，native 烘焙、逐帧计算与音频解码能力归 [NR](../native-capabilities/README.md)。

## 阅读路径

| 问题 | 页面 |
|---|---|
| MN 机制的行为、删除失败与证据入口 | [机制](mechanisms.md) |
| 模型、资源和网络各覆盖到哪里 | [证据、覆盖与根依据](evidence-and-scope.md) |
| 局部因果、协调关系与高扇出候选 | [因果图与高扇出候选](causal-graph.md) |
| 表示、发布流程和缓存可以如何归约 | [归约支点](reduction-pivots.md) |
| 存储、状态与运行成本上还有哪些未验证缺口 | [因果缺口与差异](open-questions.md) |

## 子系统拓扑

| 机制簇 | 主要机制 | 边界 |
|---|---|---|
| Catalog 与精确内容 | MN-01–03、05、24–25 | 完整发布、表示替代、精确读取、converted 消费者登记以及声音获取与保留 |
| Ready/Pending 与恢复 | MN-06–10、17–20、25、32 | lease 与 LRU、有限工作调度、默认模型、期望与已安装状态、Flight、宿主纹理发布、失败记忆、音频保留回收与启动一次性预热 |
| Session 与传输 | MN-11–14 | 精确会话隔离与有界缺图观察、按类型组装、超帧父动作、已接纳分发、当前选择聚合与原操作资格 |
| 状态与存储边界 | MN-16、22、26–31 | 玩家状态视图、物理路径约束、按业务划分的数据库、UUID 偏好、有界写缓冲、配置分类、表达式跨端共享语义与本地特化身份 |

本主题负责维护 MN 内部关系；跨域关系由[跨子系统关系](../governance/cross-subsystem-relations.md)统一维护，加载规则见[关系登记入口](../governance/relationship-register.md)。
