# 资源诊断

本页记录 `ResourceDiagnostics` 的开关、快照命令与观测边界；资源生命周期以[运行作用域](../architecture/runtime/overview.md#运行作用域与所有权)为准。

## 日志与快照

- **自动日志开关**：默认关闭。进程启动前设置环境变量 `YSM_DEBUG_RESOURCE` 可自动开启；运行期可通过客户端命令 `/ysmdebug resource true|false` 动态切换。
- **日志覆盖范围**：
  - 几何 bake、AnimationSet 缓存暂存与原子提交（按 set 汇总，不逐条动画输出）、动画索引与惰性 payload 读取；
  - Molang 冷编译完成与模块加载绑定（附带缓存来源）；
  - GUI 解释程序按求值轮次汇总新增程序准备次数与耗时（仅计解码与绑定，内存命中不输出）；
  - 宿主纹理登记/移除（`TextureManagerMixin` 仅在客户端非生产环境注册）、Oculus PBR 组件与 render target 的构造和销毁。
- **按需快照**：无需开启自动日志，执行 `/ysmdebug resource dump geo|anim|texture|rendertarget` 将当前可达对象及 identity 写入日志（聊天栏仅输出计数）。跟踪表使用弱引用，不计入未求值的惰性动画，也不触发加载、GC 或持有 lease。

## 证据边界

弱引用快照、缓存计价、lease 计数与实际堆/显存占用分属不同维度：Dump 既不能单独证明存在泄漏，也不能证明 Cleaner、宿主队列或原生 allocator 已完成物理回收。按[JVM 进程退出](../architecture/runtime/overview.md#jvm-进程退出)策略，退出时直接放弃运行资源，驻留与退休证据需在进程持续运行期间采集。
