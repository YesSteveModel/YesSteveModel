# 宿主适配

本页定位[宿主适配与借用](../overview.md#宿主输入与绘制借用)的实际窗口；接入不新增领域 authority。

## 游戏侧接入

`CommonEvent`/`ClientSetupEvent` 建立基础设施和客户端接入，启动顺序归[运行设计](../../runtime/overview.md)。`CapabilityEvent` 将模型选择与动画 owner 关联到实体；服务端不运行客户端骨骼求值。登录、退出和 server 生命周期事件转交原 session/service owner。

## 已有事件表面

`api.model.v0` 与 `api.rendering.v0` 使用 mod event bus。`RegisterModelLocatorEvent` 在 locator owner 建立的窗口提供特定 `ModelKind` 注册能力，不授予完整骨骼修改权。

`ReplacePlayerRenderEvent` 将真实取消点交给 `CustomPlayerRenderer`：获得有效结果、同次借用内发布 hook、复核并接管。Projectile/vehicle mixin 也依据实际 replacement 结果，不能从模型分类推断已消费。

`RenderModelEvent` 位于有效 ModelState 的模型提交前；`RenderLayerEvent` 包围默认 layer 遍历。取消只影响对应窗口，`PoseStack`、buffer 和 `GeoRenderData` 不能跨 draw 保存。

第一人称 owner 在本地门禁、完整安装与当次 part 准备成立后发布 `SpecialPlayerRenderEvent`。Hook 后复核同一 installation、model、part 和 output revision；失效拒绝旧交接，不重新求值。标准 adapter 只有局部消费成功（含合法空/隐藏）才取消对应 `RenderArmEvent`，背景不取消 `RenderHandEvent`。Consumer 异常原样传播，finally 只归还借用和栈。

`SWarfareCompat` 的手部注册只接纳 metadata 版本 `0.8.7.1`，对应依赖构建 `0.8.7.1-c6d401e0e`，不按版本大小推定兼容。资格先于构造，失败撤销半注册。可选 API 读取的 linkage catch 与 YSM 绘制/consumer 分开，后两者异常不能被降级吞掉。实际版本组合和视觉证据见[渲染状态](../../../status/known-issues/rendering.md#视觉与接入)。

`RegisterRenderStateModifierEvent` 尚不能当作已接通入口，见[扩展接线](../../../status/known-issues/rendering.md#扩展接线)。资源诊断的开关和证据边界见[资源诊断](../../../verification/resource-diagnostics.md)。
