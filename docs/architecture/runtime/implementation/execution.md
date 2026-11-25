# 执行容量映射

本页落实[有限工作与发布](../overview.md#有限工作调度与领域交接)和[客户端资源调度](../overview.md#客户端资源调度)，定位各线程池的执行边界、交接路径与收尾责任。

## 工作线程与发布线程

| 执行环境 | 当前交接关系 |
|---|---|
| Catalog 协调线程与验证池 | 协调线程汇总完整目录候选并写入最新槽位；验证 worker 返回完整结果，不等待主线程 ACK |
| `ClientResourceScheduler` | 本地玩家与后台队列分开排队和领取；Flight、共享准备和 required bootstrap 各自消费结果 |
| 服务端资源 worker | 网络输入齐全后执行有界读取与准备；结果仅投递回本 runtime 队列 |
| Java 动画 worker | 基于实体当前代次私有状态串行求值并执行 Extract；纯观测路径不提交游戏 effect |
| `ResourceDispatchWorker` | 独占已接纳传输的出站游标与关闭权，不参与游戏权限判断 |
| Native render worker | 消费冻结的渲染任务并写入指定区间，不访问 `Entity`、Molang 或宿主纹理对象 |
| `DatabaseWork` | 独立低优先级 worker 串行执行短数据库操作并做有界接纳；关闭时等待当前同步 JNI 返回再释放连接 |
| Minecraft render thread | 负责纹理注册、上传、render adapter 调用及最终顶点提交 |

`ClientResourceScheduler` 专用本地玩家 worker 仅消费本地玩家队列，不领取后台队列任务。优先级提升与任务领取共同竞争同一未领取节点；通知定时器（timer）不执行加载或解码。调度器撤出队列仅表示取消排队，资源结束与未交付输入清理由业务负责方确认。

## 线程策略

原负责方的线程工厂以 `ThreadScheduling.entry(role, policy, body)` 包装整个 worker 循环，并将自建后台线程设为 daemon（落实[进程退出](../overview.md#jvm-进程退出)）。Catalog 协调线程、后台资源加载、数据库执行与临时编译采用低档策略；Catalog 验证池区分首次扫描与后续扫描策略；本地玩家加载、动画求值与网络 dispatch 沿用正常创建路径。

低档后台 worker 创建的子线程会继承已降低的 OS 调度属性，且 `Thread.setPriority(5)` 无法恢复，因此正常优先级线程池需在常规线程创建。JDK 内部线程及非默认 JVM 优先级策略需单独验证；平台系统调用映射与部分失败处理见[Native 调度](../../native-runtime/overview.md#调用线程调度)。
