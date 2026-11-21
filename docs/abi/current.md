# 当前 ABI 版本

项目维护五个全局 ABI。`AbiVersion` 是 Java 侧唯一的当前值定义；测试和文档直接引用对应字段，不另行抄录版本号或各分量。Native 侧在 `abi_version.h` 中维护相同值的 native ABI。版本迭代方式与兼容门禁见[维护规则](maintenance.md)。

## 五个全局 ABI

| `AbiVersion` 字段 | 覆盖范围 |
|---|---|
| `CACHE_GEOMETRY` | 几何烘焙语义、数据布局、私有 baked model Schema、几何缓存格式及身份编码 |
| `CACHE_ANIMATION` | 动画烘焙、表达式宿主与 linkage、effect、事件 lowering、共同声明资源、私有 baked asset 及动画缓存身份编码 |
| `CONTAINER` | 公开 Asset Container 的 header、chunk table 与存储封装 |
| `MODEL_SCHEMA` | 公开 `mixel/character` 模型 Schema |
| `NATIVE` | 全部 JNI 签名、共享内存布局、handle、初始化配置及[设备分类](../architecture/native-runtime/implementation/runtime-capabilities.md#设备配置默认)契约 |

`MODEL_SCHEMA` 不覆盖私有 baked Schema。几何和动画派生物分别以完整的 `CACHE_GEOMETRY`、`CACHE_ANIMATION` 作为 Bake 分区的 `cacheVersion`；物理文件路径只使用整数 ID，不编码版本号。Container 与公开 Schema 的规范见[标准入口](../standards/README.md)。

## 保留的协议与环境描述

| 描述 | Java 定义 | 保留原因 |
|---|---|---|
| 模型协议与 Forge transport | `ProtocolVersion.java` 的 `CURRENT` / `TRANSPORT_VERSION` | 独立的网络会话契约 |
| 表达式 target wire Schema | `ExpressionProtocol.java` 的 `SCHEMA_VERSION` | 网络层的目标身份描述，不随本地编译缓存迭代 |
| Portable Program 编码 | `PortableProgramCodecs.v1()` | 对外 program wire 编码，不等同于私有编译制品版本 |
| Artifact envelope 家族标记 | `ArtifactCodecs.v1()` | 识别封装格式家族，内部组件兼容由引擎制品 ABI 约束 |
| Java 源码 / 目标 / 字节码版本 | `JavaCompilerOptions.java17()` | 编译目标环境 |
| Semaweave 制品 ABI | `ArtifactAbi.VERSION` / `EngineAbi.current()` | 覆盖引擎语义、runtime、codegen、backend 与 normalizer 以及内部 AOT 制品格式；独立于宿主动画 ABI，宿主不另加独立的引擎版本门槛 |
| Janino 实现版本 | `JaninoJavaCompilerBackend.IMPLEMENTATION_VERSION` 与 backend descriptor 的源码指纹 | 标识实际编译器实现与内容身份 |
| 身份 hash 算法 | `ExpressionIdentityHash.java` 的 `descriptor()` | 算法标识，不是缓存维护计数器 |
| 子上下文默认适配器 | `ContextTypes.create()` 的默认 adapter descriptor | 未注册 adapter 时的占位描述 |
| 扩展 checker manifest | `ManifestWriter.java` 的 `VERSION` | 外部扩展兼容性检查元数据 |

Registry 声明、事件目录、共享表达式语义、实际类与成员签名，以及 OS、CPU 架构与 SIMD 能力的内容指纹均继续保留。raw 模型转换 profile、内置模型索引和历史导入格式各自按用途独立维护，不能替代运行期 ABI。
