# 页面 runtime 的落实关系

本页落实[页面作用域](../overview.md#运行作用域与所有权)、[页面结果交接](../overview.md#页面需求与结果)和[选择导航](../overview.md#选择与资源需求)。共同取得和用途准备机制见[需求协调](../../model-management/demand/overview.md)。

## 页面与资源 owner

`ModelScreen` 为页面流程保留世界身份与退休状态，只弱引用世界和当前页面。`ScreenEvent.Opening` 校验身份；子页切换保留流程，离开流程或 `LevelEvent.Unload` 终止流程。卸载发生在 Minecraft 替换 level 前，不能借旧 level 尚可见而接纳新流程。

页面移除关闭卡片、batch 与预览实体，还清除 widgets、focus 与 narration 引用；仅 `clearWidgets()` 不解除后两类保留。导航取消时恢复仍显示的来源页记录。`FakePlayer` 始终绑定创建时世界，不通过 setLevel 迁移。

`CatalogModelCardState` 只保存展示状态、GUI entity、图片投影与资格，不拥有 future、retry 或完成回调。`PageImages` 拥有图片 batch、slot、standalone texture；`CustomTextureManager` 承担宿主注册和物理释放。模型 target 的 adopted texture 不归该页面 owner。

`AnimatableCacheUtil` 是客户端共享的通用展示实体 cache，访问过期或世界卸载清理；它不拥有模型卡的私有预览实体。`EntityLoadEvent` 的客户端等待列表按实体 ID 保留 callback，在实体到达、离开、过期或世界卸载时清理。它们都不从属于某次模型 installation。

## 页面主流程

`ClientModelService.tick()` 推进 `ClientDemandCoordinator`；GUI render 只读投影。`PageScope` 按成员、顺序、内容与页码形成语义身份。Replacement 先建立接收 scope，再关闭旧 scope；迟到 close 不能关闭 replacement。

`PageImages.advance(allowed)` 消费准入上限，先取得本地结果、获准联网后提交 batch，并非阻塞观察完成。GUI 读取图片不启动工作。图片 Ready 只指 owner 持有可用 `TextureHolder`，bytes 或磁盘文件不是 Ready。Adapter 负责迟到结果和全部 slot 终态。

完整媒体验证同时产生封闭 encoded 内容与 RGBA 能力。远端传输终态后，各条目独立通过媒体／当前性门禁并交付；不等待同批其他图片解码。Batch 在结果终态后仍保留释放责任；`CustomTexture` 构造接纳时取得像素，并独立保留 reload 内容。首次 load 直接上传 prepared pixels，上传结束或失败均归还 CPU 能力。注册拒绝与页面退出关闭本次能力，旧页面无需再次推进。独立 reload 重新解码，不复用已消费像素。

`ClientPreviewGenerator` 在原 operation 数量和每 tick 接纳限制内直接派发解码、host 与 export 工作，不等待像素字节额度。`PreviewPixels.Owner` 只保留 backing 的退休责任和串行 reload 门禁；独立引用与同步借用保护实际使用期。Owner shutdown 封口并退休保留，执行中借用归还后释放 backing。

`CatalogModelCardState.deactivate()` 先撤展示标志，再 retire 并清动画引用。重新获准时显式指派和建立 EntityScope。准备和消费均要求当前资格与 Matching，hook 返回后再次复核；假实体 world tick 不推进资源门禁。

## 选择与显示的分离

页面经 `ClientProtocolGateway.selectModel()` 提交选择命令。LOCAL 应用 capability，业务会话建立后走联网请求，不乐观写模型、纹理或 Roaming。准备失败不抑制仍获准的发送，反馈与独立实体需求分开。

`openTexturePage()` 复核世界、等待与当前目标，只查询匹配纹理的 FULL Ready，普通 GUI 不足以供中央动画预览。卡片解析被点内容，主体按钮读取 authority 与请求纹理，不借 Retained 替代当前内容。

来源先保留入口 lease，接收页取得独立 lease 并非阻塞安装中央预览，成功接管后才导航。Busy、构造失败、门禁变化、取消或导航失败都释放本次使用；后续 Ready 不恢复旧导航。中央实体使用 FULL 和自身私有状态，在同内容 init 中保留，removed 时退休。纹理候选使用 PageScope 的 GUI 准入，鼠标／键盘切换及 resize 保留同一目标的连续起点。卡片名称不消费选择等待；左侧名称继续等待真实玩家 Matching 安装。纹理子页通过父模型页 Adapter 提交选择，不新增 authority。

## 本地目录进度

`LoadingStateScreen` 与 `PlayerModelScreen` 各在一次 render 内读取同类型快照，再用 `CatalogProgressText` 映射阶段或 `n/N`。HUD 保留位置配置，显示标题、有色状态文字和处理阶段的进度条；页面的分页控件居中，进度文字右边缘对齐右下角。无活动时不绘制进度。两处不消费 `ClientModelService` 的 runtime 数量；runtime 自身的数量诊断保留。事实与终态归[Catalog](../../model-management/catalog/overview.md#扫描进度投影)。

## GUI 取景

`CatalogModelButton` 与 `PreviewRenderHost` 共用固定构图映射；截图输出透明背景并固定 idle。清单关闭预览旋转且指定非空、非 idle 自定义 GUI 动画时，截图开启旋转；其他情况与实时卡按清单约定。

`CatalogTextureButton` 的 `GuiModelFraming` 在预渲染后、body/layer 输出前使用当前骨骼几何。`GuiModelView` 按安装 identity 保留有效帧包络，换安装清空。非法帧不进入包络；无法形成有限绘制矩阵时跳过本帧。取景不修改 native 属性或法线，buffer delegate 只在同步 draw 内借用。

## 宿主动作与诊断

`CatalogCardActions` 的 Ctrl＋右键只复制发布位置，不查权限或启动资源准备。Ctrl＋左键只在 integrated 且具有已验证本地来源绑定时打开逻辑目录；builtin、远端或无证明时不打开。宿主目录打开失败不写模型失败记忆，void 返回也不能证明文件管理器已响应。

卡片错误只显示分类摘要，不因 Shift 暴露宿主路径或底层异常。清单作者、许可及缺图指引仍各自展示，不由失败替代模型声明。真实 Ctrl 点击与宿主启动反馈仍未验证；其余证据见[展示设计的验证入口](../overview.md#实现与验证入口)。
