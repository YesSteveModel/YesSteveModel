# 构建指南

当前 Java 工程依赖单独构建的 native 库。Native 与 JAR 的构建和打包流程近期会集成到 GitHub CI；在此之前，本地运行和构建都需要手动准备 native 库。

## 构建脚本

根 `build.gradle` 保留生产依赖、Forge 开发运行和制品配置。以下脚本分别拥有声明处理器、mock 和表达式验证配置：

- [`declaration.gradle`](../gradle/declaration.gradle)：处理器自举、ASM 隔离和处理器测试。
- [`mock.gradle`](../gradle/mock.gradle)：mock source set、Forge runs、场景验证和制品隔离检查。
- [`expression-verification.gradle`](../gradle/expression-verification.gradle)：依赖收据、打包验证和跨 JVM 冷热加载验证。
- [`sound-verification.gradle`](../gradle/sound-verification.gradle)：成品声音接口与方法引用的链接验证。

[`schema.gradle`](../gradle/schema.gradle) 在各脚本设置源码目录后应用，保留 Protobuf 生成源码及其任务依赖。

## 开发环境运行

1. 按照 native 仓库中的 `docs/build_cn.md` 构建当前平台的 native 库。
2. 在 Java 项目根目录修改 `local.properties`，将 `ysm.native_path` 设置为生成的 native 库的绝对路径。
    - 如果该文件尚不存在，先执行一次下一步的 `runClient`；Gradle 会在首次运行时自动生成该文件，修改后再重新运行。
    - `local.properties` 仅保存本机开发配置，已被 Git 忽略，不会被版本控制追踪。
    - Windows 路径建议使用 `/`，避免 `.properties` 文件将反斜杠解释为转义字符。
    - 如需资源诊断，在 `local.properties` 中设置 `ysm.resource_debug=true`。Gradle 为所有 Forge 开发启动任务（包括 `runClient`、`runClient2`、`runServer`）设置 `YSM_DEBUG_RESOURCE=1`；默认关闭。
3. 在 Java 项目根目录运行客户端：

   ```bash
   ./gradlew runClient
   ```

   Windows PowerShell 使用：

   ```powershell
   .\gradlew.bat runClient
   ```

## 构建 JAR

1. 按照同一份 native 构建指南，构建需要支持的各平台 native 库。
2. 将这些库放入 `src/main/resources/META-INF/native/`。当前运行时使用的资源文件名为：Windows x86_64 的 `ysm.dll`、GNU/Linux x86_64 的 `libysm.so`，以及 Android arm64 的 `libysm-android.so`。Android 构建生成的 `libysm.so` 需要以 `libysm-android.so` 放入该目录。
3. 在 Java 项目根目录运行：

   ```bash
   ./gradlew shadowJar
   ```

   Windows PowerShell 使用：

   ```powershell
   .\gradlew.bat shadowJar
   ```

最终 JAR 位于 `build/libs/`。

运行 `.\gradlew.bat verifyPackagedSoundLinkage`，验证成品声音接口的普通调用和方法引用。该任务从最终 JAR 加载声音管理器及实现，使用开发环境提供的 Minecraft 类定义，不启动游戏或调用 Minecraft 方法。`check` 包含此任务；音频设备与真实游戏行为仍需单独验证。

`shadowJar` 关闭依赖的 `Multi-Release` 属性自动传播。当前依赖的版本化条目只有被 Shadow 剔除的模块描述符；保留该属性会使 Forge 在扫描不存在的 `META-INF/versions/` 时崩溃。`verifyExpressionRuntimeBundle` 检查该属性与版本化条目是否一致。新增依赖若包含版本化实现，需要重新评估此配置。

## 表达式引擎依赖

Semaweave 与定制 Janino 跟随 QuickBuffers 的方式，从 `libs/` 引用生产 jar。更新时，先在 Janino 仓库运行 `./build-semaweave.ps1 -Repository <repository>`，测试并发布 fork。再在 Semaweave 根目录运行：

```powershell
.\gradlew.bat :semaweave-engine:check :semaweave-backend-janino:check verifyProductionDependencyBoundaries :semaweave-acceptance:test publishToMavenLocal -Pjanino.repository=<repository> --refresh-dependencies
```

将发布的两个 Semaweave jar 与 `janino-3.1.12-semaweave.jar` 复制到 `libs/`，保留文件名中的版本。源码、Javadoc 和 test fixtures 不进入运行包。`ysmImplementation` 显式声明 POM 中的运行依赖 `commons-compiler:3.1.12`。运行时的 `asm` 与 `asm-tree` 使用 Forge 提供的版本；工程以 `9.7.1` 声明编译和测试依赖，不将运行时 ASM 打包或 relocate。Mixin 插件与表达式引擎共用宿主 ASM，避免改写 `IMixinConfigPlugin` 的回调签名。注解处理器继续内嵌私有 ASM，因为扩展项目的 processor classpath 不保证提供 ASM。

`shadowJar` 将 Semaweave、Janino 和 commons-compiler relocate 到 `com.elfmcys.ysm.internal.expression` 下。Semaweave 根据实际加载的引擎包名生成代码，无需额外改写字符串。宿主从 manifest 的 `rootBinaryName()` 或 `generatedPackage()` 获取生成名称。更新后运行 `.\gradlew.bat verifyExpressionRuntimeBundle`，检查依赖指纹、源码收据、包名隔离、Mixin 插件 ABI，以及使用宿主 ASM 时打包后的编译、加载和执行。

## Java 代码格式

在项目根目录运行 `./gradlew spotlessApply` 自动格式化，运行 `./gradlew spotlessCheck` 检查格式。Windows PowerShell 使用 `.\gradlew.bat`。`check` 包含格式检查。

Spotless 覆盖手写 Java 源码、测试、验证程序和 `buildSrc` Java 源码，排除生成源码与资源目录。格式器固定为 Eclipse JDT 4.26，使用 `config/spotless/eclipse-codestyle.xml`。配置来源和许可见 `config/spotless/README.md`。

## ABI 与表达式依赖

五个全局 ABI 在 `AbiVersion` 中维护；native 侧在 `abi_version.h` 中维护同值 native ABI。执行 `verifyNativeAbiDefinitions` 可检查两侧定义。版本归属见[当前 ABI 版本](abi/current.md)，兼容门禁见[维护规则](abi/maintenance.md)。

修改动画 ABI 或表达式引擎后，执行 `buildExpressionRuntime`。任务把当前动画 ABI 作为通用 `artifactAbi` 参数传给引擎构建，并安装配套 engine/backend jars。`compileJava` 检查 bundled jars 的 ABI 和构建 receipt，不接受混合构建。
