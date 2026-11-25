# 共享准备与发布的落实

本页落实[Pending 与 Ready](../overview.md#seam-contract)、[宿主交接](../overview.md#ready-与宿主交接)及[物理关闭](../overview.md#引用撤销与物理关闭)，说明当前实现中的独占完成权与宿主采用点。

## Flight 与准备依赖

- **`ModelRenderTargetCache`**：分开维护 Pending 注册表与 Ready 缓存，键均含发布角色，`ResourceLease.isCurrent` 复核完整发布身份。所有取得模式返回 `ResourceLease` 持有对应 Flight interest 或已完成资源。每个 Flight 有独立 `Dispatch`，本地玩家 interest 仅提升调度优先级，不改资源键或工作 owner；Flight 结束按对象身份移出 Pending 索引，避免误删替换后的新 Flight。
- **`PreparedTargetCache`**：维护 loader 内部共享准备依赖：
  - 准备 interest 成功后转为 target 的 pin；短期提权仅传递调度优先级，不传递取消权。
  - `Production` 单独协调输入交接；调度节点被领取不代表 producer 已接管输入，迟到 producer 仅清理自有输入与候选。
  - `PreparedTarget` 统一持有全部声音首块与媒体前缀资格。Producer 在输入借用期内批量读取并校验，失败不发布半成品。Target 共享同一 `SharedBuffer`，关闭时只撤销自身引用与 pin，由最后一个 pin 关闭共享准备；已交付字节与已接纳音频播放继续独立有效。
  - `ModelRenderTargetLoader` 按实际内容绑定选择存储区域；共享准备键与 Pending 键均含发布角色与存储区域。声音使用保存发布身份（普通播放沿普通策略，intrinsic 播放走必需默认槽位）。静态声明身份贯通请求、准备与制品，实体安装使用同代 `SubjectBinding`。

## 完成与采用

- **固定输入预览**：复用同一注册表但使用独立工作身份，普通 Ready 查找与普通 Pending 不命中；最后消费者退出即撤销保留，不进 unused 缓存。`ClientPreviewGenerator` 仅观察自身 `lease`，不直接发布或关闭 loader 候选。
- **预就绪候选与宿主发布**：后台 worker 交付预就绪候选（pre-Ready candidate）。等待渲染线程交接期间，Candidate guard 持有退休清理权，owner 退出即刻拒收。`HostTexturePublisher` 提供无状态注册、上传与采用接缝，回流运行时由精确状态门禁决定提交。必需默认模型采用见[唯一发布](../default-model.md#seam-contract)。
- **Unused 保留**：普通 target 最后消费者退出后进入 unused 缓存，再次申请时移出。`ModelRetentionCount` 限制无消费者完整 target 保留上限（0 表示不保留），不计 active target 与 `lease`；超限撤销最旧缓存引用（默认值依据见[设备默认](../../../../product-decisions/decisions/device-quality.md#bcdevice-derived-configuration-defaults)）。
- **启动预热**：`StartupModelWarmup` 仅观察并关闭自身申请的普通 `lease`，Pending 不提前释放，Ready 不新增 pin 或持久化屏障；后续真实消费者仍按相同规则取得独立使用（接入见[运行组合](../../../runtime/implementation/lifecycle-and-publication.md#启动预热的接入)）。

## 关闭责任

| 对象 | 关闭与回收边界 |
|---|---|
| `ModelRenderTarget` | 清理查询引用、声音保留与准备 pin，对 `PublishedTextureBinding` 执行一次逻辑释放。将完整资源封装为单份 `Resources` 快照，显式关闭即使抛错也先摘除快照，关闭后 getter 拒绝访问，清理失败统一汇总。`StateIdentity.preparation` 仅存 `PreparedTarget.identityToken`，避免间接保活完整动画定义与 store。 |
| `PreparedSet` | 构造成功后接管 `AnimationStore`，最后一个 pin 关闭时发起 `AnimationStore` 退休。宿主已采用纹理物理销毁归 Minecraft 与 Iris；`CustomTextureManager` 仅管独立 GUI 纹理。 |
| 共享 `GeoModel` | 交付后底层 native geometry 为共享不可变资源；Target、候选拒绝与消费者关闭仅撤销自身引用与诊断登记，后台存储独立持有 native handle 与 metadata。共享后 `NativeBakedModel.close` 拒绝直接物理销毁，改由最后强引用消失后的 Cleaner 回收（未共享前构造失败仍支持显式回滚）。 |
| `ModelContent.PreparationInputs` | 固定当次准备读取能力：远端探测与离线打开排队前保留，loader 与共享 producer 各持独立保留；排队被拒或未启动取消立即归还，已运行 worker 退出时归还。远端准备建不可变已接收字节视图供纹理 reload 强持有，内存结果不依赖可选持久化。 |
| 内部 Future 与 `RemoteStore` | 缓存探测、离线打开与远端 body 取得由内部 Future 串联，每阶段交接由资源方兜底清理。`RemoteStore` 将公开结果与底层传输结束解耦：取消公开结果只撤销采用并请求停止传输，不提前归还活动输入。本地 `ContentBinding` 与 `ModelContent` 仅提供强保留，不暴露共享关闭权。JNI token 与借用规则见[JNI 与内存](../../../native-runtime/implementation/jni-and-memory.md)。 |

## 动画解析与保留

- **输入解耦**：存储区 `AnimationInputStore` 独立提供未绑定动画与程序输入，AOT 准备与其他消费者各自直接申请（来源与组合保证见[派生物](../../storage/derived-artifacts.md#独立身份与原子集合)）。消费者各自保活来源引用，AOT 淘汰、其他消费者退出或持久化失败不影响自身使用；局部读取批次仅持自有 channel 借用。
- **查询视图与缓存判定**：`AnimationStore` 统一管理本地与 fallback 解析、完整名称范围与执行资源，发布后名称表不可变；target 暴露的查询视图绑定固定 store 与原有失败门禁。单份程序索引仅记录正面事实，确认函数缺失须核对完整有效来源范围。解析结果含 `Animation`、生效 fallback 来源与可缓存标记：
  - 完整名称范围内稳定成功可缓存；有效本地与 fallback 范围均证实不存在时可缓存缺失。
  - I/O 错误、空载荷、损坏内容、失败 fallback、取消与 OOM 均沿原有失败边界传播，不转为缺失缓存。
  - 已校验内容不依赖磁盘源文件持续可读；fallback 标记归当次解析上下文，不写入共享 `Animation`。
- **唯一关闭入口**：`AnimationStore` 是解释执行与 AOT 共享资源的唯一关闭入口（契约见[动画资源所有权与退休](../overview.md#动画资源所有权与退休)）。Loader 仅在构造作用域申请保护，缓存仅存保留引用；固定输入预览按真实工作申请保护，worker 取消以实际退出为归还点。最后一个 pin 触发退休，最后一份真实使用保护归还时同步执行唯一清理，清理异常沿该调用栈传播。
- **代码入口映射**：
  - `RenderTargetExpressionModuleFactory.createStore` 构造完整 `AnimationStore`；工厂仅管构造，服务关闭不经工厂封口。
  - `ModelRenderTarget.animationStore` / `armAnimationStore` 提供身份与执行入口；`animations` / `armAnimations` 仅提供只读查询视图。
  - `AnimationStore.prepare` / `openExecution` 分别签发构造保护与私有执行保护。
  - `CommonAsset` 持有声音资源，函数与事件程序绑定归 `AnimationStore`。
- **Controller 查询缓存**：只保存派生查询状态，不记忆加载失败、不持 store 关闭权。兼容绑定更换时刷新查询视图并清空绑定相关缓存，已有播放沿[播放历史](../../../animation/controllers-and-playback.md#解析缓存与播放寿命)延续；使用结束时清缓存、播放与来源引用。该缓存不设容量上限，随访问名称增长，共享内容长期寿命由 `AnimationStore` 统一约束。

## 图片内容与 CPU 像素

- **编码内容与像素引用**：`PreviewStore.EncodedPreview` 仅通过首次完整解码建立，多引用共享同一封闭字节且各自结束使用。`PreviewPixels` 区分引用与同步上传借用，最后引用退出且全部借用结束时释放底层像素。`PreviewPixels.Owner` 关闭时封口并退休保留，等活动借用归还后释放；owner 拒收时由解码方关闭 buffer。
- **宿主交接与释放**：预览操作在宿主接纳前持有 CPU 像素。`CustomTexture` 构造时接管像素与独立 reload 内容，批次关闭不影响已接纳上传；注册被拒、上传失败或显式关闭均释放对应能力。同步 reload 受原有串行宿主门禁约束，解码像素在上传结束后立即关闭。CPU 像素不做字节限额排队等待；无显示用途的图片在完整校验后立即释放像素。
