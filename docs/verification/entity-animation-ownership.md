# 实体动画所有权验证

本页记录验证入口。执行、交接和退出责任见[实体生命周期](../architecture/animation/entity-lifecycle.md)，缓存规则见[播放状态](../architecture/animation/controllers-and-playback.md#解析缓存与播放寿命)。

## 受控回归

配置同一 native 库和第一人称几何 fixture 后，运行定向测试：

```powershell
$env:YSM_NATIVE_PATH='<absolute-native-library>'
$env:YSM_FIRST_PERSON_FIXTURES='<absolute-fixture-directory>'
.\gradlew.bat compileJava test --tests '*EntityOwnershipIntegrationTest' --tests '*EntityProjectionIntegrationTest' --tests '*EntityExecutionLifecycleTest' --tests '*FirstPersonOwnerIntegrationTest' --tests '*EntityComponentOwnerLifecycleTest' --tests '*EntityModelBindingTest' --tests '*ControllerAnimationCacheIntegrationTest' --tests '*RuntimeRoamingOwnershipTest' --tests '*ExpressionArtifactPipelineTest' --tests '*AnimationStoreTest' --tests '*QualifiedStateReportTest'
```

Fixture 目录包含 `parts-a.pb`、`parts-b.pb`、`markers-a.png`、`surfaces-supported.pb` 及第一人称套件使用的变体。运行前提供同一份 fixture manifest 固定的输入包；本命令不生成输入。条件跳过不表示通过。

`EntityOwnershipIntegrationTest` 在真实 JNI Extract 的 locator 阶段注入普通异常。最终汇合应隔离故障、撤销几何并保留一次逻辑动作与合法投影；日志保留原异常。Required default 的异常仍传播。另一个场景用不同输入和固定种子比较串行与并行 owner。该场景核对原生姿态、随机序列、controller 历史和动作次数。多 pass 重复消费原生输出，并检查动作不重放。

生命周期套件覆盖完成未 get、普通字段可见性、等待中断、失败归约、绘制回调撤销、永久退休和旧安装拒绝。`EntityProjectionIntegrationTest` 联合运行实际本地会话投影、body、vehicle 和 arm，核对运行中／退出未 get／get 后的字段与后续求值。Standalone JVM 仅移除 Forge capability token 的 transformer 初始化，保留会话代码。

`FirstPersonOwnerIntegrationTest` 保留世界 renderer 的等待前主体读取回归。原 scheduler 排队后，主线程可取得同一 subject，动画状态读取仍拒绝。`GuiEntityExecutionTest` 另检查 GUI subject 在预留及返回未 join 时拒绝读取，只有对应任务和 join 后的主线程可读取。

编译套件分别记录 compile hold、program lease 和 access lease。Inbox 用例覆盖发布先、关闭先及并发竞争；执行中 watch 的退休用例检查 get 先于关闭。受控 JVM/JNI 结果不证明全部 Minecraft 输入或可选模组 API 的线程安全。

模型列表另有真实宿主分页入口，见[模型列表宿主](../tools/benchmark/README.md#模型列表真实宿主)。`CatalogPageFrameExecutionTest` 运行实际卡片任务，核对图片与绑定属于同一次执行、容量准入、输入恢复、共享 native 结果及首卡发布重入退出。注册替身不证明 GL；分页宿主不替代变化的鼠标/键盘、装备、朝向和 optional provider 的串并行等价 oracle。

独立分页宿主已在 Immersive Melodies `0.6.0+1.20.1-forge` 存在时观察 `update` 在 render thread 与 GUI worker 正常返回。该结果只证明 hook 可达；全局 melody progress、乐器装备输入、串并行状态等价及第三方等待仍未验证。其余七个 optional provider 的 present 路径未验证。

## Forge 观察

以下入口启动隔离 server/client，等待 fixture 可接纳后选模：

```powershell
.\gradlew.bat verifyEntityPresentationForge '-PentityPresentationScope=ownership' '-PentityPresentationEvidenceDir=<new-absolute-output-directory>' '-PentityPresentationFixtureDir=<absolute-fixture-directory>'
```

专用 agent 只进入验证宿主。事件包含实际 `renderLevel` 进入／返回、实体提交、worker 返回和 get 返回；事件日志只验证顺序，不能作为可见性 oracle。探针不注入宿主崩溃，也不为崩溃路径增加排空保证。

专用场景观察页面关闭、远处实体退出、世界重连和旧 arm owner 释放，并记录各自的 lease 关闭次数。worker 闸门另检查退休等待、输出失效和晚到安装资格。该闸门证明受控宿主顺序，不是 A12 的可见性测试。

专用 agent 在实际 transformed `renderLevel` 的 HEAD 调度后控制提前正常返回，并调用原生产 RETURN handler。用例覆盖提前消费、排队未绘制任务、部分提交失败被捕获、失败归约异常被捕获，以及独立 compiler 尚未结束时的返回。返回前断言 owner 已 get、调用范围已关闭。受控分支不证明每个可选模组的注入次序；具体未验证范围由[动画问题](../status/known-issues/animation.md#context线程与生命周期)维护。

## 成本与引用

无过滤 Java 套件的资源聚合边界用例仍失败，独立类执行通过；相同输入与运行环境的修改前 Git 基线也复现该失败。局部所有权结果不代表全量套件通过。

`EntityOwnershipBenchmark` 执行真实 owner、processor 和 controller。每个 Trial 先检查动画返回、逻辑动作次数及 JNI 输出消费，再进入计时。每个 Iteration 结束后，动作数应等于逻辑轮数。主矩阵为 1／64 owner 与同名、A→B→A、重播、重复缺失、动态缺失五种序列。`earlyConsumption` 单列提交／get 和单实体提前消费。

单 owner 在每个 Iteration 前通过退休／安装重建私有状态和时钟，并使用整数 partial tick，避免 float 大时钟合并相邻逻辑轮。动画 fixture 没有骨骼通道；主矩阵计量同步动画逻辑轮，额外场景包含异步姿态和提前消费。该负载不代表复杂动画或完整帧成本。

动态序列循环使用一万个预生成名称。该序列测量固定工作集，不能代表名称无限增长时的分配成本。`EntityOwnershipMemoryProbe` 另测正／负条目在 0／100／1000／10000 名称下的增长，并检查退休后条目与来源引用清空。可达字节不含共享 Animation 内容和资源来源图；跨 owner 共享的 key 只计一次。Map 清空可以保留容量数组。

成本命令、独立进程对照和计数方法见[基准入口](../tools/benchmark/README.md#实体动画所有权)。JMH 结果只适用于夹具工作量，不证明 FPS、GPU 或完整游戏性能。
