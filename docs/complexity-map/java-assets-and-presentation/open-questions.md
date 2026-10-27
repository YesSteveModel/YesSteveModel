# 因果缺口与差异

本页只维护 AP 子系统的未闭合判断；产品选择仍以[产品决策树](../../product-decisions/README.md)为 Root Authority，下面的实现事实不自动产生例外。

## Q-04

**用途驱动图片策略的效果与成本证据未闭合。** [图片产品边界](../../product-decisions/decisions/image-policy.md)要求保持整体视觉效果；codec、阈值及 raw 回退/legacy source-local failure 的不同处置尚未证明满足该目标。处理顺序与格式规则见[转换与导出](../../architecture/asset-pipeline/implementation/conversion-and-export.md#图像处理的位置)。

后续调整应比较复用、缩放、峰值内存与重编码开销，但不能仅以体积缩小证明整体视觉保真，也不能由“成功编码”推导出制品成本已改善。已有 JPEG、细长小 PNG、RGBA32、不同用途尺寸和 Android 策略需要组成最小验证矩阵，检查分辨率、色彩/透明边界、整体视觉、制品大小与峰值内存；同时分别覆盖 raw 的失败回退和 legacy 的 source-local failure。对应机制为 AP-03 与 NR-17。

## 其他证据缺口

AP-04/05 的集中规则、零缓存 lease 交接与协议反馈已有自动化；分级准入的工作量与可见延迟、真实 FULL 冷延迟、30 秒初值、Busy 帧成本与视觉仍需实机或对应采样。AP-05 的真实 3D 选模收益、AP-08 的 offscreen 画面与 cold/warm 成本、真实页面反复翻页和 texture 回收仍未验证。这些缺口限制收益与可靠性结论，不把现存机制自动降为 Historical。
