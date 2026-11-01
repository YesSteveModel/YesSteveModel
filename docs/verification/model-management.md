# Mock 验证架构

> **适用问题**：模型管理验证的分层、进程隔离、证据身份与发布包边界；**不包含**：产品契约、完整场景清单或某次运行的验收结论。

Mock 表示测试宿主和输入适配，不表示另写一套模型管理实现。验证复用生产 catalog、session、授权、消息分发与资源路径；fixture source、socket byte carrier 和 logical host callback 只提供受控输入与交接。`SessionCollectionPublication.fullFragments` 的显式 animation map 重载复制输入，普通入口仍读取默认动画；它是无状态组装 seam，不引入第二份 publication authority。

## 分层与可证明范围

| 层 | 入口与职责 | 不能替代的证据 |
|---|---|---|
| Domain | `modelManagementMockDomain` 执行 tagged tests，覆盖领域状态、来源变化、请求/页面终态和受控 workload | 不证明物理双端依赖或真实 Forge/渲染接入 |
| Classpath | `MockSupervisor` 以不同物理 classpath 启动普通 Java client/server 子进程，载入各自生产依赖并通过 byte carrier 交互；smoke 验证基础闭环，full 与 exact-100 分别运行系统场景与规模场景 | 不启动 Forge launcher，不证明真实游戏 hook、GPU 或 GUI 行为；smoke 明确不具备完整验收资格 |
| Forge host | `modelManagementMockForge` 由 `HostSupervisor` 启动独立 server、client A/B，`mockHost` 中的薄 probe 经宿主入口驱动并观察行为 | 只证明所运行环境与场景，不覆盖所有 shader、GPU、长期运行或物理回收时限 |

Classpath 的 full 与 exact-100 各执行两次受控回放并核对输入身份及结果。域内 workload 与物理 exact-100 是不同验证半径，不能互相冒充，也不能据场景名称推导持续并发性能承诺；性能目标适用范围由[验证政策](policy.md)定义。

## 构建与进程 ownership

`mockSupport` 提供证据数据与记录工具；`mockSupervisor` 只依赖 support 与自身工具依赖，不加载生产业务或任一 endpoint。`mockCommon` 复用生产能力，`mockClient` 与 `mockServer` 各自增加 side endpoint；运行清单要求本侧 endpoint 存在且对侧 endpoint 不在 classpath 中。`mockHost` 仅加入专用开发启动配置。

`ProcessSupervisor` 负责验证进程的启动、日志、等待与精确进程树清理。测试超时和强制退出用于暴露基础设施故障，不是生产 session retirement、资源取消或 Cleaner 的完成协议；残留进程使证据不可审查，不能作为正常退出。生产 owner 边界仍由[运行终结](../architecture/runtime/overview.md#结束与替换)与[资源关闭](../architecture/model-management/client-resources/overview.md#引用撤销与物理关闭)定义。

验证任务是显式入口，普通 `check` 不因此覆盖所有 mock 层。`verifyMockHostPackaging` 检查发布 archives 中不存在 mock host/supervisor/evidence/classpath 类、双端入口及专用日志资源，防止测试控制面进入正式包；打包隔离通过不代表业务场景通过。

`verifyMolangForgeConsumer` 复用这些隔离宿主、probe action/ack 和进程 ownership，接受 `molangForgeEvidenceDir` 指定的全新绝对目录。它经 Forge 客户端 dispatcher 运行 command/watch，核对执行反馈的值和线程、命令树补全与 Debug 目标。它保存 framebuffer、观察 generated transient 声音提交后的 Minecraft backend handle，并断线重连检查旧连接拒绝。`molangForgeFailures=true` 另安装隔离普通模型，经真实选择入口等待 world owner 就绪后注入 record/array non-null 违约并比较保护状态；就绪失败应报告不可审查，不直接改 owner 绕过。故障声明与 AUTO checker 仅由 mockHost 的既有处理器生成；声音表反射也仅属 mock probe。`SoundManager.isActive` 不等于听测，状态投影不等于全存档；入口不覆盖所有 entity profile、default 故障、explicit retry/reload 或 controller-local 声音 scope。

`verifyEntityPresentationForge` 复用同一 supervisor，接受 `entityPresentationEvidenceDir` 指定的全新绝对目录。可选 `entityPresentationFixtureDir` 提供含 `raw-packs/` 的真实 first-person 语料目录。该任务运行第一/第三人称、限制叠加、服务端指派、reload、目标 GUI、worker 封口与重连；`entityPresentationScope=lifecycle` 只执行选模及生命周期部分。`mockVanilla` 客户端只加载 Forge 和探针，用于没有 YSM 注册的真实主体；LOCAL 客户端仍有 YSM 通道，两者分别记录。

`entityPresentationScope=gui-purpose` 只运行用途页面探针。该探针使用隔离的全局缓存目录，通过正常页面点击选择模型并打开 FULL 动画页。专用 Java agent 观察既有安装、准备、网络发送和 native 绘制出口。探针借用真实 runtime barrier 形成 Busy，在接收成功后撤销 Catalog 权限，并向按钮提供不匹配的纹理目标。各次拒绝均核对零准备、零发送和无迟到导航；正常选择作为观察器的正对照。

合法入口播放 GUI 闭包外的循环 `trace` 动画，并保存 16 张连续 framebuffer、骨骼输出和 native 绘制结果。探针另观察逐 tick 玩家安装关系、中央 FULL／候选 GUI、独立 runtime、私有变量与主动换纹理。截图需要单独核对可见模型的变化；骨骼或用途值不能代替像素证据。该入口只证明固定夹具与所运行的渲染环境，不证明全部模型、shader 或 GPU 外观。

`entityPresentationScope=catalog-progress` 在隔离的多人和 integrated 世界执行 Catalog 进度探针。夹具含健康模型、同身份重复模型、坏模型和 pack；builtin model manifest 和显式 pack 清单独立提供其余输入。探针在扫描观察 seam 保留发现、逐项 outcome 和最终验证前阶段。扫描 gate 保留到 render 结束，执行生产 HUD/页面 render，记录实际 `drawString` 文本、坐标、颜色和 framebuffer。另在 Catalog 空闲时阻塞真实资源 worker 并提交 FULL，断言两处无进度。

远端对照按顺序保留真实 activation 任务与收到的 metadata 包，记录任务数、包字节数、活动 transfer ID 和 activation 终态。两处生产绘制在这些检查点均应隐藏。集成模式共用本地 Catalog，因此探针临时向 activation 提供空的本地候选索引，强制走冷远端路径；结束时恢复原 supplier。gate 释放原任务与原包，不提供替代完成结果。扫描计数仍来自原 owner。单测另覆盖空输入、最终观察/索引失败、关闭与 owner 交接。

Probe 按世界、纸娃娃、背包和手臂记录宿主取消点。资源入口代理和 lease 包装转发原实现，只记录每个主体的调用与精确关闭。测试扩展为生成程序提供动作计数和 worker gate，观察实际调度、Roaming、native 借用及 SoundManager handle。加载屏幕的 reload 完成由 render tick 轮询。脚本完成只表示 action/ack 和进程结束，应逐项核对输入、直接结果和 framebuffer；这些探针不证明所有 GPU 像素、物理回收时限或未配置的 optional 游戏实体。

## 输入与证据门禁

OOM 注入沿已有防御的受影响链执行。`EntityExpressionRuntimeTest` 观察失败整轮不提交效果；`EntityModelBindingTest` 经真实生成 provider 和异步 owner 验证失败上达、旧安装保留及后续独立执行。`ModelRenderTargetCacheTest` 覆盖同步/异步 terminal，以及 Janino／Semaweave／asset 包装 OOM 在 offline 和 cache-only 入口继续失败；相同包装中的普通 ACCESS 仍为 miss。`ReloadableModelCatalogTest` 覆盖扫描失败、健康内容保留、FINALIZING 和 owner 接纳。普通异常、取消及 required default 仍作对照；`NativeRendererTest` 直接核对 fallback consumer 异常传播。

这些注入只证明上述 owner 的控制与清理边界，不证明全入口、任意分配点或真实内存耗尽后的存活。未为注入增加生产 wrapper；独立可选 Job、未设防 callback 和 JNI wrapper 不因操作类型而新增 OOM 防护。读取与 pixels 的既有 borrow/close suite 另证正常资源责任。

Classpath 场景独立运行，不依赖冻结任务包、历史证据或旧文档基线：

```powershell
.\gradlew.bat modelManagementMockDomain modelManagementMockSmoke modelManagementMockSystem modelManagementMockExact100
```

默认输出位于 `build/model-management-mock/` 下各任务目录。`modelManagementMockEvidenceDir`、`modelManagementMockSystemEvidenceDir` 和 `modelManagementMockForgeEvidenceDir` 分别覆盖 domain、classpath 和 Forge 输出，配置值必须是绝对路径；独立执行多个 classpath 任务时分别指定目录。

证据绑定输入 hash、Java HEAD、双端 classpath hash、配置及产物 hash。Full/exact-100 记录实际运行库的绝对路径与 SHA-256，不把相邻 native 工作树的 HEAD 推定为二进制来源。失败、不可审查与通过分别保留；通过范围由当前场景的直接断言确定。

这些进程与证据协调均为验证专用设施，不增加生产状态 owner，也不计入[生产复杂度地图](../complexity-map/README.md)的机制数量。

## Catalog 链接与显式 reload

`catalogLinksVerificationJar` 将生产类、受控测试及 Windows/Linux 运行依赖打包为 `build/libs/catalog-links-verification.jar`。入口复用来源发现、raw/zip/legacy 转换、完整容器读取、Catalog、索引、pack 与 service reload 测试。JAR 不启动 Forge。`verifyMockHostPackaging` 同时检查该入口未进入发布包。

```text
./gradlew catalogLinksVerificationJar verifyMockHostPackaging
java -jar catalog-links-verification.jar <fixtures> <evidence> windows
<linux-java17> -jar catalog-links-verification.jar <fixtures> <evidence> linux-ext4
<linux-java17> -jar catalog-links-verification.jar <fixtures> <evidence> linux-drvfs <windows-junction-root>
```

`YSM_NATIVE_PATH` 应指向对应平台的 JNI 库。每次运行应使用全新 fixture 目录；入口在该目录解包运行依赖，并将测试临时目录放在同一文件系统。证据目录保存 JAR/native SHA-256、Java/provider 属性及逐项结果。

缓存写入验证应在单次操作期间保持目录结构稳定。用受控门禁在下一次复核前改变目录或引入内部链接，观察拒绝及来源 bytes/目录清单不变；不以检查与路径操作之间的外部替换竞态作为通过判据。Provider 缺少 `fileKey` 时，健康提交、失败保旧和完整内容校验仍应成立；同路径同类型替换可能不可见，不列为必然拒绝的判据。双 JVM 用例通过生产 JDK 文件锁竞争，覆盖同 key writer、索引范围合并、活跃消费者及异常退出残留；旧 `flock` 参与者不能作为互斥 oracle。

`YSM_VERIFICATION_TESTS` 可按逗号指定 `com.elfmcys.ysm` 下的 suite 后缀；未设置时保留默认链接集合。缓存路径与共享存储回归选择 `model.storage.DirectoryAccessTest,model.storage.AtomicSharedCacheTest,model.storage.SharedStorageProcessTest,model.storage.ConvertedCacheCoordinatorTest`；名称段发布回归选择 `model.catalog.ReloadableModelCatalogTest`。Linux 应使用发行版原生文件系统，不能以 `/mnt/c` 代替 Unix 单段反斜杠验证。实际运行应核对该专用方法执行成功，不能只看 suite 成功或 Windows 条件跳过。

Legacy 链接用例需要 `YSM_LEGACY_LINK_FIXTURE`、`YSM_LEGACY_LINK_MODEL_ID` 与 `YSM_LEGACY_LINK_SHA256`。语料应是已独立转换并完整重开的有效 v3 文件。未配置时该用例跳过，不能计作 legacy 加载通过。其余 raw、zip、direct 和图片输入来自 JAR 内的有效资源。

跨宿主用例要求 Windows 先创建根 junction，并在其目标内创建 `nested-junction`，指向隔离的空目录。Linux JVM 通过 DrvFS 读取这两个真实入口；测试先验证 CUSTOM/AUTH 两种创建政策及完整发布，再验证 raw 内部 junction 拒绝与后续显式恢复。测试主动删除内部 junction，其他来源读取保持只读。受控用例的临时目录可位于 ext4；此时只能把指定 junction 的读取记作 DrvFS 证据。

拒绝访问用例先验证实际目录读取失败。Linux root 运行时，应移除 `CAP_DAC_OVERRIDE` 和 `CAP_DAC_READ_SEARCH`，或使用受限身份；单独 `chmod` 不构成证据。Windows 强制文件锁和 Windows JVM 专属 junction 测试在 Linux 上不适用，结果应单列。

四层证据分别判断：发现/逻辑路径、验证/转换、Catalog 发布、运行资源。JAR 中的已验证字节资源和 service preview lease 只证明自身边界。`modelManagementMockForge` 另观察真实初始化、连接、显式 reload、远端 authority、断连、dedicated/integrated 服务与关闭；两个独立 FULL lease 直接读取 GPU texture pixels，核对来源替换与释放其中一个 lease 后的内容。服务端独立 preview 用固定 encoded bytes 检查 reload 失效，不声称新媒体已解码。

## 生成预览制品

`PreviewHostArtifactTest` 独立重开真实宿主导出的容器。运行前将 `YSM_PREVIEW_HOST_ARTIFACT` 设为导出 `.mxc` 文件的绝对路径，将 `YSM_PREVIEW_HOST_SOURCE` 设为对应原始模型目录的绝对路径，再执行：

```powershell
.\gradlew.bat test --tests '*PreviewHostArtifactTest'
```

测试核对模型身份、完整源块、GENERATED 标记，以及图像解码后的 156×270 尺寸与 RGBA 字节数。可选 `YSM_PREVIEW_HOST_IMAGE` 指定收到的生成预览缓存文件，另行检查解码结果。未配置制品路径时跳过测试，普通单测通过不代表真实宿主导出已通过。

## 有界文件读取

`verifyReadHandles` 在现有来源测试上包装真实 Java 只读 channel/stream，记录绝对路径、调用栈、读取 bytes、物理 close 和退出时未关闭项。入口覆盖用户原件、converted、remote、派生动画/几何、图片和 raw 文件；writer、staging 验证与锁保持原 owner。

```powershell
.\gradlew.bat verifyReadHandles -PreadHandleOutput=<absolute-json-path>
```

`ChannelReadPhaseTest` 核对并发区间、借用者无物理关闭权、封口后拒绝新借用、最后归还关闭、失败/取消及打开后构造失败。`FileChunkBatchTest` 核对同阶段复用、损坏/截断、旧 expected 与关闭后的原子替换。`AnimationSetArtifactTest` 核对磁盘动画延迟读取、共享结果、普通失败后的同文件 fallback/健康动画及后续独立重开。失败阶段应先关闭，再打开新阶段；健康动画不继承封口失败。`AnimationStoreTest` 另核对关闭失败、取消与 OOM 不进入 fallback 恢复。`RuntimeContentStoreTest` 核对 exact wrapper 保留批次，不扩大内容失败实例。

`GuiPurposeCacheProbe` 的独立 JVM 诊断另记录实际 controller discovery 的句柄；`full-read` 在已持久化 FULL 文件上执行暖加载，不删除 set 文件。成功批次中同一文件只打开一次，loader 返回时无空闲读取句柄。全 resident 路径可以没有磁盘动画读取，不能将零打开冒充磁盘覆盖。打开次数本身不证明固定加速比例。

Java 探针不观察 native archive 内部 I/O；wrapper 测试只证明 Java 借用终点。Windows 缺少 `fileKey` 的目录替换用例可以跳过；证据应保留逐项结果。Forge 特有 provider、其他文件系统及真实页面仍需分别验证。
