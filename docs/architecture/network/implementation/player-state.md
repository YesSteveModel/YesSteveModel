# 玩家状态落实

本页落实[玩家状态契约](../player-state.md#seam-contract)。

## 权威模型

- `ModelInfoCapability` 独占 `CommittedSelection`，`ServerModelSession` 中的选择与权威字段为其只读视图。
- `PlayerStateHandler` 在原连接服务端负责方窗口内校验结构与 intent，通过后一次性提交完整聚合；目录或授权变更时，由 `SessionProtocolHandler` 在目录发布后判断当前选择是否失效。`ServerModelService` 仅将已编码消息写入业务就绪连接的分发 FIFO。
- `ServerModelService` 集中负责枚举观察者、补入玩家自身、去重与检查发送资格。`GameTrackingView` 通过宿主 accessor 只读借用游戏自身追踪状态。握手首次业务就绪时，`SessionProtocolHandler` 先发玩家自身初值，再补齐当前已追踪远端玩家；新玩家进入追踪范围时，`CapabilityEvent` 复用同一 `EntityTrack` 发送路径。

## 客户端报告机会

- `ClientDemandCoordinator` 按 MAIN 模块编译布局原顺序整理声明字典与合法命名初值，在发送边界将初值映射为字典编号。
- `ClientDemandSelections` 构造完整命令后，由 `ClientPlayerPreferences` 完成旧使用汇合、替换选择与 scope、提交独立 clear 并尝试发送一次。`PlayerStateReportPlanner` 仅比较数值增量，不等待服务端回显也不触发 FULL 同步。
- `ClientRoamingSession` 绑定原 scope、本次操作与对应安装，采样与更新不转交给后续选择；暂留旧使用或 intrinsic 替代品仅保留自身资源使用资格。

## 服务端应用与下行投影

- **解码与投影**：`SelectionCodec` 对外部批次完整校验后生成可信数值载荷。完整下行选择自带声明字典，可在入队前解码；增量消息在负责方窗口内借助当前选择字典解码，各子状态均在写入事实前完成校验。`RoamingDictionary` 保留原始声明顺序并在安装配对时建 `wireId -> localId` 交集映射；`RoamingProjection` 在当前字典范围内维护零初始化稠密数组，snapshot 导出全部字段。`ServerDrivenPlayerPropertiesTracker` 独立发布实体属性增量。
- **客户端接收路由**：`ClientPlayerStateHandler` 确认原连接与原业务会话有效后按实体角色路由，不以目录激活作为接收门禁。`LocalPlayer` 忽略选择变化、选择增量与 `EntityTrack`。完整 `EntityTrack` 在写入前整体校验选择、实体全量与 flags；`ClientSessionRuntime` 的 `ConnectionState` 保存 server flags，`ClientPlayerPreferences` 只读访问。
- **远端安装等待与重放**：`ClientRoamingSession` 保留当前远端选择最新网络状态，并用 `PendingRemoteReplay` 暂存安装前等待输入：full 基线与 full 状态段导出整个字典域，delta 段单独记录本段写入字段集合，避免稠密快照覆盖先前修改。连接负责方统计总容量，选择负责方在消费或清理时归还额度。安装提交时读取基线与等待序列，`EntityExpressionRuntime` 在匹配安装的合法脚本窗口内顺序应用状态段、执行 `sync` 并排空延后动作；候选准备失败不消费序列，开始消费后剩余项不转交重建安装（边界见[远端安装等待与消费](../player-state.md#远端安装等待与消费)）。
