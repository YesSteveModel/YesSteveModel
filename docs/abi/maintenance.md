# ABI 维护规则

[当前版本清单](current.md)列出五个全局 ABI 及其覆盖范围。模组发布号不能替代这些版本。

## 版本归约与迭代

某域内任一组件发生兼容性变化时，统一迭代该域全局 ABI，再重建该域制品。不再分别维护 host、effect、lowering、engine、backend、normalizer 或私有组件格式的 ABI 计数器。一次变化跨越多个域时，分别迭代受影响的域；native 实现变化不自动要求清空几何或动画缓存。

## 缓存与表达式制品

Geometry cache 使用 `geometry-<CACHE_GEOMETRY>`，动画 cache 使用 `animation-<CACHE_ANIMATION>/<实际引擎包名>`。完整 ABI 进入缓存目录、profile 和身份编码。目录隔离不替代内容、能力、registry、binding plan、backend source hash 和实际 Java linkage 检查。

动画 ABI 同时作为 Semaweave 的通用构建参数 `artifactAbi`。`buildExpressionRuntime` 读取 `AbiVersion.CACHE_ANIMATION`，重建并安装配套 engine/backend jars；`verifyBundledExpressionAbi` 拒绝 ABI 不符或构建 receipt 不一致的组合。Semaweave 不拥有另一份手工版本号。

`ArtifactAbi.VERSION` 保留完整字符串；只能接收整数的 descriptor 和内部组件头使用数值投影 `major × 1000000 + minor × 1000 + patch`。宿主侧的 `ExpressionAbi` 从 `AbiVersion.CACHE_ANIMATION` 计算数值投影。投影要求 major ≤ 2000、minor/patch ≤ 999，不能替代完整字符串门禁。HostAbi、编译身份和组件指纹仍覆盖完整版本。

实际引擎包名自动进入动画缓存目录和身份。开发与打包重定位后的 bytecode 因而隔离，不需要再维护包名对应的版本号。细节见[派生物存储](../architecture/model-management/storage/derived-artifacts.md)和[Molang 运行时](../architecture/animation/implementation/molang-runtime.md)。

## Native 符号门禁

Native ABI 语法为 `[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9]+)?`。后缀只允许字母和数字；两侧保留大小写及数字原文，不归一化。将完整版本中的 `.`、`-` 替换为 `_`，再加前缀 `YSM_ABI_`。例如 `0.1.0-unstable` 对应 `YSM_ABI_0_1_0_unstable`。该语法使转换无碰撞。

Native 库只导出当前版本的标记变量。Java 用 JNA 打开指定绝对路径，查询期望符号，再调用 `System.load` 执行一次性 JNI 注册。Java 不读取变量值，也不调用 ABI 探针。缺少符号、库加载失败或 JNI 注册失败均拒绝该库；不尝试历史符号或降级绑定。符号检查不能先于 OS 加载、依赖解析和静态构造。

生产加载在 native holder thread 内完成。`System.load` 成功取得 JVM 引用后，Java 关闭临时 JNA 句柄。构建工具、测试、mock 和 benchmark 复用 `NativeRuntime.load`。`verifyNativeAbiDefinitions` 可检查 Java/native 的硬编码定义是否一致。加载与内存边界见[Native 运行边界](../architecture/native-runtime/implementation/runtime-capabilities.md)。

[设备分类入口](../architecture/native-runtime/implementation/runtime-capabilities.md#设备配置默认)也由同一全局 ABI 与生产 JNI 注册覆盖。分类未知或失败只决定保守配置默认，不放宽库的符号门禁。

## 开发版本的特殊门禁

本节定义 Container、Schema、cache ABI 与 protocol 的开发标记与比较规则，落实[开发版本精确兼容](../product-decisions/decisions/model-compatibility.md#bcdevelopment-version-qualifiers-are-exact)：

- 任一侧 qualifier 包含 `unstable`、`dev`、`snapshot` 子串时，两个完整原始版本字符串应严格相等。
- 标记检测忽略大小写，原始字符串比较区分大小写。不删除后缀，不归一化，不放行 patch 差异。
- 只有两侧均无开发标记时，才使用对应域的稳定版策略。无效版本直接拒绝。

Native 符号门禁始终要求完整版本严格相等。缓存保留完整 ABI namespace，不扫描或迁移旧目录；移除 `-unstable` 不会自动获得缓存复用资格。相同 ABI 也不能替代内容指纹与环境验证。网络仍要求[同一模组版本的同一构建](../product-decisions/decisions/session-failure.md#bcunstable-protocol-requires-matched-builds)。
