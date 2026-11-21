# 资源诊断

本页说明观测入口与证据边界，不定义资源关闭规则；具体生命周期以[运行作用域](../architecture/runtime/overview.md#运行作用域与所有权)及各资源 owner 的设计文档为准。

## 日志与快照

`ResourceDiagnostics` 的自动日志默认关闭。如果在进程启动时设置了环境变量 `YSM_DEBUG_RESOURCE` 就会自动开启，运行期可通过客户端命令 `/ysmdebug resource true|false` 覆盖当前开关。

自动日志涵盖 geometry bake、AnimationSet 缓存生成、Molang 编译与加载、baked cache I/O 与原子提交、宿主纹理登记和移除，以及 render target 的构造和销毁。其中：

- 动画 bake 在每个 AnimationSet 的缓存暂存文件写入完成后记录一次，不逐条动画输出；
- Molang 按 AnimationSet 分别记录冷编译完成与模块加载绑定完成，加载日志会附带缓存来源；
- 缓存日志明确区分暂存写入与原子提交，以及动画索引读取与惰性 payload 读取；
- `TextureManagerMixin` 仅由 `MixinTweaker` 在客户端且 `FMLEnvironment.production == false` 时动态注册，生产环境不观察该宿主接缝；Oculus PBR 组件的登记与关闭单独记录。

GUI 解释程序按求值轮次汇总新增程序的准备次数与累计准备耗时（包含被调用的函数及失败尝试），不逐条程序刷屏，内存命中也不输出；直接解释路径在关闭时报告尚未汇总的准备记录。同 store 闲置后重访应无新增准备，新 store 首次消费仍会产生准备记录。该耗时只计入程序解码与绑定，不含求值、帧内等待或日志输出时间；失败仍沿原执行通道传播异常原因。

执行 `/ysmdebug resource dump geo|anim|texture|rendertarget` 无需开启自动日志，命令会向日志输出对应快照及对象 identity，聊天栏仅返回计数。跟踪表统一采用弱引用：geometry 与 animation 统计已构造且仍可达的运行时对象，texture 统计 YSM 在宿主登记的纹理对象及 PBR 子纹理，render target 从 prepared 阶段一直记录到物理销毁，尚未求值的惰性动画不计入。Dump 操作本身不触发加载或 GC，也不持有任何资源 lease。

## 证据边界

弱引用快照、缓存计价、lease 数量与实际堆或显存占用分属不同维度。Dump 既不能证明存在资源泄漏，也不能证明物理回收已经完成；Cleaner、宿主队列与 allocator 各自拥有独立终点。

按[JVM 退出](../architecture/runtime/overview.md#jvm-进程退出)策略，进程退出时直接放弃运行资源，因此缺少实例或 store 销毁日志、或缺少最后一轮准备汇总并不构成退出失败。驻留与退休证据必须在进程持续运行期间采集，历史退出整链成功不证明当前退出仍会执行清理。
