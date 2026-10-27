# Native 运行边界

## 职责与边界

Native 是 Java 调用的能力层，消费明确的 bytes、几何、骨骼状态和输出范围。Java 仍拥有来源、identity、授权、预算、存储、发布与生命周期。支持平台由[平台决策](../../product-decisions/requirements/req-platform-availability.md)和[当前状态](../../status/support-and-verification.md)限定，CMake 可构建不等于产品可用。

本页细化[系统架构](../README.md#系统边界)的计算能力与 Java 领域交接。

### 能力与依赖方向

| 边界 | 当前职责 | 依赖方向 |
|---|---|---|
| 通用 runtime 基础 | buffer、分配、CPU capability、日志、状态与通用数据结构 | 位于底层，不依赖 codec、renderer、JNI 或 legacy 业务 |
| 生成 Proto 类型 | Native 所需的当前 schema 值类型与序列化接口 | 独立于运行算法；由渲染、legacy 和最终 glue 按需消费 |
| `ysm::codec` | hash、压缩、archive、图像和音频算法 | 依赖通用 runtime，不依赖 JNI glue |
| `ysm::gfx::bake` / `ysm::gfx::renderer` | baked 数据、数学与 CPU render | 依赖通用 runtime；只在需要 schema 输入的实现边缘消费生成 Proto 类型 |
| `ysm::java` | JNI descriptor、注册、引用、buffer 与 typed handle 基础设施 | 依赖通用 runtime，不实现领域算法 |
| `ysm::legacy` | legacy bundle/v3 解码、历史 codec 与 current schema 投影 | 复用通用 codec，并在投影边缘消费生成 Proto 类型 |
| `ysm::lib` 与 legacy Java bridge | 把上述能力适配成唯一 Java 可加载的共享库，并拥有 JNI 返回值/结果 owner 的边界 | 作为最外层聚合；算法不应反向依赖该 glue |

这个拆分约束的是依赖方向，不增加第二套模型 authority。Java 仍决定来源、identity、预算、存储、发布和生命周期；Native 子系统之间也不能借共享库聚合关系交换这些业务状态。

Native 只增加有实际消费者的能力。跨语言两侧不需要对称的 identity API、session 或 cache owner；提前复制这些机制会产生第二份 authority、生命周期与错误路径。格式标准仍约束每个实现，但不要求没有消费者的一侧提前增加解析能力。

## 运行作用域与所有权

Native 初始化与加载线程属于进程，调用不创建另一套业务运行时。Java owner 保活同步输入、接纳输出并裁决外部效果；native RAII 保护尚未交付的对象。共享 handle 保留 backing，但派生 view 没有独立关闭权。

## 数据流与交接

图中实线表示结果交付，虚线表示调用期借用。

```mermaid
flowchart LR
    Java["Java 领域 owner"] -. 明确范围与保活 .-> Call["JNI 同步调用"]
    Call -->|计算与验证| Native["native 能力"]
    Native -->|完整结果或失败| Adopt["Java adoption"]
    Adopt -->|成功才转移关闭责任| Owner["输出 owner"]
    Adopt -->|失败| Rollback["构造回滚"]
    Owner -->|原领域门禁| Publish["Ready / texture / host 提交"]
```

### JNI 与所有权

模型数据跨 JNI 只传 protobuf bytes、明确的 buffer owner 或 typed handle，不暴露 QuickBuffers message、Builder、parse cursor 或 allocator。同步借用与 owning output 分开；raw address 和 `jlong` 本身不证明资源仍然存活。

输入 owner 应覆盖整个调用窗口，返回对象在采用成功后才移交关闭责任。Wrapper、view 或 Cleaner 注册失败仍由构造路径回收，不能泄漏半成品。派生 view 不取得 backing 的独立关闭权；对应类型使用自己的释放协议。

`reachabilityFence` 只保证同步调用期 owner 可达，不允许 close 与使用并发。临时 owning buffer、共享资源和 thread-affine GPU 各按[生命周期规则](../model-management/client-resources/overview.md#引用撤销与物理关闭)终结，不能用一个 fence 替代。

明确保活和采用边界增加包装责任，但避免跨语言裸指针、Cleaner 与显式 close 竞争。Buffer token、通用 opaque handle、结果视图及构造回滚见[JNI 实现](implementation/jni-and-memory.md)。

音频 checkpoint 是可共享的不可变值，恢复结果是每播放独占的 decoder。Checkpoint 不持有会话、输入借用地址或活动解码时间线，也不序列化到进程外。共享值只撤强引用并交给原 handle owner 回收，私有 decoder 仍在播放终态显式关闭。这个划分允许并发恢复，又不引入业务 refcount 或淘汰回调；代价是深拷贝 codec/parser 状态及独立内存计价。捕获条件与 adoption 规则见[checkpoint 契约](implementation/jni-and-memory.md#音频-checkpoint)。

## 生命周期与失败

### Native ABI 准入

全部 JNI、共享内存、handle 和初始化配置契约共用一个全局 native ABI。库以导出变量声明该 ABI，Java 只查询期望符号是否存在，不依赖待验证库的 JNI 探针。该选择减少 JNI 自举耦合，代价是临时 JNA 句柄和跨平台导出规则；符号检查不能阻止 OS 加载和静态构造。精确规则见[ABI 索引](../../abi/maintenance.md#native-符号门禁)。

### 失败与交接

Native 返回失败不提交领域状态。Java 包装解释 false、null 或 fallback，不能把默认返回当作有效模型或输出；需要分类诊断的能力使用自己的结构化结果协议。

Native 返回成功也不自动完成 Ready、纹理注册或宿主顶点提交。每项外部效果仍由对应 Java owner 接纳，避免计算层获得业务发布权。加载线程、一次性注册、能力入口和 Android 适配见[Native 实现](implementation/runtime-capabilities.md)。

## 关键契约与设计取舍

### 调用线程调度

Java 选择线程职责与固定策略，native 只修改调用线程，不拥有 executor、任务终态或发布权。`NORMAL` 保留继承状态；低档策略只应用到明确自有的专属 platform thread。宿主 executor 的单项任务不使用此包装，每个新线程独立尝试。

平台策略先查询基线，再应用并读回；基线查询失败不盲改宿主。已有更低档位不应提高，部分成功不能报告完整应用。Windows 的 background mode 应恢复正常 memory priority；低于 LOWEST 的 CPU 基线只保留 CPU-only 并报告降级。回滚只撤本次成功进入的 background mode，不能把已有 IDLE 提高。Linux/Android 保留宿主实时与 idle scheduler，nice/BATCH 不代表 cpuset 或配额。macOS QoS 请求不证明实际份额或 override。策略不改进程 affinity、CPU Sets 或 native render pool，也不保证 I/O 待遇不变。

可恢复调度、JNI 与诊断失败不吞掉原 worker loop；VM 致命错误仍传播。不叠加 Java priority fallback，也不恢复整条线程。正常池由正常 owner 创建，防止低档线程懒创建时继承低档状态。该选择减少跨 owner 状态，但后台耗时可能增加；OS 属性不证明前台收益。当前责任映射见[运行模型](../runtime/overview.md#容量与线程策略)，平台落实见[运行能力](implementation/runtime-capabilities.md#调用线程调度)。

### 设备配置默认

Native 拥有只读设备查询与[统一分档规则](../../product-decisions/decisions/device-quality.md#bcdevice-derived-configuration-defaults)。Java 只提供 JVM 最大堆 byte 值、解释最终结果并映射配置默认。设备总拓扑与 renderer 可调度拓扑分开，避免进程 affinity 或停车状态改变设备默认。

Java 在首次构造配置默认时缓存一个不可变进程结果。两个配置共享该结果；后续读配置或开屏不再探测。Native 不保留另一份全局档位，也不新增采样线程。加载早于配置初始化；提前访问或加载失败取得的保守结果不后台重试。该选择减少初始化与恢复状态，代价是运行期间不重新分档。

边界失败与合法低配应可区分，但都给出保守默认。可选系统符号只动态查询，缺失时不影响最低平台加载。查询不修改 SIMD、affinity 或 renderer 绑定。编码和初始化顺序见[实现](implementation/runtime-capabilities.md#设备配置默认)。

## 实现与验证入口

[运行能力](implementation/runtime-capabilities.md)定位加载线程、初始化与失败结果；[JNI 与内存](implementation/jni-and-memory.md)定位 buffer、opaque handle、adoption 和 checkpoint。平台发布范围与验证结论见[当前状态](../../status/support-and-verification.md)。
