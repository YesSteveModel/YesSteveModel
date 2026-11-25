# 资源所有权与配额

本页按 owner 汇总[运行作用域](../runtime/overview.md#运行作用域与所有权)、共享保留边界、关闭责任与当前内存配额。表中关闭责任适用于进程内领域结束与显式受控关闭（[JVM 退出](../runtime/overview.md#jvm-进程退出)直接放弃运行资源）；典型事件对照见[资源生命周期场景](resource-lifecycle.md)。

## 生命周期根与共享范围

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 结束责任 |
|---|---|---|---|---|
| `ModelSystem` | 进程或显式创建的独立资源域 | Local Catalog、storage、服务端资源 runtime、客户端服务 | 可跨连接和多次 game server 实例存续 | 独立实例[显式关闭](../runtime/overview.md#进程内受控关闭)，进程实例随 JVM 结束 |
| 客户端服务 | 进程内 client 运行期 | Client resources、demand、audio runtime | Session 按资格使用；断连不关闭整个服务 | 显式关闭终结自有工作并撤回自身保留（不遍历实体或全域等 store），见[受控关闭](../runtime/overview.md#进程内受控关闭) |
| `ServerModelService` | 单次 game server | Server sessions、dispatch、export environment | 共用进程 Catalog 与字节 runtime；与客户端状态隔离 | Game server stop 结束本服务工作，见[连接业务资格与撤销](../network/overview.md#连接业务资格与撤销) |
| Client session | 精确客户端连接 | 本连接的 activation、传输与远端投影 | 不将 fetcher、提交资格或迟到结果转交给替代连接 | 原 owner 撤销资格并终结自身工作，见[连接撤销交接](../network/overview.md#连接撤销交接) |
| 动画 owner | 世界 capability/provider、页面与预览操作，或第一人称 renderer | 私有安装、组件及渲染输出 | 同 subject 多 owner 间可变状态隔离 | 各宿主负责结束自身使用，见[对象寿命与模型使用](../animation/entity-lifecycle.md#对象寿命与模型使用) |

客户端与集成服务端即使同处一 JVM 也各自维护所属状态；生命周期父子关系允许底层资源通过独立 lease/pin 跨父级存续。

## Ready、准备依赖与宿主纹理

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 关闭者与结束条件 |
|---|---|---|---|---|
| 普通 Ready cache | Client resources | 未使用（unused）完整 target 的缓存引用 | 与消费者 lease 并存；required default 不参与普通 unused 淘汰 | 淘汰仅撤回自身引用，见[完成与采用](../model-management/client-resources/implementation/resource-ownership.md#完成与采用) |
| Required lease | 客户端服务 | Intrinsic default | 独立于普通缓存固定保留 | 客户端服务释放 required lease，见[默认驻留](../model-management/client-resources/default-model.md#驻留与缓存) |
| Demand 用途与接收者 | 各用途 scope | 来源 anchor、独立接收 lease | 先建接收者 lease 再撤来源 anchor；关闭旧 scope 不影响已交接用途 | 各 owner 自行释放所持保留，见[用途交接](../model-management/demand/overview.md#用途准备与独立接收) |
| Entity installation | 单次模型使用 | 自身 target lease、私有组件、表达式实例与输出 | 绘制与 JNI 仅在同步窗口内借用 | 任务达真实终态且最外层借用退出后清理，见[安装边界](../animation/entity-lifecycle.md#模型使用与完整安装)、[JNI 借用](../native-runtime/overview.md#jni-能力与结果交付) |
| Ready target | 全部保留者共同维持 | 准备产物独立 pin、纹理 logical binding | 多纹理 target 可复用同一 store；裸引用不替代 lease | 撤回自身 pin 并发出 logical release，见[引用撤销与物理关闭](../model-management/client-resources/overview.md#引用撤销与物理关闭) |
| `AnimationStore` | 共享准备与实际保留者 | 绑定动画、定义与解释/AOT 运行资源 | 实体各自持有执行借用；无程序闲置超时或全域登记 | 最后一个 pin 发起退休，最后一份保护归还时清理，见[动画资源所有权与退休](../model-management/client-resources/overview.md#动画资源所有权与退休) |
| Minecraft 与 Iris 宿主 | 宿主纹理生命周期 | 已采用 texture、PBR 与 GPU id | Target 仅持 logical binding；独立 GUI 纹理与模型纹理分离 | 宿主执行物理关闭，见[引用撤销与物理关闭](../model-management/client-resources/overview.md#引用撤销与物理关闭)、[页面资源](../client-presentation/implementation/page-runtime.md#页面与资源-owner) |

## 独立播放与共享 PCM

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 关闭者与结束条件 |
|---|---|---|---|---|
| Client audio runtime | 客户端服务 | 活动播放（playback）、PCM 与 checkpoint cache | 实体安装仅触发播放，被接纳后独立存续 | 断连停活动播放并留缓存；runtime 关闭同时结束两者，见[播放终态](../model-management/audio/overview.md#播放终态) |
| Playback | 单次已接纳播放 | 私有 cursor、decoder、宿主句柄、独立 chunk capability | 可与缓存及其他播放共享不可变 PCM/checkpoint | 封口（seal）后终结，待 worker 借用退出后释放，见[音频所有权](../model-management/audio/overview.md#运行作用域与所有权)、[播放终态](../model-management/audio/overview.md#播放终态) |
| PCM 与 checkpoint cache | Audio runtime | 不可变共享 entry | 淘汰仅撤回缓存引用，不终止在用播放 | 淘汰或 runtime 关闭撤回保留，最后一份引用结束后回收，见[PCM 保留与工作集](../model-management/audio/overview.md#pcm-保留与工作集) |
| 外部音频后端 | 后端接纳的单次播放 | 本次读取、解码、输出、控制与终态 | 接纳后完整接管 | 由外部后端保证唯一终态，见[后端接管与播放终态](../model-management/audio/overview.md#后端接管与播放终态) |

## 内容、持久文件与进程保留

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 关闭者与结束条件 |
|---|---|---|---|---|
| Local Catalog | 进程 | 当前目录内容强引用 | 内容存活不保证用户 direct 源文件持续可读 | 替换或关闭仅撤回自身引用，见[Catalog 所有权](../model-management/catalog/overview.md#运行作用域与所有权)、[用户来源与精确读取](../model-management/storage/overview.md#用户来源与精确读取) |
| Server resource runtime 与发送者 | 进程与各发送请求 | Encoded chunk、独立图片与 bytes lease | 缓存与发送 lease 共享底层字节 | 缓存撤回自身引用；dispatch 终结请求并释放本次 lease，见[服务端归属](../model-management/server-resources/overview.md#运行作用域与所有权)、[传输接管与发送终态](../network/overview.md#传输接管与发送终态) |
| 各区域 storage | 进程与持久存储 | 已提交磁盘对象、表达式内存复用项、converted 登记锁 | 磁盘对象跨 session/target 存续；内存复用与磁盘提交独立 | 各 store 撤回内存保留；converted 全部使用者结束后释放登记，见[派生物](../model-management/storage/derived-artifacts.md)、[共享存储与清理](../model-management/storage/overview.md#共享存储与清理) |
| `DerivedStorageWork` | 进程共享工作域 | 队列与运行项输入及 staging 工作集 | 磁盘持久化允许失败，不影响内存 Ready | 队列取消立即归还，运行中项等借用结束，见[派生持久工作](../model-management/storage/implementation/persistence.md#派生持久工作) |
| 执行与辅助设施 | 各 runtime、线程与 allocator | 线程池、静态/`ThreadLocal` scratch、通用展示缓存、空闲容量 | 独立于单次模型安装 | 按各自边界释放，见[容量与线程策略](../runtime/overview.md#容量与线程策略)、[辅助保留](../runtime/implementation/auxiliary-resources.md)、[页面资源](../client-presentation/implementation/page-runtime.md#页面与资源-owner) |

Converted 缓存清理仅覆盖 converted 目录，磁盘清理范围见[共享存储与清理](../model-management/storage/overview.md#共享存储与清理)。

## 内存配额与计价边界

| Owner 与账本 | 默认限额 | 覆盖范围 | 超限与归还 | 依据 |
|---|---|---|---|---|
| Preview pixels | 原 operation 数、每 tick 接纳上限与串行 reload 门禁 | CPU RGBA backing（待交接、已接纳、活动借用） | 直接派发；正常释放待最后引用与借用退出，shutdown 封口后仍等活动借用归还 | [图片内容与 CPU 像素](../model-management/client-resources/implementation/resource-ownership.md#图片内容与-cpu-像素) |
| Audio retention cache | 64 MiB | 不可变 PCM 与 checkpoint 缓存 | 淘汰撤回缓存引用；超大候选不入缓存，不影响播放 | [音频实现](../model-management/audio/overview.md#实现与验证入口)、[PCM 保留](../model-management/audio/overview.md#pcm-保留与工作集) |
| Audio 活动容量 | Encoded 32 MiB；decoded 32 MiB（分计） | 当前 encoded chunk；ready PCM、候选及活动播放持有的 PCM/checkpoint（含热命中） | 不足时拒绝接纳或分配；达终态归还，worker 借用部分延后归还 | [容量与物理结束](../model-management/audio/overview.md#容量与物理结束)、[音频实现](../model-management/audio/overview.md#实现与验证入口) |
| Server resource cache | 64 MiB | Chunk 与独立图片共享 encoded 缓存 | 两类互相淘汰；超大结果交付但不留缓存；撤回缓存不关活动发送 | [服务端缓存预算](../model-management/server-resources/overview.md#实现与验证入口) |
| Expression 内存复用 | 每 store/区域 16 项 | 原始 artifact 与不可变 animation set | 按项数 LRU 淘汰，不撤回消费者保留 | [内存复用](../model-management/storage/derived-artifacts.md#实现与验证入口) |
| 派生持久工作 | 进程共享 16 项、单 worker | 队列与运行项独立输入 | 饱和仅拒可选写入；排队取消即归还，运行中项等借用结束 | [派生持久工作](../model-management/storage/implementation/persistence.md#派生持久工作) |
| 普通 Ready cache | `ModelRetentionCount`（按项） | 未使用完整 target（不含活动 lease 与 required default） | 为 0 时不保留 unused；淘汰仅撤回缓存引用 | [完成与采用](../model-management/client-resources/implementation/resource-ownership.md#完成与采用) |

计价与观测边界：
- 上表各局部配额相互独立且口径不同（如音频活动计价与缓存保留可重叠覆盖同一底层数据，服务端缓存不含在途发送 lease 与对象头，外部音频后端不计入默认 PCM 账本），不能简单累加为进程堆、native 内存或显存上限。
- 网络单对象长度与解码上限见 [Protocol V1](../../standards/protocol-v1/README.md)；lease 数量、账本归还与 RSS/显存下降的关系见[资源诊断证据边界](../../verification/resource-diagnostics.md#证据边界)。
