# Java 资产与页面复杂度总览

本主题负责输入冻结、容器准入、转换交付、音频内容投影、页面短命需求、preview 与显式 export。模型资源生命周期、Catalog/session 与网络传输归 [MN](../java-models-and-network/README.md)，legacy 解码、图片提前归约和音频 decoder 能力归 [NR](../native-capabilities/README.md)，动画求值不在本主题内。

## 阅读路径

| 问题 | 页面 |
|---|---|
| AP-01–09 的行为、删除失败与证据入口 | [机制](mechanisms.md) |
| 本主题覆盖到哪里、使用哪些根依据 | [证据、覆盖与根依据](evidence-and-scope.md) |
| 局部因果、删除边界与高扇出候选 | [因果图与高扇出候选](causal-graph.md) |
| 可以从哪些上游选择减少机制 | [归约支点](reduction-pivots.md) |
| 哪些产品判断、收益或兼容语义仍未闭合 | [因果缺口与差异](open-questions.md) |

## 子系统拓扑

| 机制簇 | 主要机制 | 边界 |
|---|---|---|
| 输入冻结与验证 | AP-01/02/03/09 | 冻结实际解析闭包，分层验证，并对 raw 图片和模型音频应用各自 profile |
| 转换交付与来源准入 | AP-06/07/09 | staging 重开验证、音频投影与 direct 来源 policy 不进入 schema reader |
| 页面与 hover 需求 | AP-04/05 | 集中保存展示资格与独立准备，页面统一准入模型和图片，自动 GUI 最多离线 |
| Preview 与 export | AP-08 | 弱关联图片 cache、有限 operation admission 与完整导出 |

本主题拥有 AP 内部关系；跨域边由[跨子系统关系](../governance/cross-subsystem-relations.md)唯一维护，加载规则见[关系登记入口](../governance/relationship-register.md)。
