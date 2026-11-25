# 宿主适配

本页说明[宿主适配与借用](../overview.md#宿主输入与绘制借用)在代码中的事件窗口与适配点。

## 游戏侧接入

`CommonEvent` 与 `ClientSetupEvent` 建立公共基础设施与客户端接入，启动顺序归[运行设计](../../runtime/overview.md)。`CapabilityEvent` 将模型选择与动画负责方绑定到宿主实体（服务端不执行客户端骨骼求值）；玩家登录、退出与服务端生命周期事件直接转发至对应会话或服务负责方。

## 已有事件表面

对外事件按总线与绘制阶段划分：

- **模组总线事件**：`api.model.v0` 与 `api.rendering.v0` 注册在 mod event bus 上。`RegisterModelLocatorEvent` 在 locator 窗口内提供按 `ModelKind` 的注册能力。
- **第三人称替换**：`ReplacePlayerRenderEvent` 将默认绘制取消点交给 `CustomPlayerRenderer`——先取有效渲染结果，在同一次借用内发布 hook，复核资格后接管绘制。投射物与载具 mixin 同样依据实际替换结果决定是否取消默认绘制。
- **模型与图层绘制**：`RenderModelEvent` 在有效 `ModelState` 提交模型前触发，`RenderLayerEvent` 包围默认图层遍历。取消事件仅影响当次绘制，`PoseStack`、buffer 与 `GeoRenderData` 仅在当次 draw 内借用。
- **第一人称绘制**：
  - 本地门禁、完整安装与当次部位准备均成立后发布 `SpecialPlayerRenderEvent`。
  - Hook 返回后复核是否为同一 installation、model、part 与 output revision；任一项变更即拒收旧交接，不再重算。
  - 标准适配器仅在局部消费成功（含合法空输出或隐藏）后取消对应 `RenderArmEvent`，背景渲染不取消 `RenderHandEvent`。
  - Consumer 异常原样传播，`finally` 仅归还借用与恢复栈状态。

可选模组适配方面，`SWarfareCompat` 手部注册仅接受 metadata 版本 `0.8.7.1`（对应依赖构建 `0.8.7.1-c6d401e0e`），先于对象构造执行兼容检查，注册失败则撤销半成品监听。可选模组 API 读取的 linkage 异常捕获与 YSM 自身绘制及 consumer 逻辑分离，后两者异常直接传播、不被降级逻辑吞掉。实测版本组合与视觉状态见[渲染状态](../../../status/known-issues/rendering.md#视觉与接入)。

`RegisterRenderStateModifierEvent` 尚未接通，状态见[扩展接线](../../../status/known-issues/rendering.md#扩展接线)；资源诊断入口见[资源诊断](../../../verification/resource-diagnostics.md)。
