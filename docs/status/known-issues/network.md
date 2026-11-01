# 网络与同步已知问题

版本与 wire 规则见[网络协议](../../standards/protocol-v1/README.md)，正常职责见[网络架构](../../architecture/network/overview.md#职责与边界)。

- 真实 Forge 双端的同构建 full/delta/resource/player-state、旧版本拒绝、secondary-login 单 session，以及多人/重连/退出组合仍缺机器可判定验证。
- Typed publication、request/fragment、assembly/terminal、逐项授权、forced selection 与 global dispatch 已有自动化。Descriptorless preview 的范围、容量、来源与终态也有局部覆盖，但 unit/contract 和 thin Forge host 证据不能替代真实 LAN frame 或完整产品 oracle。
- 协议不维护 catalog revision、缺号检测或 resync。同一 active connection 内残余跨消息乱序可能保留旧 collection view，仍需实机确认；重连前迟到工作由 exact connection 隔离。
- LOCAL、缺席 YSM channel、版本不匹配和 intrinsic-default-only，以及迟到 fragment/player-state/entity/notice、preview replacement、页面关闭仍缺真实 Forge 排队组合覆盖。
- 玩家指派与动画投影的首次 FULL/DELTA 交错、完整多人矩阵未联合验证。隔离 Forge 已覆盖限制叠加、屏蔽期间新选择、恢复与旧连接拒绝；未加载 YSM 的主体已分别验证无指派时保持宿主、强制指派后显示目标。协议规则见[玩家状态](../../architecture/network/player-state.md)。
- Dedicated 选模的 CLIENT-only 类故障已修复，隔离 classloader 和真实 dedicated 选模、退出重进已有证据；不能外推所有控制消息或网络交错。
- 授权变更仍缺全部通知失败、多人配置切换和音频后端交错。受控回归覆盖 public/grant/限制/强制选择、已接纳工作保留与资格恢复；隔离 Forge 已观察 AUTH 撤权后结束旧安装，再用同一 cache 时降为 default。规则见[授权决策](../../product-decisions/decisions/model-authorization.md)与[资格投影](../../architecture/network/implementation/catalog-sync.md#selection-与-request-admission)。

## 连接建立失败的宿主传播

`ClientSessionRuntime` 建立远端 activation 时，存储打开的 I/O 或运行异常会在清理当前 session 后重新抛出。`ClientLoggedEvent` 的登录监听器未隔离该异常，因此异常可越过监听器进入宿主事件路径。实际是否阻断正常 Minecraft 连接仍缺故障注入验证；不能据 session 已清理推定宿主可用性已受保护。该路径尚不能支撑[普通失败不终止游戏或连接](../../product-decisions/decisions/failure-isolation.md#bcordinary-failure-keeps-game-running)的保证。
