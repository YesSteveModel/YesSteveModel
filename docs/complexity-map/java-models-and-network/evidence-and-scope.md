# 证据、覆盖与根依据

分类、Fact/Inference/Hypothesis、节点与计数口径统一见[建模、证据与计数口径](../governance/modeling-and-evidence.md)。本页限定 MN 子系统的模型、资源与网络覆盖。

## 覆盖

| 区域 | 已建图内容 | 未闭合 / 未检查 |
|---|---|---|
| 模型/资源 | 增量目录、激活、精确读取、Ready lease/LRU、Pending interest、纹理发布/admission、default、fallback、失败记忆、converted consumer/prune、声音首块准入/渐进取得与 PCM/checkpoint 保留、表达式本地 specialization 身份；MN-01–03、05–08、10、17–20、22–25、27 | 大目录驻留、GC/host/audio backing 回收、真实 remote 音频、全部 shutdown 时序、其他平台真实共享 FS 与最坏 host admission 成本未验；MN-27 已观察无 optional provider 的 default 专用服启动，optional-present 与真实 client classloader 仍未验；共享 writer 收益见 Q-10 |
| 网络/状态 | Session、typed request/assembly、dispatch、forced selection、player projection、表达式 shared semantics；MN-11–16、26 | 真实 Forge 双端、全部跨消息时序、持续拥塞与重连未验 |

Windows 双 JVM 已局部验证同 key 完整容器提交、类别 index 合并、活跃／崩溃登记与 scope prune。注入来源验证区域隔离与迟到工作；公开地址及全局发现仍需联合验收。

测试和静态关系只提供可定位 oracle，不证明真实 Forge/OpenAL 提交与终态、持续拥塞、GPU/host/音频 backing 回收或未测试平台的跨进程文件系统行为已通过。

## 证据入口

[机制正文](mechanisms.md)逐项维护当前行为、直接删除失败与源码/测试入口；[因果图](causal-graph.md)唯一维护 MN 内部边，跨域边进入[跨子系统关系](../governance/cross-subsystem-relations.md)。

## 根依据

本主题使用的根键定义与权威链接只在[根依据总表](../governance/root-register.md)维护。

产品键不把当前 queue、cache 或状态表示固化为唯一实现。
