# 术语表

| 术语 | 含义 |
|---|---|
| Asset Container | 与资产类型无关的二进制容器层。 |
| Model Schema | 建立在 Asset Container 之上的公开模型数据契约。 |
| stored representation | chunk 在存储或传输中的字节表示。 |
| logical content | 完成容器级解码后交给上层 schema 的内容。 |
| verified preamble | 从容器起点到 verification payload 结束的 metadata 前缀。 |
| `ModelId` / modelHash | 转换器实际消费的原始源文件闭包按 canonical records 计算的 32-byte BLAKE3 身份。 |
| chunk hash | 单个 logical content 的完整性摘要。 |
| raw model | 作者仍可修改、尚未写成 Asset Container 的模型源资源集合。 |
| 容器语义冻结 / semantic freeze | Raw 或 legacy source 成功导出后，该 Asset Container 的 logical content 成为后续兼容需要保持的语义快照；只冻结该导出物的已定义行为，不表示当前格式版本已经稳定。 |
| legacy v1/v2 与 v3 加密模型 | 旧版私有 `.ysm` 格式世代，与 Asset Container、Model Schema 和 Protocol 版本无关。 |
| model render target / loaded render target | Model Schema 中按用途声明的目标 / 客户端结合纹理与行为资源装配、由 cache 和 lease 管理的运行时对象；均非 Minecraft framebuffer。 |
| YSM `BakedModel` / `CubeGroup` | Native CPU renderer 的不可变烘焙结果，与 Minecraft 同名类型无关；后者是同一骨骼和逻辑分区内不可拆分的 SIMD 几何与调度单元。 |
| serialized baked cache | `BakedModel` 的内部缓存表示，不是公开模型格式。 |
| current protocol | 模型会话、资产分发与玩家状态使用的[通信协议](standards/protocol-v1/README.md)；Forge 提供 transport adapter。 |
| `SessionMode` / `ActiveSessionMode` | 用户选择策略 / 当前实际玩家状态权威；`AUTO` 只属于前者。 |
| Game Server / Local Only | 当前游戏服权威 / 不建立远端玩家状态权威的模式。 |
| exact Minecraft `Connection` | 一次具体远端 model/player-state session 的唯一路由与 retirement identity；wire 不再携带另一种 session identity，玩家 UUID 不能替代 exact connection。 |
| model source | 为 local identity index 与 content materialization 提供输入的 builtin、custom 或 auth 来源；remote publication 是 server authority，cache 不属于 authority。 |
| `ContentBinding` | Catalog 内部对一个当前 `ModelContent` 对象的强引用；没有 close 能力。 |
| `ContainerId` | 精确验证的容器 representation 的 BLAKE3 身份，取 verification payload 前 32 bytes。 |
| `ModelFileIdentity` / exact tuple | `(ModelId, ContainerId)`；同时标识稳定模型与精确容器 representation。Remote 来源必须 exact；完整验证且经 local 来源证明的 content 可以按相同 `ModelId` 非精确复用。 |
| `CatalogIndexSnapshot` | 按 `ModelId` 保存 local candidates 与可重开 backing 的不可变 index；不持有 optional content，也不表示 Ready。 |
| `RemotePublicationSnapshot` / `PublicationEntry` | 当前 server session authority / 其中一个 lean catalog entry；entry 只包含 exact tuple、hierarchy path 与 access，不证明 content 已激活。 |
| `ActivationSnapshot` | 与一个 remote publication 对应的 per-entry Pending、Ready 或 Failed state；activation 不改写 server authority。 |
| `CatalogSnapshot` | 完整 Ready `ModelContent` 的不可变 current-content catalog。 |
| authoritative / effective selection | Server 发布的目标选择 / client 在目标尚未 Ready 时实际使用的 latest-publication Ready 模型或 intrinsic default；effective fallback 不回写 authority。 |
| `ResourceLease` | 每次 acquire 返回的独立、可关闭 consumer handle；它只持有 Pending interest 或 Ready 引用，不拥有 render target 的物理关闭权。 |
| Pending Flight / consumer interest / Ready resource | 同一稳定 resource key 当前唯一可命中的精确在途操作 / 某个 consumer owner 对该精确 Flight 的需求 / 由 cache 与 consumer 引用共同保活的不可变完成值。 |
| transfer lease | connection/session owner 接受的一次精确网络传输承诺；consumer 只能请求取消，只有该 owner 负责发出取消并关闭 lease。 |
| business-local inbound owner | 各类入站业务消息的重组、验证、终态与发布 owner；frame transport 不拥有这些业务状态。 |
| `ResourceDispatchWorker` | Server-global 的 accepted logical packet 唯一 owner，负责分发、背压与关闭；见[资产传输](architecture/network/implementation/asset-transfer.md)。 |
| intrinsic default | 每个进程自带、启动前验证并常驻的默认模型；网络只传专用引用，不传其资产。 |
| Backend | 未来可独立部署的服务端，不是当前游戏服逻辑。 |
| `EntityModelBinding` / `AnimatableEntity` / `AnimatedGeoModel` | 分别负责 render-target 绑定、`entity` 动画生命周期和 `BoneAttribute` 所有权。 |
| `BoneAttribute` | Native 读取的逐骨骼记录；Java `AnimatedGeoModel` 持有布局等价的连续 attribute 数组。 |
| Roaming | 通过 PlayerState 同步、按模型短键分组并暴露为 `v.roaming` 的持久 float 变量。 |
| `ysm.sync` | 触发模型 `sync` handler 的尽力而为瞬时事件，不是状态或骨骼同步。 |
| `GeoModelState` / `GeoRenderData` | 前者拥有 canonical 或 mutable 的 native `ModelState` 与 Java locator mapping，并借用 native pose view；后者组合其借用视图和本次 draw metadata。 |
| `ModelState` | Native extract 的逐帧结果；共享 `BakedModel`，拥有 pose、索引与 `RenderSchedule`，Java 借用其 view。 |
| `RenderSchedule` / `RenderTask` | 按可见骨骼与四个几何分区形成的只读 CPU 工作及固定输出区间计划。 |
| Java `RenderContext` / native `renderer::RenderContext` | 前者决定动画语义与输出复用；后者只是 level、Iris shadow、GUI 三值投影。 |
| `VertexKind` | Native 顶点输出布局选择；区分 Vanilla / Iris direct 布局与 `VertexConsumer` fallback，不表示 `RenderType` 或 `renderer::RenderContext`。 |
| Native cutout / translucent | CPU 几何的无需排序 / 需要透明排序分区；cutout 在此表示 opaque 类分区，不等同于 Minecraft `RenderType`。 |
| `ZTX` | 当前实现私有且未冻结的图像格式，不属于可移植 Model Schema profile。 |
| bake / extract / render | `BakeModel` 构建 `BakedModel`，`ModelState::Extract` 形成逐帧状态，`renderer::Render` 输出顶点。 |
| `CapturedModel` / source capture | 一次 raw 解析实际消费的文件 bytes、目录观察、模型身份和 diagnostics 的冻结输入；编译仅从该 capture 读取。 |
| `RawCompileResult` | Java 转换产生的 staging 容器、模型身份与 diagnostics；尚不代表 storage 或 catalog 已发布。 |
| `ModelFileView` / `ModelInfoView` / `ChunkDataSource` | 模型 Manifest 与引用的唯一解析视图 / 共享 immutable Info 与 player target、只增加可重建 metadata lookup 的视图 / 按 descriptor 取得 payload 的内容来源；metadata 成功不证明全部资源已取得。 |
| `UniBuffer` / ownership token | 统一 array/native bytes 的 Java 视图 / 决定该 owning 引用是否已关闭的所有权凭据；slice 共享 token，acquire 建立独立引用。 |
| `ClientAssetBatch` | 页面显式收集并提交的 preview、pack cover 与 presentation 需求集合，不是通用 wire batch 或 model body 下载 owner。 |
| `@YsmExtension` / `@YsmEventHandler` | 扩展兼容检查入口声明 / 可自动发现、通过生成 checker 后再实例化注册的 handler 声明；不构成稳定公共 API 承诺。 |
