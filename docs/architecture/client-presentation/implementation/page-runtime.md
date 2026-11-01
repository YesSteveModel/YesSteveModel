# 页面 runtime 的落实关系

本页落实[页面作用域](../overview.md#运行作用域与所有权)、[页面结果交接](../overview.md#页面需求与展示消费)和[选择导航](../overview.md#选择与资源需求)。共同取得和用途准备机制见[需求协调](../../model-management/demand/overview.md)。

## 页面与资源 owner

`ModelScreen` 为页面流程保留世界身份与退休状态，只弱引用世界和当前页面。`ScreenEvent.Opening` 校验身份；子页切换保留流程，离开流程或 `LevelEvent.Unload` 终止流程。卸载发生在 Minecraft 替换 level 前，不能借旧 level 尚可见而接纳新流程。

页面移除关闭卡片、batch 与预览实体，还清除 widgets、focus 与 narration 引用；仅 `clearWidgets()` 不解除后两类保留。导航取消时恢复仍显示的来源页记录。`FakePlayer` 始终绑定创建时世界，不通过 setLevel 迁移。

`CatalogModelCardState` 只保存展示状态、GUI entity、图片投影与资格，不拥有 future、retry 或完成回调。`CatalogCardImages` 集中 preview 政策与装饰图片投影；`PageImages` 拥有图片 batch、slot 与独立 use。`ClientAssetRepository` 签发不透明 `ImageIdentity`，`ResidentImages` 关联驻留内容并集中消费者计数。`CustomTextureManager.Registration` 提供精确注册的存活、重新激活与释放；manager 承担原保留期限和物理释放。模型 target 的 adopted texture 不进入此 registry。

`AnimatableCacheUtil` 是客户端共享的通用展示实体 cache，访问过期或世界卸载清理；它不拥有模型卡的私有预览实体。`EntityLoadEvent` 的客户端等待列表按实体 ID 保留 callback，在实体到达、离开、过期或世界卸载时清理。它们都不从属于某次模型 installation。

## 页面主流程

`ClientModelService.tick()` 推进 `ClientDemandCoordinator` 的资源取得；模型列表的 GUI scope 不在 tick 安装。页面身份包含分类、搜索、pack、页码及有序内容。Replacement 先建立接收 scope，再关闭旧 scope；迟到 close 不能关闭 replacement。Render 前推进页面政策并领取独立 Ready lease；未获名额时，原 operation 保留 Ready anchor。Ready 旁路可发现 tick 间出现的完整资源；miss 不降级或取消已接纳取得。关闭先封口，帧借用结束后才清理自有输入。

`CatalogPageFrame` 在同次 render 内完成准入、提交和交接。页面循环游标授予至多池最大容量加一张卡的新准备名额，拒绝仍推进游标；已有安装的动画不受名额限制。其余卡片先提交，主线程执行首卡并立即注册有效图片，再完整 join 所有已接纳任务、注册其他有效图片。任何失败都先完成交接，帧间不保留在途批次。共享图片在提交前确定唯一准备卡；冷前景、背景与该卡获准的完整绑定在同一任务执行。

Eager 页面不创建 preview batch、不请求或消费 preview；非 eager 按需建立独立 preview 需求。配置切换只改变后续推进和消费，不以隐藏 slot 维持 eager 工作，也不强制取消此前接纳的补图。跳过 preview 不产生缺图或错误投影。

`PageImages.advanceAcquisition(allowed)` 先查驻留 use，miss 才取得输入、获准联网后提交 batch。`Image.claimPreparation()` 为同一 identity 签发一次 CPU 能力；卡片执行方同步调用 `prepare()`，主线程用 `Image.publish()` 消费结果。发布失败由 slot 保留，后续帧不重复准备；其他 slot 继续交接。非帧调用方的 `advance()` 串行调用同一能力。GUI 的 texture/loading 投影不启动工作。图片 Ready 只指可消费的宿主注册；bytes、CPU 输入与磁盘文件均不是 Ready。

冷前景、背景和头像只在准备能力中读取、验证与解码；远端取得阶段不解码，cache 文件存在仅作准备提示。已有 preview operation 继续交付封闭 encoded 内容与 RGBA 能力。准备能力独立取得输入，拒绝时由提交方关闭；接纳后由任务或 `Prepared` 关闭。`CustomTexture` 构造接纳像素，并独立保留 reload 内容。首次 load 直接上传 prepared pixels，上传结束或失败均归还 CPU 能力。Batch 仍关闭自己的输入；它不再持有已转移像素。页面关闭只结束自己的 use，迟到结果仍由原取得 owner 收敛。

驻留关联不保留页面或 session，不强持有 RGBA，也不另设保留计时器。Manager 物理释放或 `CustomTexture` reload/close 撤销精确注册关联；过时回调只能删除原 entry。Reload 后的重新准备属于宿主重建，不属于驻留命中。

`ClientPreviewGenerator` 在原 operation 数量和每 tick 接纳限制内直接派发解码、host 与 export 工作，不等待像素字节额度。`PreviewPixels.Owner` 只保留 backing 的退休责任和串行 reload 门禁；独立引用与同步借用保护实际使用期。Owner shutdown 封口并退休保留，执行中借用归还后释放 backing。

`CatalogModelCardState.deactivate()` 先撤展示标志并封口 scope。帧借用中，撤指派和清动画引用延到任务交接后；本帧重新取得资格也等下一次正常页面推进接纳。重新获准时显式指派和建立 EntityScope；模型列表将该 scope 交给帧安装。永久关闭才 retire 私有实体。准备和消费均要求当前资格与 Matching，hook 返回后再次复核；假实体 world tick 不推进资源门禁。

## 选择与显示的分离

页面经 `ClientProtocolGateway.selectModel()` 提交选择命令。LOCAL 应用 capability，业务会话建立后走联网请求，不乐观写模型、纹理或 Roaming。准备失败不抑制仍获准的发送，反馈与独立实体需求分开。

`openTexturePage()` 复核世界、等待与当前目标，只查询匹配纹理的 FULL Ready，普通 GUI 不足以供中央动画预览。卡片解析被点内容，主体按钮读取 authority 与请求纹理，不借 Retained 替代当前内容。

来源先保留入口 lease，接收页取得独立 lease 并非阻塞安装中央预览，成功接管后才导航。Busy、构造失败、门禁变化、取消或导航失败都释放本次使用；后续 Ready 不恢复旧导航。中央实体使用 FULL 和自身私有状态，在同内容 init 中保留，removed 时退休。纹理候选使用 PageScope 的 GUI 准入，鼠标／键盘切换及 resize 保留同一目标的连续起点。卡片名称不消费选择等待；左侧名称继续等待真实玩家 Matching 安装。纹理子页通过父模型页 Adapter 提交选择，不新增 authority。

## 本地目录进度

`LoadingStateScreen` 与 `PlayerModelScreen` 各在一次 render 内读取同类型快照，再用 `CatalogProgressText` 映射阶段或 `n/N`。HUD 保留位置配置，显示标题、有色状态文字和处理阶段的进度条；页面的分页控件居中，进度文字右边缘对齐右下角。无活动时不绘制进度。两处不消费 `ClientModelService` 的 runtime 数量；runtime 自身的数量诊断保留。事实与终态归[Catalog](../../model-management/catalog/overview.md#扫描进度投影)。

## GUI 取景

`CatalogModelButton` 与 `PreviewRenderHost` 共用固定构图映射；截图输出透明背景并固定 idle。清单关闭预览旋转且指定非空、非 idle 自定义 GUI 动画时，截图开启旋转；其他情况与实时卡按清单约定。

`GuiFrameInput` 固定本轮 partial tick、inventory context、朝向、装备和 hover/focus。任务在完整绑定前应用输入，并在 finally 恢复私有主体；装备快照不借真实玩家的可变别名。主线程绘制应用同一输入，只消费 `GeoRenderData`，不再次 update 或推进 controller。中央 FULL 预览、纹理候选和世界实体保留各自的同步或预调度入口。

`CatalogTextureButton` 的 `GuiModelFraming` 在预渲染后、body/layer 输出前使用当前骨骼几何。`GuiModelView` 按安装 identity 保留有效帧包络，换安装清空。非法帧不进入包络；无法形成有限绘制矩阵时跳过本帧。取景不修改 native 属性或法线，buffer delegate 只在同步 draw 内借用。

## 宿主动作与诊断

`CatalogCardActions` 的 Ctrl＋右键只复制发布位置，不查权限或启动资源准备。Ctrl＋左键只在 integrated 且具有已验证本地来源绑定时打开逻辑目录；builtin、远端或无证明时不打开。宿主目录打开失败不写模型失败记忆，void 返回也不能证明文件管理器已响应。

卡片错误只显示分类摘要，不因 Shift 暴露宿主路径或底层异常。清单作者、许可及缺图指引仍各自展示，不由失败替代模型声明。真实 Ctrl 点击与宿主启动反馈仍未验证；其余证据见[展示设计的验证入口](../overview.md#实现与验证入口)。
