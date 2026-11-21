# 模型管理验证架构

> **适用问题**：模型管理验证的分层设计、物理端隔离、百人负载验证与发布包边界；**不包含**：产品契约、完整场景清单或某次具体运行的验收结论。

验证直接复用生产环境的 catalog、session、授权、消息分发（`TransportPort` seam）与资源链路，不再维护独立的多进程 mock 宿主演练与 JSONL 审计台账；`SessionCollectionPublication.fullFragments` 提供显式 animation map 重载用于构造测试输入，普通入口仍读取默认动画。

## 分层与可证明范围

按[验证边界](policy.md#验证分层架构)拆分为四层正交验证，各层只证明自身覆盖的边界：

| 层 | 入口与职责 | 不能替代的证据 |
|---|---|---|
| L0 静态门禁 | `ServerSideIsolationTest`（ASM 方法级调用图检查 dedicated server 入口不引用客户端类）与 `verifyVerificationPackaging`（校验 `jar` 与 `shadowJar` 不混入 `verification`/`benchmark` 测试类） | 不证明运行时并发、真实协议吞吐或 Forge 宿主行为 |
| L1 确定性契约单测 | `./gradlew test`（纯单测）与独立任务 `./gradlew serverMockTest`（`ServerHundredPlayerContractTest`），通过内存 `TransportPort` 验证百人并发会话下的背压隔离、gameplay 零阻塞、目录切换、重连/断线清理与权限准入 | 不证明真实 Netty 缓冲水位、MSPT 耗时、Forge 启动或 GPU 渲染 |
| L2 无头规模压测 | `./gradlew serverScaleLoad`（`ServerScaleLoadHarness`），使用真实多线程 `ReloadableModelCatalog`、`ResourceDispatchWorker` 与 Netty `EmbeddedChannel`（`WriteBufferWaterMark` 背压），观测 100/200/300 人并发下的 MSPT 增量、lag spike 与 gameplay 延迟 | 不证明 Forge 客户端 UI、OpenGL/着色器或跨模组联动 |
| L3 真机 Forge 冒烟 | `runServer` + `runClient` 手工与视觉验收（对照[视觉一致性验收](../standards/conformance.md#视觉一致性验收)），覆盖 Forge 频道握手、真实玩家登录/重载/切模/断连及音频渲染不崩溃 | 不替代 L1 边界覆盖或 L2 百人规模压测 |

性能软指标（100 人重载与分发平均 MSPT 增量 < 5 ms、lag spike ≤ 10 ms）与 200/300 人观测尺度由[验证政策](policy.md#性能验证)界定，均由 L2 `serverScaleLoad` 按需采样并可搭配 JFR 分析。

## 构建与进程 ownership

- **日常回归入口**：`./gradlew test` 默认执行 L0 与纯 L1 单元测试，排除带 `@Tag("mock")` 的百人模拟传输契约测试，无需启动子进程或配置额外环境变量（native JNI 边界测试由 `gradle/native.gradle` 自动注入 `YSM_NATIVE_PATH`）。
- **百人 Mock 契约入口**：通过 `.\gradlew.bat serverMockTest` 单独运行 `ServerHundredPlayerContractTest`（含 `@Tag("mock")`），从默认全量单测中拆出并独立守卫百人并发与背压不变量。
- **百人并发负载压测**：通过 `.\gradlew.bat serverScaleLoad -PscalePlayers=100 -PscaleDurationSec=10`（支持 `-PscalePlayers=200` / `300`、`-PscaleSlowRatio=0.1`、`-PscaleReloadIntervalMs=2000`、`-PscaleOutput=<json>`）运行，可直接配合 `-XX:StartFlightRecording` 采样。
- **发布制品隔离**：`verifyVerificationPackaging` 检查正式归档不包含 `com/elfmcys/ysm/verification/`、`com/elfmcys/ysm/benchmark/` 或历史 `mock` 类。
- **进程退出边界**：正常 JVM 退出遵循[进程退出契约](../architecture/runtime/overview.md#jvm-进程退出)，直接放弃运行资源与未提交工作，不要求销毁账本归零或数据库排空；进程内的 owner 边界仍由[运行终结](../architecture/runtime/overview.md#结束与替换)与[资源关闭](../architecture/model-management/client-resources/overview.md#引用撤销与物理关闭)约束。

原多进程 `mockHost` / `MockSupervisor` / `HostSupervisor` / `MolangHostSupervisor` 自动化探针体系已随验证重构移除；既有历史 Forge 记录仅代表当时所运行环境，不再作为当前自动化回归入口。

## 启动模型一次性预热

`StartupModelWarmupTest` 使用受控宿主条件、偏好查询、Catalog 与 lease 验证目标策略、输入先后顺序、单次提交和关闭，但这些断言不证明真实资源准备。`AutomaticPreviewScopeTest` 在 loader 与 factory 层交叉预置动画输入与 AOT 的命中和缺件组合，观察几何复用、MAIN 与 FP_ARM、缺件生成以及暖命中的编译计数，并在阻塞可选持久队列时验证完整 Ready 仍可先行交付（可选提交失败后的结果独立性由既有缓存测试覆盖）。

`ClientModelBootstrapTest` 的启动输入组合使用真实 `ClientInputs`、本地 Catalog 与偏好缓冲，核对远端展示期间仍能收到本地发布、未持久化选择的逻辑读取、投影与权限拒绝，以及普通 FULL player 请求的主体、纹理意图与后台优先级。该 JUnit 夹具控制资源构造与宿主交接，只证明组合与请求契约；真机主菜单预热、GPU 发布与进入世界替换原 Flight 的行为，改由发版前的 L3 `runClient` 手工冒烟确认。上述场景不证明缓存持续驻留、首次渲染零开销、主菜单帧时间收益或其他平台行为；当前通过范围见[支持状态](../status/support-and-verification.md)。

## 输入与证据门禁

OOM 注入沿已有防御的受影响链路执行：`EntityExpressionRuntimeTest` 验证失败后保留已生效动作并清理借用；`EntityModelBindingTest` 经真实生成 provider 与异步 owner 验证失败上达、保留旧安装且不影响后续独立执行；`ModelRenderTargetCacheTest` 覆盖同步与异步失败终态，以及 offline 与 cache-only 入口的 miss 与取消隔离；`ReloadableModelCatalogTest` 覆盖扫描失败、健康内容保留与 owner 接纳。普通异常、取消与 required default 作为对照；`NativeRendererTest` 直接核对 fallback consumer 异常传播。

这些注入只证明上述 owner 的控制与清理边界，不证明全入口、任意分配点或真实堆耗尽后的存活。没有为了注入额外增加生产 wrapper，独立可选 Job、未设防回调和 JNI wrapper 也不因操作类型新增 OOM 防护；读取与像素的既有 borrow/close 套件另行证明正常资源责任。

```powershell
.\gradlew.bat test --tests com.elfmcys.ysm.network.ServerSideIsolationTest
.\gradlew.bat serverMockTest
.\gradlew.bat serverScaleLoad -PscalePlayers=100 -PscaleDurationSec=10
```

## SQLite 存储与偏好

`NativeStorageIntegrationTest`、`SharedStorageProcessTest`、`CacheRootCoordinatorTest`、`CachePrunerTest`、`CacheMaintenanceTest` 与 `PlayerPreferencesTest` 均复用私有 native SQLite 与真实临时目录，运行前需将 `YSM_NATIVE_PATH` 指向当前候选库。独立 JVM 用例一律经生产 typed API 读写，不用 Python SQLite 原语实验替代。

`SharedStorageProcessTest.naturalExitAbandonsBlockedWorkersAndBufferedPreferencesButPreservesCommittedSnapshot` 验证进程直接放弃退出：先提交一份完整偏好，再接纳但不提交新值，让资源调度与数据库共三个 daemon worker 保持阻塞，随后主线程自然返回。父进程断言子进程正常退出，重开数据库后仍能读回完整的已提交旧值。该用例不关闭资源、不排空缓冲，也不调用 `System.exit` 或 `halt`，且不经过 Forge 启动。Windows Forge 下的活动 GUI 加载、切换与正常退出由真实页面宿主另行验证；其他平台以及音频、兼容扩展的退出交错仍需独立证据。

测试分别验证区域、UUID 与角色隔离、完整值、首次填充并发、usage 原子取最大值、顺序缓冲与失败分类。同一存储 owner 的读取反映当下的内存覆盖层，覆盖可见性与持久化落盘分开核对，不外推跨进程最新值或跨查询快照。数据索引 WAL 与清理协调库彼此独立，需重点观察真实文件消费者、清理阻塞与交接、进程退出以及索引写入进展；旧版同 key writer 锁与二进制索引合并用例不再作为当前对照。

业务版本校验分别核对文件 epoch、库内角色与 epoch 及 revision。新建目标必须为空且角色和版本号正确，即使存在旧数据库也不得自动导入记录；遇到错误角色、错误 epoch、低于当前 revision、未知布局或初始化中断残留时，必须明确拒绝并原样保留原文件。测试均使用临时 fixture，不改写真实 DATA。

同 epoch 较新 revision 的 fixture 须包含未知列与未知表，验证当前能力仍可正常读写、未知值原样保留且 revision 不被降级，已打开的连接跨越兼容扩展后的读写也应保持同样保证（不依赖生产升级步骤）。并发首次打开须由独立进程核对完整目标、赢家数据与明确的竞争结果：观察未初始化目标允许返回不兼容，但至少一个创建者应成功完成，后续新进程应能正常重开完整目标。同一新命名空间的多个消费者应共同访问真实的 converted 与 bake 文件，证明共享根级保护且与旧布局隔离；仅检查版本号或单进程正常打开不足以替代这些验收条件。

主动选择恢复测试通过协调器、生产 `PlayerPreferences` 与 JNI 制造真实的数据库失败：主线程下次观察完成时应携带空初值在本地提交并尝试发包，实际安装另行观察；降级 scope 内的运行变化可以上报但不写库，切模后应重新查询；收藏任一页失败返回整集合空，本地选择查询失败采用普通 builtin default；取消和过期结果不得触发降级。受控接缝另覆盖同步发送异常、replacement、迟到 Job、clear 持久化失败及提交、指派与安装交错；人为抛出的合成异常不能替代真实读取失败。取消测试必须分别核对发布资格已撤销与 Job 真正结束，不能用 future 已取消或队列为空来证明 native 工作已停止。

`SharedStorageProcessTest.discoveredRegionsKeepFactsAndRealConsumersAcrossProcesses` 在子 JVM 中联合验证目录探测与生产存储：目录健康时，两个实例共用同一份玩家事实与远端 usage，且实例目录下不生成 player 或 remote 库；当全局路径被预置为普通文件时，子进程整组回退到实例目录，其他实例读不到原事实；运行期间恢复全局目录后，原进程继续读取原区域，不在恢复位置建库。两种布局均用真实 converted 文件验证活动保护、读取、退出与崩溃后的清理以及玩家事实保留。

`ContentStorageRoutingTest` 使用四份不同内容核对用户、实例、builtin 与纯远端来源。远端激活采用同 `ModelId`、不同 `containerId` 的实例替代后，派生缓存仍归属实例 Catalog。测试通过反射调用生产 loader 的 geometry 与 expression 选择器：同键 geometry 预置不同标记并验证暖读，expression 实际冷编译落盘并清内存后读回；Preview 经生产路由写入不同图片并核对同键隔离。期望区域与业务索引由测试预设，这些断言不覆盖冷 geometry 发布或完整渲染。

三类业务门面、JNI 载体与 native 公开声明分别校验封装边界。Java 测试只断言稳定的失败类别，独立 JDBC 仅用于构造故障和外部对照，底层 SQLite 扩展错误码只在 native 私有诊断测试中核对。`NativeStorageIntegrationTest` 覆盖完整值复制、同 owner 复用、adoption 与提交后交付失败；`NativeCancellationIntegrationTest` 覆盖取消借用与 close、Cleaner 的释放顺序。上述测试不证明断电耐久、游戏帧成本或其他平台运行。

`ReloadableModelCatalogTest`、`CacheRootCoordinatorTest` 与容器测试分别覆盖目录扫描、分层准入、UUID 实例反馈及 direct 与 remote 底层存储。冷启动与重启实验须使用独立进程，记录源与缓存读取字节数、转换次数、实际样本规模和时延；仅统计命中数不能证明未遍历 payload，v3 还需独立完成完整转换并重开的规范语料，builtin 闭合则由真实物化入口另行证明。

`DirectContainerReadSample` 复用读取探针记录实际偏移与长度，分别观察冷启动、独立进程重启、同进程 reload 以及 metadata 或 profile 失效后的重建，并将读取区间与普通 payload 边界比对；命令与输入要求见[Direct 容器目录读取](../tools/benchmark/README.md#direct-容器目录读取)。生产交付另由 `verifyPackagedNativeStorage` 直接使用最终 jar 中的打包库验证，入口见[构建指南](../build.md)。

历史 Forge 联合场景（原 `modelManagementMockForge` 的存储扩展分支）曾使用 `ysm/models` 与完整命名 pack 在隔离目录验证以下验收口径；自动探针脚本移除后，新增联调改走 L1 契约单测与 L3 真机冒烟。历史情景只证明当时观察到的连接、reload、资源与关闭边界，不能由其推导出全部 SQLite、UUID 恢复、intent 或配置授权向量都已覆盖；部分旧基线仍沿用旧窗口对照，核对时须先对照[当前玩家状态验证](player-state-network.md)，当前缺口见[支持状态](../status/known-issues/model-management.md#目录存储与安装)。

联合宿主验收应在隔离目录核对版本化存储与主动选择：通过当前 typed API 写入的偏好应可恢复，真实读取故障应携临时空初值继续选择并等待安装，owner 结束后不得再有旧发送，页面关闭后仍保留独立选择，迟到指派仍无条件应用。自动化与既有 Forge 记录不能代替新候选的联合宿主证据，发布包与其他平台另按实际制品判断。

偏好验收使用按场景隔离的 `YSM_HOME` 与真实玩家 UUID，保留实际状态报文、持久库和新连接结果；配置验收通过写入实际 TOML 后触发 Forge reload，但受控投递不证明 OS watcher 自动通知。资产资格必须通过当前连接的新 metadata 与 chunk 请求响应验证，不能用本地缓存命中或 `canRequest` 谓词替代；`model set` 命令的聚合返回值也不证明每个目标都被接纳（当前命令在目标被拒后仍可能返回 1），授权验证须直接比对实际 selection、intent 与 grant。

历史上按环境变量拆分的存储验收关注点如下（供 L3 手工/视觉核对参考）：

| 关注面 | 观察边界 |
|---|---|
| 核心会话与状态（`core`） | 生产者与观察者、self 状态、迟到回调、清除后旧报文与旧命令、非法初始 roaming 与真实读取失败 |
| 本地与默认双角色（`local` / `tail`） | Local M/T、内容缺席与恢复、同 UUID 新实例与跨世界恢复、显式默认双角色 |
| 策略与授权（`policy`） | 实际 TOML 接纳、分类、grant 与 revoke、metadata 与 chunk 响应 |
| 偏好恢复（`selection`） | 当前库偏好主动恢复、真实坏 `00` payload 的空初值降级、scope 禁止持久化与清除重选（落盘结果须用独立 SQLite 读取器核对） |
| 命令闭环（`commands`） | 集成服实际 set、export 与 grant、客户端 roaming clear、完整/相对/id 选择器、歧义、缺失与补全 |

### 短路径缓存与两池维护

缓存布局、来源覆盖与消费者保护的验收标准见[共享存储与清理](../architecture/model-management/storage/overview.md#共享存储与清理)。`FlatCacheLayoutTest` 核对四池、16 位 ID、新旧命名空间隔离与完整持久身份；`CachePrunerTest` 使用真实数据库与文件，覆盖来源组合、全部 bake 分区、ABI 与 schema 失效原因、本地保护、旧 token 与删除失败。

`AnimationCacheIndependenceTest` 以真实 JNI 与独立 JVM 验证动画输入与 AOT 分文件命中：删除其一仅重建该类，单独取得输入不触发 AOT 生成，暖 FULL 不进入编译；同时核对错误来源的组合拒绝以及撤根后的活动使用。`AnimationInputStoreTest` 覆盖未消费坏函数、来源与选项身份、并发冷未命中、取消及独立提交失败；`BakedAssetWriterTest` 核对程序块按需校验与旧合并 schema 拒读。上述入口不证明 GUI 已切换解释执行或真实渲染收益。

`SharedStorageProcessTest.bothPoolsKeepAdmissionClosedThroughCompletionFailureAndCrash` 分别在 converted 和 bake 的实际删除前暂停，通过六个子进程场景核对同根新消费者等待、独立根正常接纳、成功、异常与崩溃后的交接，以及日期和持久 `all` 请求的重启恢复。`CacheMaintenanceTest` 另覆盖 72 小时周期边界、时钟回拨、启动 revision、命令与条件完成事务；单库事务成功不能代替整轮维护成功。

`deepGameDirectoryRunsNativeColdWarmAndTwoPoolMaintenance` 在 Windows 长度为 185 字符的 `gameDir` 下完成真实 raw 转换、重启暖复用、bake staging 创建、两池删除与日期写回（新 staging 最长 218 字符，关闭 staging 后最长 216 字符）。它覆盖了旧层级路径过长导致的失败条件，但不证明任意长度根目录或所有 Windows provider 均可用。

`SqliteProcess` 将在线 SQL 修改和持锁注入放在独立 JVM 中运行，避免 JDBC 与 JNI 的两份 SQLite 在同一进程内绕过 [POSIX 锁协调](https://www.sqlite.org/howtocorrupt.html)。

跨平台验证可复用 `catalogLinksVerificationJar`，通过 `YSM_VERIFICATION_TESTS` 选择上述存储测试。临时夹具应放在目标原生文件系统上，并分别记录 JVM、native 库散列、逐项运行结果和环境跳过项；交叉编译、jar 内四库散列相符与平台锁时序应各自独立判断。

`CatalogSourceBenchmark` 分别测量 directory、ZIP 与 7z 的冷、暖物化耗时。`CatalogSourceObservation` 配合 `readHandleAgent` 独立统计调用次数、读取字节数与 capture 保留量，不参与时延测量。`CacheMaintenanceObservation` 报告有限索引样本的两池排他窗口（含实际文件删除和日期提交），不承诺一般时限。

## Catalog 链接与显式 reload

`catalogLinksVerificationJar` 将生产类、受控测试及 Windows、Linux 运行依赖打包为 `build/libs/catalog-links-verification.jar`，复用来源发现、raw、zip 与 legacy 转换、完整容器读取、Catalog、索引、pack 与 service reload 测试。该 JAR 不启动 Forge，`verifyVerificationPackaging` 同时核验测试与基准类未混入正式发布包。

```text
./gradlew catalogLinksVerificationJar verifyVerificationPackaging
java -jar catalog-links-verification.jar <fixtures> <evidence> windows
<linux-java17> -jar catalog-links-verification.jar <fixtures> <evidence> linux-ext4
<linux-java17> -jar catalog-links-verification.jar <fixtures> <evidence> linux-drvfs <windows-junction-root>
```

运行前需将 `YSM_NATIVE_PATH` 指向对应平台的 JNI 库，并每次指定全新的 fixture 目录；入口会在该目录解包运行依赖，并将测试临时目录置于同一文件系统上。证据目录保存 JAR 与 native 库 SHA-256、Java 与 provider 属性及逐项测试结果。

缓存写入验证以“单次操作期间目录结构稳定”为前提，通过受控门禁在下一次复核前改目录或插入内部链接，观察是否拒绝且来源字节与目录清单不变，不把检查与路径操作之间的外部替换竞态当作通过判据。当 provider 缺少 `fileKey` 时，健康提交、失败保旧与完整内容校验仍须成立，但同路径同类型替换可能不可见，不列为必然拒绝的判据。当前双 JVM 用例通过生产 SQLite 协调与 typed 存储竞争，覆盖独立完整文件发布、活跃消费者与异常退出；历史 JDK 或 `flock` writer 锁结果不再作为当前互斥对照。

`YSM_VERIFICATION_TESTS` 支持以逗号分隔指定 `com.elfmcys.ysm` 下的 suite 后缀，未设置时运行默认链接集合。缓存路径与共享存储回归选用 `model.storage.DirectoryAccessTest,model.storage.AtomicSharedCacheTest,model.storage.SharedStorageProcessTest,model.storage.CacheRootCoordinatorTest`，名称段发布回归选用 `model.catalog.ReloadableModelCatalogTest`。Linux 验证须在发行版原生文件系统上进行，不能用 `/mnt/c` 替代 Unix 单段反斜杠验证；实际检查时应核对该专用方法真正执行通过，不能只看整个 suite 通过或 Windows 条件跳过。

Legacy 链接用例需配置 `YSM_LEGACY_LINK_FIXTURE`、`YSM_LEGACY_LINK_MODEL_ID` 与 `YSM_LEGACY_LINK_SHA256`，语料应是已独立转换并完整重开的有效 v3 文件；未配置时跳过，不得计作 legacy 加载通过。其余 raw、zip、direct 与图片输入来自 JAR 内置的有效资源。

跨宿主用例要求先在 Windows 侧创建根 junction，并在其目标目录内创建指向隔离空目录的 `nested-junction`。Linux JVM 通过 DrvFS 读取这两个真实入口，先验证当前 `models` 创建策略与完整发布，再验证 raw 内部 junction 被拒绝及移除后的显式恢复（历史 CUSTOM 与 AUTH 结果仅代表原实现）。测试会主动删除内部 junction，其余来源读取保持只读。若受控用例的临时目录位于 ext4，则只能把上述指定 junction 的读取记作 DrvFS 证据。

拒绝访问用例须先确认实际目录读取确实失败。Linux 以 root 身份运行时，应移除 `CAP_DAC_OVERRIDE` 与 `CAP_DAC_READ_SEARCH` 或改用受限身份，仅靠 `chmod` 不构成有效证据。Windows 强制文件锁与 Windows JVM 专属 junction 测试在 Linux 上不适用，结果应单列。

四层证据须分别判断：发现与逻辑路径、校验与转换、Catalog 发布以及运行资源。JAR 中的已验证字节资源与 service preview lease 只证明各自边界；真实初始化、连接、显式 reload、远端权威、断连、dedicated 与 integrated 服务及真实 GPU/像素差异由 L3 `runServer` / `runClient` 手工与视觉验收覆盖。

## 生成预览制品

`PreviewHostArtifactTest` 用于独立重开真实宿主导出的容器。运行前将 `YSM_PREVIEW_HOST_ARTIFACT` 设为导出 `.mxc` 文件的绝对路径、将 `YSM_PREVIEW_HOST_SOURCE` 设为对应原始模型目录的绝对路径，再执行：

```powershell
.\gradlew.bat test --tests '*PreviewHostArtifactTest'
```

测试核对模型身份、完整源块、`GENERATED` 标记，以及图像解码后的 156×270 尺寸与 RGBA 字节数。可选环境变量 `YSM_PREVIEW_HOST_IMAGE` 可指向收到的生成预览缓存文件以另行校验解码结果。未配置制品路径时测试跳过，因此普通单测通过不代表真实宿主导出已通过。

## 有界文件读取

`verifyReadHandles` 在现有来源测试上包装真实的 Java 只读 channel 与 stream，记录绝对路径、调用栈、读取字节数、物理关闭及退出时未关闭项。覆盖范围包括用户原件、converted、remote、派生动画与几何、图片以及 raw 文件；writer、staging 验证与锁保持原 owner。

```powershell
.\gradlew.bat verifyReadHandles -PreadHandleOutput=<absolute-json-path>
```

各专项测试边界如下：`ChannelReadPhaseTest` 核对并发区间读取、借用方无物理关闭权、封口后拒绝新借用、最后归还时物理关闭、失败与取消以及打开后构造失败；`FileChunkBatchTest` 核对同阶段复用、损坏或截断、旧期望与关闭后的原子替换；`AnimationSetArtifactTest` 核对磁盘动画延迟读取、共享结果、普通失败后的同文件回退、健康动画不受影响及后续独立重开（失败阶段须先关闭再开新阶段，健康动画不继承封口失败）；`AnimationStoreTest` 核对关闭失败、取消与 OOM 不进入回退恢复；`RuntimeContentStoreTest` 核对精确包装保留批次且不扩大内容失败影响范围。

`FullAnimationReadProbe` 在独立 JVM 中额外记录实际 controller discovery 的文件句柄：`read` 模式直接在上一进程持久化的 FULL 文件上做暖加载且不删除 set 文件，校验成功批次中同一文件只打开一次、loader 返回时无空闲读取句柄，并要求非空动画读取。全常驻路径可能不产生磁盘动画读取，不能把“零打开”冒充为磁盘路径覆盖，打开次数本身也不证明固定加速比例。

Java 探针不观察 native archive 内部 I/O，包装测试只证明 Java 借用终点。Windows 缺少 `fileKey` 时的目录替换用例允许跳过，但证据须保留逐项结果。Forge 特有 provider、其他文件系统及真实页面仍需分别验证。
