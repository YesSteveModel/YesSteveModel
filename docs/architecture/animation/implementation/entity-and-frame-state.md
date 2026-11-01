# 实体状态的落实关系

本页落实[完整安装与替换](../entity-lifecycle.md#完整安装与替换)、[结束边界](../entity-lifecycle.md#暂停reset-与-retire)及[时间推进](../overview.md#时间推进与观察)。

## 实体级所有权

`AnimatableEntity` 是生命周期根。`EntityModelBinding` 保存指派、request 与唯一完整安装；`EntityExecutionLifecycle` 保存执行阶段、永久退休事实和单个待交接任务。Lifecycle 将同一实体的执行门禁绑定到 `EntityExpressionRuntime`，使完整安装、表达式调用与实例读取共享执行权。`EntityExpressionRuntime` 拥有组件 holder、实例及源投影结果。`AnimatedGeoModel` 拥有 entity 级 `BoneAttribute`；`GeoModelState` 拥有各输出槽。共享 target/module 通过自身 lease 保留。

```mermaid
flowchart TB
    Root["AnimatableEntity：生命周期根"] -->|"协调"| Binding["EntityModelBinding：指派与安装"]
    Root -->|"协调"| Life["EntityExecutionLifecycle：任务与结束"]
    Life -->|"对应 get 后交回主线程"| Runtime["EntityExpressionRuntime：私有执行态"]
    Binding -->|"独立 lease 保活"| Target["共享 target / module"]
    Root -->|"持有"| Output["GeoModelState：私有输出"]
    Root -.->|"弱端点登记"| Demand["ClientDemandCoordinator：需求 scope"]
```

图中实线标明责任或保留，虚线表示弱登记；目录层级不表达这些持有关系。需求 scope 的取得与接管由[需求协调](../../model-management/demand/overview.md)拥有。

## 绑定与切换

`tryInstallation()` 只读取主线程调度事实。待交接任务、绘制借用或候选窗口占用即返回 Busy；完成但未经过对应 `Future.get` 的任务仍占用。普通安装入口不等待。GUI 帧路径先取得独立 Ready lease，再预留 `GuiTask`；任务通过 `GuiExecution.install` 消费 lease 并完整绑定，不调用公共指派或资源取得入口。

候选按显式 target 构造，不通过 current getter 取得候选。`EntityModelBinding` 与 expression runtime 在同一独占安装窗口发布；候选期合法查询仍见完整旧安装。GUI 任务期间主线程不能查询实例。`ModelRenderTarget.isStateCompatible()` 提供精确资源与 module 身份判断，根补充 owner、健康与串行窗口条件。

`prepareModelBinding/prepareResourceBinding` 私有验证兼容候选。激活按显式 target 通知 holder 并移交状态关闭权；清理不从可变 current getter 反查旧资源。失败记忆由 binding 按 exact request/content 保存；`reloadGeoModel()` 清除此 owner 的记忆。

根的 reset、reload、retire 为 final，完整清理不依赖角色 hook 调用 `super`。普通异步执行先取得对应 `get`；GUI 执行要求原 `GuiTask.join`，不能被公共入口隐式接回。Worker 保留原失败；兼容激活失败只标记待结束，主线程在完整交接后归约并清理。绘制回调内结束立即撤资格，清理延后到局部借用退出。Retire 永久终结 owner，reset/reload 才允许后续使用。

`reserveGuiTask` 仅接纳 GUI purpose，预留时封住实例；`run` 将许可交给该任务的唯一执行者。任务退出后许可仍保留，`join` 完成对应 `get` 后归还；等待中断不跳过交接。提交拒绝调用 `reject`，不构造已接纳任务。许可不采用全局 ThreadLocal，也不放开公共生命周期、绘制及其他 entity。

`DeclarationComponentScope` 对成功 begin 的前缀逆序执行 end，保留主失败并汇总清理失败。失败 begin 自行释放未移交的借用；组件执行期间生命周期入口不可重入。失败回到主线程后才撤销安装资格并清理。

`canAcceptPlayback`、`canEvaluate`、`canPrepare` 和 `canConsume` 分别映射[查询契约](../entity-lifecycle.md#查询与借用)。`BlockReason` 从同源事实派生，不另存资格。Ready 不等于安装、查询也不触发取得。

## 时间与更新频率

`AnimatableEntity` 独占主采样 tick 与实际主更新时间基线。新 holder 的首次样本独立建立，tracker 延续主体历史。全部 begin 成功后先接纳跨度，再分发 tick/update；失败不回滚或重放该跨度。Observation、限频或姿态复用不移动基线。

`AnimationParallelTicker` 弱引用登记异步 owner，强引用仅覆盖当次任务。宿主离开当前 level 或 removed 时移除登记并 retire；显式 retire 同样永久终结 owner。任务覆盖完整 finally，只有对应 `get` 后才清除待交接句柄；等待中断后继续交接，再恢复中断标志。

## Canonical 与 mutable 输出

各槽隔离提取结果，仍共享实体时间线。第一人称 owner 的 LeftArm、RightArm 与 Background 独占输出；body/arm 只投影命名 Roaming 值，不共享可变对象。局部根、context 复用与同步借用见[帧执行](../../rendering/implementation/frame-execution.md#java-预调度与-context)。

## 输入与副作用边界

`EntityExpressionRuntime` 在组件求值外拥有完整效果轮；表达式清理不关闭 component、module 或安装 lease。效果提交与输入门禁见[Molang 落实关系](molang-runtime.md#binding-与-invocation)。Live Minecraft/optional-mod 读取仍有[线程与验证缺口](../../../status/known-issues/animation.md)。

GUI 帧任务先应用主线程固定的 `GuiFrameInput`，再执行完整绑定和 `GuiExecution.update`，退出时恢复私有主体的临时输入。绘制只消费本帧结果，不重复求值。共享 renderer、dispatcher 与 GL 状态仍归主线程；此许可不扩展到世界实体或 FULL 中央预览。
