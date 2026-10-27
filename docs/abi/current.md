# 当前 ABI 版本

项目使用五个全局 ABI。Java 的 `AbiVersion` 硬编码当前值；native 的 `abi_version.h` 硬编码 native ABI。版本迭代与兼容门禁见[维护规则](maintenance.md)。

## 五个全局 ABI

| 字段 | 当前值 | 覆盖范围 |
|---|---|---|
| `CACHE_GEOMETRY` | `0.1.1-unstable` | 几何烘焙语义、布局、私有 baked model schema、几何缓存格式与身份编码 |
| `CACHE_ANIMATION` | `0.1.1-unstable` | 动画烘焙、表达式宿主与 linkage、effect、事件 lowering、共同声明 resource、Semaweave 语义/runtime/codegen、Janino backend/normalizer、私有 baked asset 与表达式制品格式、动画缓存身份编码 |
| `CONTAINER` | `0.1.0-unstable` | 公开 Asset Container 的 header、chunk table 与存储封装 |
| `MODEL_SCHEMA` | `0.1.1-unstable` | 公开 `mixel/character` 模型 Schema |
| `NATIVE` | `0.1.0-unstable` | 全部 JNI 签名、共享内存布局、handle、初始化配置与[设备分类](../architecture/native-runtime/implementation/runtime-capabilities.md#设备配置默认)契约 |

`MODEL_SCHEMA` 不覆盖私有 baked schema。几何和动画派生物分别使用 `CACHE_GEOMETRY`、`CACHE_ANIMATION`。Container、公开 Schema 的契约见[标准入口](../standards/README.md)。

## 保留的协议与环境描述

| 描述 | 当前值或 owner | 保留原因 |
|---|---|---|
| 模型协议与 Forge transport | `ProtocolVersion`，`0.5.0-unstable` | 独立网络会话契约 |
| 表达式 target wire Schema | `ExpressionProtocol.SCHEMA_VERSION = 2` | 网络 target identity，不随本地编译缓存迭代 |
| Portable Program 编码 | `PortableProgramCodecs.v1()` | 对外 program wire，不等于私有编译制品版本 |
| Artifact envelope 家族标记 | V1 | 识别编码家族；组件兼容仍受完整动画 ABI 约束 |
| Java source / target / classfile | `17 / 17 / 61` | 编译目标环境 |
| Janino implementation | `3.1.12-semaweave` 与 `janino-source-sha256` | 实际编译器实现与内容身份 |
| 身份 hash 算法 | `ysm:sha-256`，descriptor version `1` | 算法标识，不是缓存维护计数器 |
| Child context adapter | `none`，version `0` | 未注册 adapter 的占位 descriptor |
| 扩展 checker manifest | `ManifestWriter.VERSION = 1` | 外部扩展兼容检查元数据 |

Registry 声明、事件目录、共享表达式语义、实际 class/member、OS、架构与 SIMD capability 的内容指纹仍保留。原始转换 profile、内置模型 index 和历史导入格式按各自用途维护，不能作为 runtime ABI 的替代值。
