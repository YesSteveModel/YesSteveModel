# 动画已知问题

正常职责见[动画架构](../../architecture/animation/overview.md)。下列局部自动化测试与宿主观察结果仅证明各自覆盖的边界。

## 求值与语义

- `isMoving` 存在逻辑错误，影响依赖它的 `CodedAnimationController`。
- LOOP 播放单次跨越多个完整周期时，当前事件推进只处理旧周期尾部和最终采样周期，中间周期可能漏发离散指令。尚未通过回归测试明确多周期事件的期望与边界规则，需对照[主时间线因果](../../architecture/animation/controllers-and-playback.md#指令区间推进)核对，不能视为已证实的全部事件语义。
- 骨骼基准快照（snapshot）误用 `children_hidden` 初始化 `cubes_hidden`，可能混淆两个可见性通道。
- Controller 旋转混合仍保留不完整的历史行为。当前 Schema 已移除 `blend_via_shortest_path`，读取器固定按 `false` 处理，模型无法表达该选项。
- `ysm.projectile_owner` 已通过精确 `Entity` 引用和通用箭头接入。真实 Forge 已验证原式的正等级、零等级与缓存命中、无模型目标，以及正常的世界、GUI 和第一人称角色。当源槽仍持有 `Entity` 时，目标的 lease、component 与 runtime 已正常结束；源覆盖、退休、transient 关闭与根借用撤销均已通过联合账本校验。真实 Arrow 的 Vehicle 发布角色目前仅由 mock 资源选择器验证；正常 arrow Vehicle 仍缺少精确的默认动画域，尚未证明目录匹配。装备观察仅限于 Render 线程，不扩展 live API 的线程安全承诺。方法见[主体引用与箭头](../../verification/molang-declarations.md#主体引用与箭头)。
- 模型声音已具备内容校验、渐进消费、待提交与已提交停止以及外部后端拒绝的局部自动化证据；但真实 Forge 下的 once-only 触发、音量、OpenAL 拒绝与替换、设备容量与停止时序尚未验收。路由与所有权见[声音触发](../../architecture/animation/controllers-and-playback.md#声音触发与宿主播放)。
- 花嫁酒狐 `main` 与 `arm` 的完整静态集合已有 headless 冷编译证据，原控制器也已通过生产 frontend 和 coded-controller 在独立的冷启动和磁盘暖加载 JVM 中核对分支、命令和返回值。旧资格族（family）的预算失败问题已不适用，但正常选模后的声音、连招、reload 及 Better Combat 联动环境尚未验证。制品与对照标准见[集成验证](../../verification/molang-declarations.md)。

## Context、线程与生命周期

- 候选安装的 OOM 处理已与内容拒收记忆分离。受控 JVM 与 JNI 回归穿过真实组件的兼容纹理激活 hook，验证了旧使用结束、候选 lease 关闭，以及同请求和同内容的后续独立安装成功；必需默认模型的 OOM 与清理异常归属另有 binding 回归覆盖。失败记忆分配抛错也不再跳过共享 abort 或候选关闭。不过这些证据不覆盖真实低内存与 Forge 联动组合；契约见[OOM 隔离](../../architecture/animation/entity-lifecycle.md#共同规则)。
- 实体清理链按统一的严重错误优先级合并候选准备、role-commit 激活和结束使用过程中的失败。受控回归（含原历史集成套与当前纯 `EntityModelBindingTest` 回归）核对：原 OOM 之后抛出的 `InternalError` 或 `ThreadDeath` 仍会向上传播，候选 session、lease、多个 controller、待取消的 transient 与 watch 均完成清理尝试；同一短期 session 内的执行借用与程序 lease 双失败也会保留严重错误。取消结果通知独立于任务关闭失败，程序与实体 lease 计数均正确归还（原 `FirstPersonOwnerIntegrationTest` 历史伪宿主回归现已退役并转由纯单测与 L3 冒烟承接）。故障注入采用“先执行真实释放再抛错”的方式，不证明真实低内存或完整 Forge 联动。

<a id="default-startup"></a>

- 内置默认（intrinsic default）已[按来源与声明依赖过滤函数](../../architecture/model-management/client-resources/default-model.md#边界与权威)。在受控的可选库存在与缺失环境下，已验证 MAIN 与 FP_ARM 的冷准备、磁盘加载、missing 与 UNSET 行为及基础动画关键帧。完整 required 启动有领域测试验证；Windows Forge 已观察双角色、本地与远端初始选择、普通卡片 GUI 与中央 FULL、基础动作及第一人称手臂、reload、重连和 default-only 模式。真实等待页已验证远端空投影、普通 Pending 和自动 Matching。在完整默认源合并既有多块声音 fixture 后，headless 已验证在全模型目录、缓存与 Java 网络均禁止读取的前提下，新实体安装、原动画求值与完整非空 PCM 输出均正常；GPU 发布与音频设备不在该边界内。实测可选模组组合为 Curios、Geckolib 与 Superb Warfare；声音设备及其他未列模组组合仍未验证。入口见[宿主验证](../../verification/model-management.md#构建与进程-ownership)。
- 部分核心声明仍会间接调用 ParCool、CosmeticArmor、ElytraSlot 和 SlashBlade。函数声明过滤不能证明这些调用或组件生命周期已与外部故障隔离，后续仍需独立处理。
- 完整默认源冷准备的预期稀疏传播已有工作量账本，但冻结输入与环境下的冷暖对照样本量不足以形成预定置信区间，耗时与分配的联合收益目前无结论。新 JVM 暖路径的 Program decode、semantic、source generation 和 backend compilation 计数均为零；分配数据只是在 `prepareSet` 区间内的 JFR 采样估计。方法见[动画编译基准](../../verification/animation-bake-benchmark.md)。
- 隔离 Forge 下的 LocalPlayer 与 RemotePlayer 已消费代表性默认程序，验证了状态与 reset、close 隔离、首次消费不新增编译或解码，以及 required 读取失败不发布残缺服务。完整双实体动作、视觉、多 pass、reload 或断 I/O 以及长期回收仍缺联合证据。
- 派生动画查询已改为消费 owner 私有样本或目标的最后完成样本，远端玩家读取不可变网络状态。但声明为 live 的世界与可选模组查询仍直接读取 `Entity`、`level`、`Minecraft` 或外部 API，这些路径的线程资格与同次读取一致性仍需独立验证。
- Controller、instruction、defer 与 event、config 以及真实队列和 watch 均已通过冷暖值与效果对照（oracle），Forge 也已观察到消费及最终 framebuffer。但同次逻辑推进次数、脚本与声音及粒子和同步事件的联动，以及多 `RenderContext` 下的 once-only 语义仍未端到端联合验证。
- 兼容纹理变体、双 owner 状态隔离、候选与清理失败以及 worker 换模和退休已有受控回归。内容级准备复用的 pin、取消、默认初始化、跨 set 拒用与借用来源已有局部验证；但真实 reload、重进世界、多实体与 UI 和导出并发、联动组合、长期回收及完整准备净成本尚未验证。
- 实体任务交接、完成未 get、Extract 普通失败隔离及动作与状态保留、不同输入和种子的串并行比对、联合 body、vehicle 与 arm 状态，以及 transient 关闭账本均已有受控回归。真实 Forge 已验证正常与受控提前 `renderLevel` 返回、捕获提交或归约失败后正常返回、独立 compiler 跨返回，以及页面、世界与旧 arm 退出；可选模组注入组合仍未验证。方法见[实体所有权验证](../../verification/entity-animation-ownership.md)。
- 组件五个生命周期 hook、owner 时间裁决、六阶段故障、异步终态及仅内置默认（builtin-only default）已通过受控 owner 回归，打包矩阵与独立 JVM 冷暖加载均通过。但资源消费阶段仍使用了替身，真实 Forge 的视觉、多 pass 与可选模组线程安全尚未验证。入口见[组件生命周期验证](../../verification/molang-declarations.md#组件生命周期)。
- 历史冷准备对照在共同保留 Janino linkage 与 BindingPlan 复用后，单消费者 MAIN 的耗时、CPU 和内存分配仍出现超过噪声的退化，多纹理或多消费者的收益无法抵消这一负样本；且绕过 prepared target 接纳的 Factory fixture 不能代表完整启动或活跃 target 复用。该历史对照已结束，完整帧期性能目前无结论。
- `AnimationProcessor` 直接原地修改本 owner 的快照与属性，没有事务暂存或骨骼回滚。Extract 失败时会撤销输出，但不会把全部骨骼恢复为旧值，也不保证任意 controller 异常后能无损继续使用。

输出复用与第一人称的实机验证范围见[渲染问题](rendering.md#重复消费与第一人称)。

实体渲染领取原排队任务、普通 inline 与 GUI 读者后实际 disconnect，以及世界 Catalog 保留和会话失效撤权，已有受控 JVM 与 JNI 组合验证。固定 Windows 配对基准覆盖了稳定与变化资格、及时与阻塞以及 Direct 与 fallback 路径，但稳定路径收益不能抵消变化请求的分配负样本。完整帧、真实 Forge logout 与 optional provider 输入边界仍未闭合，复现入口见[帧路径成本](../../tools/benchmark/README.md#帧路径契约成本)。

## GUI 与模型冷准备

模型列表已采用帧内图片准备、完整绑定与动画交接。实际卡片任务的容量、公平推进、共享图片、串并行 native 输出和退出门禁均已有受控回归；真实输入变化、optional provider 的线程边界与整帧性能仍需独立宿主证据，入口见[模型列表宿主](../../tools/benchmark/README.md#模型列表真实宿主)。

GUI 当前采用[基于稳定位置与私有动态值的绑定求值树解释路径](../../architecture/animation/molang-interpreter.md)。共享动画、定义与执行资源统一由 [AnimationStore](../../architecture/model-management/client-resources/overview.md#动画资源所有权与退休)持有，兼容纹理切换时原位保留私有执行态。统一所有权定向 CLI 的 540 项测试中执行 454 项、跳过 86 项，0 失败、0 错误；required 单项、builtin 索引、benchmark 与 mock host 及启动 overlay 均编译通过。方法见[GUI 解释与驻留](../../verification/molang-declarations.md#gui-解释与驻留)。

以下为已完成的宿主采样与边界记录：

- **同 store 重访与退休对照（2026-11-16）**：Windows Forge 配对运行同一套 85 秒脚本，使用三个固定 Winefox 模型（`eager=true`、`retention=1`）。模型 A 离页 60 秒后重访，旧实现在同一 target 与动画索引上重新准备了 63 个程序；候选版本在同一 target 与 store 上准备增量为零，保留了 13 条已绑定动画。模型 B 与 C 施压后，候选的新 target 仍复用原 store，因此不能据此声称发生了真正退休。独立正对照先确认旧 store 处于 `CLOSED` 且 `uses=0`，再重入同模型：此时创建了新 store 并重新准备 63 个程序，原未绑定输入仍为内存中的同一对象，17 项检查全部通过。该采样候选当时的退出整链无挂起或失败，但这只证明当时路径，现行 [JVM 退出契约](../../architecture/runtime/overview.md#jvm-进程退出)不再执行该整链。
- **帧间隔与内存保留快照**：上述配对在首次、60 秒重访及压力后固定 2 秒窗口内的最大帧间隔，旧实现与候选分别为 189.832 与 183.118 ms、13.790 与 13.782 ms、36.627 与 37.358 ms；单组进程配对仅作描述，不证明统计性改善或不退化。在独立成本进程中按三个唯一 store 去重，关页 15 秒后旧实现的已绑定动画与程序数均归零，候选分别保留 13 条动画 / 63 个程序、11 条 / 44 个、13 条 / 59 个（完整源程序库存两版均为 2044、1984、1974）。MAT 排除 weak、soft、phantom 与 finalizer 保活边后，三个逻辑 owner 的联合 retained heap 从 1,592,272 B 增至 2,210,968 B，增加 604.2 KiB（38.86%）；这是该次整体变更的单次快照成本，不能全归因于结果寿命，且程序、动画、定义分类与各 store retained 均不可直接相加。完整源联合 footprint 为 48,731,696 → 48,731,968 B，其中动画输入均为 48,053,992 B，HPROF 证实其由独立的输入 LRU 强持有，不算作 store 增量。全进程 live bytes 不能替代这些指标，Java HPROF 也不覆盖 native 与 GPU 内存。该样本之后的宿主退出错误隔离曾通过真实 service 清理失败回归及同类 18 项测试（0 失败、0 错误、0 跳过），但该证据同样不覆盖现行直接退出策略。
- **统一所有权重构前的多页采样（2026-11-16）**：Windows Forge 使用用户原生根目录的 15 份文件、14 个模型、两页列表，在空应用缓存、既有磁盘种子及同候选暖盘三个独立进程中覆盖了首次、驻留及程序到期重取。各进程 9375 次程序准备期间文件与目录 I/O 均为零；同候选暖盘在到期后的两页最大完整帧约 45 ms 与 31 ms，驻留期间约 14 ms 与 15 ms，14 个 target 保持同一对象。但首次开页仍有约 200 ms 峰值，不宣称完全无停顿。暖盘 FULL 走实际磁盘加载、AOT 编译为零，从最早实际 FULL 输入边界到首发布正常返回约 1.240 s（内含当时 required GUI 约 111 ms）；该耗时包含排队与其他准备，不能当作纯 AOT 耗时或统计性不退化结论。宿主内容指纹使旧 AOT 种子失效，但动画输入仍可暖命中。原 `gui-purpose` 入口也已完整通过 GUI 解释执行与中央 FULL AOT、16 帧像素与骨骼变化、私有状态、拒绝和纹理选择。
- **历史候选参考**：既有候选 `f583ac3c` 的完整 P1 为 97 通过、0 失败、11 无结论，滤波项 22 通过、0 失败、2 无结论；用户已接收其结论，后续未重跑，也不将其改判为新候选通过。既有 F5 的 36 进程样本与自动初装、重连限制保持不变，近期三进程只覆盖上述真实列表与启动边界。更早的 `75358147` 出现 104 通过、1 失败、3 无结论及 S44 失败，属于历史候选。长期局部引用、物理 GC、全模型视觉及完整产品净收益仍未证明；方法见[单表达式对照](../../verification/animation-bake-benchmark.md#gui-单表达式解释对照)与[真实宿主](../../tools/benchmark/README.md#模型列表真实宿主)。

默认源事实缓存已撤回：完整冷准备对照未能证实净收益，而缓存增加了驻留内存与匹配复杂度；Prepared 与指纹复用仍保留了分配收益证据。

早期 GUI 像素耗时下降不能替代 FULL 不退化；随后的 FULL 对照同时改动了主体 binding 与编译器，无法归因到单项改动。在固定编译器、主体、输入与身份后的消融及实际撤回对照中，仍未确认完整冷准备提速。最终单边整页样本窗口并不一致，最后的 cache-only 门禁也仅做功能验证；完整玩家安装与主循环不退化尚未闭环。方法见[源准备成本](../../verification/molang-declarations.md#成本测量)，当前画面范围见[GUI 取景](rendering.md#gui-取景)。

受检路径的重复复核已经收紧，机制见[存储边界](../../architecture/model-management/storage/implementation/persistence.md#固定区域与文件边界)。Windows Winefox 单卡的独立进程冷加载 A/B 曾复现延迟下降，但当时动画与几何均为 COLD 且使用的是旧 GUI AOT 输入，不能外推到当前解释路径。目录深度成本由[路径基准](../../tools/benchmark/README.md#受检路径访问)单独测量。现有 FULL 冷准备基准的 native 提取输出保持一致，发布经由 headless 接缝完成。卡片测量目前止于首个非零顶点帧，模型偏出画框的问题尚未修复，帧期观察也只覆盖短窗口。

## Molang 与同步

- **性能收敛三轴已通过**：新旧两版使用同型有效主体、生产 owner 与固定状态，完整 1,382 个入口的初始与稳定 raw bits 完全一致，独立正弦真值及 receiver 检查通过。两种模式各跑两对反序测量，相对 B0 的冷编译耗时改善 22.4%–30.1%、执行主聚合改善 68.4%–70.0%、完整 W1 artifact 体积减少 32.4%–32.5%，没有稳定退化项。但原始测量仍带预热长尾，保守复核仅支持改善方向，不证明完全稳态成本或游戏帧率。外部 projectile 默认 domain 在两侧对称缺失，标记为 unavailable；宿主完整测试在 1 GiB 测试堆下通过（跳过项另计）。接线与复算入口见[Molang 收敛基准](../../tools/benchmark/README.md#molang-收敛矩阵)。
- **Typed AOT 与声明组合**：强类型 AOT、统一声明、固定许可、同轮共享状态、完整 builtin 静态计划与独立发布组合已有自动化测试。受控普通标量、同轮数量斜率和轮固定成本已有旧新配对样本；复杂表达式、完整安装与游戏帧期尚无净收益结论。旧数组夹具不满足当前缺值初始化规则，不能作为共同基线。
- **箭头接线与分配观察**：箭头接线已改由原 source frame 提供 ROOT，并移除了 root 自引用。受控普通 round 中曾出现的约 2.2 倍回退与 96 B/op 分配未再复现，但有限正式 fork 的耗时区间仍有重叠，未证明统计等效。真实 1382 项的完整观察窗口包含样本冻结捕获，两个 fork 均测得新增分配，冷编译分配约增加 4.9%。首次、缓存与缺值箭头、child、样本、网络视图和完整 owner 更新已分别计量，但不能代表游戏帧率或全堆 retained 成本。复现边界见[成本测量](../../verification/molang-declarations.md#成本测量)。
- **微基准范围限制**：普通 typed primitive、拒绝门禁、内部函数和准备后 transient 的稳态分配证据不包括 binding 构造、Object 观察装箱或真实载荷。Tail、record、效果、失败与单骨骼字面量插值由独立探针单列，这些样本不能代表完整姿态调度。冷编译与暖解码耗时、制品大小、特化与进程峰值清单均不能外推到游戏常驻内存、动作、帧预算或长期回收。入口见[成本测量](../../verification/molang-declarations.md#成本测量)。
- **Java owner 违约与隔离**：严重返回违约已有事务丢弃、降级、隔离、失败记忆及内容变化恢复的回归测试。Forge 已观察到 command 与 watch 的 UNSET、0 和 false 返回、ACTION 抑制、声音句柄与控制以及重连拒绝旧连接。普通 builtin default 在受控 Molang 异常后已验证切换为内置默认根替换、权威选择保持不变、被过滤入口返回 missing 且 60 tick 内不重试；其余实体 profile、内置默认自身故障与重试、跨 owner 与 controller 的声音作用域、听测和完整玩法权威快照仍未验证。Test-only 开发审计不代表生产插桩。完整 Java 测试套件通过；由于资源聚合用例需同时保留超过 128 MiB 数据，测试执行器使用 1 GiB 堆，受控小堆下的分配失败会表现为 `UNAVAILABLE`，不能将其误判为聚合额度拒绝。见[宿主验证](../../verification/model-management.md)。
- **Roaming 数值化改造**：Roaming 已改为局部 `short` 编号和始终有值的 F32 数组。真实生成器回归覆盖默认零值、同值标脏（dirty）、非消费快照、full 与稀疏变化及导出有限化，普通 nullable 规则独立保留。宿主在外部入口整批校验，准备期建立数值映射。SQLite 仍按完整发布身份与名称存储，重开及 donor 恢复后的重新编号已有组合验证，见[玩家状态网络验证](../../verification/player-state-network.md)。
- **实体状态投影与实机观察**：Body 脏标记、arm 非消费快照与实际 owner 状态投影已有受控回归。Windows Forge 双端窄场景已验证完整 track 下的实体存在与采用、flags，以及 catalog 失败后的重新追踪，见[网络问题](network.md#玩家状态协议与-roaming)。历史 NBT 与首轮 FULL 的实机记录不证明当前 SQLite 或本地提交协议。
- **远端安装前暂存与消费**：远端模型安装前已接入 roaming 状态段与 `ysm.sync` 的有界顺序暂存（含 catalog 发布前到达的输入）。匹配安装在合法执行窗口内逐项消费，sync 的延后动作排空后才应用下一状态段，期间暂停常规动画。已消费事件不随重装重放；选择、安装和会话失效按各自边界清理剩余项。
- **宿主定向与双端实机记录**：`ysm.sync` 的本地单次入队、忽略自身回显、参数边界和旧使用资格已有历史受控验证；数值 roaming 改造后的安装期顺序、容量及实体执行生命周期已纳入 29 个 suite、共 362 项宿主定向测试，于 2026-11-11 全部通过。同日在 Windows 11、Minecraft 1.20.1、Forge 47.4.3 双端通过了数值布局下的真实中继、安装提交与生成脚本消费：7 次 sync 与 7 次 defer 按序执行，普通 player update 均在最后一次 defer 后出现；等待期的选择替换、实体退出和业务会话替换均清空旧队列与预算，恢复安装及消费后重装均未重放旧事件。但任意消费中途退休、容量溢出、handler 故障、完整时钟和帧期成本仍未实机验收。详细 trace 见[玩家状态网络验证](../../verification/player-state-network.md)，正常协议边界见[状态同步](../../architecture/animation/implementation/state-inputs-and-sync.md)。

## 联动与验证

- 战斗、移动、载具和装备适配器仅部分接线，仍有大量目标尚未迁入；已接线的部分也未完成统一输入边界的线程审计与实机验收，不能视为正式支持。
- 无可选 provider 的 Windows Forge dedicated 已物化常驻默认模型并正常启动，但新音频链路的 headless raw 与 legacy 转换以及显式受控关闭尚未验证。缺少可选依赖的发布矩阵不能证明可选模组存在时也可用。
- 仍缺少完整的真实实体、controller 与配置联动、Immersive Melodies 生命周期、多人远端同步、模型热切换、多 pass、第一人称共享、第三方声音后端和长期引用回收的联合验收。JNI 音频续接、取消、预算与驻留证据见[音频缓存验证](../../verification/audio-prefix-cache.md)，headless 结果不证明音频设备的可听延迟。
- `BoneAttribute` 与 `BakedModel` 的前序遍历、可见性与 locator 跨语言契约缺少独立的 golden，重复的布局定义仍存在漂移风险。

女仆模组联动已移除；模型中的 `tlm` 动画集读取与内置动画资源暂时保留，但不再提供女仆实体接入或脚本接口。
