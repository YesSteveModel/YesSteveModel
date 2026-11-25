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

生命周期入口不依赖第三方模组监听器执行顺序。客户端服务持有启动时构造的 `ModelSystem`。Required 默认模型准备使用专用并发容量，渲染线程在服务发布前通过 `managedBlock` 推进宿主交接。启动失败原样传播原始 cause，普通目录激活失败归入 Catalog 诊断。

## 启动预热的接入

- **接入时机**：`ClientModelService` 构造时仅保存 `StartupModelWarmup` 依赖，在已发布服务的首次 tick 按[一次性预热政策](../overview.md#启动模型一次性预热)启动。因 `system.tickCatalog()` 已推进目录通知与 storage，预热只需在 `renderTargetManager.tick()` 后收集查询结果并观察资源状态。
- **目录订阅**：预热直接订阅 `ReloadableModelCatalog`（绕开远端会话对本地通知的屏蔽）。回放与后续通知仅打脏标记，由 tick 汇合最新本地目录快照与 `PlayerPreferences.readSelection` 结果；同步回放结束后拿到的订阅句柄同样由 tick 或 `close` 统一释放。预热目标只记录模型身份与纹理意图。
- **加载提交**：使用普通发布身份、`RenderTargetIds.PLAYER`、`ModelResourcePurpose.FULL` 与 `AbstractClientPlayer.class` 构造请求，经 `WorkPriority.BACKGROUND` 调用 `getOrStartOffline`。提交前复核本地 Catalog、当前客户端视图中的本地来源及 `permitsAcquisition`，全程观察同一个 lease（规则见[客户端资源契约](../../model-management/client-resources/overview.md#申请与观察)）。

## 进程目录配置

`ModelRuntime` 经 `ProcessAssetPaths` 一次性构造完整 `AssetPaths`，在整组目录探测、目录观测与探针文件清理完成后发布，供模型来源、存储、远端缓存与导出共用；发布后所有 getter 为纯内存读取。

`ModelRuntime` 随 JVM 存活；自动化测试显式构造独立 `ModelSystem` 并注入测试配置。全局目录探测失败仅记录诊断并回退到实例布局，不影响内置默认模型（intrinsic default）的必需失败边界。布局规范见[存储](../../model-management/storage/overview.md)。

## 不可变 Proto 发布边界

- Java 侧使用不可变 QuickBuffers message：嵌套 `Builder` 为代码生成的唯一可变边界，业务层仅发布完整 `build()` 或 `parseFrom(...)` 产物，嵌套消息、repeated 列表与 map 视图可安全共享。字节字段写入时拷贝剩余可读区间，读取访问器返回游标独立的只读视图；底层 backing 遵循[内存规则](../../native-runtime/implementation/jni-and-memory.md)。
- Strict profile 要求所有未标 `optional` 的单值（singular）字段存在，缺失时在构造或解析阶段失败；上层隔离整条畸形消息，仅发布完整对象。Schema 规范以[模型标准](../../../standards/model-schema/README.md#normative-proto-快照)为准，差异见[格式与 Schema 问题](../../../status/known-issues/format-and-schema.md)。
- JNI 边界仅接收字节、显式 buffer 负责方或强类型 handle，message、Builder 与 allocator 留在 Java 侧。

## 进程退出与显式关闭

生产环境 JVM 退出落实[进程退出决策](../overview.md#jvm-进程退出)，直接放弃运行期资源与未提交后台工作：`ModelRuntime` 不注册 JVM shutdown hook，Minecraft 关闭流程不调用模型运行时 `close`，YSM 自建 Java 后台线程均为 daemon。显式关闭入口保留用于启动候选回滚、测试与受控资源域终结。

- `ModelRuntime` 仅提供进程级单例一次性发布。`ModelSystem` 支持作为独立资源域显式关闭；客户端移交关闭权先校验唯一性，移交失败回收私有候选实例，同时保证 dedicated 服务端代码不依赖客户端类。
- `ModelSystem.close()` 在锁内封口（seal），锁外依次停止 Catalog、客户端 runtime、服务端 runtime，最后释放 CACHE 根登记与 builtin；首个异常保留为主异常，后续挂入 suppressed。并发 `close()` 仅观察封口态，`ClientModelService.close()` 在不持服务类锁的前提下协调首次关闭完成等待。

各子组件显式关闭边界：

- **Catalog**：等待协调线程与验证池退出后，撤销候选集并结束在途请求。
- **`ClientModelService`**：关闭启动预热 → 封口选择、偏好、需求与新任务提交 → 关闭预览、远端负责方、通知定时器与调度器 → 撤掉资源缓存与准备引用。各实体沿上级生命周期清理私有执行态与绑定，单个 store 在最后 pin 与真实使用保护归还时退休（见[动画资源所有权](../../model-management/client-resources/overview.md#动画资源所有权与退休)）。
- **服务端与存储**：服务端 runtime 停自有 worker。存储先结束派生缓存工作，再以 `StorageWriteBuffer.closeAndDrain` 排空已接纳写入，关闭 `DatabaseWork` 后释放 CACHE 根登记。单项清理失败不跳过后续项；显式受控关闭等待 worker 实际退出，不以取消信号或超时替代结束判定。
