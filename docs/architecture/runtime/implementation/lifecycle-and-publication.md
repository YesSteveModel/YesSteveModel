# 生命周期与发布映射

本页落实[启动与进入世界](../overview.md#启动与进入世界)、[不可变发布](../overview.md#不可变发布)和[结束与替换](../overview.md#结束与替换)，定位实际的宿主接入窗口与关闭责任。

## 生命周期与线程

| 进入窗口 | 当前责任映射 |
|---|---|
| 模组构造 | `NativeLibUtil.load()` 判断 native 能力是否就绪，决定是否允许 Java 业务继续初始化 |
| Common setup 的 `enqueueWork` | `ModelRuntime` 与 `ModelSystem` 组装 builtin、storage、初始 Catalog 和 server runtime；dedicated 服务端在此激活 Catalog |
| Client setup 的 `enqueueWork` | `ClientStartup` 固定兼容能力与 locator；`ClientModelService.start` 构造私有候选实例，通过 required 验收后移交关闭权并发布 |
| `ServerAboutToStartEvent` | 创建归属本次游戏服务的 `ServerModelService`；服务端停止事件触发其关闭 |
| `LoggingIn` 与 `LoggingOut` | `ClientSessionRuntime` 按对应 `Connection` 建立与撤销会话；`beginConnection()` 先尝试触发 reload |

这些生命周期入口不依赖第三方模组监听器的执行顺序。客户端服务持有启动时构造的 `ModelSystem`。Required 默认模型准备使用专用并发容量，渲染线程在服务尚未发布的启动窗口内通过 `managedBlock` 推进宿主交接。启动失败时原样传播原始 cause，而普通目录激活失败归入 Catalog 诊断。

## 启动预热的接入

`ClientModelService` 持有 `StartupModelWarmup`，构造时仅保存依赖；直到已发布服务的首次 tick，才按[一次性预热政策](../overview.md#启动模型一次性预热)启动。由于 `system.tickCatalog()` 已经负责推进目录通知与 storage，预热只需在 `renderTargetManager.tick()` 之后收集查询结果并观察资源状态，不必额外开辟存储推进入口。

预热直接订阅 `ReloadableModelCatalog`，防止 `ClientCatalogManager` 在远端会话投影期间屏蔽本地目录通知。订阅回放和后续通知只打脏标记，由 tick 统一汇合最新本地目录快照与 `PlayerPreferences.readSelection` 的结果；即使在同步回放结束后才拿到的订阅句柄，也由 tick 或 `close` 统一释放。预热目标只记录模型身份和纹理意图，不长期持有目录快照或资产内容。

提交预热请求时，资源适配层使用普通发布身份、`RenderTargetIds.PLAYER`、`ModelResourcePurpose.FULL` 和 `AbstractClientPlayer.class` 构造请求，随后以 `WorkPriority.BACKGROUND` 调用 `getOrStartOffline`。提交前会再次核对本地 Catalog、当前客户端视图中的可用本地来源以及 `permitsAcquisition`，确认离开可提交态后才进入加载入口。加载过程中始终观察同一个 lease，完成与释放规则沿[客户端资源契约](../../model-management/client-resources/overview.md#申请与观察)执行。

## 进程目录配置

`ModelRuntime` 通过 `ProcessAssetPaths` 一次性构造完整的 `AssetPaths`，供模型来源、存储、远端缓存和导出功能共用。只有在整组目录探测、目录观测和自有探针文件清理全部完成后才会对外发布；此后所有 getter 都是纯内存读取，不读取环境变量，也没有文件系统副作用。

`ModelRuntime` 一次性发布进程目录与 `ModelSystem`，随后随 JVM 存活；自动化测试则显式构造独立的 `ModelSystem` 并注入测试配置。全局目录探测失败只会记录诊断并回退到实例布局，不影响内置默认模型（intrinsic default）的必需失败边界。文件与目录布局规范见[存储](../../model-management/storage/overview.md)。

## 不可变 Proto 发布边界

Java 侧使用不可变的 QuickBuffers message：嵌套 `Builder` 是代码生成的唯一可变边界，业务层只对外发布完整的 `build()` 或 `parseFrom(...)` 产物。嵌套消息、repeated 列表以及 map 视图均可安全共享。字节字段在写入时会拷贝输入的剩余可读区间，读取访问器则返回游标独立的只读视图；底层 backing 的所有权仍遵循[内存规则](../../native-runtime/implementation/jni-and-memory.md)。

Strict profile 要求所有未标 `optional` 的普通单值（singular）字段必须存在，缺失时在构造或解析阶段直接失败。上层隔离整条畸形消息，绝不发布残缺对象；字段是否允许缺省必须由显式的可空类型或缺失分支来体现。Schema 规范以[模型标准](../../../standards/model-schema/README.md#normative-proto-快照)为准，当前差异见[格式与 Schema 问题](../../../status/known-issues/format-and-schema.md)。JNI 边界只接收字节、显式 buffer 负责方或强类型 handle，绝不把 message、Builder 或 allocator 暴露跨过 ABI。

## 进程退出与显式关闭

生产环境的 JVM 退出严格落实[进程退出决策](../overview.md#jvm-进程退出)，直接放弃运行期资源与尚未提交的后台工作：`ModelRuntime` 不注册 JVM shutdown hook，Minecraft 关闭流程也不调用模型运行时 `close`，YSM 自建 Java 后台线程全部设为 daemon。显式关闭入口仍然保留，专门服务于启动候选回滚、测试和受控资源域终结。

`ModelRuntime` 只负责进程级单例的一次性发布，不提供进程级关闭或重建入口。`ModelSystem` 则可以作为独立资源域显式关闭；客户端移交关闭权时会先校验唯一性，移交失败仍会回收私有候选实例，同时保证 dedicated 服务端代码不依赖客户端类。

`ModelSystem.close()` 在锁内完成封口（seal），锁外依次停止 Catalog、客户端 runtime 与服务端 runtime，最后释放 CACHE 根登记和 builtin。清理中首个抛出的异常会作为主异常保留，后续失败挂入 suppressed cause。并发重复调用 `close()` 只观察封口状态，不保证首次清理已经跑完；`ClientModelService.close()` 则单独协调首次关闭的完成等待，且等待期间不持有服务类锁。

各子组件的显式关闭按以下边界收尾：
- **Catalog**：等待协调线程与验证池全部退出后，撤销候选集并结束在途请求。
- **`ClientModelService`**：先关闭启动预热（取消查询、目录订阅和 lease），再封住模型选择、偏好、需求和新任务提交，接着关闭自有预览、远端负责方、通知定时器与调度器，最后撤掉资源缓存与准备引用。它不会遍历世界、GUI、手部或固定预览里的全部实体，也不阻塞等待所有 store 退休；各实体沿所属上级生命周期清理私有执行态和绑定，单个 store 何时退休取决于最后一份 pin 与真实使用保护的归还，见[动画资源所有权](../../model-management/client-resources/overview.md#动画资源所有权与退休)。
- **服务端与存储**：服务端 runtime 停止自有 worker。存储先结束派生缓存工作，再调用 `StorageWriteBuffer.closeAndDrain` 专用推进已接纳的写入队列，随后关闭 `DatabaseWork`，最后释放 CACHE 根消费登记。各个独立负责方的清理失败不会跳过后续清理项。真实的 worker 退出不能拿取消请求或超时来顶替；这些同步等待只发生在显式受控关闭中，不进入生产 JVM 退出路径。
