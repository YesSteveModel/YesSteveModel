# 模型管理验证架构

本页记录模型管理子系统的 L0–L3 验证入口、环境准备与产物位置；验证分层总则见[验证边界](policy.md#验证分层架构)，当前缺口见[模型管理问题](../status/known-issues/model-management.md)。

## 验证分层与命令入口

验证复用生产环境的 catalog、session、授权、`TransportPort` seam 与资源链路（原多进程 `mockHost` / `HostSupervisor` 体系已移除）。

| 层级 | 入口命令 | 覆盖范围 |
|---|---|---|
| L0 静态门禁 | `.\gradlew.bat test --tests com.elfmcys.ysm.network.ServerSideIsolationTest verifyVerificationPackaging` | Dedicated Server 字节码不引用客户端类；发布包不含 `verification`/`benchmark` 类 |
| L1 契约单测 | `.\gradlew.bat test`（纯单测）<br/>`.\gradlew.bat serverMockTest`（`@Tag("mock")`） | 目录/存储/缓存/资源/会话契约；`serverMockTest` 独立验证百人并发、背压隔离与重载不变量 |
| L2 无头压测 | `.\gradlew.bat serverScaleLoad -PscalePlayers=100 -PscaleDurationSec=10` | 真实多线程 Catalog、Dispatch 与 Netty `EmbeddedChannel` 背压下的 MSPT 增量、lag spike 与 gameplay 延迟（支持 `-PscalePlayers=200\|300`、`-PscaleSlowRatio`、`-PscaleReloadIntervalMs`、`-PscaleOutput=<json>`，可配 JFR） |
| L3 真机冒烟 | `runServer` + `runClient` | Forge 频道握手、真实登录/重载/切模/断连及[视觉一致性验收](../standards/conformance.md#视觉一致性验收) |

- **进程退出边界**：正常 JVM 退出遵循[JVM 进程退出](../architecture/runtime/overview.md#jvm-进程退出)策略，直接放弃运行资源与未提交工作；进程内 owner 回收由 L1 与受控测试覆盖。

## 启动模型一次性预热

- **L1 入口**：`StartupModelWarmupTest`、`AutomaticPreviewScopeTest`、`ClientModelBootstrapTest`（随 `./gradlew test` 运行），覆盖目标策略、缓存与 AOT 命中/缺件组合、阻塞可选持久队列下的 Ready 交付，以及真实 `ClientInputs`/本地 Catalog/偏好组合。
- **L3 确认与边界**：主菜单真实预热、GPU 发布与进入世界替换原 Flight 由发版前 L3 `runClient` 冒烟确认；L1 不证明主菜单帧时间收益或缓存长期驻留，当前状态见[支持状态](../status/support-and-verification.md)。

## SQLite 存储、短路径缓存与 OOM 边界

- **环境准备**：SQLite 与原生存储测试需将 `YSM_NATIVE_PATH` 指向与源码配对的 native DLL（默认 `./gradlew test` 由 `gradle/native.gradle` 注入）。
- **核心 L1 套件**：
  - 存储与偏好：`NativeStorageIntegrationTest`、`SharedStorageProcessTest`（多进程与自然退出）、`CacheRootCoordinatorTest`、`CachePrunerTest`、`CacheMaintenanceTest`、`PlayerPreferencesTest`、`NativeCancellationIntegrationTest`。
  - 短路径缓存与路由：`FlatCacheLayoutTest`、`AnimationCacheIndependenceTest`、`AnimationInputStoreTest`、`BakedAssetWriterTest`、`ContentStorageRoutingTest`、`ReloadableModelCatalogTest`。
  - OOM 隔离边界：`EntityExpressionRuntimeTest`、`EntityModelBindingTest`、`ModelRenderTargetCacheTest`、`ReloadableModelCatalogTest`、`NativeRendererTest`。
- **制品与采样入口**：发布包原生存储由 `verifyPackagedNativeStorage` 校验（见[构建指南](../build.md)）；容器目录读取区间由 `DirectContainerReadSample` 观测（见[Direct 容器目录读取](../tools/benchmark/README.md#direct-容器目录读取)）；目录/ZIP/7z 物化与维护窗口由 `CatalogSourceBenchmark`、`CatalogSourceObservation` 与 `CacheMaintenanceObservation` 采样。
- **L3 手工核对关注点**：在隔离 `YSM_HOME` 中复核五类场景——核心会话与非法初值/读取失败、本地与默认双角色及跨世界恢复、真实 TOML 配置授权与撤权（以新 metadata/chunk 响应为准）、版本化偏好恢复与降级（用独立 SQLite 读取器核对落盘）、集成服命令闭环（`set`/`export`/`grant`/客户端 `roaming clear`）。
- **验证边界**：并发锁与在线 SQL 干扰通过 `SqliteProcess` 置于独立 JVM 以遵守 SQLite POSIX 锁语义；L1 不证明断电耐久、OS watcher 自动通知或全入口堆耗尽存活。

## 跨平台链接、生成预览与有界读取

### Catalog 链接跨平台 JAR

`catalogLinksVerificationJar` 打包生产类与受控测试为 `build/libs/catalog-links-verification.jar`（不启动 Forge）：

```powershell
.\gradlew.bat catalogLinksVerificationJar verifyVerificationPackaging
java -jar build/libs/catalog-links-verification.jar <fixtures> <evidence> windows
```

Linux 下分别在原生文件系统执行 `linux-ext4`，以及指向 Windows 根 junction（内含 `nested-junction`）的 `linux-drvfs <windows-junction-root>`。

- **环境变量与产物**：需设置 `YSM_NATIVE_PATH` 并使用全新 `<fixtures>` 目录；证据目录保存 JAR/DLL SHA-256、JVM 属性与逐项结果。可选 `YSM_VERIFICATION_TESTS` 按逗号指定 `com.elfmcys.ysm` 下的套件后缀；Legacy 链接用例需额外提供 `YSM_LEGACY_LINK_FIXTURE`、`YSM_LEGACY_LINK_MODEL_ID` 与 `YSM_LEGACY_LINK_SHA256`（未设则跳过）。
- **验证边界**：Linux 校验必须在发行版原生文件系统进行，拒绝访问用例需使用非特权身份（无 `CAP_DAC_OVERRIDE` / `CAP_DAC_READ_SEARCH`）；Windows 缺少 `fileKey` 时同路径同类型替换检测可能跳过。

### 生成预览制品复核

设置 `YSM_PREVIEW_HOST_ARTIFACT`（导出 `.mxc`）、`YSM_PREVIEW_HOST_SOURCE`（原始模型目录）及可选 `YSM_PREVIEW_HOST_IMAGE`（预览缓存文件），复核真实宿主导出成品：

```powershell
.\gradlew.bat test --tests '*PreviewHostArtifactTest'
```

未配置环境变量时测试跳过，普通单测通过不代表真实宿主导出通过。

### 有界文件读取

`verifyReadHandles` 包装 Java 只读 channel 与 stream，记录路径、调用栈、读取字节数与关闭终点（覆盖 `ChannelReadPhaseTest`、`FileChunkBatchTest`、`AnimationSetArtifactTest`、`AnimationStoreTest`、`RuntimeContentStoreTest`）；`FullAnimationReadProbe` 在独立 JVM 核对暖加载句柄复用：

```powershell
.\gradlew.bat verifyReadHandles -PreadHandleOutput=<output-json-path>
```

Java 探针不观测 native archive 内部 I/O，也不替代 Forge 特有 provider 或跨文件系统实测。
