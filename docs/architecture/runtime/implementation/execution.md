# 执行容量映射

本页落实[有限工作与发布](../overview.md#有限工作与发布)和[客户端资源调度](../overview.md#客户端资源调度)，只定位执行、交接与终态责任。

## 工作线程与发布线程

| 执行环境 | 当前交接关系 |
|---|---|
| Catalog coordinator / 验证池 | Coordinator 组织完整 candidate 并交最新槽位；worker 只交完整 outcome，不等主线程 ACK |
| `ClientResourceScheduler` | 本地玩家与后台独立排队和领取；Flight、共享准备和 required bootstrap 消费各自结果 |
| Server runtime worker | 网络输入齐全后做有限读取与准备；terminal 只入本 runtime queue |
| Java 动画 worker | 使用本 entity generation 的私有状态，串行求值与 extract；observation 不能提交 effect |
| `ResourceDispatchWorker` | 独占已接纳传输的 outbound cursor，不裁决游戏权限 |
| Native render worker | 只消费冻结 task 并写指定区间，不读取 `Entity`、Molang 或宿主纹理对象 |
| Minecraft render thread | 完成纹理注册、上传、render adapter 调用及顶点提交 |

`ClientResourceScheduler` 的专用本地玩家 worker 不领取后台队列。提升与领取共同裁决同一未领取节点；通知 timer 不执行加载或解码。业务 owner 仍拥有取消和未交付输入，不把 scheduler 的撤队列结果当作资源终态。

## 线程策略

原 owner 的 thread factory 用 `ThreadScheduling.entry(role, policy, body)` 包装整个 worker loop。该门面不创建 executor，也不接管任务终态。Catalog coordinator、后台资源与临时编译采用低档策略；Catalog 验证区分首次与后续策略。本地玩家、动画与 dispatch 保留正常 owner 的创建路径。

不能从低档 worker 懒创建正常池，再用 `Thread.setPriority(5)` 推定恢复 OS 状态。JDK 内部线程与非默认 JVM priority policy 需独立验证。平台映射和部分失败见[Native 调度](../../native-runtime/overview.md#调用线程调度)；策略本身不证明帧率收益。
