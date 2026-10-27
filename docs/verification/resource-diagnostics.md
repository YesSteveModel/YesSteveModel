# 资源诊断

本页说明观测入口及证据边界，不定义资源关闭规则。生命周期以[运行作用域](../architecture/runtime/overview.md#运行作用域与所有权)及各资源 owner 的设计为准。

## 日志与快照

`ResourceDiagnostics` 的自动日志默认关闭；进程启动时存在 `YSM_DEBUG_RESOURCE` 环境变量即开启，客户端 `/ysmdebug resource true|false` 可覆盖当前开关。日志覆盖 geometry bake、AnimationSet 缓存生成、Molang 编译与加载、baked cache I/O 与原子提交、宿主纹理登记/移除和 render target 构造/销毁。动画 bake 在每个 AnimationSet 的缓存暂存文件写入完成后记录一次，不逐动画记录；Molang 按 AnimationSet 分别记录冷编译完成与模块加载绑定完成，加载日志包含缓存来源。日志区分缓存暂存写入与提交，动画 index 读取与惰性 payload 读取。`TextureManagerMixin` 仅由 `MixinTweaker` 在客户端且 `FMLEnvironment.production == false` 时动态注册；生产环境不观察该宿主 seam。Oculus PBR component 的登记与关闭单独观察。

`/ysmdebug resource dump geo|anim|texture|rendertarget` 不必开启自动日志，向日志输出对应快照和对象 identity，聊天只返回数量。跟踪表始终使用弱 identity 引用：geometry/animation 为已构造且仍可达的 runtime 对象，texture 为 YSM 宿主登记对象及 PBR children，render target 从 prepared 开始保留至物理销毁；未求值的惰性动画不计入。Dump 不触发加载或 GC，也不持有资源 lease。


## 证据边界

弱引用快照、缓存计价、lease 数量和实际堆/显存占用不同。Dump 不证明资源泄漏，也不证明物理回收完成；Cleaner、宿主队列及 allocator 仍有独立终点。
