# 渲染已知问题

正常渲染语义见[渲染架构](../../architecture/rendering/overview.md)。

## 视觉与接入

- **未实现范围**：骨骼附着点渲染层目前仅接入右手持物；副手、头部、鞘翅、肩部与背包尚未完整接入。
- **已知局限**：透明面排序仅限单次模型绘制，Iris shadow pass 不排序，跨实体/跨模型/跨 draw call 顺序由上层决定；PBR 效果依赖 Iris、shader pack 与配套纹理，已烘培 tangent 不保证跨光影画面一致。
- **未验证缺口**：命名骨骼发光在真实游戏与 Iris/shader 下的表现（见[命名初值与动态覆盖](../../product-decisions/decisions/geometry-regions.md#bcnamed-bone-glow-is-overridable)）；Superb Warfare（仅局部观测过 0.8.7.1）的完整持枪动作/武器状态及移除该模组后的标准入口回退。

## GUI 取景

- **已知缺陷与局限**：目录卡片使用固定锚点与缩放、生成预览使用 idle 姿态，含根变换或偏移舞台的模型可能超出视口；纹理卡片按骨骼 AABB 与历史姿态并集动态取景，构图可能偏松且大幅动作后不回缩（见[展示实现](../../architecture/client-presentation/implementation/page-runtime.md)）。
- **未验证缺口**：悬停/聚焦往返、完整图源与动画矩阵、GL 物理删除、输入等价性与整帧成本（见[模型列表宿主](../../tools/benchmark/README.md#模型列表真实宿主)与[动画问题](animation.md#gui-与模型冷准备)）。

## 正确性与失败处理

- **已知缺陷**：`BakeModelOptions.force_translucent` 可能改变骨骼渲染分区却未同步调整透明深度准备条件（Java 主路径暂未启用，启用前需修复并补排序验证）。
- **已知局限**：序列化 baked 缓存载荷不自证当前机器 SIMD 能力匹配，读取时不复核几何浮点有限性与语义域；bake 对极端有限输入派生的法线/tangent 缺少完整结果域校验；native 仅检查 position 与 normal 矩阵有限性，不校验两者几何匹配；native 失败不自动切换输出路径，消费方抛异常不承诺回滚（见[顶点输出](../../architecture/rendering/implementation/vertex-output.md)）。
- **未验证缺口**：`NativeModelState` cube-info Java view 改为直返 native 数组后的首次非空获取与扩容运行回归。

## 重复消费与第一人称

- **已知缺陷**：隔离复验中曾出现 resource-pack reload 未在观察窗口内完成，根因尚未定位。
- **未验证缺口**：标准与 Superb Warfare 手部及初始背景在动态场景下的逐顶点/法线对照、完整资源准入、真实 reload/断连、玩家与 capability 替换、视角晃动/完整动作/禁用恢复/换模与 PBR 视觉；Native 提取成本缺少修复后的有效配对对照，局部关闭观察不证明全进程无泄漏（入口见[性能入口](../../tools/benchmark/README.md)与[帧路径成本](../../tools/benchmark/README.md#帧路径契约成本)）。

## 并发与性能

- **当前实现约束**：`ParallelExecutor`、translucent scratch、fallback 与共享 matrix scratch 当前按全局串行使用、不可重入，同一原地输出槽的 Extract/Render/换模/释放需串行执行（见[帧执行](../../architecture/rendering/implementation/frame-execution.md#输入输出与状态)）。
- **已知性能局限**：并行调度按不可拆分的 `CubeGroup` 数量切分，未按四边形数、PBR 或面剔除成本加权，复杂模型可能存在尾部负载不均；固定偏移要求按最大可见容量预留缓冲并清零空槽。

## 扩展接线

- **未实现范围**：`RegisterRenderStateModifierEvent` 与 `RenderStateModifier.apply()` 目前仅有接口与容器声明，生产路径尚未发布事件或调用 modifier（`@ParallelInvoke("entity")` 不代表已接通；见[接入事件表面](../../architecture/integration/implementation/host-adapters.md#已有事件表面)）。

## 待验证场景

完整游戏、跨平台与用户视觉验收尚未闭环：实机仍需覆盖同帧多 pass、背包与纸娃娃可变输出、第一人称 `irisShadow`、模型热切换、降级回退、透明与 PBR、非均匀缩放及各 locator 附着层（核对 locator 与当前 native 姿态一致、旧 Java view 不越界）；Direct 与 fallback 路径的视觉等价性需在 Vanilla、Iris 与 Blockbench 间对照确认。
