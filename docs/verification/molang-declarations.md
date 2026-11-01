# Molang 声明集成验证

本页拥有独立制品、缺依赖矩阵与成本测量的复现方法；语义见[声明规则](../architecture/animation/molang-declarations.md)，实机缺口见[动画问题](../status/known-issues/animation.md)。

## 发布与真实缺依赖

从项目根执行：

```powershell
.\gradlew.bat verifyPackagedDeclarationMatrix verifyPackagedCallableComposition verifyExpressionRuntimeBundle verifyExpressionArtifactWarmLoad
.\gradlew.bat test --tests '*ExpressionArtifactPipelineTest.productionTransientQueueAndWatchReadOneCurrentOwner'
.\gradlew.bat verifyWeddingControllerWarmLoad
```

`verifyPackagedDeclarationMatrix` 只使用发布 JAR 作为 annotationProcessor path，独立生成 provider、helper 与 checker；运行 classpath 不含项目 main output 或未 relocation 引擎。游戏类型桩与 Gson、Maven artifact、Commons Lang、Log4j 是显式宿主依赖，不是 Forge 启动证据。

每次运行在 `build/verification/declaration-matrix/run-*` 保存 17 个独立 JVM 的 JSON、class-load 日志与 artifact。各场景真正省去 A/B 或声明 JAR，或替换不含所需方法的 B JAR；实现的 subject、字段和方法签名引用依赖类型。版本范围使用 Maven 原生规则。另覆盖初始化失败、缺 generated binding、manual 未提交/成功/失败/重复、auto 双路、冲突、迟到提交与顺序置换。拒绝类不加载/构造，独立 A 类不随 B 失败退出；检查最终符号、绑定、结果及实参/provider 次数。

`verifyPackagedCallableComposition` 复用同一发布接纳与 artifact loader。独立单 mod JAR 中的有状态组件与 static ACTION 组件合并同一 namespace。八条表达式在同一轮交替执行裸读取、默认参数、primitive missing、获准动作与拒绝动作。手写真值为 `1,2,7,103,7,4,100,7`，组件序列为 `1,2,3,4`。动作经 `submit` 准备，提交前轨迹为空，提交后轨迹为 `3,0`。两种 scope 的 view 身份相同。独立 JVM 的 warm 进程应读取同一 artifact，编译分支计数为零。另一 mod 的独立 JAR 导出相同 namespace 时，三个声明全部拒绝，实现类加载与初始化均为零。报告位于 `build/verification/callable-composition/run-*`。

整个运行保留缓存：发布、未发布、声明缺席、恢复发布分别按最终身份选择 artifact；恢复及顺序置换使用 DISK，同身份不同拒绝原因保留 warm，但诊断来自当前启动。缺符号、参数不匹配与 ACTION gate 分别核对局部恢复及 typed missing，没有整 program 替换。

队列测试调用真实 `AnimatableEntity.executeTransient`、`AnimationProcessor` 与 watch 管理器；冷编译与 disk-warm 均观察 execute 写 7→9、已编译 direct/shared watch 读取最新 Root、debug 共享写函数写入 1，后续 watch 读取当前值、`fn.actor()+1` 在 debug/execute 均为 6 且只允许端提交动作。真实 Minecraft subject 由受控初始化构造，效果提交接收器不连接游戏世界；此测试不证明 tick 排空时机、多 pass once-only、声音或网络双端。

原作品测试直接读取花嫁酒狐控制器原文，经生产 frontend 和 coded-controller 入口执行。夹具只固定主手剑挥击、stage 与动画完成事实。无可选依赖的独立 JVM cold/disk-warm 路径核对 stage 2、3、其他值的过渡、动画、声音命令顺序，以及非挥击的 reload 分支。声音命令接收器只证明参数和顺序；实际播放、继续连招和兼容 Better Combat 环境仍需 Forge 观察。

## 组件生命周期

Owner 回归需要 `YSM_NATIVE_PATH` 和 `YSM_FIRST_PERSON_FIXTURES`；缺少任一变量时，JUnit 会跳过该测试。完整夹具的生成方法见[第一人称观察工具](../tools/first_person_observation/README.md)。

```powershell
.\gradlew.bat processorTest
.\gradlew.bat test --tests '*ComponentScopeTest' --tests '*EntityComponentOwnerLifecycleTest' --tests '*EntityExecutionLifecycleTest' --tests '*EntityModelBindingTest' --tests '*DefaultDeclarationProjectionTest' --tests '*RenderTargetStateIdentityTest'
```

Owner 测试在独立 JVM 中编译并接纳第三方组件，经过真实安装和 `tickAnimation`。测试核对首次零跨度、限频累计、倒退、observation/复用、六个失败阶段、default 拒绝重试及 worker 真实终态。关闭故障覆盖 retire、限制停用、调用失败，以及主请求与 fallback 同时 pending；单项关闭失败后，其余资源仍释放，原异常与 suppressed 保留。兼容纹理 A→B→缓存 A 核对组件、Position 历史、播放时钟和队列延续，以及几何/controller 关联刷新；holder 测试另查成功 begin 前缀、全部 end 清理及原异常保留。投影测试核对第三方声明只进入普通 host，default 的 main/arm、瞬态入口及 memory/disk 制品沿用 builtin-only 身份。

资源测试使用生产 parser、prepared cache、module、assembler 和 pin，但替换图像解码、native geometry bake 与 GPU 消费阶段。实体、调度和效果接收器受控；这些回归不证明真实 Forge 的视觉、多 pass、可选模组线程安全或长期资源收敛。

## 准备复用与 Roaming

```powershell
.\gradlew.bat test --tests '*PreparedTargetCacheTest' --tests '*AnimationStoreTest' --tests '*ClientModelBootstrapTest' --tests '*SetIsolationCalibrationTest' --tests '*ModelProgramInventoryTest' --tests '*ModelProgramPreparationTest' --tests '*DefaultAnimationInputTest' --tests '*RuntimeRoamingOwnershipTest' --tests '*QualifiedStateReportTest' --tests '*ExpressionBindingTest'
.\gradlew.bat verifyBuiltinModelIndex verifyExpressionArtifactWarmLoad verifyExpressionRuntimeBundle
```

缓存测试核对共享准备计数、独立 interest/pin、迟到候选回收、取消/队列丢弃/关闭和绑定关闭。默认初始化使用生产内容与 module，GPU consumer stage 是测试 seam；它证明 required 集合已准备和驻留引用，并不证明游戏后续路径没有磁盘访问。集合 fixture 给 body/arm 的同名动画和 Common 函数不同手写真值，核对 F32/String、默认 origin、defer 与生命周期归属。长默认 origin 的回归用完整业务位置核对定位与跨 set 区分，完整 builtin gate 同时约束 ProgramKey 的长度和分析排序。

源准备测试分别核对 main/arm 与玩家、投射物、载具的形式条目和诊断位置。内容变化、Map 顺序、函数声明顺序及默认子集共同检查输入身份。默认输入测试用不同返回常数与累加状态核对来源函数、不同模型、独立实例、默认版本变化和 cold/warm 结果。来源内函数名映射变化时，结果应来自当前组合。用途产物应各自命中，cache-only miss 不阻止后续 cold 准备。这些手写结果是语义 oracle；源摘要相等只补充解释。

主体 binding 分区测试核对同容器的独立 memory/disk 项、空分区与仅内存候选。该检查证明存储隔离；实体的真实 provider 调用仍由集成轨迹验收。

源阶段比较应覆盖相同模型/集合的完整 ProgramSource 序列，并核对 wall time、CPU 与分配。评估跨模型缓存时须计入首次构造及后续组合成本。源阶段不包含语义分析、后端、GPU 或可见内容等待，不能据此裁决完整模型与帧期不退化。

Roaming owner 测试使用真实安装/barrier/投影和受控 module；报告测试使用真实 planner 与 FrameCodec，但宿主发送为接收器。分别核对同值写仍进入本地 delta、快照不消费 dirty、body-only 字段、arm 一次 full/一次非阻塞 patch、退休拒用与发送失败策略。受控 module、consumer stage 和发送接收器均不能代替真实 Minecraft provider、正常第一人称 hook、载具与双端网络的完整轨迹。

实机验收应固定模型输入及 hash。通过正常模型选择、相机、客户端 execute/watch 和乘坐操作进入生产路径，核对 body/arm 独立状态、实际报告、载具消费与安装退休。默认初始化后应独立观察磁盘访问，实体运行与资源关闭应保留工作计数；少一次准备或一个正确值不足以证明净收益。完整准备和帧期比较遵循[持续性能基建](../tools/benchmark/README.md)的证据要求。比较应固定旧新源码、共同工作量、独立 fork 和噪声边界，包含静态计划、subject binding、双集合、保留、快照和投影成本。

## 成本测量

标量执行使用 `verifyMolangExecutionBenchmark` 先核对返回 bits、provider 次数和拒绝门禁，再通过长期 JMH source set 测量。测量分别报告同候选 boxed/primitive carrier、相同调用语义的旧新配对、同轮数量斜率与完整轮固定成本。运行方法见[标量执行基准](../tools/benchmark/README.md#molang-标量执行)。`verifyMolangPayloadAllocation` 在两个独立 JVM 中分别计量真实 generated tail、record 与组合入口，以及空轮、失败快照、particle 与 defer 载荷的轮级总分配。编译和绑定位于计量区外；effect/defer 行覆盖 stage、来源快照与 batch commit，不包含延迟回调调度。姿态行由 `EntityExpressionRuntimeTest.literalPoseSamplingReportsAllocationSeparatelyFromScalarExecution` 计量真实观察轮内的单骨骼 literal 插值。载荷报告不作为稳态标量框架分配的解释。历史动画准备样本不构成完整安装或游戏帧期不退化的结论。

长期成本测量使用[持续性能基建](../tools/benchmark/README.md)与[默认 player/main 编译基准](animation-bake-benchmark.md)。功能真值、调用轨迹、owner 隔离与 cold/disk-warm 回归继续由本页各独立入口验证。新测量应固定共同工作量、源码与制品身份，单独核对结果、时间和分配，并报告实际覆盖范围。

## Provider 契约审计

`SoundInstanceManagerTest` 使用真实生产适配器和效果封口，核对暂存后切换 animation/controller 时的声音目标及 global 选择；播放停止由 FakePlayback 观察，不验证音频设备。`GuiAnimationClosureTest` 核对 literal、有限选择和未知名称的传递闭包。`HostSemanticContractTest` 保留函数两种源形式与非法赋值覆盖；`ModelParserTest` 核对 canonical symbol 的 default 准入。

作者与接纳回归使用 `YsmEntityComponent`、成员 namespace、来源 mod 归属和统一的 `MolangContextView`。公共 API 的独立编译正反例检查闭合泛型、声明 effect 和不可见的执行控制方法；namespace 冲突与无效来源在实现类加载前拒绝。

`MolangExtensionAdmissionTest.uniformDeclarationsBindWithoutBusinessCatalogsAndPublicHintsDoNotAuthorizePurity` 独立生成一个扩展 JAR。新查询返回 7，新 ACTION 在提交后写入 `[3]`。夹具通过来源接纳、符号发现、编译和生成调用；新增能力不改通用业务目录。公共纯目标 hint 不取得内部资格。

`PureDeclarationOracleTest` 在冻结 host seam 注入独立函数，核对 `2*x+1`、subject 拒绝、provider 预算、非有限返回及异常 identity。ASM 检查实际调用为 `(F)F`，不携带 context。探针用手工 linked declaration 隔离执行语义；processor 形状检查与真实来源接纳由独立测试覆盖，探针不证明自身具备生产纯度资格。

跨进程暖加载夹具保留冷进程写出的 artifact。完整 target 的冷暖进程执行 `PreparedEffect.commit`，按手写预期核对两个 controller 效果的载荷和顺序：`first:LOOP`、`second:HOLD_ON_LAST_FRAME`。准备及求值期间轨迹为空。暖进程分别改变声明 effect、实际纯目标 binding，以及接纳后的有效依赖集合，尝试读取原 artifact，核对拒绝后重建。effect/binding 变体注入冻结 host seam；依赖变体通过真实接纳筛选。主目标暖加载仍要求 semantic、source generation 和 backend compilation 计数为零。

`PreparedDomainEffectTest` 和 `SoundInstanceManagerTest` 核对 controller/骨骼目标准备、真实声音 manager 接纳及停止。`FirstPersonOwnerIntegrationTest` 的联合回归使用真实安装的预览 owner、已编译声明、声音 manager、controller setter 和本地 `@sync`。成功轮在必要 end 后交付 `[sound, controller, sync]`，并核对音量、音高、动画名、循环模式和 sync 参数。后续表达式、begin/end、效果验证、取消和安装失效六类提交前失败均不交付效果。Hook 失败在真实 component scope 外层注入，声音 factory 与 controller 接收器记录业务交接；测试不证明设备播放或网络发送。

开发测试中的 `ProviderContractAudit` 显式观察已接纳载体、保存发布快照并复查底层修改，诊断携带 provider 方法与字段/索引路径；借用审计关联调用线程和真实 context 窗口。`ProviderContractAuditTest` 覆盖标量 float 非有限返回归一、禁止位置 null、合法 nullable、发布后修改、过期/跨线程借用和 effect 误用。固定许可与禁止同步重入另由 runtime 生命周期回归核对；审计器不维护动态权限栈。审计器仅进入 test source set，不自动插入 generated call，也不承诺识别任意恶意 provider。

## 内置模型与制品门禁

`verifyBuiltinModelIndex` 对 27 个内置模型、60 个 physical target、80 个 set、20,717 个 program 做完整 materialization，并要求 replacement 集合为空。`verifyExpressionArtifactWarmLoad` 在两个独立 JVM 中核对 cold/disk-warm 的 identity、值、effect 与 replacement，覆盖对象清空/恢复、跨函数 presence、非法非 literal 哨兵和旧 semantic ABI 拒用；`verifyExpressionRuntimeBundle` 再校验实际解析依赖、relocation、打包后 provider linkage 与依赖 hash receipt。独立缺 JAR 矩阵与真实队列/watch 的复现方法见[发布与真实缺依赖](#发布与真实缺依赖)。这些自动化不替代真实 optional provider 模组或 Forge world 的 controller、target 恢复、调试消息、声音和性能观察；剩余边界统一见[动画已知问题](../status/known-issues/animation.md)。
