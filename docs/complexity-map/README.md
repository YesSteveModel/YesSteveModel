# 复杂度地图

范围：Java 领域层与 native 能力层当前实现的生产链路，包括资产、模型、网络、动画、渲染、JNI、展示和接入。主体是不能直接从业务语义推出的额外机制，以及“需求/约束 → 决策 → 问题 → 机制 → 派生机制”的因果、依赖和协调拓扑；高扇出与归约支点是派生视图。重要的局部、低扇出、来源未明或当前不可删机制同样收录。格式、cache 与协议仍为 **unstable**。

本目录是分析投影，不拥有产品或架构决策权威，也不授权归约。声明范围内的产品判断以[产品决策树](../product-decisions/README.md)为 Root Authority；wire/格式看 standard/schema；机制存在性以当前源码为准；架构与状态差异集中保留，不反向改写上游。

## 子系统主题

| 子系统 | 标识 | 负责范围 |
|---|---|---|
| [Java 资产与页面](java-assets-and-presentation/README.md) | AP | 输入冻结、转换准入、页面需求、preview 与 export |
| [Java 模型、资源与网络](java-models-and-network/README.md) | MN | Catalog、内容存储、资源生命周期、session、传输与状态投影 |
| [Java 动画、绘制交接与扩展](java-animation-and-integration/README.md) | AN | 实体求值、动作语义、多 pass、扩展隔离与宿主输出桥 |
| [Native 能力层](native-capabilities/README.md) | NR | JNI、bake/cache、逐帧计算、并行输出、archive 与 legacy importer |

每个子系统总览都提供同一条本地阅读路径：机制 → 证据/覆盖/依据 → 因果图与高扇出候选 → 归约支点 → 因果缺口与差异。

## 总览拓扑

先按问题选择机制页，沿原因回到根依据；不必先读排名。下表压缩的是机制关系，精确边由各子系统关系页和[跨子系统关系](governance/cross-subsystem-relations.md)唯一维护，[关系登记入口](governance/relationship-register.md#因果图)说明按需加载方式；不能把一行当成可整体删除的集合。

| 机制簇 | 复杂度为什么出现、又衍生什么 | 机制入口 |
|---|---|---|
| 输入冻结与转换交付 | 可变来源分阶段解析 → capture；archive 借用再增加复制时点约束；多格式统一为当前容器 → staging/重开验证；legacy 完整模型解码 → 图片表示提前归约 | [AP-01/02/06](java-assets-and-presentation/README.md)，NR-11/13/17 |
| 模型音频 | 统一声音表示 → 首块准入与 exact 尾块取得；完整 PCM 或首块 PCM/checkpoint 保留 → 独立引用、消费驱动 decoder 恢复、活动计价与取消交接 | [AP-09](java-assets-and-presentation/mechanisms.md)、[MN-25](java-models-and-network/mechanisms.md)、[AN-20](java-animation-and-integration/mechanisms.md)、[NR-14](native-capabilities/mechanisms.md) |
| 目录与来源演进 | 启动激活与进入刷新共用 Catalog 接纳，前置登记和后台完整构造不阻塞 owner 锁，tick 接纳最新快照；metadata 与 target Ready 分离；converted 登记覆盖扫描和 runtime 使用期，保护首次保守清理 | [MN-01/02、05、08、24](java-models-and-network/README.md) |
| 共享资源与发布 | 已完成资源跨 owner 使用 → 独立 lease；未完成工作复用 → exact interest/迟到终态；host texture 发布 → sample-ready 总门禁 → 延后 admission 及等待期取消 | [MN-06/17–19](java-models-and-network/README.md) |
| 恢复与失败记忆 | Pending/失败时保持旧表现 → desired/installed 协调；允许恢复 → exact 失败记忆、lazy 资源 gate 与显式重试资格 | [MN-10/20](java-models-and-network/README.md) |
| 传输与投影 | 连接寿命短于延后工作 → exact session/retirement；typed 分片 → coverage 与先验证后构造；超帧才引入 parent；accepted cursor → 背压/重建/单点关闭；表达式瞬时事件 → shared semantics 与三端门禁 | [MN-07、11–16、26](java-models-and-network/README.md) |
| 派生物复用与存储边界 | Exact 表示 → 原子 cache；文件 sink → 物理路径约束；converted 活跃消费者 → 保守 prune；表达式 specialization → 独立本地 identity。内存交付与可选持久化分离 → 有界工作域及独立输入保留；跨进程共享 baked 产物 → 同 key writer 协调 | [MN-05、22–24、27](java-models-and-network/README.md)，[NR-04/05](native-capabilities/mechanisms.md) |
| 动画状态与执行权限 | 原地异步状态 → 汇合；多 pass 共用历史 → 姿态恢复/动作门禁；共享解析产物 → 每实体 processor/Molang memory 与 roaming struct；求值窗口 → 动作能力；defer → 参数快照与逆序排空 | [AN-01–06、08–12、19](java-animation-and-integration/README.md) |
| Native 输出与借用 | JNI → 绑定/地址/失败协议；固定并行区间 → 容量空洞清理、透明暂存；全局 scratch → 串行调用前提；原地 frame view 与 schedule cache 各有失效条件 | [NR-01/02、04–10、15/16](native-capabilities/README.md)，AN-11/12 |
| 展示、接入与未明补偿 | 短命意图与独立用途 → 连续门槛、用途 anchor 和接收 lease；图片解码与上传分离 → 像素计价及接纳点交接。direct 来源准入与 schema 分层；可选扩展 → 加载前隔离及生成 checker；builtin 声明 → 生成 component/call plan；混合补丁与 allocator 补偿的必要性仍未闭合 | [AP-03–05、07/08](java-assets-and-presentation/mechanisms.md)，[MN-10](java-models-and-network/mechanisms.md)，[AN-08–10/21](java-animation-and-integration/mechanisms.md)，[NR-03](native-capabilities/mechanisms.md) |

## 共享口径与维护

- [建模、证据与计数口径](governance/modeling-and-evidence.md)
- [根依据总表](governance/root-register.md)
- [关系登记入口](governance/relationship-register.md)
- [跨子系统关系](governance/cross-subsystem-relations.md)
- [全局高扇出候选](governance/global-fanout.md)
- [维护与复杂度归约规则](governance/maintenance.md)

共享页面只维护跨子系统的定义和登记表；机制解释、局部投影、归约候选与缺口由对应子系统拥有。
