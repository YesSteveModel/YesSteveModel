# 构建指南

当前 Java 工程依赖单独构建的 native 库，因此在本地运行和打包前，都需要先手动准备好配套的 native 库。

## 构建脚本

根 `build.gradle` 负责生产依赖、Forge 开发运行、制品打包与默认单测/独立 Mock 测试（`serverMockTest`）配置，其余专项配置拆分在以下脚本中：

- [`declaration.gradle`](../gradle/declaration.gradle)：注解处理器自举、ASM 隔离和处理器测试。
- [`benchmark.gradle`](../gradle/benchmark.gradle)：JMH 微基准、百人无头压测（`serverScaleLoad`）与受控读句柄观测。
- [`expression-verification.gradle`](../gradle/expression-verification.gradle)：依赖收据、打包验证以及跨 JVM 的冷热加载验证。
- [`sound-verification.gradle`](../gradle/sound-verification.gradle)：成品声音接口与方法引用的链接验证。

[`schema.gradle`](../gradle/schema.gradle) 会在各脚本配置完源码目录后应用，负责 Protobuf 生成源码及其任务依赖。

## 开发环境运行

1. 按照 native 仓库中的 `docs/build.md` 构建当前平台的 native 库。
2. 在 Java 项目根目录编辑 `local.properties`，将 `ysm.native_path` 设置为生成的 native 库的绝对路径。
    - 如果该文件尚不存在，可以先执行一次下一步的 `runClient`；Gradle 会在首次运行时自动生成它，修改后再重新运行。
    - `local.properties` 仅用于保存本机开发配置，已被 Git 忽略，不会进入版本控制。
    - 在 Windows 上建议使用 `/` 作为路径分隔符，避免 `.properties` 文件将反斜杠解释为转义字符。
    - 如需开启资源诊断，在 `local.properties` 中设置 `ysm.resource_debug=true`。Gradle 会为所有 Forge 开发启动任务（包括 `runClient`、`runClient2`、`runServer`）注入 `YSM_DEBUG_RESOURCE=1`；该开关默认关闭。
    - 如需开启网络诊断，设置 `ysm.network_debug=true`，Gradle 会为同一组启动任务注入 `YSM_DEBUG_NETWORK=1`。客户端和服务端各自读取所在进程的环境变量；直接启动进程时，只要该环境变量存在即视为开启（即使值为空或 `0`）。
3. 在 Java 项目根目录启动客户端：

   ```bash
   ./gradlew runClient
   ```

   Windows PowerShell 使用：

   ```powershell
   .\gradlew.bat runClient
   ```

## 网络诊断

`YSM_DEBUG_NETWORK` 在进程启动时读取并固定，未设置时默认关闭，且与资源诊断各自独立开关。诊断日志使用 `INFO` 级别，统一带 `YSM_NETWORK` 前缀。

单次发送会产出两层日志：

- `layer=message`：记录消息类型、协议 ID、Proto 字节数，以及在当前消息中适用的主体、intent、字典项数和值数量。
- `layer=frame`：记录物理帧体积，并用 `path=ordinary` 与 `path=dispatch` 区分发送入口。

日志中的事件阶段含义如下：

- 发送侧：`send-attempt` 表示发起发送尝试，`send-submitted` 仅说明宿主传输层已接手，不保证对端已收到。
- 接收与处理侧：`received` 表示收到消息；`selection-committed`、`selection-rejected`、`accepted`、`applied` 和 `self-ignored` 分别标记对应的业务处理阶段；`dropped` 会附带拒收原因。
- 同步事件：`event-delivered` 表示 `ysm.sync` 已交给当前安装的执行入口，但不代表脚本里一定存在对应 handler 或执行成功。

关闭开关时，诊断入口不会构造摘要、参数数组或快照。开启后，逐包格式化和日志 I/O 会引入额外开销，因此做性能测量时必须关闭网络诊断。原有的 `WARN` 和 `ERROR` 日志不受此开关影响。

## 构建 JAR

1. 按照同一份 native 构建指南，分别构建需要支持的各平台 native 库。
2. 将四个目标平台的配套库放入 `src/main/resources/META-INF/native/`：Windows x86_64 对应 `ysm.dll`，GNU/Linux x86_64 对应 `libysm.so`，Android arm64 对应 `libysm-android.so`，macOS arm64 对应 `libysm.dylib`。其中 Android 构建生成的 `libysm.so` 需要改名为 `libysm-android.so`。该资源目录不进入版本控制。
3. 在 Java 项目根目录执行打包：

   ```bash
   ./gradlew shadowJar
   ```

   Windows PowerShell 使用：

   ```powershell
   .\gradlew.bat shadowJar
   ```

最终 JAR 生成在 `build/libs/` 目录下。

运行 `.\gradlew.bat verifyPackagedNativeStorage` 可以检查这四个 native 资源是否齐全，并用当前平台的包内库执行真实的存储读写与协调验证。该探针只加载最终 JAR 中的生产类、直接提取包内库，不读取 `YSM_NATIVE_PATH` 或 `local.properties`。`check` 任务已包含此检查；其余三个平台的运行时行为和最低系统版本仍需在对应环境下验证。

五类存储业务桥要求 Java 侧与四个平台的 native 库来自同一套配套构建——即使 Native ABI 标记相同，也不能保证旧库包含当前新增的 JNI 入口。目前 Windows 包内探针覆盖 allocator、Catalog、Remote、Bake、PlayerData 及根协调桥；其余三个平台库仅有交叉编译、资源 hash 与导出和依赖检查的证据。

运行 `.\gradlew.bat verifyPackagedSoundLinkage` 可以验证成品声音接口的普通调用与方法引用。该任务从最终 JAR 加载声音管理器及其实现，并借助开发环境提供的 Minecraft 类定义完成链接校验，不启动游戏也不会调用 Minecraft 方法。`check` 任务同样包含此检查；实际音频设备与游戏内播放行为仍需单独验证。

`shadowJar` 关闭了依赖项 `Multi-Release` 属性的自动传播。目前依赖中的版本化条目只包含已被 Shadow 剔除的模块描述符，如果保留 `Multi-Release` 属性，Forge 在扫描不存在的 `META-INF/versions/` 时会崩溃。`verifyExpressionRuntimeBundle` 会校验该属性与版本化条目是否一致；以后如果新增包含真实版本化实现的依赖，需要重新评估这项配置。

## 表达式引擎依赖

Semaweave 与定制 Janino 固定引用仓库 `libs/` 目录下的预构建生产 JAR：`semaweave-engine-0.1.0-SNAPSHOT.jar`、`semaweave-backend-janino-0.1.0-SNAPSHOT.jar` 和 `janino-3.1.12-semaweave.jar`。YSM 构建既不读取它们的源码，也不从 Maven 仓库解析这三个制品；更新依赖时，直接把配套的新 JAR 替换进 `libs/` 即可。

源码、Javadoc 和 test fixtures 不会打进运行包。`ysmImplementation` 显式声明了 POM 中的运行依赖 `commons-compiler:3.1.12`。引擎生产依赖的 `fastutil:8.5.9` 直接复用游戏提供的同版本类，不另行打包或 relocate；运行时的 `asm` 与 `asm-tree` 同样复用 Forge 提供的版本，工程仅在编译和测试阶段声明 `9.7.1`，不会打包或 relocate 运行时 ASM。Mixin 插件与表达式引擎共用宿主 ASM，以免破坏 `IMixinConfigPlugin` 的回调签名；注解处理器则继续内嵌私有 ASM，因为扩展项目的 processor classpath 不保证提供 ASM。

`shadowJar` 会将 Semaweave、Janino 和 `commons-compiler` 重定位到 `com.elfmcys.ysm.internal.expression` 包下。Semaweave 会根据实际加载的引擎包名生成代码，无需额外改写字符串，宿主只需从 manifest 的 `rootBinaryName()` 或 `generatedPackage()` 读取生成名称。更新依赖后，运行 `.\gradlew.bat verifyExpressionRuntimeBundle`，即可检查依赖指纹、源码收据、包名隔离、Mixin 插件 ABI，以及在复用宿主 ASM 条件下打包后的编译、加载与执行流程。

## Java 代码格式

数据库相关测试通过 `YSM_NATIVE_PATH` 加载生产 native 库。`NativeStorageIntegrationTest` 和 `SharedStorageProcessTest` 使用真实 JNI 与子 JVM；`DatabaseWorkTest` 验证有界执行与关闭逻辑。`CachePrunerTest` 与 `CacheMaintenanceTest` 覆盖两级缓存池策略、来源覆盖、逐根目录清理周期以及命令交错；`SharedStorageProcessTest` 还覆盖两池连续时间窗口、崩溃恢复以及深目录路径下的冷热读取。测试依赖的 `sqlite-jdbc` 仅用于建立外部数据库观察并验证同 JVM 共存，不会打进生产包；生产路径只使用私有 native SQLite。

`PlayerPreferencesTest` 验证按 UUID 存储的偏好、首次继承以及非法记录保留；`StorageWriteBufferTest` 验证刷盘周期、逐项反馈以及清除与关闭的竞态。`SharedStorageProcessTest` 的 SQLite 场景还覆盖独立 JVM 下的偏好读写和 usage max 统计。

代码格式检查使用 Spotless：在项目根目录运行 `./gradlew spotlessApply` 自动格式化，运行 `./gradlew spotlessCheck` 检查格式（Windows PowerShell 使用 `.\gradlew.bat`），`check` 任务已包含格式检查。

Spotless 覆盖手写 Java 源码、测试、验证程序和 `buildSrc` 下的 Java 源码，排除生成源码与资源目录。格式器固定为 Eclipse JDT 4.26，规则文件为 `config/spotless/eclipse-codestyle.xml`，配置来源和许可见 `config/spotless/README.md`。

## ABI 与表达式依赖

五个全局 ABI 统一在 `AbiVersion` 中维护，native 侧在 `abi_version.h` 中维护相同值的 native ABI。各版本的覆盖范围见[当前 ABI 版本](abi/current.md)，版本迭代与兼容门禁见[维护规则](abi/maintenance.md)。

Semaweave 的制品 ABI 由 `ArtifactAbi.VERSION` 独立定义。YSM 编译时不会把引擎 ABI 与 `AbiVersion.CACHE_ANIMATION` 做绑定比较，宿主只保留实际引擎包名和编译身份，不额外拼接或校验引擎 ABI；引擎自身的期望版本、格式与加载器校验仍照常执行。更新依赖后，按上一节步骤运行打包验证即可。
