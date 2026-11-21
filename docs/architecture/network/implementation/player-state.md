# 玩家状态落实

本页落实[玩家状态契约](../player-state.md#seam-contract)。

## 权威模型

`ModelInfoCapability` 独占 `CommittedSelection`，`ServerModelSession` 中的选择与权威字段只是对它的只读访问。`PlayerStateHandler` 在原连接的服务端负责方窗口内校验消息结构与 intent，通过后一次性提交完整聚合。目录或授权发生变化时，由 `SessionProtocolHandler` 在目录发布后判断当前选择是否失效。`ServerModelService` 只把已编码消息写入当前业务就绪连接的分发 FIFO。

`ServerModelService` 集中负责枚举观察者、补入玩家自身、去重以及检查每条连接的发送资格。`GameTrackingView` 以只读方式借用游戏自身的追踪状态，由宿主 accessor 隔离字段访问，不在 YSM 侧额外维护第二份追踪关系表。握手首次业务就绪时，`SessionProtocolHandler` 先发送玩家自身初值，再补齐当前已追踪的远端玩家；新玩家进入追踪范围时，`CapabilityEvent` 复用同一套完整的 `EntityTrack` 发送逻辑。

## 客户端报告机会

`ClientDemandCoordinator` 按 MAIN 模块编译布局的原有顺序整理声明字典和合法命名初值，并在发送边界将初值映射为字典编号。`ClientDemandSelections` 构造完整命令后，由 `ClientPlayerPreferences` 完成旧使用汇合、替换选择与 scope、提交独立 clear，随后尝试发送一次。`PlayerStateReportPlanner` 只比较数值增量，既不等待服务端回显，也不触发 FULL 同步。

`ClientRoamingSession` 严格绑定原 scope、本次操作和对应安装。采样与状态更新不能跨转给后来的选择；暂留的旧使用或 intrinsic 替代品只具备自身的资源使用资格。

## 服务端应用与下行投影

`SelectionCodec` 先对外部批次做完整校验，再生成可信的数值载荷。完整下行选择自带声明字典，可在入队前完成解码；增量消息则要在负责方窗口内借助当前选择字典解码。各个子状态均在写入事实前完成校验。`RoamingDictionary` 保留原始声明顺序，在安装配对时建立 `wireId` 到 `localId` 的交集映射，后续增量直接复用这张映射表。`RoamingProjection` 在当前字典范围内维护零初始化的稠密数组，snapshot 会导出全部字段。`ServerDrivenPlayerPropertiesTracker` 独立负责发布实体属性增量。

`ClientPlayerStateHandler` 在确认原连接与原业务会话仍然有效后，按实体的当前角色选择消费路径；目录是否激活不是接收门禁。`LocalPlayer` 整条忽略选择变化、选择增量和 `EntityTrack`。收到完整 `EntityTrack` 时，选择、实体全量和 flags 三部分会在任何写入之前完成整体验证。`ClientSessionRuntime` 的 `ConnectionState` 保存 server flags，`ClientPlayerPreferences` 只读取该视图，不持有可写副本。

`ClientRoamingSession` 保留当前远端选择的最新网络状态，并用 `PendingRemoteReplay` 暂存安装前的等待输入。full 基线与 full 状态段导出整个字典域；delta 段则单独记录本段写入的字段集合，避免借用稠密状态的完整快照覆盖先前修改。连接负责方负责统计总容量占用，选择负责方在消费或清理时归还额度。安装提交时读取当前的基线与等待序列；`EntityExpressionRuntime` 在匹配安装的合法脚本窗口内按顺序应用状态段、执行 `sync` 并排空延后动作。候选准备失败时不消费序列；一旦开始消费，剩余项也不会转交给重建后的安装。具体生命周期与失败边界见[远端安装等待与消费](../player-state.md#远端安装等待与消费)。
