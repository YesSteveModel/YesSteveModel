# 玩家状态网络验证

协议与业务契约分别由[玩家状态](../architecture/network/player-state.md)与[Protocol V1](../standards/protocol-v1/README.md)维护，本页记录自动化入口、实机场景与证据范围。

## 语义与制品

状态验证使用真实的消息注册、Proto 编解码、物理帧以及两端领域入口。测试中以传输 sink、owner 调度与 capability 查找替代完整 Forge 启动，不证明真实多人时序。

| 边界 | 验证入口 |
|---|---|
| 局部编号、字典映射、整批校验与 full 清域 | `RoamingDictionaryTest`、`SelectionIntentProtocolTest`、`RuntimeRoamingOwnershipTest` |
| 本地提交、拒绝纠正、连接与 intent | `SessionProtocolHandlerContractTest`（原伪 MC 宿主 `ClientSelectionPreferenceHandoffTest`/`ClientDemandSelectionAdmissionTest` 已退役并转交 L3 真机冒烟） |
| 追踪接收者、握手补齐及两类 flags | `PlayerStateHandlerDeltaTest`（原 `ServerPlayerStateDeliveryTest` 已退役） |
| 业务会话、当前角色与完整 track 接纳 | `ClientSessionRuntimeConnectionTest`（原 `ClientPlayerStateIngressTest` 已退役） |
| 安装期状态与事件顺序、预算及终点 | `ClientRoamingSessionTest`、`ClientRoamingReplayRuntimeTest`、`RemoteSyncBudgetTest` |
| 事件执行与 roaming owner | `ExpressionActionIngressTest`、`RuntimeRoamingOwnershipTest` |
| 专用服务端注册与字节码侧隔离 | `ControlHandlerDistributionTest`、`ServerSideIsolationTest` |
| 选择与实际使用、候选和 lease | `EntityModelBindingTest`、`ClientDemandCoordinatorTest` |
| SQLite、donor、clear 与写入顺序 | `PlayerPreferencesTest`、`StorageWriteBufferTest` |
| 配套运行时及冷启动与磁盘暖加载 | `verifyExpressionRuntimeBundle`、`verifyExpressionArtifactWarmLoad` |

*（原 `NetworkDiagnosticsProcessTest` 因采用自定义字节码 `ClassLoader` 改写核心网络类且耦合日志子串已退役；网络诊断开关与真实帧路径分别由 `ServerHundredPlayerContractTest`、`ServerScaleLoadHarness` 与 L3 真机观察覆盖。）*

`PlayerPreferencesTest.sqliteAndDonorRestoreDoNotRetainDictionaryNumbering` 将反序字典解码为名称，经真实 SQLite 写入、关闭并重开，再作为 donor 恢复到新版本，验证新字典重新编号后各名称的值与负零均保持不变。

SQLite 测试需要配置 `YSM_NATIVE_PATH`，启动方法见[构建指南](../build.md#java-代码格式)。Semaweave 的真实生成器与角色测试由引擎侧验证文档维护，宿主制品检查不能替代这些测试。

下述历史 Forge 双端记录出自 2026-11-11 的原 `modelManagementMockForge`（`YSM_MOCK_PLAYER_STATE=true`）自动化宿主演练；该自动化探针入口现已移除，后续同类实机复验转由 L3 `runServer` + `runClient` 手工冒烟配合 L1 契约单测执行。该历史记录使用 dedicated server 与两个 AUTO 客户端，先验证远端安装期输入，再验证初值与 flags。

安装期场景由客户端 A 发送真实的 C2S full、delta 与 sync，经服务端准入、tracking 中继和客户端 B 的生产 handler 接纳。服务端 `disabled` 充当安装等待门禁：`disabled=true` 时，B 须保留待消费输入，此时实际安装与表达式 module 均不存在，sync 与 defer 事件数均为 0（等待检查分别核对安装与 module，不以求值资格代证未安装）。解除 `disabled` 后，B 经真实资源准备与安装提交执行生成的 `@sync`、`@defer` 和 `@player_update`。`RemoteReplayServerProbe` 观察实际出站载荷，`RemoteReplayClientProbe` 记录连续执行 trace 以及当前和旧 owner 的队列与预算。

| 安装期检查项 | 应观察的结果 |
|---|---|
| full、delta 与 sync 交错 | 首个 sync 写入 `y=9` 后，后续 sync 在尾部 delta 之前读到 9；前一个 sync 的 defer 先于下一个 sync 执行，普通 player update 不穿插 |
| sync 后同 intent full | 前面的 sync 先完成，随后完整状态替换整个域并清除缺席值，后续 delta 按序生效 |
| 重复事件与同值 delta | 两个相同参数的 sync 均须执行；后续网络同值 `x=0` 仍覆盖脚本写入，下一个 sync 读到 0 |
| 消费后重装 | 队列与连接预算归零，已消费的 sync 不再次执行 |
| 等待中选择替换、实体退出、断连重连 | 三种终点分别使原保留队列与旧预算归零；新安装采用当前基线，事件数保持 0 |

同 intent 的 full 通过生产 `sendTrackingInitial` 发送完整 track 触发（C2S 重复完整选择按协议会被拒绝，不能用它模拟）。退出检查必须保留旧队列和旧预算的引用，不能只检查新 owner 的空队列；连续 trace 须同时核对状态值、事件与安装身份，不能只看最终结果。

初值场景由 `PlayerStateServerProbe` 记录实际 Forge 出站帧与追踪启动时的业务资格，`PlayerStateClientProbe` 读取生产 handler 已采用的实体状态。检查项包括：自身初值帧早于 catalog full 到达、实体与 server flags、自身 track 整条忽略、停止并重新追踪，以及 catalog 失败后的 track 与 server flags 接收。生产日志中的 `applied EntityTrack` 只在实体存在后输出，缺实体等导致的 track 丢弃会使场景失败。

握手与追踪的先后按真实事件记录，不设人为 gate 强制排序，未实际观察到的顺序不得判为通过。初值帧早于 catalog 字节也不证明服务端 catalog 构造失败。该入口未单独注入资源准备或回放中途的新到包，也不覆盖容量溢出、消费中途退休、全面故障注入、像素或性能成本；实际执行状态见[支持状态](../status/support-and-verification.md)。

2026-11-11，在 Windows 11、Minecraft 1.20.1、Forge 47.4.3 环境下，dedicated 服务端与双客户端通过了上述场景且无不匹配：六个等待点均有非空队列，实际安装与表达式 module 均不存在，脚本事件数为 0；放行后连续 trace 记录 7 次 sync 与 7 次 defer，每组普通 player update 均在该组最后一次 defer 后出现：

| 执行组 | 实测 trace 与终态 |
|---|---|
| 交错 | 第二次 sync 读取 `x=4`、`y=9`、`script=13`；尾部 delta 后 `x=4`、`y=5` |
| full 屏障 | 第二次 sync 读取 `x=8`、`y=0`、`script=0`、`calls=1`、`deferred=0` |
| 重复与同值 | sync 读取的 `x` 依次为 `0`、`5`、`0`，defer 读取的 `x` 依次为 `5`、`5`、`7`；尾部 delta 后 `x=2` |
| 已消费后重装 | 事件数、`calls`、`deferred` 与待消费预算均为 0，`x` 保持 2 |
| 三类等待期清理 | 旧队列与旧预算均归零并连续 5 tick 保持；恢复安装后事件数为 0，运行时 `x=12`、`y=13` |

清理 trace 分别证实选择操作已替换、旧实体已退出且选择清空，以及业务会话已替换且旧预算失活。初值与 flags 场景同样通过，实际记录业务就绪前 2 次、就绪后 4 次追踪启动；三个进程正常退出，无强制清理或残留。同日 17 个 suite 共 82 项定向测试全部通过（零失败、零错误、零跳过），其中网络诊断测试覆盖八个独立 JVM。

## 数字 roaming 验收

2026-11-11，配套引擎 `check` 通过 427 项测试，宿主通过 29 个 suite 共 362 项定向测试，均无失败或跳过。宿主测试使用真实 Windows DLL 与完整实体 fixtures，覆盖 MAIN 与 ARM 独立编号、默认零值、导出有限化、命名 SQLite 恢复、失败 scope、整批拒收及安装期稀疏回放。

真实生成器测试覆盖 roaming 的 present F32 传播与 `??` 折叠、普通 nullable 反例、原生 float transient 授权和消费端无 dirty 存储；只声明未引用的字段也保留在资源布局与完整零快照中，纯算术模块同时通过冷暖加载。

`verifyExpressionRuntimeBundle` 与 `verifyExpressionArtifactWarmLoad` 均通过。重定位后的生成类实际使用游戏自带的 fastutil，成品不含第二份 fastutil。另以历史配套 jars 真实生成旧 artifact 字节，确认新预期以 `COMPILATION_INPUT` 精确未命中拒绝旧字节，随后新的冷编译与暖加载全部通过（未清缓存或伪造身份）。

同日使用当时配套 jars 在上述 Windows Forge 环境中复跑窄场景，结论为 Pass，不匹配与失败产物均为空。259 条宿主观察记录包含 7 次 sync 与 7 次 defer，交错、full 屏障、同值覆盖、重装与三类等待期清理均符合上表预期，预算最终归零，三个进程正常停止且无强制清理或残留。

本轮结论、连续 trace 与清理记录保存在 `build/verification/model-management/roaming-array-forge-20261111-135803/MMR-FORGE-THIN-001`。畸形数值与容量边界有定向测试覆盖，但未做 Forge 实机注入；消费中途退休与新包竞争、长期运行及其他平台仍未实机验收。

同日实施后审查复现了 nullable F32 写入 roaming 的编译回归：随后读取的 `??` 触发存在性断言，赋值结果的 `??` 触发 `float` 与 `long` 形状冲突。修复方式是在赋值 lowering 阶段保留原有的标量归零转换并截断反向存在性需求，同时移除生成器的两处重复断言。真实的冷启动、暖加载与 transient 用例覆盖 roaming、普通冻结 present 与 nullable 目标，以及 null、负零、正常值、单次 RHS 求值与回退调用轨迹，普通 nullable 行为保持不变。

该次审查还移除了 transient 重复查槽与 bridge-null 检查、重复角色检查、无效 unsafe 扫描、重复编号推导、相邻网络层重复校验、同关系映射重建以及存储重复编码；载具内部投影不再执行 wire 校验，而外部 map 入口仍坚持整批拒收。编译最终重写时的一次冻结布局复制和载具 `StringPool` 转换予以保留，未新增缓存或协调状态。

审查修复通过了 167 项引擎定向测试与 13 个 suite 共 114 项宿主测试，零失败、零错误、零跳过。最终配套 jars 复验了宿主 artifact 与 ownership 两个 suite 共 48 项测试、成品重定位与冷暖加载，真实旧字节仍以 `COMPILATION_INPUT` 精确未命中拒绝，格式与文档检查也全部通过。本次审查未重跑全量 `check`、Forge 或 JMH，上文的 Forge 记录对应审查前的配套制品。

## 成本测量

`RoamingNetworkBenchmark` 使用真实生成的 roaming state、准备后的数字映射、Proto 与生产帧。`generatedExport`、`mapping`、`accumulatedApply` 和 `protobuf` 分别计量单一边界，`chain` 串联从导出到接收 apply 的完整路径，不能把各分片耗时相加来代替串联结果；`relation` 单列字典及安装映射的建立成本，稳定 delta 会复用该关系。

矩阵中的消息均无附件且 Proto 小于 30 KiB，实际走单字节 tag 的 simple frame，不触发 Zstd 压缩。结果不包含 Minecraft 外层 framing、传输压缩、socket 或真实网络 I/O。

字段规模为 1、8、32、64，六类场景分别为完整 roaming、全部 dirty 的 delta、单项稀疏 delta、空 delta、选择发布以及开始追踪。主矩阵字段名为 12 ASCII bytes，独立对照另验证 32 UTF-8 bytes。对照文件保存命名值、载荷写集合、Proto 与帧字节以及解码后读值；输出中的 `presence` 仅表示载荷写集合，不代表运行期缺值。

旧版适配器保存在 `src/verification/benchmark/legacy/RoamingNetworkAdapter.java`，只挂入固定历史源码的 benchmark source set，用以匹配历史接口工作量（当前数字接口的工作量不能直接复制到历史工程）；历史生产源码与配套 jars 保持原样。历史路径使用命名 Map、`StringPool` 与旧 codec，候选路径使用数组、selection 字典与数字 codec；旧 FULL 携带模型与实体状态，候选完整 roaming 走 selection delta，选择与 track 各自使用当前完整消息。两者恢复相同的命名值，但协议职责与发布范围不同。

双版本对照前，须先在各自匹配的源码树运行 `prepareBenchmark` 并指向同一 DLL。Campaign 比对 40 个共同对照行，另验候选的 8 个全部 dirty 行。在当前工程运行：

```powershell
python src/verification/benchmark/roaming_network_campaign.py --legacy-launch <legacy-launch.json> --output <new-output-directory> --phase oracle
python src/verification/benchmark/roaming_network_campaign.py --legacy-launch <legacy-launch.json> --output <same-output-directory> --phase pilot
python src/verification/benchmark/roaming_network_campaign.py --legacy-launch <legacy-launch.json> --output <same-output-directory> --phase measure
python src/verification/benchmark/roaming_network_analyze.py <same-output-directory>
```

Campaign 使用 Java 17、JMH 1.37 与 GC profiler。每格每版运行 2 个独立 fork，顺序为旧、候选、候选、旧；串联采用 1 次 500 ms 预热与 2 次 500 ms 测量，分层采用 1 次 100 ms 预热与 2 次 200 ms 测量。Pilot、旧对旧校准与正式采样共用 1200 秒预算。较短的预热可能残留 JIT 波动，评估 CPU 排名须结合原始迭代数据与校准噪声。

输出保存启动清单、classpath、JAR、DLL 与 JDK 哈希、完整参数、日志和 JMH JSON；一旦 classpath 发生变化，脚本拒绝沿用旧对照或 pilot。主矩阵默认关闭诊断，`diagnostics` 另行比较开关诊断对摘要与日志 I/O 的影响。

稀疏导出包含一次同值生成写以重新置脏，全部 dirty 导出包含全部字段的同值写。串联计时包含 Proto 数组、帧编码与模拟传输复制，计时区内不含名称对照 Map。JMH 的 B/op 覆盖整个被测边界，不单独归因到某个数组或对象。可信载荷构造不做内容校验，外部接收检查仍保留在 decode 边界。

冷关系建立额外计入名称校验与安装交集，编译器名称顺序不再重新排序。由于候选夹具返回一个列表包装，而旧夹具不含 `StringPool` 首次登记，单层差额不能全归因于关系对象本身。当前协议未设独立的 frame-only 计时，帧开销只在串联中观察。

### 数字布局的单版本采样

2026-11-11，在 Windows、Java 17、JMH 1.37 下测得 64 字段、12 ASCII bytes 名称的单版本样本：每格 2 个独立 fork、2×500 ms 预热、3×500 ms 测量，单线程并启用 GC profiler。对照校验与采样总耗时 375.6 秒，期间未并行运行构建或 Forge；网络对照的 48 行及运行时 16、64、256 字段对照全部通过。

以下数据对应当时 manifest 固定的制品；后续审查修复未重新采样，不据此宣称去重后的额外增益。

下表各单元格格式为“平均 ns/op | B/op”。完整 roaming 行在无新写时调用 `full`，选择与 track 的导出列调用 `snapshot`；各分层与串联各自独立计时，不能把分层时间相加来推算串联时间。

| 机会 | 生成导出 | 数字映射 | 累积及本地 apply | Proto 编码 | 串联 |
|---|---:|---:|---:|---:|---:|
| 空变化 | 7.8 \| 56 | 2.5 \| 24 | 3.0 \| 0 | 15.2 \| 112 | 364.1 \| 1120 |
| 单字段变化 | 13.7 \| 72 | 2.6 \| 24 | 13.2 \| 48 | 23.4 \| 176 | 391.5 \| 1332 |
| 全部 dirty | 159.0 \| 440 | 2.5 \| 24 | 122.0 \| 416 | 1361.5 \| 3532 | 3279.4 \| 8792 |
| 完整 roaming | 41.4 \| 440 | 2.4 \| 24 | 151.5 \| 416 | 1453.8 \| 3544 | 2958.4 \| 8792 |
| 选择及 snapshot | 43.4 \| 440 | 2.6 \| 24 | 152.7 \| 416 | 3656.3 \| 6264 | 19493.0 \| 52951 |
| track 及 snapshot | 43.9 \| 440 | 2.5 \| 24 | 150.0 \| 416 | 3708.8 \| 6432 | 20815.1 \| 51636 |

关系建立为 7362.8 ns/op、16686 B/op。`MolangRoamingBenchmark` 等量交替 producer 与 consumer：一次写加 1 次读约 12.6 ns/op，一次写加 16 次读约 15.1 ns/op，两种除零选项结果一致，内存分配均小于 0.001 B/op。

原始参数、classpath、JAR、JDK 与 DLL 哈希、各迭代明细及误差保存在 `build/verification/roaming-arrays-20261111`。本轮仅测量当前实现，未建立历史速度比；短预热与 JIT、GC 波动限制了时间排序结论，结果不能证明完整游戏收益。

## 发布场景

记 `N` 为发送时游戏当前观察者与自身去重并通过业务会话筛选后的接收者数，`R` 为旧路径的可见接收者与自身数，`T` 为开始追踪与握手补齐实际发送的 track 次数。候选路径的三类变化各按实测单包大小乘 `N`，track 乘 `T`；旧路径的变化按 `R` 计费，旧周期 FULL 单列（候选路径无周期 FULL）。

三类变化分别对应 `SelectionChange`、`SelectionStateSync` 和 `EntityStateSync`：主矩阵的 selection 对应第一类，full、sparse、empty 分别对应第二类的完整、单项与空 roaming，track 对应定向 `EntityTrack`；实体 delta 与开关变化另行计费。

对比时应分别给出选择次数、delta 次数、实体变化次数、开关变化次数和初始化次数。候选的选择与 track 消息内含字典，不得从总流量中扣除；空 delta 的编码成本也不表示稳定 tick 会发送空包。在缺少实际工作负载分布时，应报告参数化的盈亏平衡点（break-even），不要臆测玩家操作频率。

实体 flags 按 `N` 计费，server flags 按业务就绪连接数计费。接收者筛选与初值验收须经过生产发送模块，统计观察者、自身、非观察者、未就绪连接与旧连接的实际收包次数；Forge 场景另须核对先握手后追踪、先追踪后握手、重新追踪及客户端实体 spawn 顺序，单凭 handler 测试或入队成功不能代替验证。

单包字节数乘接收者数只是流量估算，不是真实多人传输或吞吐实测。受控 JVM、JNI 与自动化测试均不证明 FPS、MSPT、真实 Forge 多人、Replay 或其他平台表现；当前差异见[网络问题](../status/known-issues/network.md)。
