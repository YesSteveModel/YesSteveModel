# 持续性能基建

`benchmark` source set 使用 JMH 1.37，复用 main 依赖和 Java 17 toolchain。JMH core、annotation processor 和生成代码只进入 benchmark，不进入 mod 包。`PlayerMolangCompileBenchmark` 的输入筛选、阶段计数与覆盖范围见[动画编译基准](../../verification/animation-bake-benchmark.md)。

## 运行与结果

在项目目录配置绝对 native 库路径，与所测 Java 源码配对。模型解析、native bake 和资源创建均在 Trial setup；计时区运行真实 NativeModelState 与 NativeRenderer JNI。依赖包括 Minecraft/Forge 开发 classpath、项目 Maven 运行时及本机 native 库，不需要启动完整客户端。

```powershell
$env:YSM_NATIVE_PATH='C:/absolute/path/to/ysm.dll'
.\gradlew.bat benchmarkClasses
.\gradlew.bat benchmark '-PbenchmarkInclude=.*NativeRenderBenchmark.repeatedDraw' '-PbenchmarkArgs=-p model=body-4 -p path=direct -f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/repeated.json'
```

`benchmarkInclude` 是 JMH 类/方法正则；`benchmarkArgs` 按空白分隔，完整替换默认参数。参数值含空格时使用 `benchmarkArgsFile` 指向字符串 JSON 数组文件，它优先于 `benchmarkArgs`。默认参数为一个独立 fork、3×1 秒预热、5×1 秒测量与 GC profiler，结果默认 `build/reports/benchmark/result.json`。`benchmarkResult` 接受绝对路径或项目相对路径，入口固定保存完整 JMH JSON。用 `benchmarkArgs=-l` 查询生成后的 benchmark 列表。

修改 publication 或 render 返回语义后运行 `./gradlew.bat verifyBenchmarkGuard`。该入口通过真实 JNI 非有限 pose 和 native-only 失效验证 draw guard：调用前固定期望数量，要求成功/valid 且提交数匹配；合法空结果可以成功，无效或失败结果使运行失败。负向注入只在验证入口执行，不进入 JMH 计时。

需要交错多源对照时，`prepareBenchmark` 输出 `build/benchmark/launch.json`，包含实际 JDK、完整 fork classpath 与 native 路径。调用该 manifest 的 `java`、`main` 和 classpath，保持相同环境变量、JMH 参数与独立进程；不要在同一个 fork 内拆迭代冒充独立对照。

## 工作量维护

### 受检路径访问

`CacheDirectoryAccessBenchmark` 测量不同目录深度下的受检文件读取。`observeAndRead` 包含建立观察，`reopenAndRead` 复用观察并重新复核目录。两者均打开真实文件并读取一个字节，只覆盖路径访问成本，不代表模型加载延迟。

### Molang 标量执行

`MolangExecutionBenchmark` 在 Trial setup 完成绑定和编译。普通表达式复用同一内部 context 与预算存储。`primitive`、`boxed` 和 `internalFunction` 使用相同四元素序列；`distribution=finite|missing|mixed` 选择有值、缺值或混合输入。Boxed provider 对有限值返回 `Float`，对非有限值返回 null；primitive provider 返回原始 float。基准不假定 JIT 一定保留装箱分配。

`arithmetic` 没有 provider。`deniedAction` 和 `deniedSubject` 验证拒绝入口与其实参均不执行。`contextFreeCall` 调用真实优化 target，以有限编码输入核对非有限返回的 presence；`transientPrimitive` 复用准备期的 layout、program lease、access 和 subject binding。`consecutiveExpressions` 在同一轮运行 `expressions=1|16|256` 条表达式；结果单位是整组操作，分析时按表达式数量换算。`completeObservation` 包含轮载体和预算容量的创建与释放；`completeLogicalRound` 另包含空效果 batch 的创建与封口。主矩阵不含 varargs、效果载荷、失败快照和姿态采样；独立载荷探针见[成本测量](../../verification/molang-declarations.md#成本测量)。

运行计时前，执行 `verifyMolangExecutionBenchmark`。该任务逐位核对手写真值、provider 次数、拒绝门禁与 reset，并将生成类写入 `build/verification/molang-execution-bytecode`。真值通过只证明夹具可用于测量，不代表分配目标或性能结论。

```powershell
.\gradlew.bat verifyMolangExecutionBenchmark prepareBenchmark
.\gradlew.bat benchmark '-PbenchmarkInclude=.*MolangExecutionBenchmark.*' '-PbenchmarkArgs=-p distribution=mixed -p expressions=16 -f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/molang-execution.json'
```

`src/verification/benchmark/molang_execution_campaign.py` 消费 `prepareBenchmark` 的启动清单与已通过真值的历史适配清单。脚本先运行短 pilot 和旧/旧校准，再交错两版的独立 fork；默认每个主样本使用两次一秒预热、三次一秒测量，全部采样限于 1200 秒。脚本保留启动清单、classpath hashes、逐样本参数 JSON、日志和原始 JMH JSON；退出时检查矩阵是否完整。

```powershell
python src/verification/benchmark/molang_execution_campaign.py --legacy-launch <legacy-launch.json> --output <new-output-directory>
```

配对成本分析应使用同一输入、JDK、fork 参数与原始 JSON。旧实现的适配夹具应保留原权限与预算检查。单条表达式、同轮数量斜率和完整轮成本分别报告，不能从源码对象数推定净收益。

### 实体动画所有权

`EntityOwnershipBenchmark` 复用受控 owner fixture，执行真实生命周期、processor 与 controller。`frame` 覆盖同步逻辑轮；`earlyConsumption` 另包含固定 worker 池的异步提交、单 owner 提前 get／消费和最终汇合。Trial setup 独立检查动作次数、动画和 JNI 输出。夹具、内存统计和宿主边界见[实体所有权验证](../../verification/entity-animation-ownership.md)。

```powershell
$env:YSM_FIRST_PERSON_FIXTURES='<absolute-fixture-directory>'
.\gradlew.bat prepareBenchmark entityOwnershipProbeAgent
.\gradlew.bat benchmark '-PbenchmarkInclude=.*EntityOwnershipBenchmark.frame' '-PbenchmarkArgs=-p owners=1,64 -p query=same,switch,replay,absent,dynamic -f 3 -wi 2 -i 3 -w 1s -r 1s -prof gc'
python src/verification/benchmark/entity_ownership_campaign.py --legacy-launch <legacy-launch.json> --output <new-output-directory>
```

历史源码使用相同 benchmark 和原有 fixture 入口；适配只补测量接线，不改生产锁、播放短路或失败门禁。Campaign 在计时前分别执行真值，随后先跑旧／旧校准，再固定判据并交错独立 fork。每个 Iteration 检查动作与逻辑轮一一对应；单 owner 先重建私有状态和时钟。主矩阵十组等权，早消费另列；原始 JSON 保留 B/op。固定动态序列为一万个循环名称，不能解释为无限名称输入。

计数和内存探针使用独立进程，计时进程不加载 agent：

```powershell
$ownershipLaunch = Get-Content build/benchmark/launch.json | ConvertFrom-Json
$env:YSM_NATIVE_PATH = $ownershipLaunch.nativeLibrary
& $ownershipLaunch.java '-javaagent:build/benchmark/entity-ownership-probe-agent.jar' -cp ($ownershipLaunch.classpath -join ';') com.elfmcys.ysm.client.entity.EntityOwnershipBenchmark 64 switch probe
& $ownershipLaunch.java '--add-opens=java.base/java.util=ALL-UNNAMED' '--add-opens=java.base/java.lang=ALL-UNNAMED' '-javaagent:build/benchmark/entity-ownership-probe-agent.jar' -cp ($ownershipLaunch.classpath -join ';') com.elfmcys.ysm.client.entity.EntityOwnershipMemoryProbe 64 negative
```

`probe` 记录预热后百帧的 runtime owner 锁调用、公开解析入口和 AnimationStore monitor 进入数。`EntityOwnershipMemoryProbe` 的最后一个参数取 `positive`／`negative`，分别观察成功／缺失缓存；`owners` 取 1／64。该字节总量只覆盖可达缓存存储，跨 owner 共用对象去重，不计共享资源图。

### JNI entry 开销

`JniEntryBenchmark` 对比裸 JNI 与 `YSM_JNI_ENTRY` 的正常返回路径。`rawVoid` / `entryVoid` 测空调用；`rawIdentity` / `entryIdentity` 测整数原样返回。两组分别覆盖 `Status` 和 `StatusOr<jint>` 包装。每次操作调用一次 JNI，JMH 消费整数结果。Trial setup 加载库并校验入口；计时区不含加载、注册或输入校验。

native benchmark 库链接现有 `ysm-java`，只注册测量入口。裸函数通过 `RegisterNatives` 绑定，宏入口通过现有 `BindEntry` 绑定。生产库不包含这些入口。先在 native 项目按构建指南配置 Release 并启用 `YSM_BUILD_BENCHMARKS=ON`，再构建 `ysm-jni-benchmark` target。

```powershell
$env:YSM_JNI_BENCHMARK_PATH='C:/absolute/path/to/ysm-jni-benchmark.dll'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*JniEntryBenchmark.*' '-PbenchmarkArgs=-f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/jni-entry.json'
```

`YSM_JNI_BENCHMARK_PATH` 指向当前平台的 benchmark 动态库；Gradle 入口仍要求配置 `YSM_NATIVE_PATH` 或本地 native 路径。计算同一返回类型的 `entry - raw` 差值，单位为 ns/op；同时检查各 fork 的漂移与置信区间。结果覆盖当前编译配置下的日志 scope、status 解包与异常边界，不覆盖失败路径、参数转换或业务函数体。需要评估 Debug logging 时，使用对应 native 构建单独采样。

### 内存分配与释放

`MemoryAllocationBenchmark` 测量同一线程中的一次分配与配对释放。`mallocFree` / `mimallocFree` 通过两个裸 JNI 调用执行系统 `malloc/free` 和项目 `mi_malloc/mi_free`；`directBufferClean` 使用 `ByteBuffer.allocateDirect` / `Unsafe.invokeCleaner`；`lwjglAllocFree` 使用 `MemoryUtil.memAlloc` / `memFree`。`bytes` 默认为 64 B、4 KiB、1 MiB、128 MiB。JMH 消费已释放地址的数值，计时区不访问已释放内存。`allocateDirect` 包含清零、direct-memory 记账和 Cleaner；其他入口不承诺清零。计时区不主动读写 payload，因此原生分配可能只取得虚拟地址，结果不代表写满内存后的成本。LWJGL 后端以每个 fork 输出的 allocator 类型为准。

`MixedMemoryAllocationBenchmark` 让四种 allocator 分别重放同一固定 seed 的操作序列。每轮包含 1024 对分配/释放，报告单位为 ns/对；结果包含序列遍历和存活槽管理。四档大小的抽样权重为 60% / 30% / 9% / 1%，最多存活 64 块、合计 256 MiB。序列混合存活期和释放顺序，最后释放全部存活块。生成序列和内容校验位于计时区外。默认 seed 为 42、20261019；fork 日志输出 trace hash。独立校验入口按多个 seed 重放四种 allocator，在释放前检查每块首尾字节。

`jniBenchmarkJar` 生成独立 JAR，包含 JNI entry、内存 benchmark、JMH 与 LWJGL 3.3.1 的 Windows/Linux x64 native 依赖。JAR 与对应平台的 `ysm-jni-benchmark` 动态库即可运行，不需要复制 Gradle 项目。

```powershell
.\gradlew.bat jniBenchmarkJar
$env:YSM_JNI_BENCHMARK_PATH='C:/absolute/path/to/ysm-jni-benchmark.dll'
java -cp build/benchmark/ysm-jni-benchmarks.jar com.elfmcys.ysm.benchmark.MixedMemoryAllocationBenchmark 32
java -jar build/benchmark/ysm-jni-benchmarks.jar '.*MemoryAllocationBenchmark.*' -f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc -foe true -rf json -rff build/reports/benchmark/allocation.json
```

Linux 使用 `export YSM_JNI_BENCHMARK_PATH=/absolute/path/to/libysm-jni-benchmark.so`，并用对应 JRE 运行相同 JAR。默认分配规模和混合序列不模拟并发竞争、跨线程释放或长期碎片。JMH GC 分配量只统计 Java heap，不统计 native 分配量。

### 渲染与提取

`NativeRenderBenchmark` 的 `model=body-4|body-96` 选择小工作集与并行路径工作集，`path=direct|fallback` 选择真实 native 输出与宿主回放。headless seam 仅初始化 native、建立声明属性与接收输出，不启动 Minecraft 实例，也不替换 native 实现。direct 接收器复用固定 native buffer，fallback 接收器消费实际顶点；两条路径均核对提交顶点数量。

| 方法 | 单位操作 |
|---|---|
| `extract` | 八样本循环姿态输入，一次整模 JNI 提取 |
| `extractAndDraw` | 同一姿态序列，一次提取和输出 |
| `repeatedDraw` | 同一完整 publication 连续消费两次，无提取 |
| `changingVisibility` | 同一姿态序列及交替奇偶可见集合，一次提取和输出 |
| `emptyDraw` | 预先提取的有效空结果消费两次 |

`NativePartBenchmark` 单独测三个 subtree 输出：同槽重复、L/R/B/L/R 交替、下一姿态保持可见集合及可见集合变化。它不调度完整第一人称 owner，也不与历史没有的 subtree 能力计算加速比。

修改生产接口时同步修复 fixture；新增工作量应在计时区之外准备资源，明确操作数、输入序列、模型规模、可见集合和输出路径。资源位于 source set 内，不依赖临时任务包。声明几何来自共享 [第一人称输入生成器](../first_person_observation/README.md)，小工作集取其主体模型的前四个声明骨骼。

## GUI 与 FULL 需求准备

`DemandPreparationBenchmark` 使用生产 `ClientDemandCoordinator`、零 unused target cache、loader、表达式后端、纹理解码和 native bake。固定输入为 builtin default 的 `blue`、`default` 纹理，固定两个资源 worker。GUI 接收方消费主体，FULL 接收方消费主体和手臂的真实 native extract 输出。输入解析位于 Trial setup；资源和协调 owner 在每次 Invocation 重新建立。

普通 JVM 没有 Forge Mixin。`nativeImageBenchmarkAgent` 只向 `NativeImage` 加入生产 accessor 的两个字段 getter，并校验字段类型。该 agent 仅进入 benchmark fork。图片仍经过生产 `Image.decode`，不以替代解码器计量。GPU 发布和实体安装使用 headless 接缝，因此结果不覆盖 GPU、真实 binding、页面绘制、远端传输或完整启动。

| 参数 | 含义 |
|---|---|
| `scenario=cold` | 内容已驻留；每次清空表达式派生物，FULL Ready 为空。不是进程冷启动或网络冷内容 |
| `scenario=derivedHot` | 保留上轮表达式派生物；新 loader 和空 Ready cache。default 几何仍走 resident bake |
| `scenario=ready` | 在计时区外保有 FULL Ready lease；计时区只选择、取得独立 lease 并消费 |
| `scenario=handoff` | hover 完成 GUI 准备后选择同目标，计量同目标 GUI 与 FULL 的独立准备及接收 |
| `scenario=concurrent` | 同时选择 `blue` 的 FULL、hover `default` 的 GUI，两项目标都完成；表达式准备仅按精确 purpose/binding 共享 |
| `unrelatedCatalog=0,10000` | 固定页面与实体，扩大无关 lookup 项；不计量页面过滤和真实 Catalog 构造 |
| `viewReads=0,1,8` | 每次 tick 后读取反馈的次数；退出时停止读取，检查 lease/cache 归零 |

先运行 `./gradlew.bat benchmarkClasses nativeImageBenchmarkAgent`。使用 `benchmarkArgsFile`，传入 `-jvmArgsAppend` 和 `-Xmx3G -javaagent:<agent absolute path>`，agent 位于 `build/benchmark/native-image-access-agent.jar`。按前述 `benchmark` 入口运行 `benchmarkInclude=.*DemandPreparationBenchmark.prepare`，使用单 fork、两次 warmup、三次 measurement。参数路径含空格时，按 JMH JVM 参数语法引用 agent 路径。

`DEMAND_INPUT` 记录内容身份；`DEMAND_SAMPLE` 记录 actual loader 启动、发布、接收时刻、tick 峰值和采样 lease 峰值；`DEMAND_RETIRED` 记录退出后责任。`DEMAND_METRICS` 包含本轮 owner 的表达式阶段计数；Ready 场景包含计时区外的 priming。JMH wall time 包含 1 ms 的轮询间隔。`Level.Invocation` setup 会污染 GC profiler 的每操作分配量，因此不把该 profiler 的输出解释为计时区分配。资源计数不代表 native 字节、GPU 占用或进程内存峰值。

## GUI 用途缓存矩阵

`GuiPurposeCacheProbe` 使用真实 loader、表达式 compiler/codec、native bake 和 JFR 文件读取。`gui_purpose_cache_campaign.py` 为 Winefox 01 与 Alex 分别建立无缓存、仅 GUI、仅 FULL MAIN、仅 FP_ARM、双缓存和 FULL 已就绪六组输入。每次 seed、cache-only 和普通取得都在独立 JVM 执行；FULL 已就绪组在同一测量 JVM 保留 FULL candidate。该组证明 loader 不复用 FULL，Manager 的 Ready 匹配另由定向测试验证。

```powershell
.\gradlew.bat benchmarkClasses prepareBenchmark nativeImageBenchmarkAgent guiPurposeInputAgent readHandleAgent
python src/verification/benchmark/gui_purpose_cache_campaign.py --output <new-absolute-directory> --budget-seconds 1200
```

`guiPurposeInputAgent` 只观察生产 `createSet` 的输入，不替换结果。每次运行保存输入 hash、用途、闭包 fingerprint、编译次数、实际读取、cache root 和启动命令。GUI warm 应来自 GUI 分区；其他组应使用同一 GUI 闭包，且不读 FULL 文件。Cache-only miss 不编译。Seed 使用生产派生工作接纳。FULL 磁盘重开检查零编译和动画批次读取。测试不覆盖 GPU、页面绘制、网络或性能收益。

## 分级准入文件读取验证

`DemandDiskReadVerification` 验证真实 `ClientDemandCoordinator` → `ClientModelRenderTargetManager` → loader/cache 的工作准入。三种来源分别使用本地容器、`RemoteModelContent.openCached` 和已有 geometry cache；表达式派生物也纳入读取统计。该入口用于正确性验证，不是 JMH 性能采样。

```powershell
.\gradlew.bat verifyDemandDiskReads
```

沿用本页的 native 路径配置。任务自动准备 JDK、classpath 和 `nativeImageBenchmarkAgent`。默认输出为 `build/reports/demand-disk-reads`，可用 `-PdemandDiskReadOutput=<dir>` 指定目录。

每轮在输出目录中建立独立的 `run-*` 子目录，保存 fixture、缓存和 12 份 JFR，避免复用上轮缓存。验证程序启动时移除旧 `summary.json`，全部验证与资源关闭成功后才发布本轮汇总。汇总记录 `runDirectory`，并按路径和调用栈统计读取次数与字节数。

每种来源都使用受控单调时钟执行以下阶段；文件读取与 worker 完成仍走真实路径。

| 阶段 | Oracle |
|---|---|
| 快速翻页 | 三个不同 `ModelId` 各在 0、299、300 ms 推进；受测根目录内读取次数为零 |
| 稳定到 301 ms | 等待真实 Ready，目标来源读取次数和字节数均大于零，且调用栈包含生产读取入口；bake 来源要求 `.geo.ysm-cache` 的实际 `BakedModelCache.read` 消费 |
| Ready 后重复推进 | 连续 100 tick 不再读取受测文件 |
| 本地准入前复用 Ready | 保持独立 Ready anchor，换入新的快速页面；立即取得独立使用且零读取 |

三个零读取阶段还要求取得入口调用数和 Manager 在途数均为零，排除读取排队后越过录制窗口的假零。

JFR 以零阈值记录 `jdk.FileRead` 和调用栈，只汇总本地、remote、geometry 与 expression 的受测根目录。Java 文件读取事件不等于物理磁盘 I/O。真实 required 初始化位于录制之外，目录 snapshot 由 fixture 注入，纹理发布使用 headless 接缝。因此本验证不覆盖目录发现与准入、网络传输、GPU、真实帧时间或性能收益。

## 本地玩家资源优先调度

`ResourcePriorityBenchmark` 复用真实 loader、驻留 builtin default、两种纹理和 FULL 主体/手臂 native extract。`saturated` 在后台计算积压后发起本地请求；`sameFlight` 提升同一 Flight；`sharedPreparation` 提升两个纹理 Flight 共享的 producer。后台负载为固定数量的 CPU 工作，不模拟真实世界任务分布。

三种配置均限制为两个活动计算 worker。`single_fifo_control` 将全部工作放入两个后台 worker 的共同 FIFO，另有一个始终空闲的专用线程；它是当前代码的队列对照。`split_equal` 使用一条专用队列和一条后台队列，两线程均使用 `BELOW_NORMAL` OS 策略；`split_normal` 保留专用线程的 `NORMAL` 策略。该对照不包含历史实现，不能据此宣称整体重构收益。

```powershell
.\gradlew.bat benchmarkClasses prepareBenchmark nativeImageBenchmarkAgent
python src/verification/benchmark/resource_priority_campaign.py --iterations 8 --budget-seconds 480
```

脚本按场景交错配置，每组合启动独立 JMH fork，使用热派生物，并在总预算内保存 metadata、原始日志、JMH JSON、逐样本 JSONL 和分位数。默认输出为 `build/reports/benchmark/resource-priority`。agent 的 `priority` 参数仅在测量 fork 中记录真实 producer、consumer build、native bake 次数及线程，验证共享不重复生产且计算峰值不超过二。

时间分段为请求到 producer 入口、所有 producer/consumer 的累计计算、candidate 完成到首次 Ready 观察、请求到 native extract 接收。producer 入口晚于原子领取；Ready 观察包含 fixture 的轮询间隔；累计计算不是关键路径时间。JMH 主 score 包含全部后台工作和资源 Flight 的结束，本地接收延迟应读取分段 JSON。另行报告固定后台工作吞吐。小样本 P95/P99 只是描述性分位数，不具备尾延迟置信度。该入口不覆盖网络、GPU、真实实体安装或帧时间。

## Catalog 重载

`CatalogReloadBenchmark.freshMetadata` 与 `reusedMetadata` 对同一个 builtin default 容器执行完整文件验证。前者重新解析 metadata，后者传入旧内容并要求共享解析视图。两者都关闭本次独立 representation。模型转换位于 Trial setup；计时区包含文件 I/O、完整 chunk 验证、prefix 比较或解析及释放。

```powershell
.\gradlew.bat benchmark '-PbenchmarkInclude=.*CatalogReloadBenchmark.*Metadata' '-PbenchmarkArgs=-f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/catalog-reload.json'
```

分阶段观测使用真实 `ReloadableModelCatalog`，先停止 owner tick，等待协调线程退出，再用一次 tick 接纳最终结果。输入为指定数量的独立合法容器，派生自 builtin default，修改 ModelId 并嵌入 preview。入口依次观测初次扫描和不变目录重载，并要求后者的 candidate 构建、metadata 解析次数为零。

```powershell
.\gradlew.bat prepareBenchmark catalogReloadTimingAgent
$catalogLaunch = Get-Content build/benchmark/launch.json | ConvertFrom-Json
$env:YSM_NATIVE_PATH = $catalogLaunch.nativeLibrary
$catalogAgent = (Resolve-Path build/benchmark/catalog-reload-timing-agent.jar).Path
& $catalogLaunch.java "-javaagent:$catalogAgent" -cp ($catalogLaunch.classpath -join [IO.Path]::PathSeparator) com.elfmcys.ysm.model.catalog.CatalogReloadBenchmark 128
```

`CATALOG_PHASE` 分别输出完整 candidate 构建、含 observer 的 publish、observer 和 metadata 解析的次数、耗时及当前线程 heap 分配。`tickNs` 还包含终态接纳和 future 完成。agent 只进入观测进程，不修改生产代码；入口、计数和线程分配查询会增加观测开销。小样本用于确认工作归属及调用次数，不用于推断规模曲线。observer 只检查目录完整性，不覆盖服务端逐玩家同步。JMH 分配量与阶段分配量均不包含 native 内存；结果不代表客户端帧率。

## 音频 PCM 与 checkpoint

`AudioPrefixBenchmark.consume` 使用驻留 Opus 媒体、真实 JNI 和生产播放流。Trial setup 先连续解码形成 oracle，再由冷播放发布实际分段缓存。计时区覆盖缓存消费、冷/热准备、首次缺口、剩余尾部、loop 与冷/热完整单轮。阶段定义、参数、正确性入口及证据边界见[音频缓存验证](../../verification/audio-prefix-cache.md)。

主 score 和 GC 分配覆盖激活、阶段前置消费与清理。`stageWallNs`、`stageCpuNs` 等 EVENTS counter 是迭代累计值，须用 `operations` 归一化；不能把主 score 当作准备延迟，也不能把整操作的 create/restore 次数当作阶段内次数。

## 证据半径

### 模型列表真实宿主

`src/verification/page-host` 在隔离 Forge 世界内驱动 `PlayerModelScreen` 的分页按钮。两份源码使用同一 host 与 ASM agent；生产源码不增加诊断入口。启动前固定 baseline/candidate、Java 依赖、Forge mappings、native、模型文件和客户端配置。配置使用 LOCAL session、30 个 target 保留名额与关闭免责声明；脚本固定查询 `wine`，要求 22 个模型、3 页。

```powershell
python src/verification/page-host/run_host.py --root <source> --output <new-output-directory> --script <frozen-script.json> --config <client-config-file>
python src/verification/page-host/batch_campaign.py <frozen-plan.json> --output <new-campaign-directory>
python src/verification/page-host/batch_analyze.py <campaign-directory>/manifest.json --output <analysis-file> --jfr <jdk-jfr-executable>
```

JSON 脚本逐项指定 scene、page、eager 和 dwell_ms。参与统计的项另指定 window_ms；窗口从输入开始，跨过窗口终点的完整帧间隔仍保留。快翻窗口允许重叠，同一进程的脚本块按进程成组，帧与块不充当独立重复。需要重复 baseline、AB/BA 顺序、独立进程对数、预热、噪声估计和预算时，在采样前冻结计划；不能按 candidate 结果改窗口或扩大容忍带。进程内切换 eager 不会自动形成新的冷态。

Host 在脚本前记录帧时钟，分析器检查窗口起点和终点是否都有帧覆盖。软截止在外部硬截止前请求主线程保存部分帧、visit 和 JFR；主线程阻塞时仍可能触发硬终止并丢失内存记录。部分运行不补齐独立进程对。

`frames.csv` 保存完整帧间隔、render wall time 与主线程 CPU。`visits.json` 保存输入时钟锚点、边界计数、注册和卡片状态。Agent 记录真实 chunk read、解码、纹理构造、load、upload、register/release、GL delete、卡片 run/join、GUI 动画和 provider hook 的入口及正常返回。`operations.jfr` 保留逐窗口 I/O、park/monitor、GC、执行样本和任务区间。Join 区间是主线程等待的上界，render wall time 减 CPU 不能归因为等待；后台样本也不证明调度竞争的因果关系。

JFR 分析器使用 JDK RecordingFile 流式汇总，不展开完整 JSON 常量池。Java 分析进程限制为 512 MiB heap；输出按窗口和线程分组，并保留事件类型计数与调用栈。

`--detail` 另存事件、首次 Matching draw 标记及窗口末截图，不进入性能对照。Matching draw 标记只证明当前安装进入绘制，不能自动证明画面可识别。`--functional` 在性能脚本前独立检查 builtin 四图的真实 GPU 像素共享、关闭、保留期、物理删除和 resource reload；该检查读取已注册纹理，不用于时延比较。它不覆盖独立源解码 oracle、远端或同路径替换。

`--provider immersive-melodies` 启用单一 provider；`--provider all` 启用八个 provider，`available` 仅排除 Tacz。实际版本与启用状态由运行时 mod 清单证明。依赖失败、默认空 hook 与实际 hook 执行分别报告；正常返回不证明串并行状态等价。完整判定仍需要来源、资格、退出和变化输入矩阵、独立 baseline 波动带及首个可识别 3D。工具不自动发布整体性能通过；缺失矩阵、超时和部分运行保留为 inconclusive。

`campaign.py` 与 `analyze.py` 保留旧固定脚本的统计格式。复算历史结果时使用该次冻结的 harness，不能将当前探针视为历史探针。


模型加载交接的正确性入口与性能采样分开。`verifyGeometryRead` 以 JUnit 验证普通／cache-only 的单次 payload 取得、完整性检查、native decode 和独立持久化；`verifyPreviewHandoff` 记录完整媒体解码、encoded 交接、最终像素及自动默认 body 的构造范围。两者使用生产 codec/native 与 headless 发布接缝，结果保存在 Gradle 测试报告中，不证明真实 GPU、实体安装或主循环成本。`ClientPreviewGeneratorTest` 用小图验证保留像素期间继续解码、上传借用和取消；`DerivedStorageWorkTest` 验证 16 项饱和、单 worker、取消和提交。声音共享、首块失败和独立播放回归随普通 `test` 执行。

共享引用、owner shutdown 与活动借用结束后的物理 backing 释放由 `PreviewPixelsTest` 验证；串行 reload 与已保留图片互不等待。按本页配置 native 路径后，运行 `./gradlew.bat test --tests '*ClientPreviewGeneratorTest' --tests '*PreviewPixelsTest'`。这些验证不测量驱动或 RSS。

保存源码/native/依赖 hashes、硬件与电源状态、JDK/JVM/JMH、worker/ISA/toolchain、模型 hashes、参数、顺序和全部原始结果后，比较单位操作时间与 `gc.alloc.rate.norm`。对照使用匹配的历史 Java/native；历史缺 harness 时只添加等价测量接线。先重复 baseline 校准每指标自身噪声，再独立交错对照；分配指标按实际计数精度校准。结果有分歧、漂移或预算不足时保留未验证。

Native 渲染基准只证明所测提取与输出工作量。实体 owner／processor／controller 与异步交接使用本页的实体专用基准。各基准均不证明客户端 FPS、GPU/shader、完整用户视觉或任意模型规模。资源释放与 schedule 次数需要单独观察；JMH GC 分配量不包含 native 持久内存。
