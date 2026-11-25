# Molang 声明集成验证

本页记录 Molang 声明打包矩阵、运行时/解释器回归、Provider 审计与成本测量的可执行入口；语义规则见[声明规则](../architecture/animation/molang-declarations.md)与[GUI 解释规则](../architecture/animation/molang-interpreter.md)，实机缺口见[动画问题](../status/known-issues/animation.md)。

## 发布打包与语义矩阵

以下命令在项目根目录执行，均在独立 JVM 中校验真实打包/重定位制品与冷暖加载契约：

```powershell
.\gradlew.bat processorTest verifyBuiltinModelIndex verifyExpressionRuntimeBundle verifyExpressionArtifactWarmLoad verifyRequiredDefaultResidency
.\gradlew.bat verifyPackagedDeclarationMatrix verifyPackagedCallableComposition verifyExpressionSemanticMatrix verifyMolangArrowSemanticMatrix verifyWeddingControllerWarmLoad
```

| 任务 | 覆盖范围 | 产物目录 |
|---|---|---|
| `verifyPackagedDeclarationMatrix` | 仅以发布 JAR 作 `annotationProcessor`，独立 JVM 覆盖真实缺依赖、版本范围、冲突、迟到提交、12 种声明签名与类加载隔离 | `build/verification/declaration-matrix/run-*` |
| `verifyPackagedCallableComposition` | 独立扩展 JAR 中状态组件与静态 ACTION 合并到同 namespace、冷暖复用及跨 mod 冲突拒载 | `build/verification/callable-composition/run-*` |
| `verifyExpressionSemanticMatrix` | direct/fallback 冷启动与磁盘暖加载、旧 ABI 拒用及声明身份变更隔离 | `build/verification/expression-semantic-matrix` |
| `verifyMolangArrowSemanticMatrix` | 普通 classpath 与重定位发布 JAR 下的箭头语义、容量边界与冷暖加载一致 | `build/verification/arrow-semantic-matrix` |
| `verifyExpressionRuntimeBundle` / `verifyExpressionArtifactWarmLoad` | 依赖解析、relocation、provider linkage 与跨 JVM 冷暖加载及旧 ABI 拒用 | Gradle 测试报告 |
| `verifyBuiltinModelIndex` / `verifyRequiredDefaultResidency` / `verifyWeddingControllerWarmLoad` | 内置模型完整物化（零 replacement）、内置默认 FULL 驻留复用、花嫁酒狐控制器冷暖分支对照 | Gradle 测试报告 |

## L1 运行时与解释器回归

以下纯 L1 单测默认随 `./gradlew test` 运行，无需 `YSM_FIRST_PERSON_FIXTURES`（原依赖 `Unsafe`/伪 `Minecraft` 宿主的集成套件已退役，真机边界改由 L3 覆盖）：

```powershell
# 组件作用域、声明投影与队列/watch
.\gradlew.bat test --tests '*ComponentScopeTest' --tests '*DefaultDeclarationProjectionTest' --tests '*RenderTargetStateIdentityTest' --tests '*ExpressionArtifactPipelineTest'

# 准备复用、源准备与 Roaming
.\gradlew.bat test --tests '*PreparedTargetCacheTest' --tests '*AnimationStoreTest' --tests '*ClientModelBootstrapTest' --tests '*SetIsolationCalibrationTest' --tests '*ModelProgramInventoryTest' --tests '*ModelProgramPreparationTest' --tests '*DefaultAnimationInputTest' --tests '*RuntimeRoamingOwnershipTest' --tests '*QualifiedStateReportTest' --tests '*ExpressionBindingTest'

# GUI 解释与统一 store 驻留
.\gradlew.bat test --tests '*EntityInterpreterRuntimeTest' --tests '*InterpretedRoamingStateTest' --tests '*GuiPlayerAnimationCacheTest' --tests '*GuiInterpreterResidencyTest'

# 主体引用与箭头
.\gradlew.bat test --tests '*SubjectArrowIntegrationTest' --tests '*MolangFrontendTest' --tests '*HostSemanticContractTest' --tests '*ClientSessionRuntimeConnectionTest'
```

- **验证边界**：上述回归使用受控 subject、效果接收器与测试接缝，不替代真实 Forge 世界中的可选模组运行时、多 pass once-only、声音设备播放或整帧性能验收；JVM 退出遵循[直接放弃策略](../architecture/runtime/overview.md#jvm-进程退出)，驻留与退休证据仅在运行期采集。

## Provider 契约审计

- **覆盖与接纳测试**：`ProviderContractCoverageTest` 将已接纳的 JVM 成员与 `provider-contract-coverage.tsv` 逐项比对（新增、变更或取消接纳成员需同步该表）；`MolangDeclarationProcessorTest`、`MolangExtensionAdmissionTest`、`PureDeclarationOracleTest`、`MathIntrinsicBindingTest`、`DomainActionTest`、`SoundInstanceManagerTest`、`PublicCapabilitiesTest` 分别覆盖注解处理、扩展接纳、intrinsic 资格与动作/声音窗口。
- **开发期审计器**：`ProviderContractAudit`（及 `ProviderContractAuditTest`）位于 test source set，用于检查非有限浮点违约、禁止位置 null、发布后篡改与过期/跨线程借用，不插桩生产代码。

## 成本测量

- **微基准与探针**：`verifyMolangExecutionBenchmark` 与长期 JMH 覆盖标量执行（见[标量执行基准](../tools/benchmark/README.md#molang-标量执行)）；`verifyMolangPayloadAllocation` 计量 tail/record/效果载荷分配；`SubjectArrowCostBenchmark` 与 `ArrowCapacityCostProbe` 覆盖箭头 derive、样本冻结、网络投影及编译阶段峰值；全量默认编译基准见[动画编译性能基准](animation-bake-benchmark.md)。
- **验证边界**：各 heap pool 峰值、JMH 分配与 retained heap 分属不同维度，不可直接相加或外推为游戏帧率与长期 RSS；评估重访与退休需先确认旧 `AnimationStore` 达到 `CLOSED` 且 `uses=0`。
