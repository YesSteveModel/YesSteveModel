# 共享准备与发布的落实

本页落实[Pending 与 Ready](../overview.md#seam-contract)、[宿主交接](../overview.md#ready-与宿主交接)及[物理关闭](../overview.md#引用撤销与物理关闭)，说明当前实现中的独占完成权与宿主采用点。

## Flight 与准备依赖

`ModelRenderTargetCache` 将 Pending 注册表与 Ready 缓存分开维护，两者的键都包含发布角色，`ResourceLease.isCurrent` 还会复核完整的发布身份。所有取得模式都直接返回 `ResourceLease`，用来持有对应 Flight 的 interest 或已完成资源。每个 Flight 拥有独立的 `Dispatch`；合法的本地玩家 interest 只提升同一份工作的优先级，不改变资源键或工作 owner。Flight 结束时严格按对象身份移出 Pending 索引，不会误删已经替换上去的新 Flight。

`PreparedTargetCache` 负责维护 loader 内部的共享准备依赖：

- 独立的准备 interest 成功后转为 target 的 pin；短期提权连接只传递调度优先级，不传递取消权。
- `Production` 单独协调输入交接：调度节点领取任务并不代表 producer 已经接管输入，迟到的 producer 只清理自己的输入与候选。
- `PreparedTarget` 统一持有全部声音首块与媒体前缀资格。Producer 在准备输入的借用期内完成批量读取与校验，失败时不发布半成品集合。每个 target 从准备值持有同一份 `SharedBuffer`，关闭时只撤销自身引用与 pin，由最后一个 pin 负责关闭共享准备；已经交付的字节继续有效，已接纳的音频播放持有自己的读取能力，不受任何 pin 取消影响。
- `ModelRenderTargetLoader` 根据实际内容绑定选择存储区域；共享准备依赖键与 Pending 键都同时包含发布角色和存储区域，避免跨角色或同一表示切换来源后错误加入旧工作。声音使用保存发布身份：普通播放沿用普通策略和对应的失败包装，intrinsic 播放走必需默认槽位。静态声明身份贯通请求、准备与制品，实体安装使用同代的 `SubjectBinding`。

## 完成与采用

固定输入预览复用同一套资源注册表，但使用独立的工作身份，普通 Ready 查找和普通 Pending 请求不会命中它；最后一个消费者退出后立即撤销保留，不占用 unused 缓存。`ClientPreviewGenerator` 只观察自己的 `lease`，不再直接发布或关闭 loader 候选。

后台 worker 完成后交付的是预就绪候选（pre-Ready candidate）。在等待渲染线程交接期间，Candidate guard 保留退休清理权：只要 owner 退出就立刻拒绝该候选，不必等到下一个 tick 才清理。`HostTexturePublisher` 提供无状态的注册、上传和采用接缝，结果回到运行时后，由精确匹配当前状态的门禁决定是否提交。必需默认模型的采用路径见[唯一发布](../default-model.md#seam-contract)。

普通 target 在最后一个消费者退出后进入 unused 保留缓存，再次被申请时移出 unused。`ModelRetentionCount` 限制无消费者完整 target 的保留数量（设为 0 表示不保留），正在被消费的 active target 和 `lease` 数量不计入其中；超出上限时只撤销最旧的缓存引用。该配置默认值的依据见[设备默认](../../../../product-decisions/decisions/device-quality.md#bcdevice-derived-configuration-defaults)。

`StartupModelWarmup` 只观察并关闭它自己申请的普通 `lease`：处于 Pending 时不提前释放，变为 Ready 后也不新增 pin 或持久化屏障。它不接管候选、共享 `AnimationStore`、宿主纹理或后台任务，后续真实消费者仍按相同的 Flight 与 Ready 规则取得独立使用。接入方式与一次性判定见[运行组合](../../../runtime/implementation/lifecycle-and-publication.md#启动预热的接入)。

## 关闭责任

各核心资源对象的关闭边界划分如下：

- **`ModelRenderTarget` 与 `PreparedSet`**：`ModelRenderTarget` 关闭时清理自身查询引用、声音保留与准备 pin，并对 `PublishedTextureBinding` 执行恰好一次的逻辑释放（logical release）。`PreparedSet` 构造成功后接管完整的 `AnimationStore`，并在最后一个 pin 关闭时发起 `AnimationStore` 退休，不再分别关闭程序、本地与 fallback 索引或执行后端。宿主已采用纹理的物理销毁仍归 Minecraft 与 Iris；`CustomTextureManager` 只管理独立 GUI 纹理，不能保存模型候选或完成状态。
- **单份资源快照与清理**：`ModelRenderTarget` 将完整资源封装为单份 `Resources` 快照；显式关闭即使在清理过程中抛错也会摘除该快照，关闭后再调用资源 getter 会明确拒绝。`StateIdentity.preparation` 只保存 `PreparedTarget.identityToken`，防止旧 target 或安装凭据间接保活完整的动画定义和 store；清理时先摘除引用，再汇总资源与宿主销毁过程中产生的失败。
- **共享几何 `GeoModel`**：`GeoModel` 交付后，底层的 native geometry 成为共享不可变资源。Target、候选拒绝和消费者关闭都只撤销自己的引用与诊断登记；后台存储独立持有 native handle 和 metadata，不反向保活 `GeoModel` 或 target。共享后的 `NativeBakedModel.close` 会拒绝直接物理销毁，改由最后强引用消失后的 Cleaner 回收；尚未共享前的构造失败仍支持显式回滚。这条规则不改变其他 `NativeObject` 固定 owner 的关闭语义。
- **准备输入与远端字节**：`ModelContent.PreparationInputs` 固定本次准备的读取能力。远端探测和离线打开在排队前先保留输入，loader 接收后建立自己的保留，共享 producer 另持一份；排队被拒或未启动就取消时立即归还，已运行的 worker 则在真正退出时归还。Runtime wrapper 沿用同一读取失败门禁。远端内容会为准备建立不可变的已接收字节视图，纹理 reload 来源强持有该视图，来源用途退出时只撤销自身引用；内存结果不依赖可选持久化是否成功。
- **内部阶段与外部边界**：资源 manager 的缓存探测、离线打开和远端 body 取得仍通过内部 Future 串联，但每个阶段的交接都由资源方兜底——拒收时归还原候选，取消时关联到已启动工作，迟到的 Ready 只关闭本次产物。`RemoteStore` 将对外公开的结果与底层真实传输结束分开：取消公开结果只撤销采用并请求传输停止，不提前归还仍在活动中的输入，消费者也不会拿到这些内部阶段的完成权。本地 `ContentBinding` 与 `ModelContent` 只提供强保留，不暴露共享关闭权。JNI token 与借用视图的释放规则见[JNI 与内存](../../../native-runtime/implementation/jni-and-memory.md)。

## 动画解析与保留

存储区域的 `AnimationInputStore` 独立提供未绑定的动画与程序输入，AOT 准备与其他输入消费者各自直接申请使用，查询与生成之间没有必须先走哪条执行路径的前置限制（来源与组合保证见[派生物](../../storage/derived-artifacts.md#独立身份与原子集合)）。输入消费者只保活自己的来源引用：AOT 缓存淘汰、另一消费者退出或持久化任务失败，都不会撤销这份输入使用。局部读取批次只持有它自己的 channel 借用。

`AnimationStore` 统一管理本地与 fallback 解析、完整名称范围和执行资源，target 暴露的查询视图（query view）只绑定固定 store 与原有失败门禁，对外返回解析结果。store 发布后名称表不可修改，构造期的可变索引留在内部。单份已加载的程序索引只证明其中记录的正面事实，要确认某个函数缺失，仍需核对完整的有效来源范围。每轮读取批次只持有局部读取阶段和必要保护，不引入新的来源或失败权威。

动画解析结果包含 `Animation`、本次生效的 fallback 来源以及可缓存性标记：

- 在完整名称范围内得到的稳定成功可以缓存；只有当有效的本地与 fallback 范围都证实不存在时，才可缓存缺失。
- I/O 错误、空载荷、确定性损坏内容以及失败的 fallback 仍走原有资源失败边界，不会变成缺失证明或稳定替代结果；取消和 OOM 也不会转为缺失。
- 已经校验通过的内容可以继续使用，不要求磁盘源文件持续可读。Fallback 标记属于当次解析上下文，不写进共享的 `Animation` 对象。

`AnimationStore` 是解释执行与 AOT 共享动画资源的唯一关闭入口（职责与使用保护见[动画资源所有权与退休](../overview.md#动画资源所有权与退休)）。Loader 只在实际构造作用域内申请保护，构造完成后不再经由候选或 Pending 与 Ready `lease` 接力，缓存只保存资源保留引用。固定输入预览按真实工作申请保护，worker 被取消时以实际退出作为归还点。Store 的存活由 target、实体和在途工作各自的引用维持，最后一个 pin 触发退休，最后一份真实使用保护归还时同步执行唯一清理。

具体入口映射如下：
- `RenderTargetExpressionModuleFactory.createStore` 构造完整的 `AnimationStore`。
- `ModelRenderTarget.animationStore` 与 `armAnimationStore` 提供身份与执行入口，而 `animations` 与 `armAnimations` 只提供只读查询视图。
- `AnimationStore.prepare` 与 `openExecution` 分别签发构造保护和私有执行保护，底层执行后端不再作为 target 或实体的并列公开所有权入口。
- `CommonAsset` 继续持有声音资源，函数与事件程序的绑定归 `AnimationStore`。工厂只管构造，服务关闭不经由工厂封口或等待所有 store；清理抛出的异常沿执行清理的调用栈传播。

Controller 缓存只保存派生的查询状态，既不记忆加载失败，也不持有 store 的关闭权。兼容绑定更换时会刷新查询视图并清掉绑定相关缓存，已有播放按[播放历史](../../../animation/controllers-and-playback.md#解析缓存与播放寿命)延续；使用结束后清除缓存、播放和来源引用。该缓存不设容量上限，成功引用与负缓存键会随访问到的名称增长，共享动画与程序的长期保留仍由 `AnimationStore` 寿命统一约束。

## 图片内容与 CPU 像素

`PreviewStore.EncodedPreview` 只能通过首次完整解码建立，外部传入的原始副本不继承这项资格。多个独立引用可以共用同一份封闭字节，每个引用只结束自己的使用能力。`PreviewPixels` 严格区分引用与同步上传借用，只有当最后一份引用退出且所有借用都结束时，才释放底层像素内存。`PreviewPixels.Owner` 在关闭时封口并退休现有保留，等活动借用全部归还后释放内存；如果 owner 拒绝接管，解码方仍须负责关闭本次 buffer。

预览操作在宿主接纳前持有 CPU 像素。`CustomTexture` 构造时取得本次像素能力与独立的 reload 内容，因此批次关闭不会破坏已经接纳的上传。注册被拒、上传失败或显式关闭都会释放对应能力。同步 reload 只受原有的串行宿主门禁约束，本次解码得到的像素在同步上传结束后立即关闭。CPU 像素不按字节限额接纳或阻塞等待，GPU 纹理仍归原宿主 owner；如果图片没有显示用途，则在完整校验后立即释放像素，不为了潜在展示而滞留内存。
