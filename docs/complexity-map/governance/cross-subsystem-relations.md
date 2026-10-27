# 跨子系统关系

本页只维护同时触及多个 AP/MN/AN/NR 命名空间的关系。单一子系统内部关系由对应的 `causal-graph.md` 唯一维护；分类规则和分片计数见[关系登记入口](relationship-register.md)。

## Fact 因果关系

| From | Type | To | Basis |
|---|---|---|---|
| D-SOURCE | requires | MN-05, MN-24, AP-06, AP-07, AP-08 | 精确读取、converted 活跃使用、转换交付、实际来源准入与独立 preview/export 的来源边界 |
| D-JOIN | creates | P-PARTIAL | MN-02/AP-02：列表不等于完整内容 |
| D-META | creates | P-PARTIAL | AP-02/MN-02 |
| D-CHUNKS | creates | P-PARTIAL, P-ASSEMBLY | MN-02/12 |
| P-PARTIAL | addresses | AP-02, MN-02 | AP-02/MN-02 |
| D-IDENTITY | requires | MN-03, MN-05, AP-02, NR-05 | 同源替代与 remote exact 的并存约束这四处表示使用 |
| D-EXACT | requires | MN-05, NR-05 | MN-05/NR-05 |
| A-WORK | requires | MN-07, AP-08 | MN-07：domain-owned runtime admission；AP-08：preview/export 跨 host 阶段的总 operation admission 与固定 owner terminal |
| D-FAIL | requires | MN-10, MN-11, NR-15 | MN-10/11/NR-15 |
| D-SOUND | requires | AP-09, MN-25, AN-20, NR-14 | 当前音频 profile、首块准入/精确渐进取得、完整播放 owner 和 codec/JNI 能力共同闭合模型声音 |
| A-AUDIO-RETENTION | requires | MN-25, AN-20, NR-14 | 完整/首块缓存要求原子候选与双域计价、消费缺口才恢复、不可变 checkpoint 与独立 decoder；三项分别落在已有机制中 |
| A-CONTINUOUS-DEMAND | creates | P-SHORT-LIVED | AP-04/05、MN-10：同一 Coordinator 中各用途保留独立连续门槛 |
| P-SHORT-LIVED | addresses | AP-04, AP-05, MN-10 | AP-04/05、MN-10 |
| A-NATIVE | creates | P-JNI, P-OUTPUT, P-COMMIT | NR-01/02/15、AN-11/12 |

## 非因果关系与候选扩展

| From | Relation / Confidence | To | 含义 |
|---|---|---|---|
| NR-05 | depends / Fact | MN-23 | 当前 baked 可选持久化采用 shared key lock 与有界工作域；运行和工作各持共享不可变 native 引用，能力 profile 仍由 bake owner 判断 |
| MN-12 | depends / Fact | AP-02, MN-05, MN-14 | 内容验证、cache commit 与 accepted dispatch |
| AP-06 | depends / Fact | AP-02, MN-05 | 转换产物验证后交存储；不等于已发布 Catalog |
| AP-06 | depends / Fact | NR-13 | 仅 legacy 输入经 native typed result，raw 不依赖 legacy |
| AP-08 | depends / Fact | AP-02, MN-05, MN-12, MN-14, A-WORK | Export 复制已验证 stored chunks 并重开 direct 验证；preview cache 保持独立 identity；client tick admission 覆盖完整 preview/export obligation，integrated environment 持有导出选集与 commit 资格；显式补图复用 typed request、主目标闭包、反向 cursor 与严格提交 |
| AP-09 | depends / Fact | AP-02, NR-14 | Current content admission 复用分层 chunk 验证，并以 native/host codec 能力核对媒体时间轴 |
| AP-04 | coordinates / Fact | MN-11, MN-17 | 页面绑定原 session；过时 ABSENT 取消终态，退出只撤需求。图片接纳点转移独立像素能力，活动上传借用结束才归还容量；页面不能取得共享 Ready 的物理 close 权 |
| AP-05 | coordinates / Fact | MN-11, MN-17 | 缺图与接收资格归 session，自动准备持默认 GUI body 的独立 interest/lease；显式用途另行取得完整资源，用途交接不转移资源关闭权 |
| AP-04 | depends / Fact | MN-10 | 页面是同一集中协调机制的用途；GUI 不另存规则或推进资源 |
| AP-05 | depends / Fact | MN-10 | hover 是同一集中协调机制的用途；GUI 不另存规则或推进资源 |
| AN-02 | coordinates / Fact | AN-01, MN-06, NR-06 | 单实体状态、target lease 与帧 view 寿命 |
| AN-11 | coordinates / Fact | NR-09, NR-10 | Native task 和共享 scratch 的调用串行 |
| MN-25 | depends / Fact | AP-02, MN-05, NR-14 | 精确 chunk 取得与媒体解释先完成，才可通过首块门禁、渐进消费或发布完整 PCM/首块 PCM 与 checkpoint |
| AN-20 | depends / Fact | MN-25, NR-14 | 每播放取得 exact chunk capability；默认后端消费缓存或按需恢复私有 decoder，所选后端独占输出与终态 |
| MN-26 | coordinates / Fact | AN-01, AN-05 | shared semantics 只选择接收 entity 的当前 module；实际 handler 使用该 entity 的本地状态与动作门禁 |
| MN-27 | depends / Fact | AN-21 | 本地 specialization 的 binding plan 与长期字段布局消费生成 catalog、typed adapter 和 holder |
| D-LOOP | motivates / Inference | A-AN-01, A-AN-03, A-WORK, A-DISPATCH, A-SIMD, A-PARALLEL | 优化方向合理，但不证明这些具体方案必需或更优 |
| D-GUI | motivates / Inference | A-PAGE, A-PREVIEW, A-CONTINUOUS-DEMAND, A-PREVIEW-CACHE | 页面局部异步、短命意图过滤、已缓存 3D 与弱关联图片 cache 是展示选择 |
| D-IMAGE | motivates / Inference | A-IMAGE | 保真/减体积不强制当前输入门禁、用途阈值、编码时点或两条失败策略 |

候选范围只把单一根的 `motivates` 扩展当作验证入口，不提高 Hypothesis 的可信度，也不把普通数据流补成因果边。
