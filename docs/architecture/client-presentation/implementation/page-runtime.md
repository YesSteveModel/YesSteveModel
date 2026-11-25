# 页面 runtime 的落实关系

本页说明[页面作用域](../overview.md#运行作用域与所有权)、[页面结果交接](../overview.md#页面需求与展示消费)和[选择导航](../overview.md#选择与资源需求)的代码映射。通用资源获取与用途准备见[需求协调](../../model-management/demand/overview.md)。

## 页面与资源 owner

页面流程生命周期由 `ModelScreen` 锚定在创建时的世界实例上：

- 记录世界身份与回收状态，仅弱引用世界与当前页面。
- 打开或切换界面时由 `ScreenEvent.Opening` 校验身份：子页切换保留当前流程，离开流程或收到 `LevelEvent.Unload` 立即终止。因为卸载事件早于 Minecraft 替换 `level`，卸载触发后即使旧 `level` 仍可见也不再接纳新流程。
- 页面移除时关闭卡片、图片 batch 与私有预览实体，并显式清除 widgets、focus 与 narration 引用（`clearWidgets()` 不解除后两类引用）；导航取消则恢复仍在显示的来源页记录。
- 预览 `FakePlayer` 始终绑定创建时的世界，不通过 `setLevel` 迁移。清理全由页面流程自身承担，[JVM 退出](../../runtime/overview.md#jvm-进程退出)时不等待页面清理。

展示与图片组件分工如下：

| 组件 | 当前职责 |
|---|---|
| `CatalogModelCardState` | 保存展示状态、GUI 预览实体、图片视图与展示资格 |
| `CatalogCardImages` | 管理预览图策略与装饰图片展示状态 |
| `PageImages` | 管理当前页面图片 batch、结果 slot 与独立使用权（use） |
| `ClientAssetRepository` / `ResidentImages` | 前者签发不透明 `ImageIdentity`，后者关联驻留内容并统计消费者计数 |
| `CustomTextureManager.Registration` | 提供精确注册项的存活查询、再激活与释放；`CustomTextureManager` 负责保留期与物理释放（模型 adopted texture 不入此表） |

客户端另有两类独立于单次模型安装的通用结构：`AnimatableCacheUtil` 按访问过期或世界卸载清理通用展示实体，不拥有模型卡私有预览实体；`EntityLoadEvent` 客户端等待列表按实体 ID 保存回调，在实体到达、离开、过期或世界卸载时清理。

## 页面主流程

### 帧计划与卡片调度

- **Tick 与页面身份**：`ClientModelService.tick()` 通过协调器调度世界实体 `EntityModelDemand`，由实体负责方决定安装；模型列表 GUI 用途不在 tick 中直接安装。模型列表页面身份由分类、搜索词、pack、页码与有序条目内容共同确定。
- **Scope 切换与 render 门禁**：替换 scope 时先建新 scope 再关旧 scope，避免迟到关闭误关新 scope。每次 render 前先推进展示策略并领独立 Ready lease；未获名额时原操作保留 Ready anchor。Ready 旁路允许发现两次 tick 间刚就绪的完整资源，旁路 miss 不影响已接纳获取任务。页面关闭先停新消费，待本帧借用结束后清理自有输入。
- **`CatalogPageFrame` 帧内交接**：
  - 循环游标每帧最多授予“线程池最大容量 + 1”张卡片的新准备名额（被拒卡片同样推进游标）；已安装卡片的动画不受名额限制。
  - 共享同一图片的卡片在提交前选定唯一准备方；该卡片的冷前景、背景与获准完整绑定合并在同一任务中执行。
  - 除首张卡片外先提交后台执行；主线程同步执行首张卡片并立即注册有效图片，再 join 全部已接纳任务并注册其余有效图片。即使中途抛错，也在本帧完成全部已接纳任务交接，不跨帧滞留在途批次。
- **Eager 模式切换**：Eager 模式下不建 preview batch 也不消费预览图；非 eager 模式按需建独立预览需求。切换配置只影响后续推进，不强行取消已接纳补图任务，跳过预览图不记缺图或报错。

### 页面图片准备与驻留

- **单次准备与发布**：`PageImages.advanceAcquisition(allowed)` 先查驻留使用权，未命中再取输入并在获准联网后提交 batch。对同一 `ImageIdentity`，`Image.claimPreparation()` 仅签发一次 CPU 准备能力：卡片执行方同步调 `prepare()`，主线程经 `Image.publish()` 消费。单张卡片发布失败保存在对应 slot，后续帧不再重做且不影响其他 slot；非帧调用方经 `advance()` 串行复用同一准备能力。仅完成宿主注册并可渲染的图片才算 Ready；读取纹理或状态本身不触发后台工作。
- **解码与所有权转移**：冷前景、背景与头像的读取、校验与解码全在准备能力内完成，远端下载不解码。准备能力独立获取输入：提交被拒由提交方关闭，接纳后由任务或 `Prepared` 对象关闭。`CustomTexture` 构造时接管像素并独立保留重载内容，首次 load 上传后（无论成败）立即归还 CPU 能力，batch 仅关闭自身输入。页面关闭仅结束自身使用权，迟到获取结果由原负责方收尾。
- **驻留失效**：`ResidentImages` 仅按精确注册项关联，不持页面、会话、RGBA 像素或独立计时器。`CustomTextureManager` 物理释放纹理或 `CustomTexture` 触发 reload/close 时仅撤销对应精确注册项，过时回调只删原 entry；reload 后重准备属宿主重建，不计驻留命中。

### 预览生成与卡片停用

- **预览生成与 backing 回收**：`ClientPreviewGenerator` 在既有操作数与每 tick 接纳上限内直接派发任务。`PreviewPixels.Owner` 负责 backing 回收与串行 reload 门禁，实际使用期由独立引用与同步借用保护；关闭时先拒新借用并标记待回收，待活动借用归还后释放 backing。
- **轻量解释路径**：目录卡片、纹理候选卡与自动补图 `FakePlayer` 走轻量解释路径，按需读动画集输入，不构建 GUI 编译闭包也不用 FULL 字节码；中央完整动画预览走 FULL 与 AOT 路径，两条路径共享业务 context 与声明、独立初始化私有状态。
- **卡片停用与恢复**：`CatalogModelCardState.deactivate()` 先清展示标志并阻断新消费；若处于帧借用期，撤销指派与清除动画引用推迟到本帧任务交接后执行，同帧内重新获准也待下次正常推进再接纳。重新获准后由页面显式建指派与安装 owner，帧在 join 后直接读实体 binding 实际状态；私有预览实体仅在永久关闭时回收。
- **退出与复核**：模型使用退出后清私有执行态并归还借用，共享动画与程序随 [AnimationStore](../../model-management/client-resources/overview.md#动画资源所有权与退休) 保留。准备与消费均要求当前展示资格与 Matching 同时有效，并在回调 hook 返回后复核；预览假实体 world tick 不推进资源门禁。

## 选择与显示的分离

页面提交模型选择意图给 `ClientDemandCoordinator`，仅消费结构化反馈：协调器处理远端准备、偏好读取、发送资格与有限等待，`ClientProtocolGateway` 同步发送已准备命令。`LOCAL` 模式直接应用并保存；业务会话模式下客户端仅等待服务端权威同步，不乐观修改模型、纹理或 Roaming 状态。

- **部分失败容错**：已有合法初值时 FULL 准备失败不阻断获准命令发送；偏好查询失败回退空初值发送。发送失败、服务端拒绝与实体安装失败分别反馈，关闭页面不取消已发起选择。
- **本地查询降级**：收藏任一页查询失败则整表按空显示；本地选择记录查询失败回退普通 `builtin:default`。查询失败均记 `DEBUG` 日志，仅 `DebugAnimationScreen.isEnabled()` 开启时向聊天栏提醒。

进入纹理子页的 `openTexturePage()` 流程：

- **前置与入口内容**：先复核世界、等待状态与目标，只查匹配纹理的 FULL Ready。点击卡片解析所点条目，点击主体按钮读权威状态与当前请求纹理，不用世界中的 Retained 替代。
- **先借后切**：来源页保住入口 lease，接收页申领独立 lease 并以非阻塞方式安装中央预览，全部接管后再切换页面。任一环节失败（忙碌、构造失败、门禁变更或取消）即刻释放本次使用权，后续就绪不自动恢复旧导航。
- **实体与交互状态**：中央预览实体走 FULL 路径与私有状态，内容未变的重初始化中保持存活，页面移除时回收。纹理候选卡走 `PageScope` GUI 准入，键鼠切换与 resize 保持连续交互起点。卡片名称不消费选择等待状态，仅左侧玩家名等待真实玩家 Matching 安装；纹理子页经父页 Adapter 提交选择。

## 本地目录进度

`LoadingStateScreen` 与 `PlayerModelScreen` 在单次 render 中读取同类进度快照，经 `CatalogProgressText` 映射为阶段文案或 `n/N` 计数：HUD 保持用户配置位置并展示标题、状态文字与进度条；模型页分页居中、进度文字右对齐右下角。无扫描活动时不绘制。进度事实归 [Catalog](../../model-management/catalog/overview.md#扫描进度投影)，两处不读取 `ClientModelService` 的 runtime 数量。

## GUI 取景

- **构图与旋转**：`CatalogModelButton` 与 `PreviewRenderHost` 共用固定构图映射。自动截图输出透明背景并固定 idle 姿态；清单关闭预览旋转且指定非空非 idle 自定义 GUI 动画时自动截图开启旋转，其余情况与实时卡片按清单约定渲染。
- **帧输入冻结**：`GuiFrameInput` 在渲染前固定 partial tick、inventory context、朝向、装备快照（独立拷贝）及 hover/focus 状态。后台任务绑定前应用输入并在 `finally` 恢复预览实体；主线程复用同一输入直接消费 `GeoRenderData`，不再重复 update 或推进 controller。中央 FULL 预览、纹理候选与世界实体沿用各自入口。
- **骨骼取景**：`CatalogTextureButton` 的 `GuiModelFraming` 在预渲染后、body 与 layer 输出前读取骨骼几何计算取景。`GuiModelView` 按安装身份记录有效帧几何包络，更换安装时清空；非法帧不写包络，无法构造有限绘制矩阵则跳过本帧。取景计算不改 native 属性或法线，buffer delegate 仅在同步绘制期间借用。

## 宿主动作与诊断

`CatalogCardActions` 快捷操作：

- `Ctrl+右键`：复制模型发布位置，不查权限、不触发资源准备。
- `Ctrl+左键`：仅在集成服务器环境且有已验证本地来源绑定时打开逻辑目录；builtin、远端模型或缺少来源证明时不执行。宿主打开失败不写入模型失败记忆，`void` 返回仅表示调用发出。

卡片错误提示仅展示分类摘要，不因按住 Shift 暴露宿主路径或异常栈；作者、许可与缺图指引照常展示。真实环境 Ctrl 点击与宿主启动尚未完成实测，其余验证见[展示设计的验证入口](../overview.md#实现与验证入口)。
