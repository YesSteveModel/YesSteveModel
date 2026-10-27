# 动画编译性能基准

## 默认 player/main 的 JMH 编译基准

`PlayerMolangCompileBenchmark` 读取 builtin/default 的实际 `player/main` 输入，不启动客户端。基准保留 controller、字符串表和 settings，仅筛选动画。动画索引按文件 key 排序，文件内沿用解析结果的动画顺序。先导出索引，再指定区间：

```powershell
.\gradlew.bat playerMolangInventory
.\gradlew.bat benchmark "-PbenchmarkInclude=.*PlayerMolangCompileBenchmark.*" "-PbenchmarkArgs=-p selection=full,keep:0-170,remove:0-170 -wi 10 -i 10 -f 3 -prof com.elfmcys.ysm.benchmark.PlayerMolangPhaseProfiler"
```

索引表位于 `build/benchmark/player-molang-inventory.tsv`。`selection` 接受 `full`、`empty`、`keep:start-end` 和 `remove:start-end`；区间不含 end，多个区间用 `+` 连接。索引随模型输入变化，实验前应重新导出。二分时先比较两个半区，再在完整输入上删除候选动画复核。

每次调用前，基准删除专用临时目录内的 artifact 缓存，并新建 store 和 factory。删除与关闭模块不计入 JMH 主计时。主计时覆盖生产 `createSet` 的编译、artifact 写入与模块加载；profiler 单列引擎的 semantic、source 和 backend 阶段，以及 programs、typedNodes、sourceBytes 和 replacements。每次调用均校验 COLD、一次 inventory decode 和各一次完整编译阶段；replacement 数由报告给出，不应忽略。

此基准测预热 JVM 的冷 artifact 编译，host 不启用可选模组 provider。它不代表首次启动的 JIT 状态，也不覆盖 `fp_arm`、其他 target、动画烘焙或游戏帧时间。`prepareBenchmark` 可导出 JDK、classpath 和 native 路径，供外部 runner 直接启动 JMH。

通用运行参数、native 基准和对照的证据要求见[持续性能基建](../tools/benchmark/README.md)。完整动画准备、宿主消费和帧期仍需对应工作量的独立测量，不能从此编译基准推导。

<a id="model-scaling"></a>
## 模型输入与共享规模

`PlayerMolangCompileBenchmark` 的 `input=model` 读取环境变量 `YSM_BENCHMARK_MODEL` 指定的已转换容器。`purpose=GUI|FULL` 使用生产用途投影、实际主体 binding 和默认动画／函数闭包；`selection` 固定为 `full`。输入转换和闭包装配位于 Trial setup，计时仍覆盖完整 `createSet`。此入口仅编译 MAIN，不能把模型输入或 `purpose=FULL` 解释为 FP_ARM 编译、几何准备或实际玩家安装。

```powershell
$env:YSM_BENCHMARK_MODEL='C:/absolute/path/to/model.mxc'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*PlayerMolangCompileBenchmark.coldCompile' '-PbenchmarkArgs=-p input=model -p purpose=GUI,FULL -p selection=full -f 1 -wi 2 -i 3 -prof gc -prof com.elfmcys.ysm.benchmark.PlayerMolangPhaseProfiler'
```

`MolangCompilationScalingBenchmark` 提供四种独立输入。`repeated` 固定完整表达式契约，仅扩大来源数；`unique` 同时扩大不同常量的契约数。`sparse` 和 `dense` 固定十二个 reader，以 `size` 扩大全局实例槽；writer 写全部槽，reader 分别读一个或全部槽。基准保留正常宿主策略，逐次校验原始来源数、有效实例查询数、实际 Root／shard switch 和物理 body。默认三档 `size=1,8,65`。

```powershell
.\gradlew.bat benchmark '-PbenchmarkInclude=.*MolangCompilationScalingBenchmark.coldCompile' '-PbenchmarkArgs=-p scenario=repeated,unique,sparse,dense -p size=1,8,65 -f 1 -wi 2 -i 3 -prof gc'
```

该类的 `main` 接受 `scenario size`，在独立进程输出阶段、工作量、payload、warm decode/load 和各 heap pool 的峰值。实际 class 的 switch 与物理 body 直接由 ASM 校验。各池峰值不是同时发生的总 heap 峰值，不能相加为 RSS。进程 working set／commit、GC 分配量和 native 内存应分别记录。输入预检和诊断没有独立 stage timer；诊断用有效工作／引用计数判断，不能从 semantic 时间中虚构拆分值。

当前规模夹具已验证重复来源不增加执行入口 switch，稀疏诊断不扫描无关全局槽，真实稠密引用成本仍保留。代表模型另经完整 GUI/FULL loader、独立进程磁盘暖加载，以及 Windows Forge 的预览、选择和世界模型观察。该模型没有独立 FP_ARM geometry；MAIN 编译数值不能代替手部路径的验证。姿态观察仅覆盖静止、普通跳跃输入和第一／第三人称，没有逐骨骼 golden、完整动作／声音或联动矩阵。历史精确二进制与稳定配对样本不足，当前结果不构成历史速度比、净热路径收益或游戏帧率结论。
