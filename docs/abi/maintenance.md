# ABI 维护规则

[当前版本清单](current.md)列出了五个全局 ABI 及其覆盖范围。模组发布号不能替代这些 ABI 版本。

## 版本归约与迭代

某个域内只要任一组件发生不兼容变更，就统一递增该域的全局 ABI，并重建对应派生物。宿主侧的 effect、lowering 与私有动画格式共用 `CACHE_ANIMATION`，Semaweave 则独立维护引擎制品 ABI。如果一次改动跨越多个域，分别递增受影响的域即可；native 实现变化本身不会自动要求清空几何或动画缓存。

## 缓存与表达式制品

几何缓存与动画集分别使用完整的 `CACHE_GEOMETRY`、`CACHE_ANIMATION` 作为 Bake 索引的 `cacheVersion`。ABI、实际加载的引擎包名与精确输入都会进入索引身份，而磁盘物理路径只包含整数 ID。即使索引命中，也不能跳过内容完整性、平台能力、registry、binding plan、backend 源码 hash 与实际 Java 链接检查。

`AbiVersion.CACHE_ANIMATION` 负责给宿主动画与表达式契约做版本控制。Semaweave 的语义、runtime、codegen、backend、normalizer 与内部制品格式共用独立的制品 ABI（由 `ArtifactAbi.VERSION` 定义），不要求与宿主版本相等。当引擎制品变化影响到缓存语义或格式时，由维护者同步递增 `CACHE_ANIMATION`——这是一条维护约定，运行期、构建脚本和测试中不会额外设立联动校验。YSM 固定消费 `libs/` 目录下配套的引擎与后端 JAR，由打包验证检查构建收据一致性；依赖更新步骤见[构建指南](../build.md#表达式引擎依赖)。

`ArtifactAbi.VERSION` 保留完整版本字符串；对于只能接收整数的 descriptor 和内部组件头，使用数值编码 `major × 1000000 + minor × 1000 + patch`。宿主侧的 `ExpressionAbi` 也按相同方式从 `AbiVersion.CACHE_ANIMATION` 计算数值编码。该编码要求 `major ≤ 2000`、`minor` 与 `patch` 均 `≤ 999`，且不能替代完整字符串门禁。宿主 `HostAbi` 不额外设立独立的引擎版本门槛；引擎自身的编译身份和组件指纹仍按其内部契约覆盖完整版本。

实际加载的引擎包名会自动进入动画集的精确索引身份，从而天然隔离开发环境与打包重定位后的字节码，无需再为包名单独维护版本号。细节见[派生物存储](../architecture/model-management/storage/derived-artifacts.md)和[Molang 运行时](../architecture/animation/implementation/molang-runtime.md)。

## Native 符号门禁

Native ABI 版本遵循语法 `[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9]+)?`，可选后缀只允许字母和数字，两侧都原样保留大小写和数字，不做归一化。导出符号名由完整版本中的 `.` 和 `-` 替换为 `_`，再加前缀 `YSM_ABI_` 得到（例如 `0.1.0-unstable` 对应 `YSM_ABI_0_1_0_unstable`），该语法可保证转换无碰撞。

Native 动态库只导出当前版本的标记变量。Java 侧先通过 JNA 打开指定绝对路径的库、查找期望符号，确认存在后再调用 `System.load` 执行一次性 JNI 注册；整个过程不读取该变量的值，也不调用 ABI 探针函数。只要缺少符号、加载失败或 JNI 注册失败，就直接拒绝该库，绝不尝试兼容历史符号或降级绑定。注意，符号检查发生在动态库加载之后，无法先于操作系统加载、依赖解析和静态初始化执行。

生产环境的库加载在 native holder 线程中完成。`System.load` 成功建立 JVM 引用后，Java 会关闭临时的 JNA 句柄。构建工具、测试、mock 和 benchmark 统一复用 `NativeRuntime.load`。加载流程与内存边界见[Native 运行边界](../architecture/native-runtime/implementation/runtime-capabilities.md)。

[设备分类入口](../architecture/native-runtime/implementation/runtime-capabilities.md#设备配置默认)同样受这一全局 ABI 与生产 JNI 注册保护。分类结果未知或失败只会退回保守的默认配置，不会放宽动态库的符号门禁。

## 开发版本的特殊门禁

本节定义 Container、Schema、缓存 ABI 与通信协议中开发版本标记的比较规则，用于落实[开发版本精确兼容](../product-decisions/decisions/model-compatibility.md#bcdevelopment-version-qualifiers-are-exact)：

- 只要任一侧的 qualifier 包含 `unstable`、`dev` 或 `snapshot` 子串，两侧的完整原始版本字符串就必须严格相等。
- 检测开发标记时忽略大小写，但比较原始版本字符串时严格区分大小写：不剥离后缀，不做归一化，也不放行 patch 版本差异。
- 只有两侧都不含开发标记时，才启用对应域的稳定版兼容策略；格式非法的版本号一律直接拒绝。

Native 符号门禁在任何情况下都要求完整版本严格相等。缓存索引按完整 ABI 字符串记录，既不扫描旧目录也不做迁移，因此移除 `-unstable` 后缀不会自动复用旧缓存；同样，ABI 相同也不能替代内容指纹与环境校验。网络会话层仍要求[同一模组版本的同一构建](../product-decisions/decisions/session-failure.md#bcunstable-protocol-requires-matched-builds)。
