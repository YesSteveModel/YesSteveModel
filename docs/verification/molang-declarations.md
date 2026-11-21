# Molang 声明集成验证

本页记录独立制品、缺依赖矩阵与成本测量的复现方法；语义规范见[声明规则](../architecture/animation/molang-declarations.md)与[GUI 解释规则](../architecture/animation/molang-interpreter.md)，实机缺口见[动画问题](../status/known-issues/animation.md)。

## 发布与真实缺依赖

在项目根目录执行以下命令：

```powershell
.\gradlew.bat verifyPackagedDeclarationMatrix verifyPackagedCallableComposition verifyExpressionRuntimeBundle verifyExpressionArtifactWarmLoad
.\gradlew.bat test --tests '*ExpressionArtifactPipelineTest.productionTransientQueueAndWatchReadOneCurrentOwner'
.\gradlew.bat verifyWeddingControllerWarmLoad
.\gradlew.bat verifyExpressionSemanticMatrix
```

`verifyPackagedDeclarationMatrix` 只将发布 JAR 作为 `annotationProcessor` 路径，独立生成 provider、helper 与 checker；运行时 classpath 不包含项目 main 输出或未重定位的引擎。游戏类型桩及 Gson、Maven artifact、Commons Lang、Log4j 作为显式宿主依赖传入，不构成 Forge 启动证据。

每次运行会在 `build/verification/declaration-matrix/run-*` 保存 17 个独立 JVM 的 JSON 报告、类加载日志与 artifact。各场景真正移除 A、B 或声明 JAR，或替换为缺少所需方法的 B JAR；实现的 subject、字段与方法签名均直接引用依赖类型，版本范围采用 Maven 原生规则。矩阵另覆盖初始化失败、缺少生成绑定、manual 未提交、成功、失败与重复、auto 双路径、冲突、迟到提交及顺序置换。被拒绝的类不得加载或构造，独立的 A 类也不随 B 失败而退出；测试逐项核对最终符号、绑定、返回值及实参与 provider 调用次数。

`verifyPackagedCallableComposition` 复用同一套发布接纳与 artifact loader。单个独立 mod JAR 中的有状态组件与静态 ACTION 组件合并到同一 namespace，八条表达式在同一轮交替执行裸读取、默认参数、primitive 缺失、获准动作与拒绝动作：手写真值为 `1,2,7,103,7,4,100,7`，组件状态序列为 `1,2,3,4`，动作立即生效，求值返回前轨迹为 `3,0`，两种 scope 的 view 身份一致。独立 JVM 的暖加载进程应直接读取同一 artifact，编译分支计数为零。当另一 mod 的独立 JAR 导出相同 namespace 时，三个声明全部被拒，实现类加载与初始化次数均为零。报告输出至 `build/verification/callable-composition/run-*`。

`verifyExpressionSemanticMatrix` 对 direct 与 fallback 两种模式各运行独立的冷启动和磁盘暖加载 JVM。持久程序串联数学运算、用户函数与除法，暖加载完成后再编译临时数学链及除零表达式。夹具核对路由、返回值、恢复诊断与 controller 权限，并验证拒绝旧 semantic ABI、不同除法选项和变更后的声明身份；磁盘暖加载的 semantic、source 与 backend 计数均须为零（临时编译发生在暖加载计数校验之后，不计入暖路径重编）。结果输出至 `build/verification/expression-semantic-matrix`。

整轮运行保留缓存，使发布、未发布、声明缺席与恢复发布分别按最终身份选择 artifact：恢复发布与顺序置换均命中磁盘暖加载；同身份但不同拒绝原因仍复用暖缓存，而诊断消息来自当前启动。缺符号、参数不匹配与 ACTION 门禁分别核对局部恢复及强类型缺失，不触发整条 program 替换。

队列测试直接调用真实的 `AnimatableEntity.executeTransient`、`AnimationProcessor` 与 watch 管理器，冷编译与磁盘暖加载均验证：execute 写入 7→9，已编译的 direct 与 shared watch 读到最新 Root，debug 共享写函数写入 1 后由后续 watch 读到当前值，`fn.actor()+1` 在 debug 与 execute 下均返回 6 且仅允许端提交动作。真实 Minecraft subject 由受控初始化构造，效果提交接收器不连接游戏世界；该测试不证明 tick 排空时机、多 pass once-only、声音或网络双端。

原作品测试直接读取花嫁酒狐控制器原文，经生产 frontend 与 coded-controller 入口执行，夹具仅固定主手剑挥击、stage 与动画完成事实。无可选依赖的独立 JVM 冷启动与磁盘暖加载路径核对 stage 2、3 及其他值的状态转移、动画、声音命令顺序以及非挥击的 reload 分支。声音命令接收器只验证参数与顺序，实际播放、连招延续与 Better Combat 兼容环境仍需 Forge 实机观察。

## 组件生命周期

纯粹组件作用域与绑定状态机回归默认由 `./gradlew test` 运行，不依赖 `YSM_FIRST_PERSON_FIXTURES`（原依赖 `YSM_FIRST_PERSON_FIXTURES` 与伪 `Minecraft` 宿主的 `EntityComponentOwnerLifecycleTest`、`GuiEntityExecutionTest`、`SubjectSampleOwnerIntegrationTest`、`EntityProjectionIntegrationTest` 与 `FirstPersonOwnerIntegrationTest` 现已退役并转由 L3 真机冒烟覆盖）：

```powershell
.\gradlew.bat processorTest
.\gradlew.bat test --tests '*ComponentScopeTest' --tests '*EntityExecutionLifecycleTest' --tests '*EntityQueuedExecutionTest' --tests '*EntityModelBindingTest' --tests '*DefaultDeclarationProjectionTest' --tests '*RenderTargetStateIdentityTest'
```

*（注：下文涉及上述已退役伪宿主集成套的描述保留为历史观察边界记录，当前常规组件与声明兼容性验证以 `ComponentScopeTest`、`processorTest` 与 `verifyPackagedDeclarationMatrix` 为准。）*

Owner 测试在独立 JVM 中编译并接纳第三方组件，经真实安装与 `tickAnimation` 核对首次零跨度、限频累计、时钟倒退、观察复用、六个失败阶段、默认模型拒绝重试及 worker 真实终态。关闭故障覆盖退休、限制停用、调用失败，以及主请求与 fallback 同时处于 pending 的情况，确认单项关闭失败后其余资源仍完成释放，并保留原异常与 suppressed。兼容纹理 A→B→缓存 A 核对组件、位置历史、播放时钟和队列的延续，以及几何与 controller 关联刷新；holder 测试另核对成功 begin 前缀、全部 end 清理与原异常保留。

投影测试由 Gradle 向子进程传入 main compile classpath，分别验证可选 JAR 存在与缺失两种情况（普通 test runtime 保持不变），确认第三方与带外部依赖的 builtin 函数只进入普通宿主，而 MAIN 与 arm、GUI 与 FULL、binding、transient 及内存和磁盘制品均使用同一内置默认视图。隔离缺值夹具核对普通宿主返回 17、内置默认返回 7，且缺失调用不求值实参。完整默认源的冷准备与磁盘暖加载另核对原始关键帧、FP_ARM 空并行动画以及零整条 program 替换；这些数值观察不证明宿主动作选择、首帧绘制或外部 provider 全路径隔离。

资源测试复用生产 parser、prepared cache、module、assembler 与 pin，但替换了图像解码、native 几何烘培与 GPU 消费阶段，实体、调度和效果接收器同样受控；因此这些回归不证明真实 Forge 的视觉表现、多 pass、可选模组线程安全或长期资源回收。

## 准备复用与 Roaming

```powershell
.\gradlew.bat test --tests '*PreparedTargetCacheTest' --tests '*AnimationStoreTest' --tests '*ClientModelBootstrapTest' --tests '*SetIsolationCalibrationTest' --tests '*ModelProgramInventoryTest' --tests '*ModelProgramPreparationTest' --tests '*DefaultAnimationInputTest' --tests '*RuntimeRoamingOwnershipTest' --tests '*QualifiedStateReportTest' --tests '*ExpressionBindingTest'
.\gradlew.bat verifyBuiltinModelIndex verifyExpressionArtifactWarmLoad verifyExpressionRuntimeBundle
```

缓存测试核对共享准备计数、独立 interest 与 pin、迟到候选回收、取消、队列丢弃、关闭和绑定关闭。默认初始化使用生产内容与 module，GPU 消费阶段为测试接缝，仅证明 required 集合已准备并持有驻留引用，不证明游戏后续路径零磁盘访问。集合 fixture 为 body 与 arm 的同名动画和 Common 函数设定不同手写真值，核对 F32 与 String、默认 origin、defer 及生命周期归属。长默认 origin 回归使用完整业务位置核对定位与跨 set 区分，完整 builtin 门禁核对完整 `ProgramKey`、跨 set 区分与分析排序，不设 key 长度上限。

源准备测试分别核对 main 与 arm 以及玩家、投射物、载具的形式条目与诊断位置，并通过内容变化、Map 顺序、函数声明顺序及默认子集检验输入身份。默认输入测试用不同返回常数与累加状态核对来源函数、不同模型、独立实例、默认版本变化和冷暖结果，确认来源内函数名映射变化时结果出自当前组合。各用途产物须各自命中，cache-only 未命中不阻止后续冷准备；手写真值是语义对照，源摘要相等仅作辅助解释。

主体 binding 分区测试核对同一容器下的独立内存和磁盘项、空分区与仅内存候选，用于证明存储隔离；实体上的真实 provider 调用仍需集成轨迹验收。

比较源准备阶段耗时应覆盖同一模型或集合的完整 `ProgramSource` 序列，并记录墙钟时间、CPU 与内存分配；评估跨模型缓存时必须计入首次构造和后续组合开销。由于源阶段不含语义分析、后端编译、GPU 或可见内容等待，不能仅凭源阶段数据裁决完整模型加载或帧期不退化。

Roaming owner 测试采用真实安装、对应 get、主线程投影与受控 module；报告测试使用真实 planner 与 `FrameCodec`，宿主发送由接收器承接。测试核对同值写入仍进入本地 delta、快照不消费 dirty、body 专属字段、arm 全量与增量更新、退休拒用及发送失败策略。受控 module、消费接缝与发送接收器均不能代替真实 Minecraft provider、正常第一人称 hook、载具与双端网络的完整轨迹。

实机验收应固定模型输入与哈希，经正常选模、相机切换、客户端 execute 与 watch 及乘坐操作进入生产路径，核对 body 与 arm 独立状态、实际网络报告、载具消费与安装退休。默认初始化后应独立观察磁盘访问，实体运行与资源关闭应保留工作计数；少一次准备或单个正确返回值不足以证明净收益。完整准备与帧期对照遵循[持续性能基建](../tools/benchmark/README.md)的证据要求，固定新旧源码、共同工作量、独立 fork 与噪声边界，涵盖静态计划、subject binding、双集合、保留、快照与投影成本。

## GUI 解释与驻留

```powershell
.\gradlew.bat test --tests '*EntityInterpreterRuntimeTest' --tests '*InterpretedRoamingStateTest' --tests '*GuiPlayerAnimationCacheTest' --tests '*GuiInterpreterResidencyTest'
.\gradlew.bat verifyRequiredDefaultResidency
```

解释运行时回归经生产 factory、来源索引与真实主体，核对 F32、BOOLEAN 与 VOID、私有变量、按需函数、事件参数、defer、顶层失败来源、原异常身份、函数深度终止及安装归还。`InterpretedRoamingStateTest` 在真实解释环境中核对新增名称后外部编号保持稳定、观察不消费 dirty、full 与 patch、负零以及角色边界。GUI 缓存回归覆盖与 FULL 的任意先后顺序、并发冷未命中、一方失败或取消、仅加载动画输入及 cache-only 零写入，并在关闭存储后验证首次程序准备，确保不依赖磁盘程序副本。

统一 store 的驻留回归应经真实 Ready lease、store 与实体 runtime，区分同 store 长间隔重访和真正淘汰后新建：前者核对程序准备计数不增、已绑定动画复用、新实例私有变量重新初始化，且多实体或多纹理各自退出不退休其他使用者；读取阻塞时封口后，已接纳回调与嵌套准备仍能完成，backend 仅在最后一次真实退出后关闭一次，清理失败沿执行该清理的调用向上传播。未发布候选的回滚，以及世界与手部、GUI、固定预览的进程内退休分别沿原 owner 验证，服务不以全域登记或全局等待替代这些生命周期。

兼容纹理回归核对 session 与实例身份、变量、controller、播放进度与事件游标保持不变，只刷新 binding、resolver、view 与 failure gate：候选校验失败保留旧安装，激活 hook 开始修改后失败则结束使用；真正切换 store 或退出时清空旧安装、player 队列以及 deferred 和 transient 的资源引用。`verifyRequiredDefaultResidency` 核对内置默认请求统一复用驻留 FULL，普通 `builtin:default` 的 GUI 则保持独立。`GuiEntityExecutionTest` 另比对串并行 GUI 输入与 JNI 姿态和顶点输出。实际通过范围记录在[状态页](../status/known-issues/animation.md#gui-与模型冷准备)，这些 headless 轨迹不证明真实页面、GPU、reload 或性能达标。

性能对照须固定 eager、retention、模型、日志与同一测量入口，分别记录离页 60 秒后的同 store 重访和真正退休后的首次使用。由于重建 target 可能仍复用 prepared pin 持有的 store，后者必须先确认旧 store 处于 `CLOSED` 且 `uses=0`，再验证重入创建了新 store。程序准备次数、store 与 target 新建次数及帧长尾需分开报告。Retained heap 按唯一 store 去重（旧实现以动画 owner 加对应 backend 为逻辑组），分别统计已访问的动画和程序以及完整源定义；共享对象去重并排除弱引用后的各分类不可直接相加。JMH 分配、全进程 live bytes 或单次 GC 不能替代上述保留观察，真实页面入口见[持续性能基建](../tools/benchmark/README.md#模型列表真实宿主)。

[JVM 退出](../architecture/runtime/overview.md#jvm-进程退出)只核对进程能正常终结、自有后台线程不阻止 JVM 退出，以及重启后持久化数据合法；退出时不要求观察全部实例或 store 清理，也不要求数据库排空。显式 flush、受控存储关闭与进程内退休仍各有独立验收标准，历史候选的完整退出清理成功只证明当时的路径。

## 主体引用与箭头

```powershell
.\gradlew.bat test --tests '*SubjectArrowIntegrationTest' --tests '*MolangFrontendTest' --tests '*HostSemanticContractTest' --tests '*ClientSessionRuntimeConnectionTest'
.\gradlew.bat verifyMolangArrowSemanticMatrix
```

*（原 `SubjectSampleOwnerIntegrationTest`、`EntityProjectionIntegrationTest` 与 `ClientPlayerStateIngressTest` 因依赖 `Unsafe`/自定义 `ClassLoader` 伪造 MC 宿主已退役；当前以 `SubjectArrowIntegrationTest`、`verifyMolangArrowSemanticMatrix` 及纯协议/会话单测为准。）*

`SubjectArrowIntegrationTest` 经生产 frontend、registry、Janino、artifact codec 与 loader 及源实例，核对 `v.flame_level ?? (v.flame_level = ysm.projectile_owner -> ysm.equipped_enchantment_level('Mainhand', 'minecraft:flame'))`：正等级与零等级均缓存，命中时不调用 child 或 provider，missing 保持可重试；另覆盖 source 与 current 双向调用、求参前拒绝、只读 Entity 列表、VALUE 与 transient、自指箭头及目标无模型时的纯事实查询。`MolangDeclarationProcessorTest` 核对两种注解的 `alwaysOrigin` 和 `action` 映射，以及成员方法必须声明 `alwaysOrigin = true` 的约束。

`verifyMolangArrowSemanticMatrix` 让同一套手写对照分别消费普通 classpath 和实际重定位后的发布 JAR，两条路径各自运行独立的冷启动和磁盘暖加载 JVM，逐项比对持久与临时程序的返回值和调用轨迹。矩阵覆盖原式零值、正值、缺值重试与缓存，以及合法分组、LOOP 次数、CURRENT、ROOT 与 source frame、nullable derive 和函数深度；容量语料让赋值、字符串、参数尾与列表跨过各自旧阈值。磁盘暖加载在持久制品装载、执行和身份拒载完成后，要求 source、semantic、syntax 与 backend 四阶段计数均为零；随后单列 transient 编译。完整结果输出在 `build/verification/arrow-semantic-matrix`。

箭头矩阵使用受控的精确 registry 与 provider；发布声明矩阵和宿主暖加载入口验证真实 Entity 声明接线；Forge 实机验收则核对真实装备、owner 角色与线程边界。

完成样本的受控 barrier 与 owner 回归区分本轮私有值和上次完成值，覆盖固定发布角色、失败或取消不提交、真实结束与迟到发布排序。玩家投影回归在捕获后修改 effects 原容器，确认目标值不受别名修改影响。测试不等待目标任务，也不证明 live 可选 API 的线程安全。引用根须分别核对源槽、context、transient 与 cache 的撤销，以及目标组件、runtime 与 lease 的终态，`WeakReference` 只作辅助观察。

大容量与取消边界由 Semaweave 的 `DefaultExpressionAotCompilerTest`、`FunctionParameterBoundaryTest`、`CompilationLedgerTest` 及运行时验收测试覆盖。有限的代表性输入不能证明任意模型都可编译，也不能替代尚未取得的原报告模型；真实 Forge 与完整成本状态由[动画问题](../status/known-issues/animation.md)维护。

同轮连续解释表达式回归核对局部变量和 return 重置、普通变量与 Roaming 持续；引擎回归核对各出口借用根撤销、共享签名计划与同名位置的并发准备、不同准备顺序及失败准备后的复用、跨实例值与 Roaming 成员隔离、惰性函数增长后的父读取与字段发布，以及递归和箭头局部激活。这些入口与驻留、箭头套件、普通及重定位冷暖矩阵和运行包联合验证，性能判定仍独立执行。

## 成本测量

标量执行先由 `verifyMolangExecutionBenchmark` 核对返回 bits、provider 调用次数和拒绝门禁，再经长期 JMH source set 测量，分别报告同候选的 boxed 与 primitive 载体、相同调用语义的新旧配对、同轮数量斜率与完整轮固定成本，运行方法见[标量执行基准](../tools/benchmark/README.md#molang-标量执行)。`verifyMolangPayloadAllocation` 在两个独立 JVM 中分别计量真实生成的 tail、record 与组合入口，以及空轮、失败快照、particle 与 defer 载荷的轮级总分配（编译与绑定在计时外；action 与 defer 行仅含即时载荷或专用登记及来源快照，不含延迟回调调度）。姿态行由 `EntityExpressionRuntimeTest.literalPoseSamplingReportsAllocationSeparatelyFromScalarExecution` 计量真实观察轮内的单骨骼字面量插值。载荷报告不能当作稳态标量框架分配的解释，历史动画准备样本也不构成完整安装或游戏帧期不退化的结论。

`SubjectArrowCostBenchmark` 的独立 `main` 核对原式首次正值、首次零值、缓存命中、missing，以及嵌套和重复 1、2、4、8 次时的返回值和 CURRENT、ROOT 调用轨迹。JMH 的 `execute` 测固定执行场景，`childAndBinding` 单测真实 derive，`freeze`、`publish`、`projection` 与 `projectionEffects2` 分别测样本和网络接纳。（注：原依赖伪宿主的 `EntityOwnershipBenchmark.frame` 与 `PlayerMolangExecutionBenchmark.scan` 已随第一人称宿主夹具一并退役。）启动清单由 `prepareBenchmark` 导出，运行方法见[持续性能基建](../tools/benchmark/README.md)。

`ArrowCapacityCostProbe` 针对箭头语义矩阵单列冷编译各阶段耗时、heap pool 峰值与定时取消响应。各池峰值可能出现在不同时刻，不能相加当作同时峰值；单次取消点也不证明 Janino 内部可随时抢占。Instrumentation 浅大小、已知独占对象图、运行期分配与全堆 retained 是四类不同指标；源借用结束与目标资源释放应由 owner 和 Forge 联合账本验证，不能由浅大小或 GC 时序替代。

长期成本测量沿用[持续性能基建](../tools/benchmark/README.md)与[默认 player/main 编译基准](animation-bake-benchmark.md)，功能真值、调用轨迹、owner 隔离与冷暖回归继续由本页各独立入口负责。新增测量应固定共同工作量、源码与制品身份，分别核对结果、耗时与分配；稳定位置存储另须披露名称登记、容量增长、根局部清理以及目录和实例的保留成本，并标明实际覆盖范围。

## Provider 契约审计

`SoundInstanceManagerTest` 使用真实生产适配器，核对动作当下的动画和控制器声音目标及全局选择；播放停止由 `FakePlayback` 观察，不验证音频设备。GUI 已不再建立编译闭包，相关输入与执行回归见[GUI 解释与驻留](#gui-解释与驻留)。`HostSemanticContractTest` 保留函数的两种源形式与非法赋值覆盖，`ModelParserTest` 核对默认源保留外部引用并交由接收宿主按 missing 语义定型。

作者声明与接纳回归覆盖 `YsmEntityComponent`、成员 namespace、来源 mod 归属以及统一的 `MolangContextView`。公共 API 的独立编译正反例校验闭合泛型、声明 effect 以及不可见的执行控制方法；namespace 冲突与无效来源在实现类加载前即被拒绝。

`MolangExtensionAdmissionTest.uniformDeclarationsBindWithoutBusinessCatalogs` 独立生成一个扩展 JAR，其中新查询返回 7、新 ACTION 直接写入 `[3]`，夹具完整走过来源接纳、符号发现、编译和生成调用，证明新增能力无需改动通用业务目录。公共函数保持普通声明，数学运算独立使用引擎 intrinsic。

`PureDeclarationOracleTest` 以普通声明核对 `2*x+1`、subject 拒绝、超过旧 provider 与 control 阈值的正常执行、nullable 非有限返回及异常身份，且不授予 intrinsic 权限。`MathIntrinsicBindingTest` 核对语言声明与 registry、发现事实一致；引擎接纳、恢复和实际生成运算测试覆盖闭集资格、14 项数学运算的 raw bits、存在性、实参轨迹及无 provider 包装。

跨进程暖加载夹具保留冷进程写出的 artifact，在冷暖两个进程上核对只读 controller target 不授予修改能力且窗口外动作轨迹为空。真实 `CodedAnimationController.process` 到 player 的同帧选择，以及源失败后的 sync 交付由 `FirstPersonOwnerIntegrationTest` 独立观察。暖进程分别修改声明 effect、普通成员 binding 以及接纳后的有效依赖集合，验证原 artifact 被拒并触发重建（effect 与 binding 变体通过冻结宿主接缝注入，依赖变体经真实接纳筛选）。主目标暖加载仍要求 semantic、source generation 与 backend compilation 计数为零。

`DomainActionTest` 与 `SoundInstanceManagerTest` 核对 controller 同步窗口、骨骼立即写入、无效粒子拒绝、声音 manager 接纳与停止。`FirstPersonOwnerIntegrationTest` 穿过真实 `CodedAnimationController.process` 与 player，手写轨迹为 `[sound, controller, player, sync]`：controller 选择在本帧由 player 消费，sync 等待源组件与 context 借用退出后交付。函数深度终止保留正常交付与后续表达式；普通 body、end 或 handler 失败保留已生效动作、丢弃未交付的 sync 与 defer，并由原 owner 结束精确安装；安装失效或严重退出同样清空待分发载荷。测试核对音量、音高、动画名、循环模式与参数，不证明音频设备播放或网络发送。

开发测试中的 `ProviderContractAudit` 显式观察已接纳载体、保存发布快照并复查底层修改，诊断信息包含 provider 方法及字段或索引路径；借用审计同时关联调用线程和真实 context 窗口。`ProviderContractAuditTest` 区分未标注 float 的非有限违约与 nullable `float`/`Float` 的合法 missing，覆盖禁止位置 null、发布后篡改、异常原 cause、字段与索引位置、转换角色、过期或跨线程借用以及 effect 误用。固定许可与禁止同步重入由 runtime 生命周期回归核对，审计器不维护动态权限栈；审计器仅位于 test source set，不自动插入生成调用，也不承诺识别任意恶意 provider。

`ProviderContractCoverageTest` 将接纳后的 111 个精确 JVM 成员与 `provider-contract-coverage.tsv` 逐项比对，新增、变更或取消接纳的成员须同步夹具。95 个查询与 16 个动作运行受控 subject 与业务边界输入，动作另关联领域轨迹夹具；测试还核对 registry 中的 7 个字段读取、长度、索引读取及转换绑定与精确方法夹具一致。分类覆盖不代表遍历了全部业务输入，可选适配器的默认缓存及未启用状态不证明真实模组采样，数学函数的定义域与溢出语义由 intrinsic 验证负责。`PublicCapabilitiesTest` 另测随机等界、反向界和全范围以及真实物理发散；`ComponentScopeTest` 核对非法轴与有限 double 采样向 F32 溢出时的 nullable 结果。

`MolangDeclarationProcessorTest` 组合测试 builtin 与 public、static 与 member、有无 view 以及 FIXED、OPTIONAL、VARIADIC，核对精确 linkage、subject 与脚本重载键。`verifyPackagedDeclarationMatrix` 在独立扩展 JAR 中执行 12 种签名，区分省略与显式传入 UNSET，核对 `±0`、nullable 非有限结果与 `??`：无 view 的 static 仍接受其他合法 Entity，窄 subject、member 与 ACTION 拒绝时不求值实参，实际生成的 class 不含符号查找或通用值派发。

## 内置模型与制品门禁

`verifyBuiltinModelIndex` 对 27 个内置模型、60 个物理 target、80 个 set、20,717 个 program 执行完整物化，要求 replacement 集合为空。`verifyExpressionArtifactWarmLoad` 在两个独立 JVM 中核对冷启动与磁盘暖加载的身份、返回值、effect 与 replacement，覆盖对象清空与恢复、跨函数存在性、非法非字面量哨兵以及旧 semantic ABI 拒用。`verifyExpressionRuntimeBundle` 进一步校验实际解析依赖、relocation、打包后的 provider linkage 与依赖哈希清单。独立缺 JAR 矩阵与真实队列、watch 的复现方法见[发布与真实缺依赖](#发布与真实缺依赖)。上述自动化测试不能替代真实可选 provider 模组或 Forge 世界中的 controller、target 恢复、调试消息、声音与性能观察，剩余边界统一见[动画已知问题](../status/known-issues/animation.md)。
