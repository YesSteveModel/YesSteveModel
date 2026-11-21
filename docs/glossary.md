# 术语表

| 术语 | 含义 |
|---|---|
| Asset Container | 与具体资产类型无关的二进制容器层。 |
| Model Schema | 建立在 Asset Container 之上的公开模型数据契约。 |
| stored representation | chunk 在磁盘存储或网络传输中的原始字节表示。 |
| logical content | 完成容器级解码后交给上层 Schema 的逻辑内容。 |
| verified preamble | 从容器起始位置到 verification payload 结束为止的元数据前缀。 |
| `ModelId` | 普通模型的完整业务身份，可以是 `owner/name`、附带可选 SemVer 的命名标识，或沿用既有源内容集合计算出的 `hash:<hex>`；它不代表精确二进制内容，也不构成作者身份证明。 |
| chunk hash | 单个 logical content 的完整性摘要。 |
| raw model | 作者仍可直接编辑、尚未导出为 Asset Container 的模型源资源集合。 |
| 容器语义冻结 / semantic freeze | raw 模型或 legacy 源成功导出后，该 Asset Container 的 logical content 将作为后续兼容需要维持的语义快照；它只冻结该导出物已定义的行为，不代表当前格式版本本身已经稳定。 |
| legacy v1/v2 与 v3 加密模型 | 旧版私有 `.ysm` 格式世代，与 Asset Container、Model Schema 和 Protocol 版本无关。 |
| model render target / loaded render target | 前者是 Model Schema 中按用途声明的渲染目标；后者是客户端结合纹理与行为资源装配完成、由 cache 和 lease 管理的运行时对象。两者都不是 Minecraft framebuffer。 |
| YSM `BakedModel` / `CubeGroup` | 前者是 native CPU renderer 产出的不可变烘焙结果，与 Minecraft 同名类型无关；后者是同一骨骼和逻辑分区内不可拆分的 SIMD 几何与调度单元。 |
| serialized baked cache | `BakedModel` 的内部缓存序列化格式，不属于公开模型格式。 |
| current protocol | 模型会话、资产分发与玩家状态同步使用的[通信协议](standards/protocol-v1/README.md)；Forge 仅提供传输适配层。 |
| `SessionMode` / `ActiveSessionMode` | 前者是用户配置的会话模式策略，后者是当前实际生效的玩家状态权威；`AUTO` 只属于前者。 |
| Game Server / Local Only | 前者以当前游戏服务端作为玩家状态权威；后者是不建立远端玩家状态权威的纯本地模式。 |
| exact Minecraft `Connection` | 某次具体远端模型与玩家状态会话的唯一路由和退役身份；wire 不再携带额外的会话 ID，玩家 UUID 也不能替代具体的连接实例。 |
| model source | 为 Catalog 提供只读输入的内置、全局或本地模型来源；远端发布来自服务端权威，本地缓存本身不是来源权威。 |
| `ContentBinding` | Catalog 内部对某个当前 `ModelContent` 对象的强引用，不提供关闭能力。 |
| `ContainerId` | 经精确验证的容器表示的 BLAKE3 身份，取 verification payload 的前 32 字节。 |
| `ModelFileIdentity` / exact tuple | 由 `(ModelId, ContainerId)` 组成，同时标识稳定模型身份与精确容器表示。远端发布和命名 `ModelId` 必须精确匹配；`hash:` 身份保留经验证的本地同源替代例外。 |
| `CatalogIndexSnapshot` | 按 `ModelId` 记录本地候选与可重新打开的数据源的不可变索引；不持有可选内容，也不代表已进入 Ready。 |
| `RemotePublicationSnapshot` / `PublicationEntry` | 前者是当前服务端会话发布的目录权威；后者是其中的精简目录项，只包含精确二元组、层级路径与访问权限，不证明内容已激活。 |
| `ActivationSnapshot` | 对应某次远端发布中逐条目的 Pending、Ready 或 Failed 激活状态；激活状态变化不会改写服务端权威。 |
| `CatalogSnapshot` | 收录当前全部 Ready `ModelContent` 的不可变目录快照。 |
| authoritative / effective selection | 前者是服务端发布的目标选择；后者是客户端在目标尚未 Ready 时实际渲染所用的模型（来自最新发布的 Ready 模型或内置默认模型），生效层的回退不会回写服务端权威。 |
| `ResourceLease` | 每次申请资源返回的独立、可关闭消费句柄；它只代表某份 Pending 需求或 Ready 引用，不拥有 render target 的物理销毁权。 |
| Pending Flight / consumer interest / Ready resource | 三者分别指：同一稳定 resource key 当前唯一可命中的精确在途加载操作；某个消费方对该在途操作登记的需求；以及由缓存与消费方引用共同保活的不可变完成结果。 |
| transfer lease | 连接或会话负责方所接纳的一次精确网络传输承诺；消费方只能请求取消，只有该负责方有权发出取消并关闭 lease。 |
| business-local inbound owner | 各类入站业务消息在各自模块内的重组、验证、收尾与发布负责方；底层帧传输层不持有这些业务状态。 |
| `ResourceDispatchWorker` | 服务端全局的已接纳逻辑包唯一负责方，统一处理分发、背压与关闭；见[资产传输](architecture/network/implementation/asset-transfer.md)。 |
| intrinsic default | 每个进程自带、启动前完成验证并常驻的默认模型；网络只传输专用引用，不传输其资产内容。 |
| Backend | 未来可独立部署的服务端，不是当前游戏服务端内的逻辑。 |
| `EntityModelBinding` / `AnimatableEntity` / `AnimatedGeoModel` | 三者分别负责 render-target 绑定、实体动画生命周期以及 `BoneAttribute` 的所有权。 |
| `BoneAttribute` | native 侧读取的逐骨骼属性记录；Java 侧 `AnimatedGeoModel` 持有内存布局等价的连续属性数组。 |
| Roaming | 模型声明的有限 float 状态，运行时暴露为 `v.roaming`；LocalPlayer 按 UUID 与完整发布身份持久化，服务端只保存当前所选模型的表现状态。 |
| `ysm.sync` | 触发模型 `sync` handler 的尽力而为瞬时事件，不是持续状态同步或骨骼同步。 |
| `GeoModelState` / `GeoRenderData` | 前者持有规范或可变的 native `ModelState` 以及 Java locator 映射，并借用 native pose 视图；后者组合该借用视图与本次绘制的元数据。 |
| `ModelState` | native extract 产出的逐帧结果；它共享 `BakedModel`，拥有姿态、索引与 `RenderSchedule`，Java 侧仅借用其视图。 |
| `RenderSchedule` / `RenderTask` | 按可见骨骼与四个几何分区编排出的只读 CPU 工作及固定输出区间计划。 |
| Java `RenderContext` / native `renderer::RenderContext` | 前者决定动画语义与输出复用；后者仅表示 level、Iris shadow、GUI 这三种渲染上下文枚举。 |
| `VertexKind` | native 顶点输出布局选择，用于区分 Vanilla 或 Iris 的 direct 布局与 `VertexConsumer` 回退布局，不代表 `RenderType` 或 `renderer::RenderContext`。 |
| Native cutout / translucent | CPU 几何中无需透明排序与需要透明排序的两个分区；这里的 cutout 表示不透明类分区，不等同于 Minecraft 的 `RenderType`。 |
| `ZTX` | 当前实现私有且尚未冻结的图像格式，不属于可移植的 Model Schema profile。 |
| bake / extract / render | 渲染管线的三个阶段：`BakeModel` 构建 `BakedModel`，`ModelState::Extract` 生成逐帧状态，`renderer::Render` 输出顶点。 |
| `CapturedModel` / source capture | 一次 raw 模型解析实际读取到的文件字节、目录观察快照、模型身份与诊断信息的冻结集合；后续编译只从这份快照读取。 |
| `RawCompileResult` | Java 侧转换产出的暂存容器、模型身份与诊断信息；此时尚未提交到存储层或目录。 |
| `ModelFileView` / `ModelInfoView` / `ChunkDataSource` | 三者分别指：模型 Manifest 与引用的唯一解析视图；共享不可变 Info 与 player target、仅补充可重建元数据索引的视图；以及按 descriptor 读取 chunk payload 的内容来源（元数据解析成功不证明全部资源已就绪）。 |
| `UniBuffer` / `BufferStorage` / `SharedBuffer` | 三者分别指：数组或 native 内存的统一访问视图；由固定 owner 显式关闭的底层存储；以及由共同 GC owner 保活的共享视图。所有 `slice()` 均返回借用视图，`ownedSlice()` 保留共同 GC owner。 |
| `ClientAssetBatch` | 由界面显式收集并提交的预览图、模型包封面与展示资源需求集合，不是通用的网络批处理通道，也不是模型主体的下载负责方。 |
| `@YsmExtension` / `@YsmEventHandler` | 前者用于声明扩展兼容性检查入口，后者用于声明可自动发现、先经生成的 checker 校验再实例化注册的事件处理器；两者当前均不构成稳定的公共 API 承诺。 |
