# 网络与同步已知问题

协议规范见[网络协议](../../standards/protocol-v1/README.md)，正常职责见[网络架构](../../architecture/network/overview.md#职责与边界)，验证入口见[玩家状态网络验证](../../verification/player-state-network.md)。

## 协议与状态同步

### 已知局限

- 协议不维护 catalog 版本号、缺号检测、周期 FULL、ACK 或后台恢复：同一条活跃连接内若残留跨消息乱序或状态/通知遗漏，可能保留旧 collection 视图或产生长期状态漂移。
- 远端安装前暂存队列采用固定预算（单选择 64 个 sync、单连接合计 4096 个），超限时丢弃新事件并计数。

### 未实现范围

- Replay 模组的实际录制、资源取得与中途基线不在当前承诺范围。

### 未验证缺口

- 真实 Forge 双端及真实 LAN 传输下的完整 full/delta/资源交错、旧版本拒绝、二次登录单 session 约束与长期资源回收。
- LOCAL 模式、无 YSM channel、版本不匹配、仅内置默认模式（intrinsic-default-only）与迟到分片/状态/通知/preview 替换的真实排队组合。
- 玩家状态与安装期回放的消费中途退休、容量溢出、handler 故障、完整多人交错及性能成本。
- 授权变更在通知失败、多人配置热切换与音频后端交错下的实机表现（规则见[授权决策](../../product-decisions/decisions/model-authorization.md)与[资格投影](../../architecture/network/implementation/catalog-sync.md#selection-与-request-admission)）。

## 连接建立失败的宿主传播

- **已知缺陷**：`ClientSessionRuntime` 建立远端激活时，若打开存储遇到 I/O 或运行时异常，在清理当前 session 后会将异常重新抛出；`ClientLoggedEvent` 登录监听器尚未隔离该异常，异常会越过监听器传回宿主事件总线。
- **未验证缺口**：缺少故障注入验证该异常是否阻断正常 Minecraft 连接；当前实现尚未满足[普通失败不终止游戏或连接](../../product-decisions/decisions/failure-isolation.md#bcordinary-failure-keeps-game-running)的产品保证。
