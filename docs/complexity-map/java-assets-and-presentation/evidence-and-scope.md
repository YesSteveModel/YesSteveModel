# 证据、覆盖与根依据

分类标准、Fact / Inference / Hypothesis 等级、节点与计数口径统一见[建模、证据与计数口径](../governance/modeling-and-evidence.md)。本页只限定 AP 子系统的证据范围。

## 覆盖

| 已建图内容 | 尚未验证或未查清部分 |
|---|---|
| Capture、元数据与 chunk 验证、图片策略、音频投影、集中展示资格、GUI 与选择 FULL 独立准备、staging、direct 准入、preview cache、离屏生成与 export（AP-01–09） | 全格式 golden、真实 legacy 音频包装、实机翻页与纹理回收、离屏渲染画面与成本尚未验证；AP-06 目前以现有架构交付规则为据 |

音频 fixture 在 raw、当前格式与投影器上的局部测试已经运行；其余验证边界见[支持状态](../../status/support-and-verification.md)。

## 证据入口

各机制的行为事实、直接删除导致的失败以及局部缺口由[机制正文](mechanisms.md)逐项维护；AP 内部边由[本地因果图](causal-graph.md)唯一维护，跨子系统边收录于[跨子系统关系](../governance/cross-subsystem-relations.md)。

## 根依据

根键定义与权威链接统一在[根依据总表](../governance/root-register.md)维护。

跨子系统前提：NR-11 的 archive 借用视图覆盖约束了 AP-01 必须及时完成复制。
