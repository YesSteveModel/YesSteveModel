# 资源所有权与配额

本页按 owner 汇总[运行作用域](../runtime/overview.md#运行作用域与所有权)、共享保留、关闭责任与内存配额。表中摘要链接各域的权威规则；事件影响见[生命周期场景](resource-lifecycle.md)，关闭偏序见 [Runtime 结束图](../runtime/overview.md#结束与替换)。

## 生命周期根与共享范围

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 结束责任 |
|---|---|---|---|---|
| `ModelSystem` | 进程 | Local Catalog、storage、server resource runtime、client 服务 | 资源可跨连接和 game server 实例存续 | 按 [Runtime 偏序](../runtime/overview.md#结束与替换)关闭各域 |
| Client 服务 | 进程内 client 运行期 | Client resources、demand、audio runtime | Session 按资格使用；disconnect 不关闭整个服务 | 服务关闭时终结所属资源，见[运行作用域](../runtime/overview.md#运行作用域与所有权) |
| `ServerModelService` | 单次 game server | Server sessions、dispatch、export environment | 使用进程 Catalog 与 bytes runtime；不拥有独立 client 状态 | Game server stop 结束本服务工作，见[网络归属](../network/overview.md#连接业务资格与撤销) |
| Client session | Exact client connection | 本连接 activation、传输及远端投影 | 不把 fetcher、提交资格或迟到结果交给 replacement | 原 owner 撤资格并终结自身工作，见[连接撤销交接](../network/overview.md#连接撤销交接) |
| 动画 owner | 世界 capability/provider、页面／preview 操作或第一人称 renderer | 私有 installation、组件及输出 | 同一 subject 可有多个 owner；不因此共享可变状态 | 各宿主结束自身使用，见[对象寿命与模型使用](../animation/entity-lifecycle.md#对象寿命与模型使用) |

Client 与 integrated server 同处一个 JVM，仍各自修改所属状态。生命周期父子关系不代表独占全部 backing。

## Ready、准备依赖与宿主纹理

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 关闭者与结束条件 |
|---|---|---|---|---|
| 普通 Ready cache | Client resources | Unused 完整 target 的缓存引用 | 与消费者 lease 并存；required default 不进入普通 unused 淘汰 | Cache 淘汰只撤自身引用，见[完成与采用](../model-management/client-resources/implementation/resource-ownership.md#完成与采用) |
| Required lease | Client 服务 | Intrinsic default | 独立于普通 cache 固定保留 | 服务释放 required lease，见[默认驻留](../model-management/client-resources/default-model.md#驻留与缓存) |
| Demand 用途与接收者 | 各用途 scope | 来源 anchor、独立接收 lease | 先建立接收者 lease，再撤来源 anchor；关闭旧 scope 不撤已交接用途 | 各 owner 释放自己的保留，见[用途交接](../model-management/demand/overview.md#用途准备与独立接收) |
| Entity installation | 单次模型使用 | 自身 target lease、私有组件、expression instance 与输出 | 安装成功不是 Ready 的组成部分；draw/JNI 仅在窗口内借用 | 任务真实终态且最外层借用退出后清理，见[安装边界](../animation/entity-lifecycle.md#模型使用与完整安装)与[JNI 借用](../native-runtime/overview.md#jni-能力与结果交付) |
| Ready target | 所有保留者共同维持 | 准备结果的独立 pin、纹理 logical binding | 多个 target 可复用不可变 module/animation；裸 target 或 view 不替代 lease | 最后保留结束后由资源 owner 清理，并撤 pin、发 logical release，见[资源关闭](../model-management/client-resources/overview.md#引用撤销与物理关闭) |
| Minecraft／Iris host | 宿主纹理生命周期 | 已采用 texture、PBR 与 GPU id | Target 只持 logical binding；standalone GUI texture 不取得模型纹理关闭权 | 宿主执行物理关闭，见[资源关闭](../model-management/client-resources/overview.md#引用撤销与物理关闭)与[页面资源](../client-presentation/implementation/page-runtime.md#页面与资源-owner) |

## 独立播放与共享 PCM

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 关闭者与结束条件 |
|---|---|---|---|---|
| Client audio runtime | Client 服务 | 活动 playback、PCM/checkpoint cache | Installation 只触发播放；接纳后的 playback 独立存续 | Disconnect 停活动播放并保留 cache；runtime close 结束两者，见[播放终态](../model-management/audio/overview.md#播放终态) |
| Playback | 一次已接纳播放 | 私有 cursor、decoder、host handle、独立 chunk capability | 可与 cache、其他 playback 共同保留不可变 PCM/checkpoint | 本次播放 seal 后终结；worker 借用退出后释放所持资源，见[音频所有权](../model-management/audio/overview.md#运行作用域与所有权)与[播放终态](../model-management/audio/overview.md#播放终态) |
| PCM/checkpoint cache | Audio runtime | 不可变共享 entry | 淘汰只撤缓存引用，不终止持有 entry 的播放 | Cache 淘汰或 runtime close 撤保留；最后引用结束后回收，见[PCM 保留](../model-management/audio/overview.md#pcm-保留与工作集) |
| 外部音频后端 | 后端接纳的一次播放 | 本次读取、解码、输出、控制与终态 | 接纳后完整接管，不与默认后端分摊生命周期 | 后端保证唯一终态，见[后端契约](../model-management/audio/overview.md#后端接管与播放终态) |

## 内容、持久文件与进程保留

| Owner | 生命周期根 | 持有资源 | 共享与保留边界 | 关闭者与结束条件 |
|---|---|---|---|---|
| Local Catalog | 进程 | 当前内容的强引用 | 内容对象存活不保证用户 direct 文件未来可读 | 替换或关闭只撤 Catalog 自身引用，见[Catalog 所有权](../model-management/catalog/overview.md#运行作用域与所有权)与[精确读取](../model-management/storage/overview.md#用户来源与精确读取) |
| Server resource runtime 与发送者 | 进程／各发送请求 | Encoded chunk、独立图片及 bytes lease | Cache 与发送 lease 可共同保留 backing | Cache 撤自身引用；dispatch 终结请求并释放本次 lease，见[服务端归属](../model-management/server-resources/overview.md#运行作用域与所有权)与[分发所有权](../network/overview.md#传输接管与发送终态) |
| 各区域 storage | 进程／持久存储 | 已提交对象、expression 内存复用项、converted 登记锁 | 磁盘对象不随 session/target 结束；内存复用与磁盘提交独立 | 各 store 撤内存保留；converted 使用者全结束后释放登记，见[派生物](../model-management/storage/derived-artifacts.md)与[存储清理](../model-management/storage/overview.md#共享存储与清理) |
| `DerivedStorageWork` | 进程共享工作域 | 队列与运行项的输入及 staging 工作集 | 持久化可失败，不撤有效内存 Ready | 队列取消即归还；运行项等借用结束，见[派生持久工作](../model-management/storage/implementation/persistence.md#派生持久工作) |
| 执行与辅助设施 | 各 runtime／线程／allocator | 线程池、静态或 ThreadLocal scratch、通用展示缓存、空闲容量 | 不从属于某次模型 installation | 按各自边界释放，见[执行容量](../runtime/overview.md#容量与线程策略)、[辅助保留](../runtime/implementation/auxiliary-resources.md)与[页面资源](../client-presentation/implementation/page-runtime.md#页面与资源-owner) |

Converted prune 不清理 remote、preview 或 baked cache。磁盘删除范围由[存储清理规则](../model-management/storage/overview.md#共享存储与清理)决定。

## 内存配额与计价边界

下表为当前实现的数量限制与保留政策。非音频客户端资源不按字节拒绝、等待或淘汰；音频和服务端预算独立生效。数量限制不构成进程堆、native 内存或显存总上限。

| Owner／账本 | 默认限额 | 覆盖范围 | 超限与归还 | 依据 |
|---|---|---|---|---|
| Preview pixels | 原 operation 数量、每 tick 接纳与串行 reload 门禁 | CPU RGBA backing，含待交接、已接纳与活动借用 | 直接派发；正常释放等最后引用与借用退出，owner shutdown 封口并退休，仍等活动借用归还 | [像素所有权](../model-management/client-resources/implementation/resource-ownership.md#图片内容与-cpu-像素) |
| Audio retention cache | 64 MiB | 不可变 PCM/checkpoint 缓存保留 | 淘汰只撤缓存引用；超大候选放弃缓存，不因此停止播放 | [音频实现](../model-management/audio/overview.md#实现与验证入口)、[PCM 保留](../model-management/audio/overview.md#pcm-保留与工作集) |
| Audio 活动容量 | Encoded 32 MiB；decoded 32 MiB，分别计价 | 当前 encoded chunk；ready PCM、候选及播放持有的 PCM/checkpoint，含热命中 | 活动容量不足时拒绝相应接纳／分配；终态归还自身预算，worker 借用部分延后 | [容量与物理结束](../model-management/audio/overview.md#容量与物理结束)、[默认容量](../model-management/audio/overview.md#实现与验证入口) |
| Server resource cache | 64 MiB | Chunk 与独立图片共享 encoded 缓存预算 | 两类互相淘汰；超大结果可交付但不保留；撤缓存不关闭活动发送 backing | [缓存预算](../model-management/server-resources/overview.md#实现与验证入口) |
| Expression 内存复用 | 每 store／区域 16 项 | 原始 artifact 与不可变 animation set | 按项数触发 LRU 淘汰；撤 store 引用不撤消费者保留 | [内存复用](../model-management/storage/derived-artifacts.md#实现与验证入口) |
| 派生持久工作 | 进程共享 16 项、单 worker | 队列与运行项持有的独立输入 | 饱和只拒绝可选写入；排队取消可归还，运行项等借用结束 | [派生持久工作](../model-management/storage/implementation/persistence.md#派生持久工作) |
| 普通 Ready cache | `ModelRetentionCount`，数量限制 | Unused 完整 target；不含活动 lease 与 required default | 0 表示不保留 unused；淘汰只撤 cache 引用，准备依赖无额外 LRU | [完成与采用](../model-management/client-resources/implementation/resource-ownership.md#完成与采用) |

Server cache 预算不含全部 in-flight、发送 lease 与对象开销。音频活动计价与 cache 保留可能覆盖同一 backing，外部后端内存也不由默认 PCM 账本完整计量。

传输的单对象长度／解码上限另见 [Protocol V1](../../standards/protocol-v1/README.md)，不能用它们推导连接或进程总内存上限。Lease 数、预算归还和 RSS／显存下降也不是同一指标；观测范围见[资源诊断](../../verification/resource-diagnostics.md#证据边界)。
