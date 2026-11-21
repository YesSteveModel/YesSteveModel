# 渲染已知问题

正常渲染语义见[渲染架构](../../architecture/rendering/overview.md)。

## 视觉与接入

- 命名骨骼发光已有语义回归和真实 JNI 的 direct 与 fallback 光照、overlay 验证，但实际游戏及 Iris、shader 下的视觉表现仍待验收。规则见[命名初值与动态覆盖](../../product-decisions/decisions/geometry-regions.md#bcnamed-bone-glow-is-overridable)。
- 透明面排序仅限于单次模型绘制，Iris shadow pass 不排序；跨实体、跨模型与跨 draw call 的绘制顺序仍由上层决定。PBR 效果还依赖 Iris、shader pack 和配套纹理，已烘培的 tangent 不能保证最终画面一致。
- 骨骼附着点渲染层目前只有右手持物接入主链；副手、头部、鞘翅、肩部和背包尚未完整接入。
- Superb Warfare 联动仅验证过 Minecraft 1.20.1、Forge 47.4.3、SW 0.8.7.1-c6d401e0e（metadata 版本 0.8.7.1）这一组合。左右手、新旧变换及拒绝与降级已有自动化测试，隔离 Windows Forge 已观察到 taser 双手接管以及屏蔽后恢复宿主模型。四种持枪画面、完整动作与武器状态，以及运行时完全移除该模组后的标准入口尚未联合验收，结论不能外推到更高版本。

## GUI 取景

模型列表目前已改为绘制已交接的帧结果，渲染器、GL 与 buffer 操作仍严格留在 render 线程。实际卡片任务和注册替身已有顺序、失败与退出测试；独立 LOCAL 分页宿主已观察到注册、上传、释放入口及正常返回都在 render 线程执行。全部图源、GL 物理删除、输入等价性与整帧改善按[模型列表宿主](../../tools/benchmark/README.md#模型列表真实宿主)独立验证，不能凭线程数或主线程 CPU 占比推断。

目录卡片使用固定锚点与缩放，生成预览图使用 idle 姿态；作者编写的根变换或舞台位置可能超出视口，系统不承诺包住全部几何。纹理卡片仍按几何动态取景，骨骼 AABB 与历史姿态的并集可能导致构图偏松，且大幅度动作后不会回缩。正常路径见[展示实现](../../architecture/client-presentation/implementation/page-runtime.md)。

隔离 Windows Forge 已复核 `wine_fox` 第一页十张卡片与十张生成图的可见与透明像素、动画来源及缓存尺寸；旋转设置与纹理卡片的极端变换也有受控数学与 JNI 回归。悬停（hover）与聚焦（focus）往返、其他模型和完整动画仍需实机核验。旧的自动取景截图与 HOT 对照不能证明当前构图或主循环成本，见[冷准备性能](animation.md#gui-与模型冷准备)。

## 正确性与失败处理

- `NativeModelState` 的 cube-info Java view 已改为直接使用 native 返回的数组，消除了旧实现扩容后仍指向原数组的问题。首次非空获取与扩容的运行回归仍需确认，且 Extract 成功不证明 view 一定可读。
- 整模与第一人称输出的失败撤销、同帧恢复和 locator 尾组已有真实 JNI 回归；native 失败不会自动切换输出路径，消费方抛异常也不承诺回滚。完整交接规则见[顶点输出](../../architecture/rendering/implementation/vertex-output.md)。
- 序列化后的 baked 缓存载荷不能独立证明当前机器的 SIMD 能力匹配，读取时也未复核几何浮点的有限性和语义域。Bake 阶段针对极端有限输入派生出的平面法线与 tangent 同样缺少完整的结果域校验。
- Native 侧只检查 position 与 normal 矩阵数值是否有限，不校验两者是否几何匹配，该组合也缺少端到端验证。
- `BakeModelOptions.force_translucent` 可能改变骨骼渲染分区，却未同步调整透明深度准备条件。Java 主路径当前未启用该选项；启用前须补齐最终排序验证。

## 重复消费与第一人称

- 标准与 Superb Warfare 手部及初始背景的局部输出，已有受控的交替消费、采样、metadata、失败恢复、合法空输出以及 direct 与 fallback 代表性几何回归，独立矩阵对照也已覆盖提取姿态。但所有动态场景的最终顶点与法线尚未逐场景比对。
- 安装更替、兼容变体、输出关闭、共享底层缓冲、hook 切换与借用退出已有受控 JNI 验证。但完整资源准入、真实 reload 与断连、玩家或 capability 替换以及可选联动宿主行为尚未覆盖；宿主手部放置、视角晃动（view bob）、完整动作、禁用恢复、换模与 shader 及 PBR 视觉仍待实机验收。
- 隔离 Windows Forge 已观察到默认模型的双臂、第三人称、纸娃娃、同 tick reset、屏蔽与限制叠加以及精确 lease 关闭。逐 draw 轨迹覆盖了缺根与有效空、hook 拒绝、Pending 退休、坏输出重提取及动作不重复，但这些短窗口不证明完整动作或任意模型。
- GUI、Arrow 与 Boat 的注册接缝及真实 worker 退出已有局部生命周期证据，包括失败后保持游戏连接、借用结束后关闭和封口后拒绝迟到效果。它们不证明第三方自动分发或全部可选模组版本；验证入口见[宿主验证](../../verification/model-management.md)。
- 最近两次隔离复验中，resource-pack reload 未在观察窗口内完成，原因尚未定位。此前的成功记录、渲染循环仍在运行或 worker 空闲都不能消除该风险。
- Native 提取对照仍存在配对分歧；旧输出样本未完整检查成功提交，修复后尚无有效配对，整体成本未验证。相关工作量不含动画 processor、动作提交和异步 join，局部存储与关闭观察也不证明全进程无泄漏或适用于任意模型规模。方法见[性能入口](../../tools/benchmark/README.md)。

Direct 预留地址、容量与 anchor 的真实 JNI 范围、canary、逐顶点等价性和失败不提交已有受控回归；GUI hook 撤销的旧 Prepared 不会再执行 reserve 或 advance，新安装也有成功提交对照。4 与 96 个 body group 的配对输出基准仅覆盖所测的同步 Render，不能替代上文的历史提取成本、GL、Embeddium 宿主或跨平台运行时评估；方法见[帧路径成本](../../tools/benchmark/README.md#帧路径契约成本)。

## 并发与性能

- `ParallelExecutor`、translucent scratch、fallback 与共享 matrix scratch 均不可重入，多次 Render 必须全局串行；同一原地输出槽上的 Extract、Render、换模与释放也必须串行。借用边界见[帧执行](../../architecture/rendering/implementation/frame-execution.md#输入输出与状态)。
- 并行调度目前按不可拆分的 `CubeGroup` 数量切分任务，未按四边形数量、PBR 或面剔除成本加权，复杂模型可能出现尾部负载不均。固定偏移还要求按最大可见容量预留缓冲并清零空槽。

## 扩展接线

`RegisterRenderStateModifierEvent` 与 `RenderStateModifier.apply()` 目前只有接口声明与容器，生产路径尚未发布该注册事件或调用 modifier；`@ParallelInvoke("entity")` 不代表已经接通。现有事件窗口见[接入事件表面](../../architecture/integration/implementation/host-adapters.md#已有事件表面)。

## 待验证场景

完整游戏、跨平台和用户视觉验收尚未闭环。Minecraft 实机验证仍需覆盖同帧多 pass、背包与纸娃娃的可变输出、第一人称 `irisShadow`、模型热切换、降级回退、透明与 PBR、非均匀缩放及各个 locator 附着层，并核对 locator 与当前 native 姿态一致、旧 Java view 不越过 Extract 或 close 边界。

Direct 与 fallback 路径的完整视觉等价性需要在 Vanilla、Iris 与 Blockbench 之间对比，并区分几何偏差与 shader 或光照差异；[packed normal 约定](../../architecture/rendering/implementation/vertex-output.md)一致不能替代完整的视觉验收。
