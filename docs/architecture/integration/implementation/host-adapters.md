# 宿主适配

本页说明[宿主适配与借用](../overview.md#宿主输入与绘制借用)在代码中的具体事件窗口与适配点；接入层本身不新增领域权威。

## 游戏侧接入

`CommonEvent` 与 `ClientSetupEvent` 负责建立公共基础设施和客户端接入，整体启动顺序归[运行设计](../../runtime/overview.md)管理。`CapabilityEvent` 将模型选择与动画负责方绑定到宿主实体上（服务端不执行客户端骨骼求值）；玩家登录、退出及服务端生命周期事件则直接转交给对应的会话或服务负责方。

## 已有事件表面

对外事件按总线和绘制阶段划分如下：

- **模组总线事件**：`api.model.v0` 与 `api.rendering.v0` 注册在 mod event bus 上。`RegisterModelLocatorEvent` 在 locator 负责方建立的窗口中提供针对特定 `ModelKind` 的注册能力，不授予完整骨骼修改权。
- **第三人称替换**：`ReplacePlayerRenderEvent` 把真实的默认绘制取消点交给 `CustomPlayerRenderer`——先取得有效渲染结果，在同一次借用内发布 hook，复核资格后再接管绘制。投射物与载具 mixin 同样依据实际替换结果决定是否取消默认绘制，不能仅凭模型分类推断已经消费。
- **模型与图层绘制**：`RenderModelEvent` 在有效 `ModelState` 提交模型前触发，`RenderLayerEvent` 包围默认图层遍历。取消事件只影响对应的本次绘制窗口，`PoseStack`、buffer 和 `GeoRenderData` 均不能跨 draw 保存。
- **第一人称绘制**：第一人称负责方在本地门禁、完整安装与当次部位准备均成立后，才发布 `SpecialPlayerRenderEvent`。Hook 返回后必须复核是否为同一个 installation、model、part 和 output revision；任一项失效都直接拒绝旧交接，不再重新求值。标准适配器只有在局部消费成功（含合法空输出或隐藏）后才取消对应的 `RenderArmEvent`，背景渲染不取消 `RenderHandEvent`。Consumer 抛出的异常原样向上传播，`finally` 只归还借用与恢复栈状态。

可选模组适配方面，`SWarfareCompat` 的手部注册只接受 metadata 版本 `0.8.7.1`（对应依赖构建 `0.8.7.1-c6d401e0e`），不按版本号大小推定兼容。兼容检查先于对象构造执行，注册失败会撤销已注册的半成品监听。读取可选模组 API 时的 linkage 异常捕获与 YSM 自身的绘制及 consumer 逻辑严格分开，后两者抛出的异常绝不能被降级逻辑吞掉。实际版本组合与视觉实测结果见[渲染状态](../../../status/known-issues/rendering.md#视觉与接入)。

当前 `RegisterRenderStateModifierEvent` 尚未接通，不能当作可用入口，详情见[扩展接线](../../../status/known-issues/rendering.md#扩展接线)。资源诊断的开关与观察边界见[资源诊断](../../../verification/resource-diagnostics.md)。
