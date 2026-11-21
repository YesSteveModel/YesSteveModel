# 实体动画所有权验证

本页记录实体动画所有权的验证入口。执行、交接与退出责任见[实体生命周期](../architecture/animation/entity-lifecycle.md)，缓存规则见[播放状态](../architecture/animation/controllers-and-playback.md#解析缓存与播放寿命)。

## 受控回归

无需配置 `YSM_FIRST_PERSON_FIXTURES`，默认运行以下纯粹 L1 单元测试：

```powershell
.\gradlew.bat compileJava test --tests '*EntityExecutionLifecycleTest' --tests '*EntityQueuedExecutionTest' --tests '*EntityModelBindingTest' --tests '*EntityRequestRelationTest' --tests '*ClientSharedFactWindowTest' --tests '*RuntimeRoamingOwnershipTest' --tests '*ExpressionArtifactPipelineTest' --tests '*AnimationStoreTest' --tests '*QualifiedStateReportTest' --tests '*ClientModelBootstrapTest.realDisconnectFollowsInlineAndGuiReadersAndKeepsRequiredResources'
```

原依赖 `Unsafe.allocateInstance(Minecraft.class)`、自定义字节码 `ClassLoader` 与 `YSM_FIRST_PERSON_FIXTURES` 的伪宿主集成测试（`FirstPersonOwnerIntegrationTest`、`EntityOwnershipIntegrationTest`、`EntityProjectionIntegrationTest`、`EntityComponentOwnerLifecycleTest`、`ControllerAnimationCacheIntegrationTest`、`GuiEntityExecutionTest`、`CatalogPageFrameExecutionTest`、`SubjectSampleOwnerIntegrationTest`）以及对应受控基准（`EntityOwnershipBenchmark`、`EntityOwnershipMemoryProbe`）已按[验证分层架构](policy.md#验证分层架构)退役；真实实体与绘制宿主交接改由 L3 手工/视觉验收覆盖，以下保留此前受控观察记录的边界口径：

`EntityOwnershipIntegrationTest` 在真实 JNI Extract 的 locator 阶段注入普通异常，校验最终汇合能隔离故障、撤销几何输出，同时保留一次逻辑动作与合法状态投影，并在日志中保留原异常；required default 的异常则继续向上传播。另一个场景使用不同输入和固定随机种子对比串行与并行 owner，逐项核对原生姿态、随机序列、controller 历史和动作次数，并在多 pass 重复消费原生输出时确认动作不重放。

生命周期套件覆盖任务已完成但未 get、普通字段可见性、等待中断、失败归约、绘制回调撤销、永久退休和旧安装拒绝。`EntityProjectionIntegrationTest` 联合运行实际本地会话投影、body、vehicle 与 arm，核对运行中、退出未 get 以及 get 后的字段值与后续求值。独立 JVM 中仅移除 Forge capability token 的 transformer 初始化，其余会话代码完整保留。

`FirstPersonOwnerIntegrationTest` 包含世界渲染器等待前的主体读取回归：原 scheduler 排队后，主线程虽可拿到同一 subject，但读取动画状态仍被拒绝。`GuiEntityExecutionTest` 另核对 GUI subject 在预留期间及返回尚未 join 时拒绝读取，只有对应任务与 join 后的主线程才能读取。

`FirstPersonOwnerIntegrationTest` 还覆盖原任务 inline 执行后沿后续 shadow 或 world context 消费、故障 sibling 的完整交接，以及真实会话失效时的撤权。当 GUI hook 在 Direct 预留前切换选择时，旧 Prepared 不得执行 reserve 或 advance，新安装完成后则有成功提交对照。

`ClientModelBootstrapTest.realDisconnectFollowsInlineAndGuiReadersAndKeepsRequiredResources` 组合了普通 inline、GUI worker 和真实服务 disconnect：普通实体完成交接时，GUI 读者仍看到原 ACTIVE 会话；直到 GUI join 后才结束连接并返回 Demand、音频与 Catalog 收尾调用，同时 required default 继续可用（该 fixture 不含活动播放或额外 Demand 负载）。该用例在受控 JVM 中运行真实服务，不证明 Forge logout 接线或任意嵌套队列泵。

编译套件分别记录 compile hold、program lease 和 access lease。Inbox 用例覆盖先发布、先关闭及并发竞争，执行中 watch 的退休用例验证 get 早于关闭。受控 JVM 与 JNI 结果不能证明全部 Minecraft 输入或可选模组 API 的线程安全。

模型列表另有真实宿主分页入口，见[模型列表宿主](../tools/benchmark/README.md#模型列表真实宿主)。`CatalogPageFrameExecutionTest` 运行实际卡片任务，核对图片与绑定出自同一次执行、容量接纳、输入恢复、共享 native 结果以及首卡发布时的重入退出。注册替身不能证明真实 GL；分页宿主也不能替代变化的鼠标和键盘输入、装备、朝向与 optional provider 的串并行等价对照。

独立分页宿主已在 Immersive Melodies `0.6.0+1.20.1-forge` 存在时验证 `update` 在 render 线程与 GUI worker 上均正常返回。这仅证明 hook 可达；全局 melody progress、乐器装备输入、串并行状态等价及第三方等待仍未验证，其余七个 optional provider 的存在路径也尚未验证。

## Forge 观察

原 `verifyEntityPresentationForge` 自动化探针与专用 agent 已随验证重构移除；新增宿主执行顺序与真机联动观察改由 L3 `runServer` + `runClient` 手工/视觉验收覆盖，以下保留此前受控观察记录的边界口径：

原专用 agent 只进入验证宿主，通过事件日志记录实际的 `renderLevel` 进入与返回、实体提交、worker 返回和 get 返回，仅用于验证执行顺序，不能作为可见性证明。探针不注入宿主崩溃，也不为崩溃路径附加排空保证。

专用场景覆盖页面关闭、远处实体退出、世界重连与旧 arm owner 释放，并记录各自的 lease 关闭次数；worker 闸门另检查退休等待、输出失效和晚到安装资格。该闸门验证的是受控宿主顺序，不是 A12 的可见性测试。

专用 agent 在实际 transformed `renderLevel` 的 HEAD 调度之后控制其提前正常返回，并触发原生产的 RETURN handler。用例覆盖提前消费、排队未绘制任务、部分提交失败被捕获、失败归约异常被捕获，以及独立 compiler 尚未结束时的返回，并在返回前断言 owner 已完成 get 且调用范围已关闭。受控分支不证明所有可选模组的注入次序；具体未验证范围由[动画问题](../status/known-issues/animation.md#context线程与生命周期)维护。

## 成本与引用

无过滤的全量 Java 测试套件说明保留于此；原 `EntityOwnershipBenchmark` 与 `EntityOwnershipMemoryProbe` 已随伪宿主夹具退役，以下保留历史工作量设计口径供对照参考：

`EntityOwnershipBenchmark` 运行真实的 owner、processor 与 controller。每个 Trial 先核对动画返回值、逻辑动作次数和 JNI 输出消费，再开始计时；每个 Iteration 结束后断言动作数等于逻辑轮数。主矩阵包含 1 与 64 个 owner，以及同名、A→B→A、重播、重复缺失和动态缺失五种序列；`earlyConsumption` 单列提交、get 与单实体提前消费。

单 owner 场景在每个 Iteration 前通过退休再安装重建私有状态和时钟，并使用整数 partial tick，避免大 float 时钟合并相邻逻辑轮。所用动画 fixture 不含骨骼通道：主矩阵计量同步动画逻辑轮，额外场景才包含异步姿态与提前消费，因此该负载不代表复杂动画或完整帧成本。

动态缺失序列循环使用一万个预生成名称，测的是固定工作集，不能代表名称无限增长时的分配成本。`EntityOwnershipMemoryProbe` 另测正、负缓存条目在 0、100、1000、10000 个名称下的增长情况，并核对退休后条目与来源引用是否清空。统计的可达字节不包含共享 Animation 内容与资源来源图，跨 owner 共享的 key 只计一次，且 Map 清空后允许保留底层容量数组。

成本测量命令、独立进程对照与计数方法见[基准入口](../tools/benchmark/README.md#实体动画所有权)。JMH 结果仅适用于夹具工作量，不证明 FPS、GPU 或完整游戏性能。
