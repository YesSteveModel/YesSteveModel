# 复杂度地图

本目录梳理 Java 领域层与 native 能力层当前生产链路中的非平凡机制，涵盖资产、模型、网络、动画、渲染、JNI、展示和扩展接入。记录主体是无法直接从业务语义推导出的额外机制，以及“需求或约束 → 决策 → 问题 → 机制 → 派生机制”的因果、依赖与协调拓扑；高扇出排名与归约支点属于由此衍生的视图。重要的局部机制、低扇出机制、来源未明或当前不可删除的机制也会一并收录。当前格式、cache 与协议仍处于 **unstable** 状态。

本目录仅作为分析视图，不拥有产品或架构决策权威，也不直接授权归约。声明范围内的产品判断以[产品决策树](../product-decisions/README.md)为 Root Authority，协议与格式以 standard 和 schema 文档为准，机制是否存在以当前源码为准；架构设计与实际状态的差异会集中记录，不反向改写上游文档。

## 子系统主题

| 子系统 | 标识 | 负责范围 |
|---|---|---|
| [Java 资产与页面](java-assets-and-presentation/README.md) | AP | 输入冻结、转换准入、页面需求、preview 与 export |
| [Java 模型、资源与网络](java-models-and-network/README.md) | MN | Catalog、内容存储、资源生命周期、session、传输与状态同步 |
| [Java 动画、绘制交接与扩展](java-animation-and-integration/README.md) | AN | 实体求值、动作语义、多 pass 渲染、扩展隔离与宿主输出桥 |
| [Native 能力层](native-capabilities/README.md) | NR | JNI 桥接、bake 与 cache、逐帧计算、并行输出、归档读取与 legacy 导入 |

每个子系统总览都遵循同一条阅读路径：机制正文 → 证据、覆盖与根依据 → 因果图与高扇出候选 → 归约支点 → 因果缺口与差异。

## 总览拓扑

阅读时建议先按具体问题进入对应机制页，再沿上游原因追溯到根依据，不必先看排名。下表只是机制簇的高层压缩视图，不能把其中一行当成可以整体删除的集合；精确的关系边由各子系统关系页和[跨子系统关系](governance/cross-subsystem-relations.md)唯一维护，按需加载方式见[关系登记入口](governance/relationship-register.md#因果图)。

| 机制簇 | 复杂度为什么出现、又衍生什么 | 机制入口 |
|---|---|---|
| 输入冻结与转换交付 | 独立的 builtin 与 export 需要分阶段消费同一份输入，因此引入 capture；Catalog 直接解析，由调用方保证来源稳定；archive 借用视图进一步增加了复制时点约束。多种输入格式需要统一为当前容器，衍生出 staging 与重开验证；legacy 完整模型解码则触发了图片表示提前归约。 | [AP-01/02/06](java-assets-and-presentation/README.md)、NR-11/13/17 |
| 模型音频 | 统一声音表示带来首块准入与精确匹配尾块的获取约束。客户端保留完整 PCM，或保留首块 PCM 与 checkpoint，由此衍生出独立引用、消费驱动的 decoder 恢复、活动播放计价与取消交接。 | [AP-09](java-assets-and-presentation/mechanisms.md)、[MN-25](java-models-and-network/mechanisms.md)、[AN-20](java-animation-and-integration/mechanisms.md)、[NR-14](native-capabilities/mechanisms.md) |
| 目录与来源演进 | 启动激活与进入刷新共用 Catalog 接纳链路，前置登记与后台完整构造避免阻塞 owner 锁，并在 tick 中接纳最新快照。元数据与目标 Ready 状态分离；根级登记同时覆盖业务消费与在途工作，保护首次合格扫描后的两池周期清理。 | [MN-01/02、05、08、24](java-models-and-network/README.md) |
| 共享资源与发布 | 已完成资源需要跨 owner 复用，因此引入独立 lease；未完成工作需要复用，衍生出精确匹配的 interest 与迟到结果处理。宿主纹理发布采用 sample-ready 总门禁，进一步衍生出延后准入与等待期取消。 | [MN-06/17–19](java-models-and-network/README.md) |
| 恢复与失败记忆 | Pending 或失败时需要保持旧表现，因此分离 desired 与 installed 状态并做协调；为了支持条件变化后恢复，引入精确匹配的失败记忆、惰性资源门禁与显式重试资格。 | [MN-10/20](java-models-and-network/README.md) |
| 传输与状态同步 | 连接寿命短于延后工作，需要精确匹配 session 并在退役时清理；按类型区分的分片带来覆盖度检查与先验证后构造；仅在超出单帧预算时才引入 parent 组装。accepted cursor 衍生出背压、重建与单点关闭；表达式瞬时事件依赖 shared semantics 与三端门禁。 | [MN-07、11–16、26](java-models-and-network/README.md) |
| 派生物复用与存储边界 | 要求精确匹配表示衍生出原子 cache；文件写入目标受物理路径约束；根级活跃消费者与来源引用保护两池周期回收；表达式特化（specialization）需要独立的本地身份。内存交付与可选持久化分离带来有界工作域和独立输入保留；跨进程共享派生物则需要独占完整文件与短索引事务。 | [MN-05、22、24、27–30](java-models-and-network/README.md)、[NR-04/05](native-capabilities/mechanisms.md) |
| 动画状态与执行权限 | 原地异步求值需要主线程汇合；多 pass 共用动画历史衍生出姿态恢复与动作门禁；共享解析产物要求每实体独立持有 processor、Molang 内存与 roaming 结构；求值窗口限定动作能力；`defer` 衍生出参数快照与逆序排空。 | [AN-01–06、08–12、19](java-animation-and-integration/README.md) |
| 玩家骨骼物理 | 各安装独立持有物理世界。多个绘制 pass 共享模拟历史，因此需要主槽独占、观察姿态隔离与样本代际。原生提取通过骨骼增量合成绘制结果，分别报告求解与绘制状态。 | [AN-01、02、04](java-animation-and-integration/mechanisms.md#玩家骨骼物理)、[NR-06](native-capabilities/mechanisms.md#物理提取的帧发布) |
| Native 输出与借用 | JNI 边界引入绑定、地址与失败协议；固定并行区间衍生出容量空洞清理与透明暂存；全局 scratch 要求串行调用前提；原地帧视图（frame view）与 schedule cache 各有失效条件。 | [NR-01/02、04–10、15/16](native-capabilities/README.md)、AN-11/12 |
| 展示、接入与未明补偿 | 短命意图与独立用途衍生出连续门槛、用途 anchor 与接收 lease；图片解码与上传分离带来像素计价及接纳点交接。direct 来源准入与 schema 校验分层；可选扩展依赖加载前隔离与生成的 checker；builtin 声明生成组件与调用计划。历史旋转混合补丁与 allocator 补偿的必要性目前仍未查清。 | [AP-03–05、07/08](java-assets-and-presentation/mechanisms.md)、[MN-10](java-models-and-network/mechanisms.md)、[AN-08–10/21](java-animation-and-integration/mechanisms.md)、[NR-03](native-capabilities/mechanisms.md) |

## 共享口径与维护

- [建模、证据与计数口径](governance/modeling-and-evidence.md)
- [根依据总表](governance/root-register.md)
- [关系登记入口](governance/relationship-register.md)
- [跨子系统关系](governance/cross-subsystem-relations.md)
- [全局高扇出候选](governance/global-fanout.md)
- [维护与复杂度归约规则](governance/maintenance.md)

共享页面只维护跨子系统的定义与登记表；各机制的具体解释、局部视图、归约候选与未决缺口由对应子系统维护。
