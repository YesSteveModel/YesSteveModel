# 共享准备与发布的落实

本页落实[Pending 与 Ready](../overview.md#pending-与-ready)、[宿主交接](../overview.md#ready-与宿主交接)及[物理关闭](../overview.md#引用撤销与物理关闭)，定位当前独占完成权与采用点。

## Flight 与准备依赖

`ModelRenderTargetCache` 分开 Pending registry 与 Ready cache。`ResourceLease` 持 exact Flight interest 或完成资源；Flight 自有 `Dispatch`，合法本地玩家 interest 只提升同一工作，不更改 key 或 work owner。完成路径按对象 identity 移除索引，不能覆盖 replacement。

`PreparedTargetCache` 保存 loader 内的依赖登记。独立 interest 成功后转成 target pin；短期提权连接只传播优先级，不传播取消权。`Production` 单独仲裁输入交接，调度节点已领取不代表 producer 已接管输入。迟到 producer 只处置自己的输入和候选。

`PreparedTarget` 同时拥有全部声音首块与媒体前缀资格。Producer 在准备输入的借用期内完成批量读取与验证，失败不发布部分集合。每个 target 从准备值保留同一 `SharedBuffer`，关闭时撤销自身引用与 pin；最后 pin 关闭共享准备。已交付的 bytes 继续有效。已接纳 playback 自持读取能力，不随任一 pin 取消。

`ModelRenderTargetLoader` 按实际内容绑定选区；prepared dependency 与 pending key 包含区域，避免同表示换来源后加入旧区持久生产。静态声明身份贯通 request、准备与 artifact，实体安装使用同代 SubjectBinding。

## 完成与采用

Worker terminal 交付 pre-Ready candidate。Candidate guard 在等待 host 时保留退休处置权；owner 退出即拒绝候选，不能依赖后续 tick 才清理。`HostTexturePublisher` 提供无状态注册、上传和 adoption seam；结果回到 runtime 由 exact-current 门禁提交。Required default 采用路径见[唯一发布](../default-model.md#私有构造与唯一发布)。

普通 target 最后 consumer 退出后进入 unused 保留；再次 acquire 离开 unused。`ModelRetentionCount` 约束无消费者完整 target 的数量，零表示不保留；active target 与 lease 数不计入该数量。超限只撤最旧 cache 引用。配置默认依据见[设备默认](../../../../product-decisions/decisions/device-quality.md#bcdevice-derived-configuration-defaults)。

## 关闭责任

`ModelRenderTarget` 收束固定从属的运行状态与 animation cleanup，并对 `PublishedTextureBinding` exactly-once logical release。Minecraft/Iris 继续拥有 adopted texture 的物理关闭；`CustomTextureManager` 只拥有 standalone GUI texture，不能保存模型 candidate 或完成状态。

`GeoModel` 交付后，native geometry 成为共享不可变 backing。Target、candidate rejection 和消费者关闭只撤销自己的引用与诊断登记。后台存储独立持有 native handle 和 metadata，不保留 `GeoModel` 或 target。共享后 `NativeBakedModel.close` 拒绝物理销毁，最后强引用消失后由 Cleaner 回收；未共享的构造失败仍可显式回滚。该规则不改变其他 `NativeObject` 的固定 owner 关闭语义。

远端准备桥接层持独立输入，内部 future 登记前取消时在登记后补取消。结果完成但接收者已取消时，调用本次加载的 discard；输入随内部 future 真正终态释放。Received source 的读取先取独立 token，再在锁外验证；关闭只拒绝后续读，不破坏已经开始的合法读。

本地 `ContentBinding` 与 `ModelContent` 只提供强保留，不提供共享 close 权。JNI token 与 borrowed view 的释放规则见[JNI 与内存](../../../native-runtime/implementation/jni-and-memory.md)。

## 动画解析与保留

`AnimationStore` 拥有 local／fallback 解析、完整名称范围和原有 failure gate。完整工厂产物与共享发布后的名称不可修改；可变构造期 store 不提供缺失证明。Target 的 consumer view 是稳定解析来源，固定精确内容、绑定、fallback 和 failure gate；每轮 read batch 只是借用视图，不创建新来源。

解析结果包含 Animation、本次有效 fallback 来源与可缓存性。完整名称范围中的稳定成功可以缓存；只有有效 local 与 fallback 范围均证实不存在，才可缓存缺失。I/O、空载荷、确定性坏内容及失败 fallback 沿原资源失败边界裁决，不成为缺失证明或稳定替代结果。取消和 OOM 不转换为缺失。已验证内容可继续使用，不要求源文件保持可读。Fallback 标记属于解析上下文，不写入共享 Animation。

Controller 缓存只有派生查询状态，不拥有加载失败记忆或 store 的关闭权。兼容绑定更换与使用结束撤销缓存条目和来源引用，不关闭其他 owner 的 store／backing；既有合法播放按[播放历史](../../../animation/controllers-and-playback.md#播放状态)独立保留。缓存不设容量上限；成功引用和负缓存 key 随不同名称数量增长，失效与结束边界负责撤引用。

## 图片内容与 CPU 像素

`PreviewStore.EncodedPreview` 只由首次完整解码建立；外部原始副本不继承资格。独立引用可共用封闭 bytes，各引用只结束自身能力。`PreviewPixels` 区分引用与同步上传借用，最后引用退出且借用结束后释放 backing。`PreviewPixels.Owner` 在 shutdown 时封口并退休保留；活动借用归还后释放 backing。Owner 拒绝接管时，解码方仍负责关闭本次 buffer。

预览 operation 在 host 接纳前持有像素。`CustomTexture` 构造时取得本次像素能力和独立 reload 内容；batch 的关闭不破坏已接纳上传。拒绝注册、上传失败与显式关闭释放对应能力。同步 reload 只受原串行 host 门禁约束，本次解码像素在同步上传结束后关闭。CPU 像素不按字节接纳或等待；GPU texture 仍归原 host owner。无显示用途在完整验证后释放像素，不保留潜在显示用途。
