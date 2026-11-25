# 实体动画所有权验证

本页记录实体动画所有权的 L1 回归与 L3 宿主验证入口；执行交接与退出规则见[实体生命周期](../architecture/animation/entity-lifecycle.md)，缓存寿命见[解析缓存与播放寿命](../architecture/animation/controllers-and-playback.md#解析缓存与播放寿命)。

## L1 确定性回归

以下纯 L1 单测无需 `YSM_FIRST_PERSON_FIXTURES` 或伪宿主环境，默认随 `./gradlew test` 运行，也可定向执行：

```powershell
.\gradlew.bat test --tests '*EntityExecutionLifecycleTest' --tests '*EntityQueuedExecutionTest' --tests '*EntityModelBindingTest' --tests '*EntityRequestRelationTest' --tests '*ClientSharedFactWindowTest' --tests '*RuntimeRoamingOwnershipTest' --tests '*ExpressionArtifactPipelineTest' --tests '*AnimationStoreTest' --tests '*QualifiedStateReportTest' --tests '*ClientModelBootstrapTest.realDisconnectFollowsInlineAndGuiReadersAndKeepsRequiredResources'
```

- **覆盖范围**：实体执行生命周期与退休、排队交接、模型绑定与失败归约、请求关系、共享事实窗口、Roaming 所有权、表达式编译管线、统一 `AnimationStore` 寿命及真实服务断连收尾。
- **验证边界**：原依赖 `Unsafe`/自定义字节码 `ClassLoader` 伪造 `Minecraft` 宿主的集成套件已按[验证分层架构](policy.md#验证分层架构)退役；L1 测试使用受控输入，不证明 Minecraft 渲染线程交接或第三方模组线程安全。

## L3 真机与宿主观察

原 `verifyEntityPresentationForge` 自动化探针与专用 agent 已移除。真实 `renderLevel` 执行顺序（含正常与提前返回）、GUI 模型列表卡片渲染、实体退场与重连释放以及可选模组 hook（如 Immersive Melodies），改由 L3 `runServer` + `runClient` 手工与视觉验收覆盖。分页宿主参考[模型列表宿主](../tools/benchmark/README.md#模型列表真实宿主)，未闭合缺口见[动画已知问题](../status/known-issues/animation.md#context线程与生命周期)。

## 成本与引用

原依赖伪宿主夹具的 `EntityOwnershipBenchmark` 与 `EntityOwnershipMemoryProbe` 已退役。实体所有权相关的现行基准入口见[持续性能基建](../tools/benchmark/README.md#实体动画所有权)；受控 JMH 夹具结果仅适用于对应工作量，不外推为完整游戏 FPS 或 GPU 表现。
