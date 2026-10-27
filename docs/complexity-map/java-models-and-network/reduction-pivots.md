# 归约支点

本页保留由 MN 子系统主导的归约支点；高扇出只作为检查信号，不直接决定删除。

| 支点 | 最小改变 → 可减少部分 | 剩余义务 / 语义、性能和可靠性成本 / 最小验证 |
|---|---|---|
| V-03 表示替代规则 | 如所有来源都要求 exact → MN-03 非 exact 候选及 publication/actual 双表示例外可缩减。 | **产品语义变化**，重裁 D-IDENTITY/D-REUSE；AP-02、MN-05、NR-05、Pending/Ready、旧 lease 均有独立需求。中高成本；同源重封装会增加下载。核验替代矩阵及实际带宽，不能据 R=4 宣称可删四机制。 |
| V-13 核实后收缩 publication 调度 | 测量 ordinary terminal 的实际 burst/host 开销，比较现有 terminal 数限额、较简单 owner 调度及上游错峰 → MN-19 排队/guard 组合可能缩减。 | MN-18 全 component sample-ready 与 exact-current 门禁不能删除；terminal 计数已覆盖 stale/失败但不限制单次 upload 耗时或 live bytes，取消限额可能增加卡顿，新增限额也会增加状态。中成本；混合 stale/失败/成功并覆盖 required bootstrap，测处置次数、峰值驻留和 owner 耗时。 |

不因按需机制扇出较高恢复已被拒绝的入服全量下载；其数千模型带宽/驻留成本由[分发决策](../../product-decisions/decisions/model-distribution.md)明确说明。帧预算/preview 的 V-10 由 [AP 归约页](../java-assets-and-presentation/reduction-pivots.md)统一记录，授权例外的收窄条件仍受本主题 [Q-09](open-questions.md#q-09) 约束。
