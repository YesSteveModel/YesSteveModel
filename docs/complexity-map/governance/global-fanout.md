# 全局高扇出候选

本页仅在做全局归约、排名或复算时读取。所有 Fact 边由四个子系统关系页和[跨子系统关系](cross-subsystem-relations.md)共同维护，分片入口见[关系登记入口](relationship-register.md)。

表中两个指标的定义如下：
- `D`：Fact 因果图中去重后的直接后继数（问题节点与机制节点均计入）。
- `R`：沿 Fact 因果边可达的独立生产机制数。共享节点只计一次，AP、MN、AN、NR 机制不按语言实现次数翻倍。

指向同一产品 DD 的多个短键合并为一个候选：先对这些键的直接邻接取并集得到 `D`，再遍历计算 `R`，避免重复排名同一 DD 的不同摘要或将其误算为多项决策；`A-` 键则分别代表独立的内部架构选择。这些计数仅反映已有 Fact 关系的覆盖下界，不代表全系统运行机制的完整审计。

下表按 `R` 降序、再按 `D` 降序排列，用于识别跨子系统的共同来源；`R=1` 的局部选择仍保留在因果表中。主循环目标虽然影响面大，但动机边不足以计入主表，因此在文末单列扩展范围，不人为凑高排名。

| 候选 | R | D | 当前解释 |
|---|---:|---:|---|
| D-FAIL / D-SESSION-FAIL / D-EXT / D-HOST | 9 | 7 | 对应同一局部失败 DD，覆盖 target、session、联动扩展、生成的可选计划、JNI 与宿主提交等各自独立的故障边界，不能合并为单一 owner |
| A-NATIVE | 6 | 3 | ABI 交接、音频 decoder 与宿主顶点提交共用同一跨语言来源 |
| D-SOURCE | 5 | 5 | 用户来源只读且派生物独立处置；精确读取、转换交付、direct 准入、preview cache 与 converted 清理各自保留独立的 owner |
| D-FREEZE / D-MIGRATION | 5 | 5 | 对应同一语义冻结 DD，落到输入 capture、staging、完整 export、当前音频 profile 与单向投影 |
| D-CATALOG / D-HOLD / D-EXACT | 4 | 4 | 对应同一当前目录 DD，涵盖完整发布、旧使用保活与派生物完整性 |
| D-IDENTITY | 4 | 4 | 本地替代、精确 cache 与分层验证共享同一身份边界 |
| D-JOIN / D-CHUNKS / D-META / D-VERIFY | 4 | 4 | 对应同一按需内容 DD，涵盖分层可用性、离线预览与按类型组装 |
| D-SOUND | 4 | 4 | 可观察模型声音分别落到内容 profile、首块与渐进资源、完整播放 owner 以及 native codec 能力 |
| D-DEFAULT / D-FALLBACK / D-HOST-FALLBACK | 4 | 3 | 对应同一默认基线 DD，涵盖启动时补齐兜底基线、分类回退、本地特化及其恢复记忆 |
| A-CONTINUOUS-DEMAND | 4 | 1 | 短命意图门槛覆盖页面、hover 与实体，并沿模型恢复边界延伸到精确匹配的失败记忆 |
| A-PARALLEL | 4 | 1 | 固定并行区间衍生出同步汇合、空洞清理与透明暂存 |
| A-AUDIO-RETENTION | 3 | 3 | 完整 PCM 或首块保留分别要求缓存与活动计价、消费驱动恢复、不可变 checkpoint，影响 MN-25、AN-20 与 NR-14 |
| A-RESOURCE | 3 | 3 | 将 Ready 强可达性、Pending interest 与表达式特化的本地资源身份拆开管理 |
| D-ENTITY | 3 | 3 | 实体隔离同时落到私有求值状态、生成的组件计划与本地特化 |
| D-SCRIPT | 4 | 4 | PlayerState、瞬时 shared semantics、本地产物身份与 UUID 偏好分别维护各自的基线、地址或持久事实 |
| D-VALIDATE | 3 | 3 | 覆盖资产请求、player reports 与表达式 shared semantics 的生效前校验 |
| A-AN-02 | 2 | 2 | 多 pass 共用动画历史，同时衍生出姿态恢复与动作门禁 |
| A-AN-04 | 2 | 2 | 生成期能力同时落到扩展 checker 与内建类型化计划，但两者的失败边界不同 |
| A-IMAGE | 2 | 2 | Raw 的输入门禁与失败回退、legacy 的解码期表示归约共用用途驱动策略 |
| A-LEGACY-READ | 2 | 2 | 有界 buffer 读入与完整模型解码衍生出来源边界及图片峰值表示约束 |
| A-ONE-ARTIFACT | 2 | 2 | 统一运行制品出口衍生出 staging 与 legacy 结果交付 |
| D-CAPABILITY | 2 | 2 | 约束按类型准入与物理文件系统写入目标 |
| D-EFFECT | 2 | 2 | 动作门禁同时约束领域动作与模型声音；动作即时生效，不承诺跨系统回滚 |
| D-REUSE | 2 | 2 | 涵盖本地替代与跨会话精确复用 |
| D-SESSION | 2 | 2 | 涵盖 session 身份隔离与有限工作的退役清理 |
| A-ARCHIVE | 2 | 1 | 归档借用提取衍生出独立拷贝（owning capture）约束 |
| A-SIMD | 2 | 1 | 热点数据布局衍生出 cache 能力隔离 |
| A-TEXTURE-PUBLISH | 2 | 1 | 宿主完整发布门禁进一步衍生出延后准入 |
| A-FAILURE-MEMORY | 1 | 1 | 模型请求与惰性资源的失败资格共用同一精确匹配的登记表 |
| D-RECOVER | 1 | 1 | 涵盖模型请求与惰性资源的失败记忆与恢复 |

**扩展候选**：`D-LOOP` 的主表计数为 R=0、D=0，沿 Inference 动机边展开到具体策略后候选 R=10；`D-GUI` 主表为 0/0，候选 R=5；`D-IMAGE` 主表为 0/0，候选 R=2。`D-GUI` 的扩展范围经当前意图到达 MN-20，仅说明短命实体需求会与失败资格相遇，并不意味着 GUI 持有模型失败记录。AN-08、NR-03 的存在虽有事实证据，但其根理由仍为 Hypothesis（H），不能计入 Fact 祖先的 `R`。

**复算方法**：从五个 Fact 分片表展开 `From → each(To)`；按根依据总表中相同的 DD 链接合并产品短键，先取这些键直接邻接的并集计为 `D`，再做带 `visited` 去重的图遍历，每遇到机制页中列出的生产机制 ID 计一次 `R` 并继续沿其因果边遍历。表外关系、节点引用次数、普通调用、正文里提及的机制名，以及视图中的折叠集合均不参与计数。合并摘要仅用于本排名视图，不改变根依据总表中的适用范围或单个机制的独立删除条件。
