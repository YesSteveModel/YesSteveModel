# 动画编译性能基准

本页记录 Molang 编译、执行与准备成本的 L2 基准入口及运行约定；通用参数与对照规则见[持续性能基建](../tools/benchmark/README.md)，验证分层见[验证边界](policy.md#验证分层架构)。

## 默认 player/main 编译基准

`PlayerMolangCompileBenchmark` 读取内置默认 `player/main` 输入（不含客户端启动与可选模组 provider）。先导出动画索引，再按需切片运行：

```powershell
.\gradlew.bat playerMolangInventory
.\gradlew.bat benchmark "-PbenchmarkInclude=.*PlayerMolangCompileBenchmark.*" "-PbenchmarkArgs=-p selection=full,keep:0-170,remove:0-170 -wi 10 -i 10 -f 3 -prof com.elfmcys.ysm.benchmark.PlayerMolangPhaseProfiler"
```

- **产物与参数**：动画索引输出至 `build/benchmark/player-molang-inventory.tsv`（输入变更后需重新导出）。`selection` 支持 `full`、`empty`、`keep:start-end`、`remove:start-end`（左闭右开，`+` 连接多段）；`hostMode` 支持 `ordinary`（默认）、`intrinsic` 与 `ordinary-player`；builtin 输入通过 `divZeroFallback=false|true` 切换除零回退选项。
- **独立探针与外部启动**：`PlayerMolangCompileBenchmark --probe`（配合 `-Dysm.benchmark.divZeroFallback=...`）在主计时外核对落盘 artifact 的 class 字节数与分析计数；`prepareBenchmark` 导出 JDK、classpath 与 native 路径供外部 runner 直接启动 JMH。
- **验证边界**：主计时覆盖预热后 JVM 上完整 `createSet`（编译、异步写盘提交与模块加载），不覆盖首次启动 JIT、`fp_arm`、动画烘培或游戏帧时间；GC profiler 会包含 setup/teardown 与后台写任务分配。

## 模型输入与共享规模

### 外部模型输入

设置环境变量 `YSM_BENCHMARK_MODEL` 指向已转换容器，以 `input=model` 运行 `PlayerMolangCompileBenchmark`（固定 `purpose=FULL`、`selection=full`）：

```powershell
$env:YSM_BENCHMARK_MODEL='<path/to/model.mxc>'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*PlayerMolangCompileBenchmark.coldCompile' '-PbenchmarkArgs=-p input=model -p purpose=FULL -p selection=full -f 1 -wi 2 -i 3 -prof gc -prof com.elfmcys.ysm.benchmark.PlayerMolangPhaseProfiler'
```

### 规模与标量编译矩阵

`MolangCompilationScalingBenchmark` 覆盖结构规模（`repeated`、`unique`、`sparse`、`dense`，默认 `size=1,8,65`）与固定输入标量场景（`scalarEquivalent`、`scalarObjects`、`scalarCompare`、`scalarJoin`、`scalarConflicts`）：

```powershell
.\gradlew.bat benchmark '-PbenchmarkInclude=.*MolangCompilationScalingBenchmark.coldCompile' '-PbenchmarkArgs=-p scenario=repeated,unique,sparse,dense -p size=1,8,65 -f 1 -wi 2 -i 3 -prof gc'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*MolangCompilationScalingBenchmark.coldCompile' '-PbenchmarkArgs=-p scenario=scalarEquivalent,scalarObjects,scalarCompare,scalarJoin,scalarConflicts -p size=8 -f 1 -wi 2 -i 3 -prof gc -prof com.elfmcys.ysm.benchmark.PlayerMolangPhaseProfiler'
```

- **独立诊断入口**：运行 `MolangCompilationScalingBenchmark` 的 `main <scenario> <size>`（可选 `-Dscalar.requireOracle=true`）可在独立进程输出各阶段耗时、payload 体积、暖加载耗时、各 heap pool 峰值及 ASM 结构校验。
- **验证边界**：`PlayerMolangCompileBenchmark` 的 `input=model` 仅编译 `MAIN`，不覆盖 `FP_ARM`、几何准备或游戏安装；scaling 基准仅计时编译与 class 校验，各 heap pool 峰值未必同时发生，不可相加作为总堆峰值或 RSS。

## 表达式执行与完整准备探针

- **`MolangSemanticBenchmark`**：在无宿主依赖的冻结安装上测量数学链、超越函数与小型 controller 的生产观察执行（`execute`）及临时编译（`coldTransient`）。原依赖第一人称伪宿主的 `PlayerMolangExecutionBenchmark` 已退役。
- **`DefaultModelPreparationCostProbe`**：通过 `prepareBenchmark nativeImageBenchmarkAgent` 获取 classpath 与 headless agent，在独立 JVM 测量默认源的空缓存准备与同目录磁盘暖加载耗时、消费者次数、堆与 direct buffer 及进程内存（源复制、转换、GPU 发布与游戏 tick 在计时外）。

## GUI 单表达式解释对照

有界对照 runner 用于比较独立解释路径与旧解释器，运行约束如下：

- **统一计量边界**：新侧从内存 Portable Program 字节解码并绑定（禁用缓存命中）；旧侧从源字符串完整解析并绑定。执行计时覆盖真实 F32/`executeFloat` 窗口（begin、root reset、end），消费结果前先与独立真值及声明轨迹核对。`second_order` 需分列同轮、恒定输入与变化输入序列。
- **采样与统计**：使用 3 个独立 JVM，每阶段先预热 ≥ 200 ms 并校准固定操作数，再交替成对预热 2 s，随后执行 4 轮约 60 ms 的交替配对并轮换样本顺序；按进程速度比的对数计算进程级 t 区间。产物需保存输入/制品摘要、原始配对、分配数据及冷首/暖态诊断。
- **JFR 录制**：在 3 个正式 JVM 结束后由独立成本进程录制并留存配置与原始文件，不替代正式置信区间。微基准不证明真实 GUI 首帧或持续帧率，真机宿主入口见[模型列表宿主](../tools/benchmark/README.md#模型列表真实宿主)。
