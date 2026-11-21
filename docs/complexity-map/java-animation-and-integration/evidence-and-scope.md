# 证据、覆盖与根依据

分类标准、证据等级、节点与计数口径统一见[建模、证据与计数口径](../governance/modeling-and-evidence.md)。

## 覆盖

| 已建图内容 | 尚未验证或未查清部分 |
|---|---|
| 实体状态、异步与多 pass 调度、动作门禁、`defer` 排空、模型声音完整后端与宿主适配器、旋转混合补丁、兼容 checker、生成的 builtin 与调用计划、controller 来源缓存、输出桥（AN-01–06、08–12、19–21） | Forge 与扩展联动、第三方线程安全、完整数值与画面表现、真实 OpenAL 与设备容量尚未验证；AN-19 在 Forge 实机中的排空时机，以及 AN-21 在包含可选扩展的 classpath 下的行为也未核验 |

声音的 pending、submitted 与结束顺序已有局部自动化测试，但 Forge 下的单次生效约束、真实宿主的拒绝或替换与设备容量、第三方音频后端、真实多 pass 画面以及跨线程可见性仍需运行验证。

实体交接方面的受控 executor、JNI owner、Roaming 与瞬时事件测试覆盖了局部所有权与失败收尾，但不证明真实 Forge 中的全部正常返回接线或调度收益。

Controller 缓存在真实 Coded 与 Bedrock 序列上覆盖了切换、显式重播、多 player 复用、失败恢复、来源失效和结束撤引用；资源 fixture 区分了稳定缺失、失败回退与不完整范围。边界计数只说明减少了共享解析调用，不证明吞吐、内存分配或大量动态名称占用的字节成本，这些仍需在相同条件下实测采样。

## 证据入口

[机制正文](mechanisms.md)逐项记录 Fact、Inference、Hypothesis 以及直接删除导致的失败；[因果图](causal-graph.md)唯一维护 AN 内部边，跨子系统边收录于[跨子系统关系](../governance/cross-subsystem-relations.md)。

## 根依据

本主题使用的根键定义与权威链接见[根依据总表](../governance/root-register.md)。

模型资源 lease、native 视图与 worker 完成状态属于跨子系统前提，不会转移到 AN 成为第二权威。
