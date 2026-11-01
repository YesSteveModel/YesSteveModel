# 渲染已知问题

正常语义见[渲染架构](../../architecture/rendering/overview.md)。

## 视觉与接入

- 命名骨骼发光已有语义回归和真实 JNI direct/fallback light、overlay 验证；实际游戏与 Iris/shader 视觉仍待验收。规则见[命名初值与动态覆盖](../../product-decisions/decisions/geometry-regions.md#bcnamed-bone-glow-is-overridable)。
- 透明排序限于单次模型 draw，Iris shadow 不排序；跨实体/模型/draw 的次序仍由上层决定。PBR 还依赖 Iris、shader pack 与 companion texture，baked tangent 不能证明效果一致。
- 骨骼附着点 layer 仅右手持物进入主链；副手、头部、鞘翅、肩部和背包仍未完整接入。
- Superb Warfare 仅验证 Minecraft 1.20.1 / Forge 47.4.3 / SW 0.8.7.1-c6d401e0e（metadata 0.8.7.1）组合。左右手、old/new 变换与拒绝/降级有自动化，隔离 Windows Forge 已观察 taser 双手接管和屏蔽后宿主恢复。四种持枪画面、完整动作/武器状态及 runtime classpath 完全移除后的标准入口仍未联合验收，不能外推更高版本。

## GUI 取景

模型列表现在绘制已交接的帧结果，renderer、GL 与 buffer 仍在 render thread。实际卡片任务和注册替身已有顺序、失败及退出测试；独立 LOCAL 分页宿主观察到注册、上传与释放入口及正常返回均在 render thread；全部图源、GL 物理删除、输入等价与整帧改善按[模型列表宿主](../../tools/benchmark/README.md#模型列表真实宿主)独立验证，不由线程数或主线程 CPU 推断。

目录卡使用固定锚点与缩放，生成图使用 idle；作者根变换或舞台可以超出视区，不承诺包住全部几何。纹理卡仍按几何取景，骨骼 AABB 与历史姿态并集可能使构图偏松，较大动作后不回缩。正常路径见[展示实现](../../architecture/client-presentation/implementation/page-runtime.md)。

隔离 Windows Forge 已复核 `wine_fox` 第一页十卡与十张生成图的可见/透明像素、动画来源和缓存尺寸；旋转设置与纹理卡极端变换有受控数学/JNI 回归。Hover/focus 往返、其他模型及完整动画仍需实机核验。旧自动取景截图与 HOT 对照不证明当前构图或主循环成本，见[冷准备性能](animation.md#gui-与模型冷准备)。

## 正确性与失败处理

- `NativeModelState` 的 cube-info Java view 在 native 扩容后仍引用旧数组，首次非空获取已实机复现越界。需修复并验证首次使用/扩容；Extract 成功不证明 view 可读。
- 整模/第一人称输出的失败撤销、同帧恢复和 locator 尾组已有真实 JNI 回归；native 失败不自动切换输出路径，consumer 异常不承诺回滚。完整交接规则见[顶点输出](../../architecture/rendering/implementation/vertex-output.md)。
- Serialized baked cache payload 不能独立证明 SIMD capability 匹配；读取未复核几何浮点有限性及语义域。Bake 对极端有限输入派生的 plane/tangent 也缺完整结果域验证。
- Native 只校验 position/normal matrix 数值有限，不验证二者匹配，该组合也缺端到端验证。
- `BakeModelOptions.force_translucent` 可能改变骨骼分区而未同步透明深度准备条件。Java 主路径不启用它；启用前需补最终排序验证。

## 重复消费与第一人称

- 标准/SW 手部与初始背景的局部输出已有受控交替消费、采样、metadata、失败恢复、合法空及 direct/fallback 代表性几何回归；独立矩阵 oracle 覆盖提取姿态。所有动态场景的最终顶点/法线尚未逐场景对照。
- 安装更替、兼容变体、输出关闭、共享 backing、hook 切换与借用退出已有受控 JNI 验证。完整资源准入、真实 reload/断连、玩家/capability 替换和可选联动宿主行为仍未覆盖；宿主放置、view bob、完整动作、禁用恢复、换模和 shader/PBR 视觉待验。
- 隔离 Windows Forge 已观察 default 的双臂、第三人称、纸娃娃、同 tick reset、屏蔽/限制叠加及精确 lease 关闭。逐 draw 轨迹覆盖缺根与有效空、hook 拒绝、Pending 退休、坏输出重提取及动作不重复；这些窗口不证明完整动作或任意模型。
- GUI、Arrow/Boat 注册接缝及真实 worker 退出已有局部生命周期证据，包括失败后保持游戏连接、借用结束后关闭和封口后拒绝迟到效果。它们不证明第三方自动 dispatch 或全部 optional 版本；验证入口见[宿主验证](../../verification/model-management.md)。
- 最近两次隔离复验的 resource-pack reload 未在观察窗口内完成，原因未定位。此前成功、render loop 运行和 worker 空闲均不能关闭该风险。
- Native 提取对照仍有配对分歧；旧输出样本未完整检查成功提交，修复后无有效配对，整体成本未验证。工作量不含动画 processor、动作提交和异步 join；局部存储/关闭观察不证明全进程无泄漏或任意模型规模。方法见[性能入口](../../tools/benchmark/README.md)。

## 并发与性能

- `ParallelExecutor`、translucent scratch、fallback 和共享 matrix scratch 不可重入，多个 Render 必须全局串行；同一原地输出槽的 Extract、Render、换模和释放也必须串行。借用边界见[帧执行](../../architecture/rendering/implementation/frame-execution.md#输入输出与状态)。
- 调度按不可拆分 `CubeGroup` 数分配，未按 quad、PBR 或剔除成本加权，复杂模型可能尾部不均衡。固定 offset 还要求按最大可见容量预留并清零空槽。

## 扩展接线

`RegisterRenderStateModifierEvent` / `RenderStateModifier.apply()` 只有声明与容器，生产路径未发布注册事件或调用 modifier；`@ParallelInvoke("entity")` 不证明已接通。现有事件窗口见[接入事件表面](../../architecture/integration/implementation/host-adapters.md#已有事件表面)。

## 待验证场景

完整游戏、跨平台和用户视觉尚未闭环。Minecraft 验证仍需覆盖同帧多 pass、inventory/paperDoll mutable 输出、第一人称 irisShadow、热切换、fallback、透明/PBR、非均匀缩放及各 locator layer；核对 locator 对应当前 native pose，旧 Java view 不越过 Extract/close。

Direct/fallback 的完整视觉等价需比较 Vanilla、Iris 与 Blockbench，并区分几何偏差与 shader/光照差异；[packed normal 约定](../../architecture/rendering/implementation/vertex-output.md)一致不能替代该验收。
