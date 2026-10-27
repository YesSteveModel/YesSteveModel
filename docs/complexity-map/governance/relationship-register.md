# 关系登记入口

本页提供关系分片、类型与检查入口。机制语义见各子系统的 `mechanisms.md`。

## 按需加载

| 问题 | 只需加载 |
|---|---|
| 单一子系统的原因、失败机会、协调或删除边界 | 对应子系统的 `causal-graph.md` |
| 关系跨越两个以上子系统 | [跨子系统关系](cross-subsystem-relations.md)，再按需读取两端子系统页 |
| 全局高扇出、R/D 排名或复算方法 | [全局高扇出候选](global-fanout.md) |
| 完整关系审计 | 全部五个关系分片，或从仓库根目录运行 `python docs/tools/check_complexity_relationships.py` |

## 因果图

`creates` 表示上游产生失败机会或派生问题，`addresses` 表示机制处理该问题，`requires` 表示契约义务在当前机制中的落点，不表示实现唯一。每条关系只登记一次：单命名空间关系归该子系统，跨命名空间关系归跨子系统页，未闭合叶节点归来源机制 owner。

| 分片 | 权威页面 |
|---|---|
| AP | [Java 资产与页面](../java-assets-and-presentation/causal-graph.md#fact-因果关系) |
| MN | [Java 模型、资源与网络](../java-models-and-network/causal-graph.md#fact-因果关系) |
| AN | [Java 动画、绘制交接与扩展](../java-animation-and-integration/causal-graph.md#fact-因果关系) |
| NR | [Native 能力层](../native-capabilities/causal-graph.md#fact-因果关系) |
| 跨子系统 | [跨子系统关系](cross-subsystem-relations.md) |

非因果关系中，`coordinates` 为双向协调，`depends` 为左方使用右方，`preserves` 为机制保持产品语义，`independent-authority` 表示两端不共享裁决权，`conflicts` 为实现/选择与规定冲突，`motivates` 仅是候选动机，`conformance-pending` 表示设计允许但运行语义仍待验证。它们不进入 Fact 因果计数。

## 高扇出决策候选

全局排名、扩展候选和复算方法已移至[全局高扇出候选](global-fanout.md)。各子系统页保留不带全局计数的本地候选解释。

## 完整性检查

`docs/tools/check_complexity_relationships.py` 从五个分片重建 Fact 图，检查关系唯一性、分片归属与机制端点，并复算全局高扇出表中的 R/D；`docs/tools/check_docs.py` 会一并运行该检查。
