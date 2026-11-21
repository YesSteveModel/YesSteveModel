# 动画编译性能基准

## GUI 单表达式解释对照

独立解释准备从内存中的 Portable Program 字节开始，包含格式解码、结构检查与绑定，直到产出可执行程序为止；测试时禁用已绑定程序缓存命中。源文本解析与编码单列为资产转换成本。与旧解释器对照时，旧侧须从实际字符串输入完成解析与绑定，不能用纯绑定耗时或两个拆分阶段之和替代完整操作。

执行阶段使用两侧各自的持久实例与实际变量访问机制，准备和初值写入放在计时之外。数值样本走当前宿主的 F32 与 `executeFloat` 完整入口，包含真实的 begin、根状态 reset 和 end，不能强行套一层通用值包装，也不能省略窗口工作；入口形状发生变化时，须与历史成绩分开披露。每次消费执行结果前，先用独立真值核对返回值、全部字段及真实声明轨迹。

稳定变量位置的登记、查询以及程序元数据计入实际准备；必要容量增长、Roaming 成员登记和局部清理则沿真实执行入口计时。初值建立、首次新增名称或高位置扩容，以及非空局部变量的首次与暖清理成本需单独列示，因为暖态目录中已登记的条件不代表首次工作消失。

控制器条件需要同时验证全假分支以及各必需字段独占为真的分支，调用轨迹必须精确止于短路项；正式全假样本须保留相同的可选模组可用状态和真实读取链。因可选模组状态、缺失绑定或简化声明导致的不可比项必须单列。

独立 JVM 的命名映射与启动装配必须与依赖保持一致；夹具访问器沿用实际字段读取，装配与构造成本在计时之外单列。

候选数学调用按生产绑定资格选择 intrinsic，夹具不额外授权限。当前绑定求值树的共享计划、程序和实例保留成本需分别披露；局部参数和尾数组按真实调用路径创建，稳态分配下降不代表常驻内存下降。

`second_order` 需分开报告不推进时间的同轮求值、恒定输入以及变化输入的有限序列，时钟推进与状态重建的成本单独列出。

有界 runner 使用三个独立 JVM：每侧每样本每阶段先至少预热 200 ms，校准后固定两侧相同的操作数，再用正式入口交替成对预热 2 秒，到时后补完当前整对。新增预热记录单独落盘，不得根据成绩延长、重新校准或追加 JVM。随后执行四轮交替配对（目标窗口约 60 ms），并在进程内轮换样本顺序。统计时先求各进程的速度比，再对其对数计算进程级 t 区间，循环次数不能当作独立样本。完整矩阵包含 108 个主项、24 个滤波附加项及另报的 S06 原版，仅最低预热和名义计时就约需 1158 秒；启动、重置、整对超时及正确性检查另计，完整时间预算须在采样前固定。产物需保存输入、源码和制品摘要、原始配对、分配数据，以及首次链接、暖绑定和首次执行诊断。微基准不证明模型首帧或持续展示收益，真实 GUI 验证方法见[模型列表宿主](../tools/benchmark/README.md#模型列表真实宿主)。

JFR 在三个正式 JVM 结束后，由独立成本进程录制，并保存原始文件、实际配置、操作数与制品身份。先完成冷首、首次与暖态以及有界图观察，再开启录制；启动时附带的 JFR 配置与 SizeAgent 也必须披露。录制不能替代正式置信区间或真实 GUI，完整 reset 与 advance 序列的采样栈占比也不能当作净表达式时间占比。

## 默认 player/main 的 JMH 编译基准

`PlayerMolangCompileBenchmark` 直接读取 builtin/default 的实际 `player/main` 输入，不启动客户端。基准保留 controller、字符串表与 settings，只按条件筛选动画。动画索引按文件 key 排序，文件内沿用解析后的动画顺序。实验前先导出索引，再指定区间运行：

```powershell
.\gradlew.bat playerMolangInventory
.\gradlew.bat benchmark "-PbenchmarkInclude=.*PlayerMolangCompileBenchmark.*" "-PbenchmarkArgs=-p selection=full,keep:0-170,remove:0-170 -wi 10 -i 10 -f 3 -prof com.elfmcys.ysm.benchmark.PlayerMolangPhaseProfiler"
```

索引表输出到 `build/benchmark/player-molang-inventory.tsv`。`selection` 支持 `full`、`empty`、`keep:start-end` 与 `remove:start-end`（左闭右开区间，多段区间以 `+` 连接）。索引会随模型输入变化，实验前应重新导出；二分定位时先比较两个半区，再回到完整输入上删除候选动画复核。

每次调用前，基准会清空专用临时目录下的 artifact 缓存，并新建 store 与 factory。主计时覆盖生产 `createSet` 的编译、异步 artifact 写入提交与模块加载；关闭模块、等待写任务释放和清理缓存放在计时区外，每次调用都会校验写任务无失败、取消或 shutdown。Profiler 单独记录引擎的 semantic、generation 和 backend 阶段，以及 programs、typedNodes、generatedNodes 和 replacements。每次调用都断言命中 COLD、恰好执行一次 inventory 解码及各一次完整编译阶段；报告中的 replacement 数量不可忽略。需要注意的是，GC profiler 可能把 invocation 的 setup、teardown 和后台写任务分配也统计在内，不能直接解释为主计时区的线程分配。

该基准测的是预热后 JVM 的冷 artifact 编译，宿主未启用可选模组 provider。它不代表首次启动的 JIT 状态，也不覆盖 `fp_arm`、其他 target、动画烘培或游戏帧时间。通过 `prepareBenchmark` 可以导出 JDK、classpath 与 native 库路径，供外部 runner 直接启动 JMH。

参数 `hostMode=intrinsic` 使用 builtin 函数投影与 `LivingEntity` binding，`hostMode=ordinary-player` 使用普通宿主与 `AbstractClientPlayer` binding，默认 `ordinary` 保留无固定主体的原基准。三种模式均关闭可选 provider，且只编译完整 MAIN。配对对照应固定相同的工作量、依赖和 benchmark 源码；两种角色的独立编译耗时不能直接相加当作完整启动或首次安装耗时，GC profiler 的分配量也不等于驻留字节。

通用运行参数、native 基准及对照的证据要求见[持续性能基建](../tools/benchmark/README.md)。

`PlayerMolangCompileBenchmark --probe` 会从实际落盘的 artifact 核对 class 总字节数；该读取与校验位于独立 probe 中，不进入 JMH 主计时。

对于 builtin 输入，`divZeroFallback=false|true` 分别切换除零回退编译选项；模型输入则始终沿用接收模型自身的来源配置。独立 `--probe` 与执行真值入口通过 JVM 属性 `ysm.benchmark.divZeroFallback` 指定 builtin 选项。两种选项的结果、耗时与 class 字节数须分别报告；共享 helper 与 linkage 的固定成本单列，最终 JAR 统计包含这些类。

## 默认 main 的表达式执行基准

原依赖第一人称宿主夹具的 `PlayerMolangExecutionBenchmark` 已随伪宿主测试套一并退役；以下继续维护无宿主依赖的 `MolangSemanticBenchmark` 口径。

`MolangSemanticBenchmark` 在相同的有效主体和冻结安装上，分别测试便宜数学链、超越函数与小型 controller 表达式。其中 `execute` 走生产观察入口，`coldTransient` 每次编译并关闭独立临时程序（覆盖 frontend、语义分析、代码生成与 backend）。Trial setup 会先核对手写真值；小型 controller 表达式不能代替真实控制器的分支与动作验证。

<a id="model-scaling"></a>
## 模型输入与共享规模

`PlayerMolangCompileBenchmark` 在 `input=model` 时读取环境变量 `YSM_BENCHMARK_MODEL` 指定的已转换容器，只接受 `purpose=FULL`，使用生产完整输入、实际主体 binding 与默认补全，`selection` 固定为 `full`。输入转换和清单装配位于 Trial setup，计时仍覆盖完整 `createSet`。该入口只编译 MAIN，不能把模型输入或 `purpose=FULL` 误解为 FP_ARM 编译、几何准备或实际玩家安装。

```powershell
$env:YSM_BENCHMARK_MODEL='C:/absolute/path/to/model.mxc'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*PlayerMolangCompileBenchmark.coldCompile' '-PbenchmarkArgs=-p input=model -p purpose=FULL -p selection=full -f 1 -wi 2 -i 3 -prof gc -prof com.elfmcys.ysm.benchmark.PlayerMolangPhaseProfiler'
```

`MolangCompilationScalingBenchmark` 提供四种独立输入形态：`repeated` 固定完整表达式契约、只扩大来源数；`unique` 同步扩大含不同常量的契约数；`sparse` 与 `dense` 固定 12 个 reader，并按 `size` 扩大全局实例槽（writer 写入全部槽，reader 分别读取单个槽或全部槽）。基准保留正常宿主策略，逐次校验原始来源数、有效实例查询数、实际 Root 与 shard 的 switch 结构及物理函数体，默认三档为 `size=1,8,65`。

```powershell
.\gradlew.bat benchmark '-PbenchmarkInclude=.*MolangCompilationScalingBenchmark.coldCompile' '-PbenchmarkArgs=-p scenario=repeated,unique,sparse,dense -p size=1,8,65 -f 1 -wi 2 -i 3 -prof gc'
```

该类的 `main` 接受 `scenario size`，在独立进程中输出各阶段耗时、工作量、payload 体积、暖解码加载耗时及各 heap pool 峰值，并通过 ASM 直接校验实际生成 class 的 switch 与物理函数体。各内存池峰值未必同时出现，不能相加当作总堆峰值或 RSS；进程工作集（working set）与提交内存（commit）、GC 分配量以及 native 内存须分开记录。输入预检和诊断没有独立的阶段计时器，诊断开销通过有效工作量与引用计数评估，不能从 semantic 耗时里凭空拆分。

`scenario` 还支持 `scalarEquivalent`、`scalarObjects`、`scalarCompare`、`scalarJoin` 和 `scalarConflicts`。这些场景输入固定、不随 `size` 扩大，运行时 provider 分别提供有限 scalar、String、数组和 record：`scalarObjects` 测三类对象的 Java scalar 参数；`scalarCompare` 测对象与非字面量数值的比较及下游调用；`scalarJoin` 测持久与局部 BOOL、F32 的控制流合流；`scalarConflicts` 保留对象与 scalar、不同 record 或数组 schema 的独立候选冲突（冲突夹具允许显式零回退，只作恢复成本对照）。

```powershell
.\gradlew.bat benchmark '-PbenchmarkInclude=.*MolangCompilationScalingBenchmark.coldCompile' '-PbenchmarkArgs=-p scenario=scalarEquivalent,scalarObjects,scalarCompare,scalarJoin,scalarConflicts -p size=8 -f 1 -wi 2 -i 3 -prof gc -prof com.elfmcys.ysm.benchmark.PlayerMolangPhaseProfiler'
```

scaling 基准的主计时包含编译单元建立、编译和 class 校验，不含 artifact 编码、加载与执行，阶段 profiler 同样适用。冲突组允许记录 `SemanticCompilationException`；未产出 artifact 时，节点数、源码大小和 replacement 指标以 `-1` 表示不可用，generation 与 backend 因未执行记为零耗时。

`main` 会输出原始输入字节数、恢复与 replacement 来源、分析计数以及实际值与调用轨迹；传入 `-Dscalar.requireOracle=true` 可校验手写 scalar 真值（冲突组只记录诊断）。`PlayerMolangCompileBenchmark --probe` 则输出完整 builtin 输入的报告和分析计数。对照前应先比对两版的有效工作量，再判断是否具备配对资格；旧版因提前恢复或触发 replacement 而减少工作的样本须单列，不能据此宣称提速。

当前规模夹具已证明重复来源不会增加执行入口的 switch，稀疏诊断也不会扫描无关全局槽，而真实稠密引用成本依然保留。代表模型另有旧 GUI AOT 与 FULL loader、独立进程磁盘暖加载的观察，以及 Windows Forge 的预览、选择和世界模型观察。但该模型没有独立的 FP_ARM 几何，MAIN 的编译数据不能替代手部路径验证；姿态观察也只覆盖静止、普通跳跃输入和第一与第三人称，缺少逐骨骼 golden、完整动作与声音或联动矩阵。由于历史精确二进制与稳定配对样本不足，当前结果不构成历史速度比、净热路径收益或游戏帧率结论。

## 默认模型完整准备与实例

`DefaultModelPreparationCostProbe` 使用真实完整默认源和 required loader，分别计量全部 required target 与纹理、普通玩家 FULL、普通 GUI 以及私有 holder。`main` 接受输出目录，通过 `prepareBenchmark nativeImageBenchmarkAgent` 获取 classpath 与 headless NativeImage agent，由 runner 在独立 JVM 中依次执行预热、空缓存准备和同目录磁盘暖加载。

探针记录消费者次数与用途、表达式 COLD 或 DISK 来源、共享阶段调用次数、耗时、存活线程分配计数、GC 后的堆与直接缓冲区（direct buffer）以及进程内存。Intrinsic FULL 的消费者累计耗时与并发墙钟总时长分开报告；私有 holder 的绝对保留量不代表相对 intrinsic 的净增量。

源复制、assembler 转换、目录准入、实际 GPU 发布与游戏 tick 均在计时之外。存活线程分配统计可能漏计已退出线程，堆、直接缓冲区与进程内存既不能直接相加，也不等于精确的 native 对象保留量。这些数据用于审查准备与驻留成本，不外推完整应用启动或帧性能。
