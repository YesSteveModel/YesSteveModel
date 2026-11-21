# workload-budget

- Requirement: [REQ.preserve-gameplay-and-availability](../requirements/req-preserve-gameplay-and-availability.md#reqpreserve-gameplay-and-availability)
- Select: tick 与 render 阻塞、后台并发、资产分发与 gameplay 消息竞争、MSPT 软指标
- Needs:
  - 比较设备表现: [DD.high-end-quality-with-basic-availability](device-quality.md#ddhigh-end-quality-with-basic-availability)
- Landing:
  - [design](../../architecture/animation/molang-runtime.md#内容信任与预算)
  - [design](../../architecture/animation/molang-static-language.md#循环与函数深度)
  - [design](../../architecture/animation/molang-interpreter.md#实例驻留与失败)
  - [verification](../../verification/policy.md#性能验证)
  - [implementation](../../architecture/rendering/implementation/frame-execution.md#任务发布与并发边界)
  - [implementation](../../architecture/model-management/catalog/overview.md#实现与验证入口)
  - [implementation](../../architecture/runtime/implementation/execution.md#工作线程与发布线程)
  - [implementation](../../architecture/network/implementation/asset-transfer.md#分发负载保护)
  - [design](../../architecture/network/overview.md#传输接管与发送终态)
  - [status](../../status/known-issues/rendering.md#并发与性能)
  - [design](../../architecture/rendering/overview.md)
  - [design](../../architecture/model-management/catalog/overview.md#seam-contract)
  - [design](../../architecture/runtime/overview.md)
  - [design](../../architecture/network/overview.md)

## DD.main-loop-has-priority

- Claim: 模型加载、转换、求值、渲染和同步的工作量必须服从游戏主循环的资源优先级。
- Rationale: 客户端帧率和服务端 tick 是所有玩家共享的基础体验。阻塞等待、主线程重任务以及过量后台并发，都会把局部模型需求放大为全局卡顿——仅仅把工作挪到后台并不能自动消除这项成本。

### BC.no-main-loop-starvation

- Claim: 正常模型工作不得阻塞 tick 或 render 线程等待异步结果，也不得以无界主线程任务或过量后台并发挤占游戏主循环；无法承受的工作应局部拒绝或降级。Catalog 与 runtime resource 的重负载容量必须相互独立。每个有界计算 worker 连续完成一条输入已齐全、线程安全（worker-safe）、工作量有界，且不等待其他 worker、网络或宿主回调（host callback）的处理链。Catalog 扫描协调线程可以等待本次 worker，但必须具备独立容量、无环依赖、取消唤醒机制与 owner 关闭协议，且绝不等待主线程消费结果。公开业务状态只能由对应 owner 在 tick 中接纳完整不可变结果后再提交。宿主回调完成后可以触发新的独立工作，但不能让原 worker 挂起等待交接。

客户端的非音频工作不按内存字节额度拒绝、等待、淘汰或禁止持久写入；条目数、工作数、并发度和主循环发布预算继续成立。音频活动与保留额度、服务端独有额度，以及格式和解码的实际表示约束，仍按各自职责执行。Integrated 模式下客户端与服务端虽在同一进程中，但两侧政策不合并。数量上限不承诺进程 RSS 上界；实际内存分配失败按[失败隔离](failure-isolation.md)终结所属操作。

表达式编译链不对源码长度、节点数、嵌套深度、版本、分析工作量、生成量或产物数量施加人工额度。运行时不维护累计的 loop、value-work 或 scratch 额度；每次 loop 独立对次数取整（round）并截断至 1024，`for_each` 不截断，活跃用户函数深度仍受限。GUI 解释器只限制活跃用户函数深度，不额外限制语法树、箭头或组合调用栈，也不承诺任意深度输入下的 JVM 栈安全。解释取消在既定执行边界检查，不承诺固定耗时或指令数响应；对象退休与借用释放以实际工作退出为准。编码、类型、取消、实际 JVM 表示与原有失败隔离继续成立，不设人工额度也不承诺任意内容的耗时或内存上界。

## DD.gameplay-traffic-precedes-asset-distribution

- Claim: 服务端模型资产分发必须让位于 gameplay 业务消息的正常传输，不能为了加快模型获取而损害其他游戏内容的游玩体验。
- Rationale: 模型资产与游戏业务共享网络带宽，分发占满带宽或造成发送积压会把模型加载成本扩散到原版及其他模组的游玩过程。资源竞争时宁可接受模型获取变慢或局部拒绝分发，也要优先保护玩家正常游戏。

### BC.asset-distribution-does-not-block-gameplay

- Claim: 服务端模型资产分发占用的带宽不得阻塞 gameplay 业务消息并影响其他游戏内容的游玩体验；分发压力增大时应限流、延后或局部拒绝资产工作。此约束独立于 MSPT 等性能软目标，tick 耗时达标不能替代对 gameplay 消息传输的保护。

## DD.performance-targets-guide-optimization

- Claim: 性能参考设备、并发规模与耗时指标仅作为优化排序和后续演进的软目标；平台适配范围与最低可用性要求另行成立。
- Rationale: 制定共同目标是为了比较模组开销、安排优化排序并探测容量上限；这些目标不等于任意内容下的服务保证，软指标也不放宽普通错误隔离与游戏数据保护。

### BC.server-performance-objectives

- Claim: 服务端性能优化按以下规模和耗时目标评估，表中所有数值均属于软约束。

| 场景 | 优化与观测目标 |
|---|---|
| 100 名玩家以内 | 正常运作；重载模型列表并分发模型时，平均 MSPT 增量低于 5 ms，lag spike 不超过 10 ms |
| 200 名玩家 | 面向未来 Bukkit 插件端适配的前瞻测试，不据此扩大当前平台支持承诺 |

MSPT 指每个服务端 tick 的处理耗时。
