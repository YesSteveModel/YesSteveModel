# 跨子系统关系

本页只维护同时涉及多个子系统（AP、MN、AN、NR）的关系。单一子系统内部的关系由对应子系统的 `causal-graph.md` 唯一维护，分类规则与分片计数见[关系登记入口](relationship-register.md)。

## Fact 因果关系

| From | Type | To | Basis |
|---|---|---|---|
| D-SOURCE | requires | MN-05, MN-24, AP-06, AP-07, AP-08 | 精确读取、缓存根活跃使用、转换交付、实际来源准入与独立 preview 和 export 各自的来源边界 |
| D-JOIN | creates | P-PARTIAL | MN-02 与 AP-02：目录列表可见不代表完整内容已就绪 |
| D-META | creates | P-PARTIAL | AP-02 与 MN-02 |
| D-CHUNKS | creates | P-PARTIAL, P-ASSEMBLY | MN-02 与 MN-12 |
| P-PARTIAL | addresses | AP-02, MN-02 | AP-02 与 MN-02 |
| D-IDENTITY | requires | MN-03, MN-05, AP-02, NR-05 | 同源替代与远端精确表示并存，共同约束这四处表示的使用方式 |
| D-EXACT | requires | MN-05, NR-05 | MN-05 与 NR-05 |
| A-WORK | requires | MN-07, AP-08 | MN-07 负责领域层自有运行准入；AP-08 负责 preview 与 export 跨宿主阶段的总操作准入及固定 owner 收尾 |
| D-FAIL | requires | MN-10, MN-11, NR-15 | MN-10、MN-11 与 NR-15 |
| D-SOUND | requires | AP-09, MN-25, AN-20, NR-14 | 当前音频 profile、首块准入与精确渐进获取、完整播放 owner 以及 codec 和 JNI 能力共同支撑模型声音链路 |
| A-AUDIO-RETENTION | requires | MN-25, AN-20, NR-14 | 保留完整 PCM 或首块 PCM 要求原子候选与双域计价、遇到消费缺口时才恢复、不可变 checkpoint 与独立 decoder，三部分分别落在已有机制中 |
| A-CONTINUOUS-DEMAND | creates | P-SHORT-LIVED | AP-04、AP-05 与 MN-10：同一 Coordinator 中各类用途保留独立的连续门槛 |
| P-SHORT-LIVED | addresses | AP-04, AP-05, MN-10 | AP-04、AP-05 与 MN-10 |
| A-NATIVE | creates | P-JNI, P-OUTPUT, P-COMMIT | NR-01、NR-02、NR-15 与 AN-11、AN-12 |

## 非因果关系与候选扩展

| From | Relation / Confidence | To | 含义 |
|---|---|---|---|
| NR-05 | depends / Fact | MN-05, MN-28 | Baked 文件完整验证后，通过独占文件和带类型约束的索引事务发布；运行期与工作期各自持有共享不可变 native 引用，profile 仍归 bake owner 维护 |
| MN-12 | depends / Fact | AP-02, MN-05, MN-14 | 依赖内容验证、cache 提交与已接纳的发送调度 |
| AP-06 | depends / Fact | AP-02, MN-05 | 转换产物经验证后交给存储层，但不等同于已发布到 Catalog |
| AP-06 | depends / Fact | NR-13 | 仅 legacy 输入经由 native 带类型的结果交付，raw 输入不依赖 legacy |
| AP-08 | depends / Fact | AP-02, MN-05, MN-12, MN-14, A-WORK | Export 复制已验证的存储 chunk 并按 direct 来源重开验证；preview cache 保持独立身份；client tick 准入覆盖完整的 preview 与 export 生命周期，integrated environment 持有导出选集与提交资格；显式补图复用按类型区分的请求、主目标闭包、反向 cursor 与严格提交 |
| AP-09 | depends / Fact | AP-02, NR-14 | 当前内容准入复用分层 chunk 验证，并结合 native 与宿主 codec 能力核对媒体时间轴 |
| AP-04 | coordinates / Fact | MN-11, MN-17 | 页面绑定原 session；过时的 ABSENT 会取消结果落地，页面退出只撤回需求。图片在接纳点转移独立像素能力，活动上传借用结束后才归还容量；页面不获得共享 Ready 资源的物理关闭权 |
| AP-05 | coordinates / Fact | MN-11, MN-17 | 缺图与接收资格归属 session，自动准备持有默认 GUI body 的独立 interest 与 lease；显式用途另行获取完整资源，用途交接不转移资源关闭权 |
| AP-04 | depends / Fact | MN-10 | 页面是同一集中协调机制下的用途之一，GUI 不单独保存规则或推进资源状态 |
| AP-05 | depends / Fact | MN-10 | hover 是同一集中协调机制下的用途之一，GUI 不单独保存规则或推进资源状态 |
| AN-02 | coordinates / Fact | AN-01, MN-06, NR-06 | 协调单实体状态、target lease 与帧视图寿命 |
| AN-11 | coordinates / Fact | NR-09, NR-10 | 协调 native 任务与共享 scratch 的串行调用 |
| MN-25 | depends / Fact | AP-02, MN-05, NR-14 | 先完成精确 chunk 获取与媒体解释，才能通过首块门禁、渐进消费或发布完整 PCM、首块 PCM 与 checkpoint |
| AN-20 | depends / Fact | MN-25, NR-14 | 每次播放获取精确匹配 chunk 的能力；默认后端消费缓存或按需恢复私有 decoder，所选后端独占输出与结束处理 |
| MN-26 | coordinates / Fact | AN-01, AN-05 | shared semantics 只决定接收实体的当前 module，实际 handler 仍使用该实体的本地状态与动作门禁 |
| MN-27 | depends / Fact | AN-21 | 本地执行绑定消费冻结后的声明 catalog、binding plan 与 holder；AOT 使用静态字段布局，GUI 解释共享声明事实并使用私有动态状态 |
| D-LOOP | motivates / Inference | A-AN-01, A-AN-03, A-WORK, A-DISPATCH, A-SIMD, A-PARALLEL | 优化方向与主循环目标一致，但不证明这些具体方案必需或更优 |
| D-GUI | motivates / Inference | A-PAGE, A-PREVIEW, A-CONTINUOUS-DEMAND, A-PREVIEW-CACHE | 页面局部异步、短命意图过滤、已缓存 3D 预览与弱关联图片 cache 均属于展示层选择 |
| D-IMAGE | motivates / Inference | A-IMAGE | 保真与减体积目标并不强制采用当前的输入门禁、用途阈值、编码时点或两条失败策略 |

候选扩展只把单一根的 `motivates` 边作为核验入口，既不提高 Hypothesis 的可信度，也不把普通数据流补成因果边。
