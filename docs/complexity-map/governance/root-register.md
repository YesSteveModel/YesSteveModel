# 根依据总表

本表是所有根键解释的唯一登记位置。短键只用来压缩图中的表达：产品键（`D-`）指向唯一的产品判断或其具体契约，架构键（`A-`）指向内部取舍。多个历史短键可能指向同一条归约后的产品决策，不能按短键数量重复计算产品决策数。业务目标经各 DD 的 Requirement 与 Motivation 回到[共享约束](../../product-decisions/shared.md#constraints)，本图不重复抄录完整产品树。

| 键 | 既有决策与短语义 |
|---|---|
| D-RELOAD | [DD.custom-content-hot-load](../../product-decisions/decisions/authoring-input-and-reload.md#ddcustom-content-hot-load)：支持进程内热重载以迭代内容 |
| D-CATALOG | [DD.single-current-catalog](../../product-decisions/decisions/catalog-publication.md#ddsingle-current-catalog)：完整发布唯一的当前目录 |
| D-HOLD | [DD.single-current-catalog](../../product-decisions/decisions/catalog-publication.md#ddsingle-current-catalog)：目录退役不撤销已有使用 |
| D-SOURCE | [DD.user-model-sources-are-readonly](../../product-decisions/decisions/artifact-storage.md#dduser-model-sources-are-readonly)：用户来源只读，不归缓存维护所有 |
| D-EXACT | [DD.single-current-catalog](../../product-decisions/decisions/catalog-publication.md#ddsingle-current-catalog)：不混用不同表示，也不暴露半写派生物 |
| D-IDENTITY | [DD.model-and-representation-identities](../../product-decisions/decisions/model-identity.md#ddmodel-and-representation-identities)：来源等价与字节等价分开管理 |
| D-JOIN | [DD.catalog-before-model-body](../../product-decisions/decisions/model-distribution.md#ddcatalog-before-model-body)：入服与浏览时不触发全量下载 |
| D-CHUNKS | [DD.catalog-before-model-body](../../product-decisions/decisions/model-distribution.md#ddcatalog-before-model-body)：按需只拉取目标依赖闭包 |
| D-REUSE | [DD.reuse-content-before-downloading](../../product-decisions/decisions/model-distribution.md#ddreuse-content-before-downloading)：下载前先复用本地合格内容 |
| D-META | [DD.catalog-before-model-body](../../product-decisions/decisions/model-distribution.md#ddcatalog-before-model-body)：元数据描述与普通内容分阶段使用 |
| D-VERIFY | [DD.catalog-before-model-body](../../product-decisions/decisions/model-distribution.md#ddcatalog-before-model-body)：实际消费 chunk 时再独立验证 |
| D-FREEZE | [DD.semantic-freeze-at-export](../../product-decisions/decisions/model-compatibility.md#ddsemantic-freeze-at-export)：导出时固定当次作品语义 |
| D-MIGRATION | [DD.semantic-freeze-at-export](../../product-decisions/decisions/model-compatibility.md#ddsemantic-freeze-at-export)：单向迁移并保留可解释行为 |
| D-SOUND | [DD.model-sounds-are-observable-content](../../product-decisions/decisions/model-sound.md#ddmodel-sounds-are-observable-content)：有效模型声音形成实际播放结果，并将失败隔离在局部 |
| D-IMAGE | [DD.compression-preserves-model-meaning](../../product-decisions/decisions/image-policy.md#ddcompression-preserves-model-meaning)：压缩降低重复分发成本并保持视觉效果 |
| D-GUI | [DD.visual-model-selection](../../product-decisions/decisions/selection-and-attribution.md#ddvisual-model-selection)：支持 GUI 浏览、连接内补图展示与发起选择 |
| D-DEFAULT | [DD.default-model-is-reliability-baseline](../../product-decisions/decisions/model-fallback.md#dddefault-model-is-reliability-baseline)：兜底内容提前就绪 |
| D-FALLBACK | [DD.default-model-is-reliability-baseline](../../product-decisions/decisions/model-fallback.md#dddefault-model-is-reliability-baseline)：Humanoid 目标回退到默认基线 |
| D-HOST-FALLBACK | [DD.default-model-is-reliability-baseline](../../product-decisions/decisions/model-fallback.md#dddefault-model-is-reliability-baseline)：其他目标恢复为宿主默认表现 |
| D-RECOVER | [DD.degraded-presentation-can-recover](../../product-decisions/decisions/model-fallback.md#dddegraded-presentation-can-recover)：保留失败请求，在有效条件变化后尝试恢复 |
| D-FAIL | [DD.local-failure-degradation](../../product-decisions/decisions/failure-isolation.md#ddlocal-failure-degradation)：普通错误只触发局部降级 |
| D-LOOP | [DD.main-loop-has-priority](../../product-decisions/decisions/workload-budget.md#ddmain-loop-has-priority)：模型工作服从主循环，禁止等待异步结果 |
| D-SESSION | [DD.session-scopes-remote-state](../../product-decisions/decisions/session-isolation.md#ddsession-scopes-remote-state)：旧连接的状态不进入新连接 |
| D-SESSION-FAIL | [DD.local-failure-degradation](../../product-decisions/decisions/failure-isolation.md#ddlocal-failure-degradation)：模型会话失败时保留 Minecraft 连接 |
| D-WIRE | [DD.protocol-compatibility-has-bounded-cost](../../product-decisions/decisions/session-failure.md#ddprotocol-compatibility-has-bounded-cost)：unstable 阶段不维护多版本协议读取 |
| D-VALIDATE | [DD.validate-client-before-effect](../../product-decisions/decisions/trust-boundaries.md#ddvalidate-client-before-effect)：客户端输入在生效前严格校验 |
| D-CAPABILITY | [DD.trusted-server-with-capability-floor](../../product-decisions/decisions/trust-boundaries.md#ddtrusted-server-with-capability-floor)：远端内容不得获得任意宿主能力 |
| D-ACCESS | [DD.selection-permission-is-separate-from-asset-access](../../product-decisions/decisions/model-authorization.md#ddselection-permission-is-separate-from-asset-access)：选择、展示和运行内容各自独立授权；显式 level 2 主目标管理用途不授予普通资产或选择权限 |
| D-ADMISSION | [DD.request-time-authorization](../../product-decisions/decisions/model-authorization.md#ddrequest-time-authorization)：已接纳的传输保留接纳时的授权结果 |
| D-SERVER | [DD.server-decides-model-operations](../../product-decisions/decisions/model-authorization.md#ddserver-decides-model-operations)：由服务端决定模型操作结果 |
| D-PLAYER | [DD.game-state-plus-mod-projection](../../product-decisions/decisions/player-state-sync.md#ddgame-state-plus-mod-projection)：复用游戏自带状态，只补齐模组额外状态 |
| D-TRACKING | [DD.tracking-scopes-state-sync](../../product-decisions/decisions/player-state-sync.md#ddtracking-scopes-state-sync)：向新观察者发快照，向当前观察者与自身（self）发变化 |
| D-SCRIPT | [DD.script-selects-sync-values](../../product-decisions/decisions/script-sync.md#ddscript-selects-sync-values)：由脚本显式选择需要跨端同步的值 |
| D-ENTITY | [DD.animation-state-is-entity-local](../../product-decisions/decisions/animation-isolation.md#ddanimation-state-is-entity-local)：不同实体不共用可变求值状态 |
| D-EFFECT | [DD.render-observation-does-not-repeat-actions](../../product-decisions/decisions/animation-isolation.md#ddrender-observation-does-not-repeat-actions)：重复观察同一次时间推进不重复触发动作 |
| D-SAMPLE | [DD.animation-sampling-is-separate-from-state-updates](../../product-decisions/decisions/animation-sampling.md#ddanimation-sampling-is-separate-from-state-updates)：整体表现与逻辑因果独立于具体采样调度 |
| D-CONTROLLER | [DD.model-controller-overrides-builtins](../../product-decisions/decisions/bone-and-controller.md#ddmodel-controller-overrides-builtins)：作品可覆盖内建 controller |
| D-VANILLA-ACTION | [DD.model-controller-overrides-builtins](../../product-decisions/decisions/bone-and-controller.md#ddmodel-controller-overrides-builtins)：保留基础动作表现 |
| D-AN-FALLBACK | [DD.model-controller-overrides-builtins](../../product-decisions/decisions/bone-and-controller.md#ddmodel-controller-overrides-builtins)：缺少自定义动画时可借用默认动作 |
| D-EXT | [DD.local-failure-degradation](../../product-decisions/decisions/failure-isolation.md#ddlocal-failure-degradation)：可选联动失败不阻断启动 |
| D-OUTPUT | [DD.authoring-semantics-before-optimization](../../product-decisions/decisions/geometry-regions.md#ddauthoring-semantics-before-optimization)：各输出路径与 CPU 路径保持语义等价 |
| D-HOST | [DD.local-failure-degradation](../../product-decisions/decisions/failure-isolation.md#ddlocal-failure-degradation)：渲染失败不污染后续世界绘制 |
| D-TRANSPARENCY | [DD.transparency-stops-at-model-boundary](../../product-decisions/decisions/transparency-scope.md#ddtransparency-stops-at-model-boundary)：透明排序保证止于单个模型边界 |

下表中的 `A-` 键记录当前架构选择，本身不是新的 ADR。这些选择的存在属于 Fact，但具体历史动机仍由各机制页单独标注证据等级。另外，`C-JNI` 代表 JVM 与 native ABI、调用期地址有效性及错误边界等外部约束，不参与“决策候选”排名。

| 键 | 选择、原因与证据 |
|---|---|
| A-ONE-ARTIFACT | [统一运行制品](../../architecture/asset-pipeline/overview.md#冻结输入与单向转换)：不同输入经转换后统一落为当前容器格式。 |
| A-DEFAULT-MEM | [默认资产构建设计](../../architecture/model-management/client-resources/default-model.md)：默认模型直接在内存中构建，不生成无收益的磁盘中间表示。 |
| A-STARTUP-WARMUP | [启动一次性预热](../../architecture/runtime/overview.md#启动模型一次性预热)：必需模型发布后，利用无玩家期间尝试一次普通 FULL 准备，完成后归还普通缓存；实际帧时间收益仍待测量。 |
| A-NATIVE | 由 Java 负责领域逻辑和宿主对接，native 负责计算并产出顶点字节，双方经 JNI 交接；[迁移理由](../../migration-overview.md#迁移方向)在于减少 native 侧业务权威。CPU renderer 为何必须采用当前策略，尚无独立性能对比。详见[设计](../../architecture/native-runtime/overview.md#能力与依赖方向)。 |
| A-CAPTURE | Builtin 与显式 export 各自使用独立的 capture 冻结实际读取闭包，并复用同一 parser；Catalog 直接解析，来源稳定性由调用方保证。详见[capture 理由](../../architecture/asset-pipeline/overview.md#冻结输入与单向转换)。 |
| A-IMAGE | 转换阶段按用途处理图片：raw 与 legacy 都复用合法的既有压缩表示，只对 PNG 或 RGBA32 重编码；编码成功即采纳结果，不做体积比较回退。raw 遇到可处理的 codec I/O 失败时保留原图，legacy 遇到编码或往返校验失败则终止该来源。详见[图像处理位置](../../architecture/asset-pipeline/implementation/conversion-and-export.md#图像处理的位置)和[设计](../../architecture/asset-pipeline/overview.md#图像与声音转换)。 |
| A-PAGE | 页面作为有限异步需求的 owner，内部重建时保持同一页面意图。详见[展示架构](../../architecture/client-presentation/implementation/page-runtime.md)和[设计](../../architecture/client-presentation/overview.md#页面需求与展示消费)。 |
| A-PREVIEW | 卡片展示资格与资源准备分开管理：自动 GUI 最多做离线准备，交互触发的准备同时受资格与页面准入约束，展示图单独获取。详见[展示实现](../../architecture/client-presentation/implementation/page-runtime.md)和[设计](../../architecture/client-presentation/overview.md#页面需求与展示消费)。真实 3D 预览改善选模体验的理由仍属 Inference。 |
| A-CONTINUOUS-DEMAND | 同一 Coordinator 为页面、交互、选择与实体分别维护连续意图：展示资格不由 Ready 授予，界面反馈只观察真实玩家安装，页面与选择按同一时间线分级准入，实体保持原有连续规则。详见[需求设计](../../architecture/model-management/demand/overview.md#连续准入)和[实现入口](../../architecture/model-management/demand/overview.md#实现与验证入口)。 |
| A-DIRECT-ADMISSION | Schema 允许合法的无图容器，只有 resolver 确认的 direct 成品在实际准入 Manifest 时才要求内嵌 preview 声明，图片 payload 在读取时再验证。详见[转换与导出](../../architecture/asset-pipeline/implementation/conversion-and-export.md#preview-取得与显式-export)和[设计](../../architecture/asset-pipeline/overview.md#分层验证与发布)。 |
| A-PREVIEW-CACHE | 独立 preview 只按 `ContainerId` 弱关联，读到损坏数据时不直接删除，验证通过后原子覆盖，且日常获取不回写模型容器。详见[存储总览](../../architecture/model-management/storage/overview.md)和[独立图片与提交资格](../../architecture/model-management/storage/overview.md#独立图片与提交资格)。 |
| A-NOTIFY | 服务端先提交权威事实，再尽力发送全量或增量集合通知，不设 ACK、历史记录、回滚或重同步机制。详见[通知理由](../../architecture/model-management/sessions/overview.md#权威提交与通知)。 |
| A-RESOURCE | Ready 的强可达性与 Pending 的精确 interest 分开管理；Ready 失去最后一个消费者后进入按配置上限淘汰的未使用 LRU（数量策略见[设备默认](../../product-decisions/decisions/device-quality.md#bcdevice-derived-configuration-defaults)），以放弃同步物理回收上界来换取安全共享。详见[资源理由](../../architecture/model-management/client-resources/overview.md#seam-contract)。 |
| A-AUDIO-RETENTION | 客户端在一个缓存中保留完整 PCM，或保留首块 PCM 与 checkpoint；活动播放按需恢复私有 decoder，并同时为共享值和就绪 PCM 计价。状态复制、消费缺口与取消交接是该方案的代价。详见[保留与消费](../../architecture/model-management/audio/overview.md#pcm-保留与工作集)和[checkpoint 契约](../../architecture/native-runtime/implementation/jni-and-memory.md#音频-checkpoint)。 |
| A-WORK | Catalog、客户端和服务端 runtime 各自管理准入、结束和独立容量；有限计算 worker 跑完同一条 worker-safe 链路，不等待网络、宿主或其他 worker。Catalog 独立协调容量时可以等待本次验证池，但公开状态仍只由固定 owner 在 tick 中提交。Preview 与 export 在客户端 tick 中通过单一写入的总操作准入覆盖跨宿主阶段；integrated environment 额外固定导出选集、封住提交资格并结束自有 worker 阶段；独立管理请求预算则从 worker、事实记录与发送调度一直覆盖到实际释放。详见[有限工作理由](../../architecture/runtime/overview.md#有限工作调度与领域交接)。 |
| A-FRAME-BUDGET | 物理帧预算严格小于 30 KiB，为当前协议缓冲和未来宿主留出余量。详见[帧预算理由](../../architecture/network/overview.md#transport-与物理预算)。 |
| A-TYPED | 跨类型只共用底层机械辅助逻辑，由各类型的负责方自行判定覆盖度与完成条件。详见[assembly 理由](../../architecture/network/overview.md#typed-capability-与业务组装)。 |
| A-DISPATCH | 仅设一个已接纳出站队列的 owner，只有当前 cursor 对应的消息才会构造网络帧。详见[dispatch 理由](../../architecture/network/overview.md#传输接管与发送终态)。 |
| A-REPORT | 完整选择初值建立数字索引映射；本地选择操作与精确对应的安装共同决定上报资格，持久资格与之独立。服务端只维护一份已提交的选择，各 session 只读取该视图。Self 选择回显不用于恢复；不设首包全量、周期全量、ACK 或网络重放。变化只发给游戏观察者与自身（self），追踪与握手共同补齐初值；远端当前选择在安装前会做有界暂存，以容纳 roaming 与 sync 的交错输入。详见[玩家状态](../../architecture/network/player-state.md)。 |
| A-AN-01 | 世界中的实体在 draw 之前异步原地求值，并在本帧汇合消费。详见[帧执行](../../architecture/rendering/implementation/frame-execution.md)和[设计](../../architecture/animation/overview.md#运行作用域与所有权)。为何优于其他调度方案尚未证明。 |
| A-AN-02 | 各 RenderContext 共用实体动画历史与输入，只按输出槽区分各自结果。详见[实体状态](../../architecture/animation/implementation/entity-and-frame-state.md)和[设计](../../architecture/animation/overview.md#运行作用域与所有权)。 |
| A-AN-03 | 按距离和可见性降低实际求值频率。详见[帧执行](../../architecture/rendering/implementation/frame-execution.md)和[设计](../../architecture/animation/overview.md#时间推进与观察)。限频本身是允许的架构选择，但当前阈值的收益及逻辑因果保持仍待 O-07 验证。 |
| A-AN-04 | 生成依赖级 checker，在目标运行环境中判定可选扩展是否兼容。详见[接入实现](../../architecture/integration/implementation/extension-loading.md#扩展兼容检查与注册)和[设计](../../architecture/integration/overview.md#扩展接纳与声明发布)。完整检查面的必要性见 Q-07。 |
| A-SIMD | 启动时选定一次 SIMD 宽度，bake 与 render 使用相同宽度的分组。详见[bake 实现](../../architecture/rendering/implementation/bake-and-partition.md)和[设计](../../architecture/rendering/overview.md#三阶段契约)。 |
| A-BAKED-CACHE | 私有且可重建的 baked 产物按实际输入和 profile 复用。详见[cache 理由](../../architecture/model-management/storage/derived-artifacts.md)。 |
| A-FRAME-REUSE | 输出槽原地复用 native 帧状态并返回借用视图。详见 NR-06、[帧状态](../../architecture/rendering/implementation/frame-execution.md#输入输出与状态)和[设计](../../architecture/rendering/overview.md#资源与状态)。 |
| A-PLAN-CACHE | 姿态变化但可见工作集不变时复用 schedule。详见 NR-07 源码与测试。缓存本身是性能优化选择，与正确失效边界相互独立。 |
| A-PARALLEL | 以 CubeGroup 作为不可拆分的并行单元，在同一 draw 内汇合，并预先分配输出缓冲。详见 NR-08、NR-09、[帧执行](../../architecture/rendering/implementation/frame-execution.md)和[设计](../../architecture/rendering/overview.md#并行输出与宿主提交)。 |
| A-ARCHIVE | 归档包装层缓存并返回借用的解压字节（见 NR-11）。其性能动机属 Inference，覆盖条件属 Fact。 |
| A-LEGACY-READ | Java 按观察长度把输入完整读入有界 direct buffer，native 从借用视图同步流式解密并解压，来源稳定性由调用方负责。详见 NR-12 和[输入契约](../../architecture/asset-pipeline/implementation/conversion-and-export.md#legacy-envelope-的输入契约)。 |
| A-LEGACY-PROJECTION | 由 native 侧旧解码器做解码和映射，Java 侧写入当前容器格式。详见 NR-13、[单向投影](../../architecture/asset-pipeline/implementation/conversion-and-export.md#历史输入的单向投影)和[设计](../../architecture/asset-pipeline/overview.md#冻结输入与单向转换)。 |
| A-TEXTURE-PUBLISH | 候选资源的全部 base 与 PBR 组件经宿主接纳后才发布唯一 Ready，避免发布后再引入补全或回滚逻辑。详见[资源理由](../../architecture/model-management/client-resources/overview.md#ready-与宿主交接)。 |
| A-PUBLISH-BUDGET | 普通宿主发布在 render owner 中按已处理完成的条目数分批推进。详见[owner 边界](../../architecture/model-management/client-resources/implementation/resource-ownership.md)、MN-19 和[设计](../../architecture/model-management/client-resources/overview.md#ready-与宿主交接)。当前阈值收益尚未量化，也不作为宿主上传耗时或显存驻留的硬上界。 |
| A-FAILURE-MEMORY | 失败的 Flight 立即退出命中表，诊断信息与重试资格转由精确对应的域保存。详见[Flight 终态](../../architecture/model-management/client-resources/overview.md#seam-contract)和[恢复资格](../../architecture/model-management/sessions/overview.md#失败与恢复资格)。 |
| A-REMOTE-FILES | 本地派生物与远端字节写入固定根目录下的文件型 cache。详见[存储总览](../../architecture/model-management/storage/overview.md)和[精确缓存与持久工作](../../architecture/model-management/storage/overview.md#精确缓存与持久工作)。由于文件系统存在符号链接等间接寻址，仅靠哈希文件名不足以约束物理写入目标。 |
| A-CONVERTED-CONSUMERS | 实际 CACHE 根目录共享 SQLite 准入与持续 SHARED 锁，以保护业务消费和逐根进行的 converted 与 bake 连续清理。详见[消费者保护](../../architecture/model-management/storage/overview.md#共享存储与清理)。 |
| A-DEFER | Action 可以在动画上下文 reset 之前保留已经求值的参数，并在 reset 时按登记逆序执行 `defer` handler。详见[副作用阶段](../../architecture/animation/controllers-and-playback.md#副作用阶段)。此处只记录当前执行语义，不补造最初引入理由。 |
| A-SQLITE | 私有 SQLite 承担类型化存储、短事务与资源关闭；业务 epoch 隔离不兼容版本，revision 限定兼容演进，协调协议随被保护的命名空间演进。详见[数据库执行](../../architecture/model-management/storage/overview.md#数据库执行)。 |
| A-BUFFER | 允许近期丢失或覆盖的 roaming 与使用时长按秒合并写入。详见[偏好存储](../../architecture/model-management/storage/preferences.md)。 |

`H-BLEND` 与 `H-ALLOCATOR` 是尚未确认的假设根（分别见 AN-08 与 NR-03），不能当作已接受的决策。
