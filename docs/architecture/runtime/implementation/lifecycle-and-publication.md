# 生命周期与发布映射

本页落实[启动与进入世界](../overview.md#启动与进入世界)、[不可变发布](../overview.md#不可变发布)和[结束与替换](../overview.md#结束与替换)，定位实际接入窗口与关闭责任。

## 生命周期与线程

| 进入窗口 | 当前责任映射 |
|---|---|
| 模组构造 | `NativeLibUtil.load()` 决定 native 能力是否允许 Java 业务继续初始化 |
| Common setup 的 `enqueueWork` | `ModelRuntime` 与 `ModelSystem` 组装 builtin、storage、初始 Catalog 和 server runtime；dedicated 激活 Catalog |
| Client setup 的 `enqueueWork` | `ClientStartup` 固定兼容能力和 locator；`ClientModelService.start` 建立私有 candidate，完成 required 后移交并发布 |
| `ServerAboutToStartEvent` | `ServerModelService` 属于本次游戏服务；停止事件关闭该服务 |
| `LoggingIn` / `LoggingOut` | `ClientSessionRuntime` 按 exact `Connection` 建立与撤销 session；`beginConnection()` 先尝试 reload |

这些窗口不额外假定第三方监听器顺序。客户端服务持有启动时的 `ModelSystem`。Required 准备使用专用容量，render owner 在未发布服务的启动窗口用 `managedBlock` 推进宿主交接。失败传播原始 cause；普通 activation 失败归 Catalog 诊断。

## 进程目录配置

`ModelRuntime` 通过 `ProcessAssetPaths` 一次创建完整 `AssetPaths`，供来源、storage、remote 和 export 共同消费。整组探测、目录观察与自有探针清理完成后才发布；getter 没有环境读取或文件系统副作用。

`ModelRuntime.close()` 摘除 system，不撤销进程目录配置；后续 system 重建沿用配置，测试显式注入独立配置。全局目录失败只产生诊断和实例布局，不改变 intrinsic default 的必需失败边界。文件与目录边界由[存储](../../model-management/storage/overview.md)定义。

## 不可变 Proto 发布边界

Java 使用 immutable QuickBuffers message。Nested `Builder` 是 generated mutation boundary；业务只发布完整 `build()` / `parseFrom(...)` 结果。Nested message、repeated 和 map view 可共享。Bytes 复制输入 remaining range，accessor 返回 cursor 独立的只读 view；backing ownership 仍遵循[内存规则](../../native-runtime/implementation/jni-and-memory.md)。

Strict profile 要求未标 `optional` 的 ordinary singular 字段存在，缺失时构造或解析失败。上层隔离整条消息，不发布 partial value；可缺省性应由显式 nullable 或 absence 分支证明。Schema 权威见[模型标准](../../../standards/model-schema/README.md#normative-proto-快照)，差异见[格式与 Schema 问题](../../../status/known-issues/format-and-schema.md)。JNI 只接收 bytes、显式 buffer owner 或 typed handle，message、Builder 和 allocator 不跨 ABI。

## 进程关闭

`ModelRuntime.close()` 在短临界区摘除实例，锁外调用 `ModelSystem.close()`。`attachClientRuntime(AutoCloseable)` 检查 open 与唯一性并接管客户端关闭责任；移交失败仍回收私有 candidate。该持有方式不引入 dedicated 对客户端类的依赖。进程关闭与新 startup 不并发重入。

`ModelSystem.close()` 在锁内 seal，锁外停止 Catalog、客户端 runtime、server runtime，再释放 converted 登记与 builtin。首个失败保留，后续失败作为 suppressed cause。并发再次调用只观察 seal，不证明首次清理完成；`ClientModelService.close()` 则串行等待首次关闭退出。

Catalog 等待 coordinator 与验证池结束后撤候选并终结请求。客户端排空 scheduler，server runtime 停止自有 worker，storage 最后释放消费者锁。具体 worker 终态不能由取消请求或超时替代。
