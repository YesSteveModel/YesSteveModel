# 持续性能基建

`benchmark` source set 基于 JMH 1.37，复用 `main` 的依赖和 Java 17 toolchain。JMH 核心库、注解处理器与生成代码只进入 benchmark，不会打进模组发布包。`PlayerMolangCompileBenchmark` 的输入筛选、阶段计数和覆盖范围另见[动画编译基准](../../verification/animation-bake-benchmark.md)。

## 运行与结果

运行前需配置与被测 Java 源码配套的 native 库绝对路径。模型解析、native bake 和资源准备都在 Trial setup 阶段完成，计时区内运行真实的 `NativeModelState` 与 `NativeRenderer` JNI 调用。运行依赖包括 Minecraft 与 Forge 的开发 classpath、项目 Maven 运行时及本机 native 库，无需启动完整游戏客户端。

```powershell
$env:YSM_NATIVE_PATH='C:/absolute/path/to/ysm.dll'
.\gradlew.bat benchmarkClasses
.\gradlew.bat benchmark '-PbenchmarkInclude=.*NativeRenderBenchmark.repeatedDraw' '-PbenchmarkArgs=-p model=body-4 -p path=direct -f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/repeated.json'
```

常用参数说明：

- `benchmarkInclude`：匹配 JMH 类或方法的正则表达式。
- `benchmarkArgs`：按空白拆分并整体替换默认参数；如果参数值含空格，改用 `benchmarkArgsFile` 指向一个 JSON 字符串数组文件（优先级高于 `benchmarkArgs`）。默认参数为 1 个独立 fork、3×1 秒预热、5×1 秒测量并开启 GC profiler，结果默认写入 `build/reports/benchmark/result.json`。
- `benchmarkResult`：接受绝对路径或项目相对路径，固定保存完整 JMH JSON。传入 `benchmarkArgs=-l` 可列出所有已生成的 benchmark。

修改发布或渲染返回语义后，运行 `./gradlew.bat verifyBenchmarkGuard`。该任务通过真实 JNI 注入非有限姿态和 native-only 失效来校验 draw guard：调用前固定期望提交数，要求返回成功且有效、提交数精确匹配；合法空结果允许通过，无效或失败结果会使验证报错。负向注入只在验证任务中执行，不进入 JMH 计时。

需要跨多个版本交错对照时，先运行 `prepareBenchmark` 生成 `build/benchmark/launch.json`，其中记录了实际 JDK、完整 fork classpath 与 native 库路径。直接用 manifest 里的 `java`、`main` 和 classpath 启动独立进程，保持相同的环境变量与 JMH 参数；不要在同一个 fork 内拆分迭代来冒充独立对照。

## 工作量维护

### 玩家状态网络

`RoamingNetworkBenchmark` 分层测量真实生成导出、宿主映射与累积 apply、Proto 编解码以及串联 frame；字典和安装关系以及网络诊断单独列出。历史 adapter、独立命名 oracle、两版独立 fork 以及参数化 fanout 的复现方式见[玩家状态网络验证](../../verification/player-state-network.md#成本测量)。短预热样本仍受 JIT 编译与校准噪声影响，不能直接外推为游戏内收益。

### Direct 容器目录读取

`DirectContainerReadSample` 记录真实 `DirectContainerIndex` 在首次解析与同进程重载时的耗时、源文件读取范围和文件句柄关闭状态。输入必须是完整有效的 `.mxc` 文件；`prepare` 子命令会为独立副本嵌入有效 preview，并完整校验源文件和输出文件。

```powershell
.\gradlew.bat prepareBenchmark readHandleAgent
$directLaunch = Get-Content build/benchmark/launch.json | ConvertFrom-Json
$env:YSM_NATIVE_PATH = $directLaunch.nativeLibrary
& $directLaunch.java -cp ($directLaunch.classpath -join ';') com.elfmcys.ysm.model.storage.DirectContainerReadSample prepare <source.mxc> <prepared.mxc>
& $directLaunch.java '-javaagent:build/benchmark/read-handle-agent.jar' -cp ($directLaunch.classpath -join ';') com.elfmcys.ysm.model.storage.DirectContainerReadSample cold <prepared.mxc> <new-cache-directory> <cold.json>
& $directLaunch.java '-javaagent:build/benchmark/read-handle-agent.jar' -cp ($directLaunch.classpath -join ';') com.elfmcys.ysm.model.storage.DirectContainerReadSample restart <prepared.mxc> <same-cache-directory> <restart.json>
```

`cold` 和 `restart` 只是输出标签，实际冷热状态取决于是否使用独立 JVM 与全新缓存目录；输出会同时记录首轮与重载的源读取情况。`ReadHandleProbe` 只统计 Java 层打开的文件句柄及读取偏移，不覆盖 SQLite 的 native I/O，也不等同于物理磁盘读取。由于 agent 本身会影响时延，本入口不能用来计算通用加速比。

### 受检路径访问

`CacheDirectoryAccessBenchmark` 测量不同目录深度下的受检文件读取耗时：`observeAndRead` 包含首次建立目录观察，`reopenAndRead` 则复用已有观察并重新复核目录。两者都会打开真实文件并读取 1 个字节，只反映路径访问开销，不代表完整模型加载延迟。

### Molang 标量执行

`MolangExecutionBenchmark` 在 Trial setup 阶段完成绑定与编译，普通表达式复用同一个内部上下文和预算存储。`primitive`、`boxed` 和 `internalFunction` 使用相同的四元素序列，由 `distribution=finite|missing|mixed` 控制有值、缺值或混合输入：boxed provider 对有限值返回 `Float`、对非有限值返回 `null`，primitive provider 直接返回原始 `float`。基准不假定 JIT 必然保留装箱分配。

其余工作量含义如下：

- `arithmetic`：纯算术，不调用 provider。
- `deniedAction` 与 `deniedSubject`：验证被拒绝的入口及其实参都不会被求值。
- `ordinaryIdentityCall`：走普通声明调用，以有限编码输入核对 nullable 返回值的存在性。
- `transientPrimitive`：复用准备阶段建好的 layout、program lease、access 和 subject binding。
- `consecutiveExpressions`：在同一轮连续执行 `expressions=1|16|256` 条表达式；输出结果是整组耗时，分析时需按表达式条数换算。
- `completeObservation`：包含单轮载体和预算容量的创建与释放；`completeLogicalRound` 额外包含空效果批次的创建与封口。

主矩阵不包含 varargs、效果载荷、失败快照和姿态采样；独立载荷探针见[成本测量](../../verification/molang-declarations.md#成本测量)。

计时前应先运行 `verifyMolangExecutionBenchmark`。该任务会逐位比对手写真值、provider 调用次数、拒绝门禁和 reset 行为，并将生成的字节码输出到 `build/verification/molang-execution-bytecode`。真值校验通过只说明夹具可用于测量，不代表分配目标或性能结论。

```powershell
.\gradlew.bat verifyMolangExecutionBenchmark prepareBenchmark
.\gradlew.bat benchmark '-PbenchmarkInclude=.*MolangExecutionBenchmark.*' '-PbenchmarkArgs=-p distribution=mixed -p expressions=16 -f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/molang-execution.json'
```

`src/verification/benchmark/molang_execution_campaign.py` 消费 `prepareBenchmark` 生成的启动清单和已通过真值校验的历史适配清单。脚本先跑短 pilot 和旧版自对照校准，再交错启动两个版本的独立 fork；默认每个主样本采用 2×1 秒预热、3×1 秒测量，总采样预算为 1200 秒。脚本会保存启动清单、classpath hash、逐样本参数 JSON、日志与原始 JMH JSON，并在退出时校验矩阵完整性。

```powershell
python src/verification/benchmark/molang_execution_campaign.py --legacy-launch <legacy-launch.json> --output <new-output-directory>
```

做配对成本分析时必须使用相同的输入、JDK、fork 参数和原始 JSON，旧实现的适配夹具也必须保留原有的权限和预算检查。单条表达式成本、同轮条数斜率与完整单轮成本应分开报告，不能仅凭源码里的对象数量推算净收益。

### Molang 收敛矩阵

*（注：原 `molang_convergence_campaign.py` 与 `PlayerMolangExecutionBenchmark` / `MolangBenchmarkHost` 因耦合已退役的 `YSM_FIRST_PERSON_FIXTURES` 伪宿主夹具，已随验证收敛移除；当前仍保留 `MolangConvergenceCompileBenchmark`、`MolangBoundaryBenchmark`、`MolangRoamingBenchmark` 与 `MolangSemanticBenchmark`。）*

`MolangConvergenceCompileBenchmark` 枚举完整的 default target 与 set（包含生产空集合哨兵）；外部模型只测 FULL 的 `MAIN` 与 `FP_ARM`，旧的 GUI AOT 分支已经退役。Trial setup 读取已转换输入，计时从 source 和 inventory 准备开始，到真实的 Artifact V1 编码完成为止（调用生产 `compileCold`，不含异步持久化或磁盘 I/O）。独立的 `main <input> <divZeroFallback> <outputDirectory>` 可导出逐单元 class、完整 artifact、工作量以及冷热加载观测数据。

`MolangBoundaryBenchmark` 覆盖固定主体与 child 交替门禁、nullable record 以及 Managed 持久写后读，运行前先核对参数次数、结果 bits、导出快照与实例隔离。`MolangRoamingBenchmark` 覆盖 16、64、256 字段以及 1 次或 16 次读配 1 次写，在同一行等量交替执行 Producer 与 Consumer，迭代结束后核对布局、数值读回与真实 delta。两个类的 `main` 方法只用于执行手写真值核对，不产出性能结论。

### 实体动画所有权

原 `EntityOwnershipBenchmark`、`EntityOwnershipMemoryProbe`、`entityOwnershipProbeAgent` 与 `entity_ownership_campaign.py` 因直接继承已退役的 `FirstPersonOwnerIntegrationTest` 伪宿主夹具，现已随验证重构一并移除；相关生命周期、并发抢占与缓存淘汰回归已转由纯单测 `EntityExecutionLifecycleTest`、`EntityQueuedExecutionTest` 与 `EntityModelBindingTest` 覆盖，历史基准边界记录留存于[实体所有权验证](../../verification/entity-animation-ownership.md#成本与引用)。

### JNI entry 开销

`JniEntryBenchmark` 对比裸 JNI 与 `YSM_JNI_ENTRY` 在正常返回路径上的开销：`rawVoid` 对照 `entryVoid` 测空调用，`rawIdentity` 对照 `entryIdentity` 测整数原样返回，分别覆盖 `Status` 和 `StatusOr<jint>` 两种包装。每次操作发起一次 JNI 调用并由 JMH 消费整数结果；Trial setup 负责加载库和校验入口，计时区不含加载、注册或输入校验。

native benchmark 库链接现有的 `ysm-java`，只额外注册测量入口——裸函数通过 `RegisterNatives` 绑定，宏入口通过现有的 `BindEntry` 绑定，生产动态库不包含这些入口。构建时先在 native 项目按构建指南配置 Release 并开启 `YSM_BUILD_BENCHMARKS=ON`，再构建 `ysm-jni-benchmark` 目标。

```powershell
$env:YSM_JNI_BENCHMARK_PATH='C:/absolute/path/to/ysm-jni-benchmark.dll'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*JniEntryBenchmark.*' '-PbenchmarkArgs=-f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/jni-entry.json'
```

`YSM_JNI_BENCHMARK_PATH` 指向当前平台的 benchmark 动态库；Gradle 入口仍要求配置 `YSM_NATIVE_PATH` 或本地 native 路径。分析时计算同一返回类型的 `entry - raw` 差值（单位 `ns/op`），同时检查各 fork 的漂移与置信区间。结果只覆盖当前编译配置下的日志 scope、status 解包与异常边界，不覆盖失败路径、参数转换或实际业务函数体；若需评估 Debug 日志开销，应使用对应的 native 构建单独采样。

### 内存分配与释放

`MemoryAllocationBenchmark` 测量单线程内一次分配配对一次释放的开销：

- `mallocFree` 与 `mimallocFree`：通过两次裸 JNI 调用执行系统 `malloc`/`free` 与项目内 `mi_malloc`/`mi_free`。
- `directBufferClean`：使用 `ByteBuffer.allocateDirect` 与 `Unsafe.invokeCleaner`。
- `lwjglAllocFree`：使用 `MemoryUtil.memAlloc` 与 `memFree`。

`bytes` 参数默认取 64 B、4 KiB、1 MiB 和 128 MiB。JMH 消费已释放地址的数值，计时区内不访问已释放内存。注意 `allocateDirect` 包含内存清零、direct-memory 记账和 Cleaner 注册，其余入口不保证清零；同时计时区不主动读写分配出的 payload，原生分配器可能仅保留虚拟地址，因此结果不代表写满整块物理内存的成本。LWJGL 实际使用的后端以各 fork 日志打印的 allocator 类型为准。

`MixedMemoryAllocationBenchmark` 让四种分配器重放同一固定 seed 生成的操作序列：每轮执行 1024 对分配与释放，报告单位为 `ns/对`（包含序列遍历和存活槽管理开销）。四档大小的抽样权重依次为 60%、30%、9%、1%，最多同时存活 64 块、总计不超过 256 MiB，序列中混合不同存活期和释放顺序，并在末尾释放全部剩余存活块。序列生成与内容校验都在计时区之外。默认 seed 为 `42` 和 `20261019`，fork 日志会输出 trace hash；独立校验入口会按多个 seed 重放四种分配器，并在每次释放前检查内存块的首尾字节。

`jniBenchmarkJar` 会打出一个独立 JAR，内置 JNI entry 与内存 benchmark、JMH 以及 LWJGL 3.3.1 的 Windows 与 Linux x64 native 依赖。拿到该 JAR 和对应平台的 `ysm-jni-benchmark` 动态库即可直接运行，无需复制整个 Gradle 工程。

```powershell
.\gradlew.bat jniBenchmarkJar
$env:YSM_JNI_BENCHMARK_PATH='C:/absolute/path/to/ysm-jni-benchmark.dll'
java -cp build/benchmark/ysm-jni-benchmarks.jar com.elfmcys.ysm.benchmark.MixedMemoryAllocationBenchmark 32
java -jar build/benchmark/ysm-jni-benchmarks.jar '.*MemoryAllocationBenchmark.*' -f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc -foe true -rf json -rff build/reports/benchmark/allocation.json
```

在 Linux 上执行 `export YSM_JNI_BENCHMARK_PATH=/absolute/path/to/libysm-jni-benchmark.so`，再用对应平台的 JRE 运行同一个 JAR 即可。默认的分配规模和混合序列不模拟多线程竞争、跨线程释放或长期运行碎片；JMH GC 分配量也只统计 Java 堆，不统计 native 内存分配。

### 渲染与提取

`NativeRenderBenchmark` 用 `model=body-4|body-96` 切换小工作集与触发并行路径的大工作集，用 `path=direct|fallback` 切换真实 native 直接输出与宿主回放路径。Headless 接缝只负责初始化 native、建立声明属性并接收输出，不启动 Minecraft 客户端，也不替换 native 实现。direct 接收器复用固定的 native buffer，fallback 接收器消费实际顶点，两条路径都会校验最终提交的顶点数。

| 方法 | 单位操作 |
|---|---|
| `extract` | 按 8 样本循环姿态输入执行一次整模 JNI 提取 |
| `extractAndDraw` | 使用同一姿态序列执行一次提取加输出 |
| `repeatedDraw` | 对同一份完整发布连续消费两次，不重新提取 |
| `changingVisibility` | 使用同一姿态序列并交替切换奇偶可见骨骼集合，执行一次提取加输出 |
| `emptyDraw` | 对预先提取好的合法空结果连续消费两次 |

`NativePartBenchmark` 单独测量三个 subtree 输出场景：同槽重复、L/R/B/L/R 交替、下一姿态保持可见集合以及可见集合变化。它不调度完整的第一人称 owner，也不与历史版本中并不存在的 subtree 能力计算加速比。

修改生产渲染接口时应同步更新对应 fixture；新增工作量要在计时区外准备好资源，并写明操作定义、输入序列、模型规模、可见集合与输出路径。相关测试资源放在 source set 内，不依赖临时任务包。声明几何来自共享的[第一人称输入生成器](../first_person_observation/README.md)，小工作集取其主体模型的前 4 个声明骨骼。

## GUI 与 FULL 需求准备

`DemandPreparationBenchmark` 使用生产 `ClientDemandCoordinator`、容量为零的 unused target cache、真实 loader、表达式后端、纹理解码和 native bake。固定输入为内置默认模型的 `blue` 与 `default` 纹理，配备 2 个固定资源 worker。GUI 接收方消费主体，FULL 接收方消费主体和手臂的真实 native extract 输出。输入解析在 Trial setup 完成，资源和协调 owner 在每次 Invocation 重新创建。

普通 JVM 环境没有 Forge Mixin，因此 `nativeImageBenchmarkAgent` 只向 `NativeImage` 注入生产 accessor 所需的两个字段 getter 并校验字段类型，且该 agent 仅进入 benchmark fork。图片仍走生产 `Image.decode`，不用替代解码器计量。GPU 发布与实体安装采用 headless 接缝，因此结果不覆盖 GPU、真实 binding、界面绘制、远端传输或完整客户端启动。

| 参数 | 含义 |
|---|---|
| `scenario=cold` | 模型内容已驻留，每次清空表达式派生物且 FULL Ready 为空；不代表进程冷启动或网络冷内容 |
| `scenario=derivedHot` | 保留上一轮表达式派生物，使用新 loader 和空 Ready cache；默认几何仍走常驻 bake |
| `scenario=ready` | 在计时区外提前保有 FULL Ready lease；计时区只执行选择、获取独立 lease 并消费 |
| `scenario=handoff` | hover 完成 GUI 准备后再选择同一目标，测量同目标 GUI 与 FULL 各自独立准备及接收的开销 |
| `scenario=concurrent` | 同时选择 `blue` 的 FULL 并 hover `default` 的 GUI，等待两项目标全部完成；表达式准备仅在用途与 binding 完全匹配时共享 |
| `unrelatedCatalog=0,10000` | 固定页面与实体，向目录加入无关查找项；不计量界面过滤和真实 Catalog 构造 |
| `viewReads=0,1,8` | 每次 tick 后读取反馈视图的次数；退出时停止读取并校验 lease 与 cache 全部归零 |

运行前先执行 `./gradlew.bat benchmarkClasses nativeImageBenchmarkAgent`。通过 `benchmarkArgsFile` 传入 `-jvmArgsAppend` 与 `-Xmx3G -javaagent:<agent absolute path>`（agent 位于 `build/benchmark/native-image-access-agent.jar`），再通过前述 `benchmark` 任务运行 `benchmarkInclude=.*DemandPreparationBenchmark.prepare`（单 fork、2 次预热、3 次测量）。如果 agent 路径含空格，按 JMH 的 JVM 参数语法引用。

输出标记含义：`DEMAND_INPUT` 记录内容身份，`DEMAND_SAMPLE` 记录实际 loader 启动、发布、接收时刻、tick 峰值和采样 lease 峰值，`DEMAND_RETIRED` 记录退出后的清理归属，`DEMAND_METRICS` 记录本轮 owner 的表达式阶段计数（Ready 场景包含计时区外的 priming）。注意 JMH 的 wall time 包含 1 ms 轮询间隔，且 `Level.Invocation` 的 setup 会污染 GC profiler 的每操作分配量，不能把它当作计时区内的分配。资源计数也不等同于 native 字节占用、GPU 显存或进程内存峰值。

## FULL 动画批次磁盘读取

`FullAnimationReadProbe` 用于验证真实 loader 在 FULL 模式下的磁盘读取行为：每个模型先在独立进程中完成持久化，再启动新进程执行暖加载，要求走真实 `DISK` 命中、零编译、非空动画读取、loader 返回时全部文件句柄已关闭，且同一动画批次内的每个文件只打开一次。默认输入为 Winefox 01 和 Alex，可通过 `--model` 指定单个内置模型路径。

```powershell
.\gradlew.bat prepareBenchmark nativeImageBenchmarkAgent readHandleAgent
python src/verification/benchmark/full_animation_read_campaign.py --output <new-absolute-directory> --budget-seconds 1200
```

每次运行都会保存输入 hash、编译次数、JFR 实际读取、物理句柄记录、cache root 和启动命令。旧的 GUI AOT 六组合及专用输入 agent 已经退役，当前 GUI 的独立输入、解释绑定和驻留行为由专项回归测试与页面探针验证。本入口不覆盖 GPU、界面绘制、网络或性能收益。

## 分级准入文件读取验证

`DemandDiskReadVerification` 用于验证 `ClientDemandCoordinator` → `ClientModelRenderTargetManager` → loader 与 cache 这一整条链路的工作准入行为。三种测试来源分别使用本地容器、`RemoteModelContent.openCached` 和已有几何缓存，表达式派生物也计入文件读取统计。该入口用于功能正确性验证，不是 JMH 性能采样。

```powershell
.\gradlew.bat verifyDemandDiskReads
```

任务沿用本页的 native 路径配置，自动准备 JDK、classpath 和 `nativeImageBenchmarkAgent`。默认输出目录为 `build/reports/demand-disk-reads`，可用 `-PdemandDiskReadOutput=<dir>` 覆盖。

每轮验证会在输出目录下新建独立的 `run-*` 子目录，存放 fixture、缓存和 12 份 JFR 录制，避免复用上一轮缓存。程序启动时会先删除旧的 `summary.json`，只有当全部检查通过且资源全部关闭后，才写出本轮汇总；汇总中记录 `runDirectory`，并按文件路径与调用栈统计读取次数和字节数。

三种来源都在受控单调时钟下执行以下四个阶段，文件读取与 worker 完成则走真实路径：

| 阶段 | 判定标准（Oracle） |
|---|---|
| 快速翻页 | 3 个不同 `ModelId` 分别在 0、299、300 ms 推进；受测根目录内的读取次数必须为 0 |
| 稳定到 301 ms | 等待进入真实 Ready：目标来源的读取次数与字节数均大于 0，且调用栈包含生产读取入口；bake 来源要求 `.geo.ysm-cache` 经过真实的 `BakedModelCache.read` 消费 |
| Ready 后重复推进 | 连续推进 100 tick，不再读取受测文件 |
| 本地准入前复用 Ready | 保持独立 Ready anchor，换入新的快速翻页页面；应立即获得独立使用权且读取次数为 0 |

三个“零读取”阶段还会同时校验取得入口的调用次数和 Manager 在途任务数均为 0，防止后台读取只是排队延后、恰好落在录制窗口外而造成假阴性。

JFR 以零阈值采集 `jdk.FileRead` 事件及调用栈，汇总时只统计本地、remote、geometry 和 expression 这四个受测根目录。注意 Java 文件读取事件不等同于物理磁盘 I/O；此外，必需资源的初始化位于录制窗口之外，目录快照由 fixture 注入，纹理发布使用 headless 接缝，因此本验证不覆盖目录发现与准入、网络传输、GPU、真实帧耗时或性能收益。

## 本地玩家资源优先调度

`ResourcePriorityBenchmark` 复用真实 loader、常驻内置默认模型、两种纹理以及 FULL 主体与手臂的 native extract。三种场景分别为：`saturated`（后台计算积压后再发起本地请求）、`sameFlight`（提升同一 Flight 的优先级）和 `sharedPreparation`（提升两个纹理 Flight 共享的 producer 的优先级）。后台负载采用固定数量的 CPU 计算，不模拟真实世界中的任务分布。

三种对照配置都把并发计算 worker 上限固定为 2：

- `single_fifo_control`：所有任务进入两个后台 worker 共享的单个 FIFO 队列，另保留一条始终空闲的专用线程，作为当前代码的队列基线对照。
- `split_equal`：拆成 1 条专用队列和 1 条后台队列，两条线程均采用 `BELOW_NORMAL` 操作系统调度策略。
- `split_normal`：专用线程保留 `NORMAL` 策略，后台线程采用 `BELOW_NORMAL`。

这组对照不包含历史实现，不能据此宣称整体重构的性能收益。

```powershell
.\gradlew.bat benchmarkClasses prepareBenchmark nativeImageBenchmarkAgent
python src/verification/benchmark/resource_priority_campaign.py --iterations 8 --budget-seconds 480
```

脚本按场景交错执行各配置，每个组合使用独立 JMH fork 和热派生物，并在总时间预算内保存元数据、原始日志、JMH JSON、逐样本 JSONL 和分位数，默认输出至 `build/reports/benchmark/resource-priority`。agent 的 `priority` 参数仅在测量 fork 内记录真实的 producer、consumer build 和 native bake 次数及执行线程，用来确认共享准备没有重复执行，且并发计算峰值不超过 2。

时间分段统计四个区间：请求到进入 producer、所有 producer 与 consumer 的累计计算耗时、candidate 完成到首次观察到 Ready、以及请求到收到 native extract。注意进入 producer 晚于原子领取，Ready 观察包含 fixture 的轮询间隔，累计计算也不等于关键路径耗时；JMH 主 score 覆盖全部后台工作与资源 Flight 的总耗时，查看本地玩家的实际接收延迟应读取分段 JSON，同时另行核对固定后台工作的吞吐量。小样本的 P95 与 P99 仅作描述性参考，不具备尾延迟统计置信度。该入口不覆盖网络、GPU、真实实体安装或游戏帧时间。

## Catalog 重载

`CatalogReloadBenchmark.freshMetadata` 与 `reusedMetadata` 对同一个内置默认模型容器执行完整文件校验：前者重新解析元数据，后者传入旧内容并要求复用已解析视图，两者在结束后都会关闭本次独立容器表示。模型转换放在 Trial setup 中，计时区覆盖文件 I/O、完整 chunk 校验、前缀比较或元数据解析以及释放。

```powershell
.\gradlew.bat benchmark '-PbenchmarkInclude=.*CatalogReloadBenchmark.*Metadata' '-PbenchmarkArgs=-f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/catalog-reload.json'
```

分阶段观测使用真实的 `ReloadableModelCatalog`：先停止 owner tick，等后台协调线程退出，再用一次 tick 接纳最终结果。测试输入为指定数量的独立合法容器（从内置默认模型派生，修改 `ModelId` 并嵌入 preview），依次观测初次扫描和无变化目录重载，并要求重载阶段的 candidate 构建次数和元数据解析次数均为 0。

```powershell
.\gradlew.bat prepareBenchmark catalogReloadTimingAgent
$catalogLaunch = Get-Content build/benchmark/launch.json | ConvertFrom-Json
$env:YSM_NATIVE_PATH = $catalogLaunch.nativeLibrary
$catalogAgent = (Resolve-Path build/benchmark/catalog-reload-timing-agent.jar).Path
& $catalogLaunch.java "-javaagent:$catalogAgent" -cp ($catalogLaunch.classpath -join [IO.Path]::PathSeparator) com.elfmcys.ysm.model.catalog.CatalogReloadBenchmark 128
```

`CATALOG_PHASE` 分别输出完整 candidate 构建、含 observer 的发布、observer 自身以及元数据解析的调用次数、耗时和当前线程堆分配量；`tickNs` 另包含终态接纳与 future 完成耗时。agent 只进入观测进程，不改动生产代码，但插桩计数与线程分配查询会引入额外开销。小样本仅用于核实工作归属和调用次数，不能用来外推大规模目录曲线；observer 只检查目录完整性，不覆盖服务端逐玩家同步。JMH 分配量和阶段分配量均不含 native 内存，结果也不代表客户端帧率。

## 音频 PCM 与 checkpoint

`AudioPrefixBenchmark.consume` 使用常驻 Opus 媒体、真实 JNI 和生产播放流。Trial setup 先连续解码产出真值 oracle，再通过一次冷播放发布实际分段缓存；计时区覆盖缓存消费、冷热准备、首次缺口、剩余尾部、循环播放以及冷热完整单轮。阶段定义、参数、正确性验证入口及证据边界见[音频缓存验证](../../verification/audio-prefix-cache.md)。

主 score 和 GC 分配统计覆盖激活、阶段前置消费与清理全过程。`stageWallNs`、`stageCpuNs` 等 `EVENTS` 计数器是迭代累计值，分析时必须除以 `operations` 归一化；不要把主 score 当作单阶段准备延迟，也不要把整次操作的 create 与 restore 次数误当成某阶段内的次数。

## 帧路径契约成本

原 `FrameContractBenchmark`、`frameContractProbeAgent` 与 `frame_contract_campaign.py` 因直接依赖已退役的 `FirstPersonOwnerIntegrationTest` 伪宿主夹具，已随验证重构一并移除。相关资格缓存与队列交接不变量改由纯 L1 单测 `EntityModelBindingTest`、`EntityRequestRelationTest` 与 `EntityQueuedExecutionTest` 持续守卫。

## 证据半径

### 模型列表真实宿主

`src/verification/page-host` 在隔离的 Forge 世界中自动驱动 `PlayerModelScreen` 的翻页按钮。基线和候选两份源码共用同一套验证宿主和 ASM agent，生产源码不加入诊断代码。启动前需固定基线与候选源码、Java 依赖、Forge mappings、native 库、模型文件及客户端配置——默认配置采用 `LOCAL` 会话模式、30 个 target 保留名额并关闭免责声明，脚本固定搜索 `wine`，要求匹配 22 个模型、共 3 页。

```powershell
python src/verification/page-host/run_host.py --root <source> --output <new-output-directory> --script <frozen-script.json> --config <client-config-file>
python src/verification/page-host/batch_campaign.py <frozen-plan.json> --output <new-campaign-directory>
python src/verification/page-host/batch_analyze.py <campaign-directory>/manifest.json --output <analysis-file> --jfr <jdk-jfr-executable>
```

JSON 脚本逐项指定 `scene`、`page`、`eager` 和 `dwell_ms`，参与统计的项另指定 `window_ms`：统计窗口从输入触发开始计算，跨过窗口终点的那一帧完整间隔仍予保留。快翻窗口允许重叠；同一进程内的脚本块按进程归组，单个帧或脚本块不能当成独立重复样本。涉及重复基线、AB/BA 顺序、独立进程对数、预热、噪声估计和预算的实验计划必须在采样前冻结，不得根据候选结果事后调整窗口或放宽容忍带。同一进程内切换 `eager` 不会自动重置为冷态。

脚本还支持用 `models` 指定目录中唯一匹配的完整展示路径，并用 `copies` 复制条目，以便观察共享同一模型输入下的多个独立卡片实体；这只改变验证宿主的列表输入，资源获取、权限、安装与绘制仍然走完整生产路径（未指定时沿用 `wine` 分页场景）。`YSM_PAGE_SEED_CACHE` 可将既有独立运行产生的缓存复制到新的 `gameDir`，用于磁盘命中与新进程绑定对照；使用时必须固定缓存来源及其摘要。

宿主在脚本开始前记录帧时钟，分析器会核对每个窗口的起点和终点是否都有帧覆盖。软截止时间会在外部硬截止前请求主线程刷出已采集的帧数据、visit 记录和 JFR；但如果主线程已经卡死，仍可能触发硬终止并丢失内存中的记录。未跑完的部分运行不能拿来补齐独立进程对。

探针会分别记录每张卡片的首次 Matching draw 以及整页全部卡片首次完成的时间，但画面是否真实可识别仍需靠 framebuffer 截图确认。每次页面访问还会记录存活 Java 线程的分配字节差值——已退出的线程可能漏计，且该值覆盖整个访问区间，不能当作稳态单帧分配量或 native 内存占用。输入准备和首次解释程序准备的宿主区间分开记录，但确认完整边界仍需结合调用计数判断。

采集产物说明：

- `frames.csv`：记录完整帧间隔、render wall time 与主线程 CPU 时间。
- `visits.json`：记录输入时钟锚点、边界计数、注册记录与卡片状态。
- ASM agent 事件：记录真实的 chunk 读取、解码、纹理构造、load、upload、register 与 release、GL delete、卡片 run 与 join、GUI 动画以及 provider hook 的进入和正常返回。
- `operations.jfr`：保留逐窗口的 I/O、park 与 monitor、GC、执行样本和任务区间。注意 join 区间只是主线程等待时间的上界，`render wall time - CPU` 不能直接归因为等待，后台线程样本也不能单独证明调度竞争的因果关系。

JFR 分析器基于 JDK `RecordingFile` 做流式汇总，不展开完整 JSON 常量池；Java 分析进程堆上限设为 512 MiB，输出结果按窗口和线程分组，保留事件类型计数与调用栈。

常用运行开关：

- `--detail`：额外保存细粒度事件、首次 Matching draw 标记和窗口末截图，不纳入性能对照。Matching draw 标记只说明当前安装进入了绘制流程，不自动证明画面可识别。
- `--functional`：在性能脚本之前独立检查内置四张图的真实 GPU 像素共享、关闭、保留期、物理删除以及资源重载。该检查直接读取已注册纹理，不用于耗时比较，也不覆盖独立源解码 oracle、远端模型或同路径替换。
- `--provider`：`--provider immersive-melodies` 仅启用单一 provider，`--provider all` 启用全部 8 个 provider，`available` 仅排除 Tacz；实际版本与启用状态以运行时模组清单为准。依赖失败、默认空 hook 与实际 hook 执行会分别统计，正常返回不代表串行与并行状态等价。

给出完整性能判定仍需齐备来源、资格、退出和变化输入矩阵、独立基线波动带以及首个可识别 3D 画面证据。工具不会自动签发“整体性能通过”，遇到缺失矩阵、超时或部分运行一律保持为 `inconclusive`。

旧固定脚本的启动与统计组合已经退役。当前探针直接读取统一的 `AnimationStore` 接口；跨重构对照与历史数据复算应使用对应运行冻结下来的 harness。

模型加载交接的正确性验证与性能采样彼此独立：

- `verifyGeometryRead`：通过 JUnit 验证普通模式与 cache-only 模式下的单次 payload 读取、完整性校验、native 解码以及独立持久化。
- `verifyPreviewHandoff`：验证完整媒体解码、encoded 交接、最终像素以及自动默认 body 的构造范围。两者均使用生产 codec 与 native 库搭配 headless 发布接缝，结果输出到 Gradle 测试报告中，不覆盖真实 GPU、实体安装或主循环成本。
- `ClientPreviewGeneratorTest`：使用小图验证像素保留期间的继续解码、上传借用与取消。
- `DerivedStorageWorkTest`：验证 16 项饱和排队、单 worker、取消和提交行为。
- 声音共享、首块失败与独立播放回归测试随普通 `test` 任务执行。

共享引用、owner 关闭以及活动借用结束后的底层物理内存释放由 `PreviewPixelsTest` 验证，且串行重载与已保留图片互不等待。按本页配置好 native 路径后，运行 `./gradlew.bat test --tests '*ClientPreviewGeneratorTest' --tests '*PreviewPixelsTest'`；这组测试不测量显卡驱动或进程 RSS。

对比任何基准结果前，必须完整归档源码、native 库与依赖的 hash、硬件及电源状态、JDK/JVM/JMH 版本、worker 与 ISA/toolchain 配置、模型 hash、参数、运行顺序以及全部原始输出，然后对比单位操作耗时与 `gc.alloc.rate.norm`。跨版本对照必须使用配套的历史 Java 与 native 库；如果历史版本缺少 harness，只补充等价的测量接线。先重复跑基线以校准每项指标的自身噪声，再做独立交错对照，分配指标也按实际计数精度校准；一旦出现结果分歧、明显漂移或时间预算不足，结论一律保留为未验证。

总体证据半径约束：native 渲染基准只证明被测的提取与输出工作量；实体 owner、processor、controller 与异步交接成本使用本页的实体专用基准衡量。以上所有微基准都不能替代或证明真实客户端 FPS、GPU 与光影表现、完整用户视觉效果或任意模型规模下的表现；资源释放与 schedule 次数需单独观测，JMH GC 分配量也不包含 native 持久内存。
