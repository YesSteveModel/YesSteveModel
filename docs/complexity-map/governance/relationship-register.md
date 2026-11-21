# 关系登记入口

本页提供关系分片索引、关系类型定义与完整性检查入口。各机制的具体行为见对应子系统的 `mechanisms.md`。

## 按需加载

| 问题 | 只需加载 |
|---|---|
| 单一子系统的原因、失败机会、协调关系或删除边界 | 对应子系统的 `causal-graph.md` |
| 跨越两个及以上子系统的关系 | [跨子系统关系](cross-subsystem-relations.md)，再按需读取两端对应的子系统页 |
| 全局高扇出、R/D 排名或复算方法 | [全局高扇出候选](global-fanout.md) |
| 完整关系审计 | 全部五个关系分片，或在仓库根目录运行 `python docs/tools/check_complexity_relationships.py` |

## 因果图

Fact 因果边使用三种关系类型：`creates` 表示上游产生失败机会或派生问题，`addresses` 表示机制解决该问题，`requires` 表示契约义务落到当前机制上（不代表当前实现唯一）。每条关系只在全图登记一次：单子系统内部关系归对应子系统维护，跨子系统关系归跨子系统页维护，尚未收尾的叶节点归来源机制的 owner 维护。

| 分片 | 权威页面 |
|---|---|
| AP | [Java 资产与页面](../java-assets-and-presentation/causal-graph.md#fact-因果关系) |
| MN | [Java 模型、资源与网络](../java-models-and-network/causal-graph.md#fact-因果关系) |
| AN | [Java 动画、绘制交接与扩展](../java-animation-and-integration/causal-graph.md#fact-因果关系) |
| NR | [Native 能力层](../native-capabilities/causal-graph.md#fact-因果关系) |
| 跨子系统 | [跨子系统关系](cross-subsystem-relations.md) |

非因果关系不计入 Fact 因果统计，包含以下七种类型：
- `coordinates`：双方存在双向协调。
- `depends`：左方依赖或使用右方。
- `preserves`：机制维持某项产品语义。
- `independent-authority`：两端各自独立判定，不共享决定权。
- `conflicts`：当前实现或选择与上游规定冲突。
- `motivates`：仅作为候选动机参考。
- `conformance-pending`：设计上允许，但运行语义仍待验证。

## 高扇出决策候选

全局排名、扩展候选与复算方法见[全局高扇出候选](global-fanout.md)；各子系统内部的高扇出候选由对应子系统页解释。

## 完整性检查

`docs/tools/check_complexity_relationships.py` 会从五个关系分片重建完整的 Fact 图，核对关系唯一性、分片归属与机制端点，并复算全局高扇出表中的 R/D；运行 `docs/tools/check_docs.py` 时会自动执行该检查。
