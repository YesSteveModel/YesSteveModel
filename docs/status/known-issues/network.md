# 网络与同步已知问题

版本与 wire 规则见[网络协议](../../standards/protocol-v1/README.md)，正常职责见[网络架构](../../architecture/network/overview.md#职责与边界)。

- 真实 Forge 双端目前仅局部验证了当前 player-state 的初值、flags 与重新追踪。完整的 full、delta 与资源交错、旧版本拒绝、二次登录的单 session 约束，以及多人、重连与退出组合仍缺少机器可判定的端到端验证。
- 强类型 publication、请求与分片、组装与终态、逐项授权、强制选择以及全局 dispatch 已具备自动化测试。无 descriptor 的 preview 在范围、容量、来源与终态方面也有局部覆盖，但单元、契约测试与薄 Forge 宿主证据不能替代真实局域网（LAN）传输或完整产品对照。
- 协议不维护 catalog 版本号、缺号检测或全量重同步。在同一条活跃连接内，如果残留跨消息乱序，可能保留旧的 collection 视图，这一点仍需实机确认；重连前的迟到任务则按精确连接隔离。
- LOCAL 模式、缺少 YSM channel、版本不匹配和仅内置默认模式（intrinsic-default-only），以及迟到的分片、玩家状态、实体状态、通知、preview 替换和页面关闭，仍缺少真实 Forge 排队组合覆盖。
- 玩家指派与动画状态投影的首次 FULL 与 DELTA 交错及完整多人矩阵尚未联合验证。隔离 Forge 已覆盖限制叠加、屏蔽期间的新选择、恢复与旧连接拒绝；未安装 YSM 的客户端主体已分别验证在无指派时保持原宿主模型、收到强制指派后显示目标模型。协议规则见[玩家状态](../../architecture/network/player-state.md)。
- 专用服务端选模触发 CLIENT-only 类加载的故障已修复，隔离 classloader 与真实 dedicated 选模、退出重进已有证据，但不能外推到所有控制消息或网络交错场景。
- 授权变更仍缺少全部通知失败、多人配置热切换和音频后端交错的验证。受控回归已覆盖 public、grant、限制、强制选择、已接纳任务保留与资格恢复；隔离 Forge 已观察到 AUTH 撤权后结束旧安装，且再次使用同一缓存时降级为默认模型。规则见[授权决策](../../product-decisions/decisions/model-authorization.md)与[资格投影](../../architecture/network/implementation/catalog-sync.md#selection-与-request-admission)。

## 玩家状态协议与 roaming

实体状态已改为只向游戏当前观察者及发送者自身下发，沿原业务会话筛选并遵循 dispatch FIFO；握手就绪时补发当前追踪关系，`StartTracking` 发送完整 `EntityTrack`。实体 `disabled` 标志与会话级 `ServerFlags` 已拆分，状态入口不再等待 catalog publication。远端当前选择保留最新的 roaming 网络状态，以及安装前的有界交错队列，按状态段和 sync 的实际接收顺序消费；每个选择最多暂存 64 个 sync，单条连接合计最多 4096 个，超限时丢弃新事件并计数。

- **自动化与制品验证（2026-11-11）**：数值 roaming 改造通过了 427 项引擎测试及 29 个 suite 共 362 项宿主测试，零失败、零错误、零跳过。测试覆盖 present F32、独立编号与映射、入口整批校验、SQLite 恢复、安装期顺序、预算与生命周期，同时通过了成品重定位、冷暖加载与真实旧 artifact 拒收。详细入口与单版本成本样本见[玩家状态网络验证](../../verification/player-state-network.md)。
- **Forge 双端初值与重追踪（2026-11-11）**：在 Windows 11、Minecraft 1.20.1、Forge 47.4.3 环境下，dedicated 服务端与两个 AUTO 客户端通过了扩展窄场景且无任何不匹配。实际记录到业务就绪前 2 次、就绪后 4 次追踪启动；双方初始消息（ID 16、18、37）均早于 catalog（ID 2）到达，完整 track 在当前实体存在时经生产入口正常采用。`disabled` 标志正确送达自身与观察者，服务器切模开关变更对每条连接只发送一次 `ServerFlags`，LocalPlayer 整条忽略测试 track。客户端 A 进入 `INTRINSIC_DEFAULT_ONLY` 后，重新追踪与服务器开关依然正常生效。
- **Forge 双端安装期回放**：同日使用最终配套 jars 复跑了安装期场景，完整走过真实 C2S 准入、tracking 中继、客户端接纳与资源安装，连续记录 7 次 sync 与 7 次 defer。安装提交前的事件数为 0；交错序列、full 屏障、重复事件与同值 delta 均按序消费，常规动画未穿插。等待期间的选择替换、实体退出和断连重连分别清空旧队列与预算，恢复安装不执行旧事件，消费完成后重装也不重放，最终预算归零。三个进程正常退出，无强制清理或进程残留。精确 trace 见[玩家状态网络验证](../../verification/player-state-network.md)。

真实 Forge 下的消费中途退休、容量溢出、handler 故障、完整多人交错、渲染、GPU 与游戏性能仍未验收。Replay 模组的实际录制、资源取得与中途基线不在当前承诺范围。由于协议不设周期 FULL、ACK 或后台恢复，状态或通知遗漏允许造成长期状态漂移。

## 连接建立失败的宿主传播

`ClientSessionRuntime` 建立远端激活时，如果打开存储遇到 I/O 或运行时异常，会在清理当前 session 后将异常重新抛出。由于 `ClientLoggedEvent` 的登录监听器尚未隔离该异常，异常会越过监听器传回宿主事件总线。该异常实际是否会阻断正常的 Minecraft 连接仍缺少故障注入验证，不能仅凭 session 已清理就推定宿主可用性未受影响。当前实现尚不能支撑[普通失败不终止游戏或连接](../../product-decisions/decisions/failure-isolation.md#bcordinary-failure-keeps-game-running)的产品保证。
