# Java 资产与页面复杂度总览

本主题涵盖输入冻结、容器准入、转换交付、音频内容投影、页面短命需求、preview 与显式 export。模型资源生命周期、Catalog、session 与网络传输归 [MN](../java-models-and-network/README.md)，legacy 解码、图片提前归约和音频 decoder 能力归 [NR](../native-capabilities/README.md)，动画求值也不在本主题内。

## 阅读路径

| 问题 | 页面 |
|---|---|
| AP-01–09 的具体行为、删除失败后果与证据入口 | [机制](mechanisms.md) |
| 本主题的覆盖范围与所用根依据 | [证据、覆盖与根依据](evidence-and-scope.md) |
| 局部因果、删除边界与高扇出候选 | [因果图与高扇出候选](causal-graph.md) |
| 可以从哪些上游选择入手减少机制 | [归约支点](reduction-pivots.md) |
| 哪些产品判断、收益或兼容语义尚未明确 | [因果缺口与差异](open-questions.md) |

## 子系统拓扑

| 机制簇 | 主要机制 | 边界 |
|---|---|---|
| 输入冻结与验证 | AP-01、02、03、09 | 冻结实际解析闭包，按对象分层验证，并对 raw 图片与模型音频分别应用对应 profile |
| 转换交付与来源准入 | AP-06、07、09 | staging 重开验证、音频投影以及 direct 来源准入策略均留在上层，不侵入 schema reader |
| 页面与 hover 需求 | AP-04、05 | 集中保存展示资格与独立准备，页面统一管理模型与图片准入，自动 GUI 最多做离线准备 |
| Preview 与 export | AP-08 | 弱关联图片 cache、有限操作准入与完整容器导出 |

本主题维护 AP 内部的所有关系；跨子系统边由[跨子系统关系](../governance/cross-subsystem-relations.md)唯一维护，按需加载规则见[关系登记入口](../governance/relationship-register.md)。
