# 资源所有权与配额

本页按 owner 汇总[运行作用域](../runtime/overview.md#运行作用域与所有权)、共享保留边界、关闭责任与内存配额。表中的关闭责任适用于进程内的领域结束与显式受控关闭；[JVM 退出](../runtime/overview.md#jvm-进程退出)时会直接放弃运行资源。各类事件对资源的影响见[生命周期场景](resource-lifecycle.md)。

## 生命周期根与共享范围

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 结束责任 |
|---|---|---|---|---|
| `ModelSystem` | 进程或显式创建的独立资源域 | Local Catalog、storage、服务端资源 runtime、客户端服务 | 资源可跨连接和多次 game server 实例存续 | 独立实例可[显式关闭](../runtime/overview.md#进程内受控关闭)，进程实例随 JVM 结束 |
| 客户端服务 | 进程内 client 运行期 | Client resources、demand、audio runtime | Session 按资格使用；断连不关闭整个服务 | 显式关闭终结自有工作并撤回自身保留，不遍历全部实体也不等待全部 store，见[受控关闭](../runtime/overview.md#进程内受控关闭) |
| `ServerModelService` | 单次 game server | Server sessions、dispatch、export environment | 共用进程 Catalog 与字节 runtime；不拥有独立客户端状态 | Game server stop 结束本服务工作，见[网络归属](../network/overview.md#连接业务资格与撤销) |
| Client session | 精确客户端连接 | 本连接的 activation、传输与远端投影 | 不把 fetcher、提交资格或迟到结果转交给替代连接 | 原 owner 撤销资格并终结自身工作，见[连接撤销交接](../network/overview.md#连接撤销交接) |
| 动画 owner | 世界 capability 或 provider、页面与预览操作，或第一人称 renderer | 私有安装、组件及渲染输出 | 同一个 subject 可对应多个 owner，彼此不共享可变状态 | 各宿主负责结束自身使用，见[对象寿命与模型使用](../animation/entity-lifecycle.md#对象寿命与模型使用) |

客户端与集成服务端即使运行在同一 JVM 内，也各自修改所属状态。生命周期上的父子关系不代表父级独占全部底层资源。

## Ready、准备依赖与宿主纹理

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 关闭者与结束条件 |
|---|---|---|---|---|
| 普通 Ready cache | Client resources | 未使用（unused）完整 target 的缓存引用 | 与消费者 lease 并存；required default 不进入普通 unused 淘汰 | 缓存淘汰只撤回自身引用，见[完成与采用](../model-management/client-resources/implementation/resource-ownership.md#完成与采用) |
| Required lease | 客户端服务 | Intrinsic default | 独立于普通缓存固定保留 | 客户端服务释放 required lease，见[默认驻留](../model-management/client-resources/default-model.md#驻留与缓存) |
| Demand 用途与接收者 | 各用途 scope | 来源 anchor、独立接收 lease | 先建立接收者 lease，再撤回来源 anchor；关闭旧 scope 不影响已交接用途 | 各 owner 自行释放所持保留，见[用途交接](../model-management/demand/overview.md#用途准备与独立接收) |
| Entity installation | 单次模型使用 | 自身 target lease、私有组件、表达式实例与输出 | 安装成功不是 Ready 的一部分；绘制与 JNI 仅在窗口内借用 | 任务到达真实终态且最外层借用退出后清理，见[安装边界](../animation/entity-lifecycle.md#模型使用与完整安装)与[JNI 借用](../native-runtime/overview.md#jni-能力与结果交付) |
| Ready target | 由全部保留者共同维持 | 准备产物的独立 pin、纹理 logical binding | 多个纹理 target 可复用同一个 store；裸 target 或 view 不能替代 lease | 撤回自身 pin 并发出 logical release，见[资源关闭](../model-management/client-resources/overview.md#引用撤销与物理关闭) |
| `AnimationStore` | 共享准备与实际保留者 | 绑定动画、定义以及解释或 AOT 运行资源 | 实体各自持有独立执行借用；不设程序闲置超时，也不做服务全域登记 | 最后一个 pin 发起退休，最后一份真实保护归还时清理，见[动画资源所有权](../model-management/client-resources/overview.md#动画资源所有权与退休) |
| Minecraft 与 Iris 宿主 | 宿主纹理生命周期 | 已采用的 texture、PBR 与 GPU id | Target 只持 logical binding；独立 GUI 纹理不接管模型纹理的关闭权 | 宿主执行物理关闭，见[资源关闭](../model-management/client-resources/overview.md#引用撤销与物理关闭)与[页面资源](../client-presentation/implementation/page-runtime.md#页面与资源-owner) |

## 独立播放与共享 PCM

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 关闭者与结束条件 |
|---|---|---|---|---|
| Client audio runtime | 客户端服务 | 活动播放（playback）、PCM 与 checkpoint cache | 实体安装只触发播放；被接纳后的播放独立存续 | 断连停止活动播放但保留缓存；runtime 关闭同时结束两者，见[播放终态](../model-management/audio/overview.md#播放终态) |
| Playback | 单次已接纳播放 | 私有 cursor、decoder、宿主句柄、独立 chunk capability | 可与缓存和其他播放共同保留不可变 PCM 与 checkpoint | 本次播放封口（seal）后终结，等待 worker 借用退出后释放所持资源，见[音频所有权](../model-management/audio/overview.md#运行作用域与所有权)与[播放终态](../model-management/audio/overview.md#播放终态) |
| PCM 与 checkpoint cache | Audio runtime | 不可变共享 entry | 淘汰只撤回缓存引用，不终止正在使用该 entry 的播放 | 缓存淘汰或 runtime 关闭撤回保留；最后一份引用结束后回收，见[PCM 保留](../model-management/audio/overview.md#pcm-保留与工作集) |
| 外部音频后端 | 后端接纳的单次播放 | 本次读取、解码、输出、控制与终态 | 接纳后完整接管，不与默认后端分摊生命周期 | 由外部后端保证唯一终态，见[后端契约](../model-management/audio/overview.md#后端接管与播放终态) |

## 内容、持久文件与进程保留

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 关闭者与结束条件 |
|---|---|---|---|---|
| Local Catalog | 进程 | 当前目录内容的强引用 | 内容对象存活不保证用户的 direct 源文件未来仍可读 | 替换或关闭只撤回 Catalog 自身引用，见[Catalog 所有权](../model-management/catalog/overview.md#运行作用域与所有权)与[精确读取](../model-management/storage/overview.md#用户来源与精确读取) |
| Server resource runtime 与发送者 | 进程与各发送请求 | Encoded chunk、独立图片及 bytes lease | 缓存与发送 lease 可共同保留同一底层字节 | 缓存撤回自身引用；dispatch 终结请求并释放本次 lease，见[服务端归属](../model-management/server-resources/overview.md#运行作用域与所有权)与[分发所有权](../network/overview.md#传输接管与发送终态) |
| 各区域 storage | 进程与持久存储 | 已提交磁盘对象、表达式内存复用项、converted 登记锁 | 磁盘对象不随 session 或 target 结束；内存复用与磁盘提交相互独立 | 各 store 撤回内存保留；converted 的全部使用者结束后释放登记，见[派生物](../model-management/storage/derived-artifacts.md)与[存储清理](../model-management/storage/overview.md#共享存储与清理) |
| `DerivedStorageWork` | 进程共享工作域 | 队列与运行项的输入及 staging 工作集 | 磁盘持久化允许失败，不撤销内存中的有效 Ready | 队列取消立即归还，运行中项等待借用结束，见[派生持久工作](../model-management/storage/implementation/persistence.md#派生持久工作) |
| 执行与辅助设施 | 各 runtime、线程与 allocator | 线程池、静态或 `ThreadLocal` scratch、通用展示缓存、空闲容量 | 不从属于某次模型安装 | 按各自边界释放，见[执行容量](../runtime/overview.md#容量与线程策略)、[辅助保留](../runtime/implementation/auxiliary-resources.md)与[页面资源](../client-presentation/implementation/page-runtime.md#页面与资源-owner) |

Converted 缓存清理不会删除 remote、preview 或 baked 缓存。完整的磁盘删除范围由[存储清理规则](../model-management/storage/overview.md#共享存储与清理)决定。

## 内存配额与计价边界

下表列出当前实现的数量限制与保留策略。非音频客户端资源不按字节拒绝、等待或淘汰，音频与服务端预算各自独立生效。这些局部限额不能直接相加为进程堆、native 内存或显存的总上限。

| Owner 与账本 | 默认限额 | 覆盖范围 | 超限与归还 | 依据 |
|---|---|---|---|---|
| Preview pixels | 原 operation 数量、每 tick 接纳上限与串行 reload 门禁 | CPU RGBA backing，含待交接、已接纳与活动借用 | 直接派发；正常释放等待最后引用与借用退出，owner shutdown 封口并退休，仍等待活动借用归还 | [像素所有权](../model-management/client-resources/implementation/resource-ownership.md#图片内容与-cpu-像素) |
| Audio retention cache | 64 MiB | 不可变 PCM 与 checkpoint 的缓存保留 | 淘汰只撤回缓存引用；超大候选放弃缓存，不因此停止播放 | [音频实现](../model-management/audio/overview.md#实现与验证入口)、[PCM 保留](../model-management/audio/overview.md#pcm-保留与工作集) |
| Audio 活动容量 | Encoded 32 MiB；decoded 32 MiB，分别计价 | 当前 encoded chunk；ready PCM、候选及活动播放持有的 PCM 与 checkpoint（含热命中） | 活动容量不足时拒绝相应的接纳或分配；到达终态时归还自身预算，worker 借用部分延后归还 | [容量与物理结束](../model-management/audio/overview.md#容量与物理结束)、[默认容量](../model-management/audio/overview.md#实现与验证入口) |
| Server resource cache | 64 MiB | Chunk 与独立图片共享 encoded 缓存预算 | 两类互相淘汰；超大结果可正常交付但不留缓存；撤回缓存不关闭活动发送的底层资源 | [缓存预算](../model-management/server-resources/overview.md#实现与验证入口) |
| Expression 内存复用 | 每 store 或区域 16 项 | 原始 artifact 与不可变 animation set | 按项数触发 LRU 淘汰；撤回 store 引用不撤回消费者保留 | [内存复用](../model-management/storage/derived-artifacts.md#实现与验证入口) |
| 派生持久工作 | 进程共享 16 项、单 worker | 队列与运行项持有的独立输入 | 饱和时只拒绝可选写入；排队取消即归还，运行中项等待借用结束 | [派生持久工作](../model-management/storage/implementation/persistence.md#派生持久工作) |
| 普通 Ready cache | `ModelRetentionCount`（按数量限制） | 未使用的完整 target，不含活动 lease 与 required default | 为 0 时不保留 unused；淘汰只撤回缓存引用，准备依赖不设额外 LRU | [完成与采用](../model-management/client-resources/implementation/resource-ownership.md#完成与采用) |

服务端缓存预算不涵盖所有在途工作、发送 lease 与对象头开销；音频活动计价与缓存保留可能重叠覆盖同一底层数据，外部音频后端的内存也不由默认 PCM 账本完整计量。

网络传输的单对象长度与解码上限另见 [Protocol V1](../../standards/protocol-v1/README.md)，不能据此推导单连接或进程总内存上限。Lease 数量、预算归还与 RSS 或显存下降也不是同一指标，具体观测边界见[资源诊断](../../verification/resource-diagnostics.md#证据边界)。
