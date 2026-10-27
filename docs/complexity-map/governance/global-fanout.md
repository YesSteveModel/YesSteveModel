# 全局高扇出候选

本页只在全局归约、排名或复算时读取。Fact 边由四个子系统关系页和[跨子系统关系](cross-subsystem-relations.md)共同拥有，分片入口见[关系登记入口](relationship-register.md)。


`D` = Fact 因果图中去重直接后继数，问题/机制均可计；`R` = 沿 Fact 因果边到达的独立生产机制数。Shared 节点一次，AP/MN/AN/NR 不按语言实现次数翻倍。同一产品 DD 的多个短键合并为一个候选，以这些键的直接邻接并集计算 D，再遍历计算 R；A 键分别表示内部选择。下表不重复排名同一 DD 的不同摘要，也不把它们当成多项决策。计数仅描述已有 Fact 关系投影的覆盖下界，排名只适用于此范围，不是全部运行机制的审计结果。

按 R、再 D 排序，头部表在此范围识别跨域共同来源；R=1 的局部选择仍保留在因果表。主循环影响严重但动机边不足以入主数，单列扩展范围，不凑高排名。

| 候选 | R | D | 当前解释 |
|---|---:|---:|---|
| D-FAIL / D-SESSION-FAIL / D-EXT / D-HOST | 9 | 7 | 同一局部失败 DD；包含 target、session、联动、generated optional plan、JNI、宿主提交等不同故障边界，不表示可统一成一个 owner |
| A-NATIVE | 6 | 3 | ABI、音频 decoder 与宿主顶点交接共享来源 |
| D-SOURCE | 5 | 5 | 用户来源只读与派生物独立处置；精确读取、转换交付、direct 准入、preview cache 与 converted prune 各保留自身 owner |
| D-FREEZE / D-MIGRATION | 5 | 5 | 同一语义冻结 DD；capture、staging、完整 export、音频 current profile 和单向投影 |
| D-CATALOG / D-HOLD / D-EXACT | 4 | 4 | 同一当前目录 DD；完整发布、旧使用保活与派生物完整性 |
| D-IDENTITY | 4 | 4 | 本地替代、精确 cache 与分层验证共享来源 |
| D-JOIN / D-CHUNKS / D-META / D-VERIFY | 4 | 4 | 同一按需内容 DD；分层可用性、离线预览和 typed assembly |
| D-SOUND | 4 | 4 | 可观察模型声音分别落到内容 profile、首块/渐进资源、完整播放 owner 与 native codec 能力 |
| D-DEFAULT / D-FALLBACK / D-HOST-FALLBACK | 4 | 3 | 同一默认基线 DD；启动闭合、分类切换、本地 specialization 及其恢复记忆 |
| A-CONTINUOUS-DEMAND | 4 | 1 | 短命意图门槛覆盖页面、hover 与 entity，并沿模型恢复边界到 exact 失败记忆 |
| A-PARALLEL | 4 | 1 | 固定区间衍生同步、空洞与透明暂存 |
| A-AUDIO-RETENTION | 3 | 3 | 完整/首块保留分别要求缓存与活动计价、消费驱动恢复、不可变 checkpoint；影响 MN-25、AN-20、NR-14 |
| A-RESOURCE | 3 | 3 | Ready 可达性、Pending interest 与表达式 specialization 的本地资源身份分离 |
| D-ENTITY | 3 | 3 | 实体隔离同时落到私有求值状态、generated component plan 与本地 specialization |
| D-SCRIPT | 3 | 3 | PlayerState、瞬时 shared semantics 与本地产物身份分离，三者不共享 baseline 或地址 |
| D-VALIDATE | 3 | 3 | 资产请求、player reports 与表达式 shared semantics |
| A-AN-02 | 2 | 2 | 多 pass 共用历史衍生恢复与动作门禁 |
| A-AN-04 | 2 | 2 | 生成期能力同时落到扩展 checker 与 builtin typed plan，但两者拥有不同失败边界 |
| A-IMAGE | 2 | 2 | Raw 的输入门禁/失败回退与 legacy 的解码期表示归约共享用途策略 |
| A-LEGACY-READ | 2 | 2 | 有界 buffer ingress 与完整模型解码衍生 source 边界及图片峰值表示 |
| A-ONE-ARTIFACT | 2 | 2 | 统一出口下的 staging 与 legacy result 交付 |
| D-CAPABILITY | 2 | 2 | Typed admission 与物理 filesystem sink |
| D-EFFECT | 2 | 2 | 动作求值窗口门禁同时约束普通 effect 与模型声音提交；允许 effect 后的部分失败仍没有全局回滚 |
| D-REUSE | 2 | 2 | 本地替代与跨会话 exact reuse |
| D-SESSION | 2 | 2 | Session identity 与有限工作 retirement |
| A-ARCHIVE | 2 | 1 | 借用 extraction 衍生 owning capture |
| A-SIMD | 2 | 1 | 热布局衍生 cache 能力隔离 |
| A-TEXTURE-PUBLISH | 2 | 1 | Host 完整发布再衍生延后 admission |
| A-FAILURE-MEMORY | 1 | 1 | 模型请求与 lazy 资源失败资格共用同一 exact registry |
| D-RECOVER | 1 | 1 | 模型请求与 lazy 资源的失败记忆 |

扩展候选：D-LOOP 的主数 R=0、D=0，经 Inference 到具体策略后的候选 R=10；D-GUI 为 0/0，候选 R=5；D-IMAGE 为 0/0，候选 R=2。D-GUI 的扩展范围经当前意图到 MN-20，只表示短命 entity 需求与失败资格相遇，不表示 GUI 拥有模型失败记录。AN-08/13、NR-03 的存在有证据，但根理由仍是 H，不能加入 Fact 祖先的 R。A-FORCED 的 1/1 只证明一个现存特权衍生一个维护机制，产品必要性仍未定。

可复算方法：从 Fact 表展开 `From → each(To)`；按根表的同一 DD 链接合并产品短键，每个候选先计这些键的邻接并集为 D，再做有 visited 去重的遍历，遇到机制页列出的生产 ID 则计 R，同时继续沿其因果边走。对表外关系、节点引用数、普通调用、含机制名称的文字不计数。投影图中的折叠集合也不参与计算。合并摘要只用于此视图，不改写根表中的适用范围或机制的独立删除条件。
