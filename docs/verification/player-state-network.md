# 玩家状态网络验证

本页记录玩家状态与数值 Roaming 的 L1 契约单测、L3 联调关注点与 L2 成本测量入口；协议与业务契约见[玩家状态](../architecture/network/player-state.md)与[Protocol V1](../standards/protocol-v1/README.md)，当前缺口见[网络问题](../status/known-issues/network.md)。

## L1 契约回归与制品检查

L1 测试复用真实消息注册、Proto 编解码、物理帧与两端领域入口（以受控传输 sink 替代完整 Forge 网络）；SQLite 相关用例需配置 `YSM_NATIVE_PATH`（见[构建指南](../build.md)）。

| 验证边界 | 对应入口 |
|---|---|
| 局部编号、字典映射、整批校验与 full 清域 | `RoamingDictionaryTest`、`SelectionIntentProtocolTest`、`RuntimeRoamingOwnershipTest` |
| 本地提交、拒绝纠正、连接与 intent | `SessionProtocolHandlerContractTest` |
| 追踪接收者、握手补齐与两类 flags | `PlayerStateHandlerDeltaTest` |
| 业务会话、当前角色与完整 track 接纳 | `ClientSessionRuntimeConnectionTest` |
| 安装期状态/事件顺序、预算与终态 | `ClientRoamingSessionTest`、`ClientRoamingReplayRuntimeTest`、`RemoteSyncBudgetTest` |
| 事件执行与 roaming owner | `ExpressionActionIngressTest`、`RuntimeRoamingOwnershipTest` |
| 专用服务端注册与字节码侧隔离 | `ControlHandlerDistributionTest`、`ServerSideIsolationTest` |
| 选择与实际使用、候选与 lease | `EntityModelBindingTest`、`ClientDemandCoordinatorTest` |
| SQLite、donor 重编号、clear 与写入顺序 | `PlayerPreferencesTest`、`StorageWriteBufferTest` |
| 配套运行时重定位与冷暖加载 | `verifyExpressionRuntimeBundle`、`verifyExpressionArtifactWarmLoad` |

原依赖 `Unsafe` 或自定义字节码 `ClassLoader` 伪造宿主/网络类的套件（含 `NetworkDiagnosticsProcessTest`）已退役，改由上述纯单测、`serverMockTest`/`serverScaleLoad` 及 L3 覆盖。

## L3 真机联调关注点

原 `modelManagementMockForge` 自动探针已移除，真实双端行为通过 L3 `runServer` + `runClient`（dedicated server + 双客户端）核对：

- **初值与追踪**：自身初值与 `disabled`/`ServerFlags` 可早于 catalog 到达；自身 track 忽略；停止与重追踪以及 catalog 失败后仍可接收 track 与 `ServerFlags`。
- **安装期回放与等待清理**：`disabled=true` 或安装就绪前暂存 full/delta/sync（安装提交前脚本事件数为 0）；就绪后按序消费交错序列、full 清域屏障、重复事件与同值覆盖，sync 的 defer 排空后再推进普通更新；等待期发生选择替换、实体退出或断连重连时，旧队列与旧连接预算归零且消费后重装不重放。

## 成本测量

`RoamingNetworkBenchmark` 按字段规模（1、8、32、64）与六类场景（完整 roaming、全部 dirty、单项稀疏、空 delta、选择发布、开始追踪）分别计量 `generatedExport`、`mapping`、`accumulatedApply`、`protobuf`、`chain`（端到端串联）与 `relation`（冷关系建立）；`MolangRoamingBenchmark` 测量 producer/consumer 交替读写。

### 新旧版本配对 Campaign

在各自源码树运行 `prepareBenchmark` 并指向同一 native DLL 后，按阶段执行对照脚本（历史适配器位于 `src/verification/benchmark/legacy/RoamingNetworkAdapter.java`）：

```powershell
python src/verification/benchmark/roaming_network_campaign.py --legacy-launch <legacy-launch.json> --output <output-dir> --phase oracle
python src/verification/benchmark/roaming_network_campaign.py --legacy-launch <legacy-launch.json> --output <output-dir> --phase pilot
python src/verification/benchmark/roaming_network_campaign.py --legacy-launch <legacy-launch.json> --output <output-dir> --phase measure
python src/verification/benchmark/roaming_network_analyze.py <output-dir>
```

- **产物与守门**：`<output-dir>` 保存启动清单、classpath/JAR/DLL/JDK 哈希、日志与 JMH JSON；classpath 变更时脚本拒绝复用旧 oracle 或 pilot。
- **流量估算口径**：单包大小乘以去重后的业务就绪接收者数 `N`（track 乘实际追踪数 `T`，`ServerFlags` 乘业务就绪连接数），选择与 track 消息内的字典计入总流量；缺少真实操作频率分布时报告参数化盈亏平衡点。
- **验证边界**：矩阵消息均小于 30 KiB（走 simple frame，不触发 Zstd 压缩），不含 Minecraft 外层 framing、socket I/O 或真实多人吞吐；各分层耗时不可直接相加替代 `chain`。
