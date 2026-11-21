# 模型管理已知问题

正常职责见[模型管理架构](../../architecture/model-management/overview.md#实现与验证入口)。以下按主题区分当前缺口、已覆盖范围与证据边界。

## 调度与平台

- **本地玩家资源隔离队列**：在固定两个活动 worker、常驻 FULL 的受控 JMH 中，隔离队列缩短了排队等待时间，代价是后台 CPU 工作的吞吐约减半；实验未证明调整线程优先级还能带来额外收益。功能回归已覆盖共享工作提权、准入、发布、取消和 required 启动初始化。当前样本只反映当前代码，不能代表历史对照或尾延迟；真实实体安装、GPU、帧时间与低核负载尚未验证。方法见[调度基准](../../tools/benchmark/README.md#本地玩家资源优先调度)。
- **OS 线程策略**：四组固定负载下的帧时间、声音补给以及 GC 与 safepoint 收益尚未验证。Windows 真实 JNI 已覆盖两种 JVM 优先级策略、属性读回与替补 worker，native 故障注入覆盖部分应用与回滚；跨进程 gate 取消，以及隔离 Forge 的启动、重连和退出已有证据。Linux 与 Android 仅确认 JNI 库与 native tests 编译链接通过；macOS 当前 native 候选已完成完整编译链接，但运行时尚未验证。职责见[线程策略](../../architecture/runtime/implementation/execution.md#线程策略)。

## 展示与需求准备

- **GUI 解释路径与用途页面**：旧 GUI AOT 的两模型六缓存组合验证只证明当时的用途分区（FULL MAIN、FP_ARM、Ready 不替代 GUI，cache-only 未命中不触发编译，以及实际文件读取），不能用于证明当前的绑定求值树解释路径。新路径已有 factory 双路径先后与并发、cache-only 本地绑定、独立失败与取消以及活动驻留与退休的受控证据，但真实 GUI 下的完整缓存、到期与重取仍有缺口。旧矩阵已归档，当前方法见[真实宿主](../../tools/benchmark/README.md#模型列表真实宿主)；稳定位置候选的完整单表达式复验仍有失败与无结论，按门控尚未进入真实 GUI 配对，可选状态真值缺口已补齐，见[动画问题](animation.md#gui-与模型冷准备)。Windows 隔离 Forge 页面另已验证：模型缺失、选择等待、真实 Busy、纹理不匹配以及接收后撤权五种情况均不开页、不准备、不发送，且无迟到导航。合法 FULL 入口已有非 GUI 循环动画的连续可见帧、native 成功输出、私有变量隔离、GUI 候选、换纹理以及等待与 Matching 名称截图，但该夹具不代表全部模型、shader 或 GPU 外观；方法见[页面探针](../../verification/model-management.md#构建与进程-ownership)。
- **分级准入文件读取**：三来源四阶段的文件读取验证已通过，受控接线与证据限制见[文件读取验证](../../tools/benchmark/README.md#分级准入文件读取验证)。
- **当前展示配置与改造前记录**：当前展示配置已覆盖 Windows LOCAL 十卡的暖 Ready、冷本地、缺图开关、输入切换、窗口 resize 与玩家安装反馈；ACTIVE 模式下的远端缓存、撤权、表示替换、迟到结果与纹理入口门禁也有宿主证据，但短窗口不能证明 GPU 成本或长期内存收益，见[展示实现](../../architecture/client-presentation/implementation/page-runtime.md)。展示配置改造前的 LOCAL 与 ACTIVE 观察只证明当时的 FULL 与 GPU、保旧、热点击、有限等待、迟到权威和远端玩家安装，不能证明当前的卡片资格、交互准备或仅限 Ready 的导航规则。临时图片或 FULL 接纳拒绝后的恢复与不重发已有实机证据；确定性内容不重试仍只由受控 owner 回归证明，完整卡片名称终态的像素也未获得清晰证据。
- **发布阶段取消与长期成本**：发布各阶段的自然取消、Busy 帧成本、冷资源期限分布、长期 RSS 与显存（VRAM）占用以及物理回收仍未独立审查。常驻默认模型的 Ready、交接与同目标并发已有 B0 与候选的等价 JMH，但误差区间重叠，不声明准备提速。固定样本的真实 GUI 与生命周期证据不代表普遍性能或全部远端几何与动画组合。方法见[需求准备](../../tools/benchmark/README.md#gui-与-full-需求准备)。

## 目录、存储与安装

- **短路径命名空间与根级保护**：Catalog、PlayerData、Remote、Bake 与 allocator 各自持有独立的强类型能力。新缓存位于 `CACHE/f`，四个资源池使用 16 位短文件 ID；新旧布局互相隔离，旧文件保留在原位。根级接纳与消费者协调负责保护 converted 与 bake 的完整消费寿命；其他业务库使用 WAL，Bake 库使用 DELETE journal 以支持 cache-only 零写入。玩家偏好沿用既有 epoch。网络共享文件系统及未列出的 provider 尚未验证。方法见[当前存储验证](../../verification/model-management.md#短路径缓存与两池维护)。
- **存储版本与并发初始化**：新缓存私有库从 epoch 1、revision 1 空建，不迁移旧缓存；玩家偏好维持 epoch 2、revision 1。同 epoch 下的较新 revision 只开放已知能力，保留未知列、表及值，且绝不降低 revision。首次观察未初始化目标时允许明确报错不兼容，原位初始化不保证并发双方同时成功。
- **版本化存储测试与历史回归边界**：当前 Windows DLL 已通过 22 项 native 存储测试、46 项 Java 与 JNI 测试及 1 个初始化提交异常退出场景，覆盖当前版本校验、旧文件忽略、兼容扩展、并发首次打开与后续重开、偏好读写和查询降级（证据见 `build/verification/storage-version-metadata-tests/summary.json`）。主动选择 owner、scope 禁止持久化、切模重试与初值资格交接仍沿用数据库简化前的 140 项回归证据，本次未重跑这些未改动用例。
- **选择宿主分支待复跑**：`selection` 宿主分支已改为通过当前 typed API 写入对照值 41，但尚未复跑 Forge。此前 54 项观察基于历史数据库导入夹具，仅代表当时的查询降级、scope 禁止持久化、切模恢复和清除重选，不作为当前候选的通过证据（历史结果在 `build/verification/preference-read-fallback-tests/forge/verdict.json`，当前方法见[选择宿主分支](../../verification/model-management.md#sqlite-存储与偏好)）。
- **UUID 偏好与覆盖层**：UUID 偏好、LocalPlayer 生产者、完整普通 `ModelId`、内置默认独立角色和本地 scope 已有领域与 JNI 证据；跨字典重编号与 SQLite donor 的组合见[玩家状态网络验证](../../verification/player-state-network.md)。历史 Forge 的 self FULL 与 DELTA、旧窗口与拒绝后写入记录不能证明当前 wire，旧协议下的后续实机条目均受此限制。同一偏好 owner 的读取包含当时内存覆盖层，覆盖可见性与 SQLite 落盘分别观察；允许未提交的近期值丢失，不保证跨查询快照或多进程读取到最新 donor。
- **主动选择、清除与旧协议拒绝**：真实主动选择已验证非法初始 roaming 拒绝且保留旧选择，服务端指派下的 CONTENT 恢复失败同样保留选择。`core` 宿主分支尚未按临时空基线的新对照复跑，scope 降级的既有证据限于上述历史会话测试与主动选择观察。成功在线 clear 后，旧恢复回调与旧窗口报告均不能复活已清空的运行时。当前 Windows Forge 还覆盖了暂停真实旧动画 worker 后请求 clear 的场景：原 join 终结 worker，新 scope 拒绝旧生产者，runtime 与逻辑读均为空，后续 Molang 新值正常提交并由独立 SQLite 读取器核对。旧特权任意源码消息 ID 21 也已经由真实 Forge 通道投递并被拒绝；但这些固定故障不代表所有 I/O 或并发窗口。
- **多实例与本地恢复**：Local M/T 暂缺恢复、同 UUID 新实例、离线 UUID 隔离及默认双角色已有实际断言。显式内置默认与普通 builtin 选择已分别持久化并由新 JVM 恢复；无记录且普通 builtin 未就绪时只采用临时内置默认，独立数据库中无 selection 或 roaming 写入。同 UUID 新进程已在另一真实世界恢复 M/T，两个世界的种子均由独立 NBT 读取器核对。真实在线登录、全部首次继承与存储失败组合仍未运行。
- **命令与配置授权**：集成服的实际 set、export、grant 以及客户端 roaming clear 已核对完整路径、无歧义相对路径和完整 ID 指向同一目标；纹理补全、跨区域歧义、非法 ID 不回退、缺失 ID、历史 clear 及旧 auth 与 reload 参数拒绝均有实际结果，导出文件身份与 clear 数据由独立读取器复核，但固定输入不代表全部路径和权限组合。配置授权已通过真实 Forge reload 链、单模型强选、grant 与 revoke、新 metadata 与 chunk 请求以及非法原文保留验证：撤权增量可被客户端接纳，新的受限 chunk 请求返回 `UNAUTHORIZED`，清空限制后恢复 `PUBLIC`。但完整权限矩阵、已接纳传输的保留、配置与目录扫描交错以及 OS watcher 自动投递尚未验证。
- **Direct 与 v3 读取样本**：Windows 独立 JVM 已按实际区间验证 Direct 容器冷启动、重启和 reload 时的目录必需读取；完整 v3 的冷启动、重启、footer 提示变化以及缓存产物缺失后重建也已通过。该采样不代表全部历史语料、Forge 联合场景或普遍性能收益。方法见[当前存储验证](../../verification/model-management.md#sqlite-存储与偏好)。
- **两池维护窗口与源物化基准**：当前 Windows NTFS 有限维护样本包含 2048 条 converted、2048 条 bake 与 2048 条来源行，实际文件删除与日期提交的连续排他窗口为 39.26 s，期间同根新消费者会等待；该结果不承诺一般时限。固定 builtin 内容的 JMH 中，directory、ZIP、7z 的暖物化 p50 分别为 0.78 ms、0.82 ms、0.90 ms（对照基线为 14.8 ms、124.3 ms、117.8 ms），冷捕获保留字节从 338197 bytes 降为 0，完整解析次数从 2 次降为 1 次。该样本不证明游戏帧时间或普遍端到端收益；方法见[短路径缓存验证](../../verification/model-management.md#短路径缓存与两池维护)。
- **受检路径与深目录边界**：受检路径仍依赖[操作期目录结构稳定](../../product-decisions/decisions/artifact-storage.md#bccache-maintenance-does-not-own-sources)。由于 Windows JDK provider 缺少目录 `fileKey`，同路径同类型替换可能不可见；健康层级、链接边界和失败保旧测试继续适用，但不承诺能检测所有外部替换。新布局已在 Windows 长度为 185 字符的 `gameDir` 下完成真实 raw 冷转换、重启暖复用、bake staging、两池删除与日期提交（staging 路径最长 218 字符，关闭 staging 后文件最长 216 字符）。旧层级根判定失败的 284 与 300 字符样本不再代表新缓存布局，但任意超长根目录、所有 provider 与各类启动器仍未验证。
- **多平台 JNI 与 WSL 存储测试**：当前缓存候选的 Windows x64、Linux x64、Android arm64 与 macOS arm64 JNI 均已编译链接，最终 jar 内的四库配套已核对。WSL2 ext4（OpenJDK 17.0.15）上的存储组合测试共 98 项，97 项通过、1 项 Windows 专用 payload 独占锁用例条件跳过，覆盖真实进程、两池维护与 typed JNI（该次运行使用 root 身份）。macOS 运行时与 `NativeLibUtil` 加载尚未接通；最低 Windows 版本、CPU 指令集以及 Android 第二身份运行时仍按[平台需求](../../product-decisions/requirements/req-platform-availability.md)单独判断。
- **根级连续保护与多进程清理**：converted 与 bake 池已由真实进程在删除前暂停，验证了成功、异常与崩溃后的同根等待、独立根接纳、重启恢复以及持久 `all` 请求；逐根 UTC 72 小时周期、命令 revision 与日期条件提交另有真实数据库回归。但更大规模目录、完整 Forge 多进程生命周期、长期资源与图片回收以及 reload 组合尚未验证。
- **链接来源与 DrvFS 历史限制**：链接来源的发现与逻辑位置、完整校验与转换、Catalog、索引、pack 发布和已校验字节读取已覆盖 Windows Java 17 与 WSL 原生 Linux 文件系统。Linux JVM 还曾通过 DrvFS 读取 Windows 创建的根 junction，覆盖当时的 CUSTOM 与 AUTH 两种创建策略及完整发布；由于当前来源已统一为 `models`，该历史结果不证明新配置授权、raw 内部 junction 拒绝与显式恢复。其余受控临时目录位于 Linux 原生文件系统，不能把整套测试记作 DrvFS 覆盖。方法见[链接验证](../../verification/model-management.md#catalog-链接与显式-reload)。
- **Forge 扫描、远端下载与独立图缓存**：Windows Forge 已覆盖显式扫描、远端权威、断连恢复最新本地目录、dedicated 服务重建与 integrated 单次触发（当时的正常关闭证据不覆盖现行[JVM 直接退出](../../architecture/runtime/overview.md#jvm-进程退出)策略）。来源替换后，两个独立 FULL lease 的 GPU 像素彼此独立，关闭其一不影响另一个，但该证据不覆盖 Linux GPU、其他显卡、长期保留或物理回收。远端 catalog、资产下载与 preview 缓存已有缺图读取、管理补图、图片复用、通知、重连与专服重启证据，受限目录补图与普通取得拒绝也曾分别观察，但当前配置授权与强选无绕过的双端完整矩阵仍需重验。Pack cover 已有双端下载、解码、render 线程纹理发布及释放证据。完整 v3、丢失通知、迟到结果、共享 target 需求、在线 delta 与动画资产仍缺少联合实机证据。
- **独立图字节保留与 reload 刷新**：普通独立图已接入服务端共享字节保留、持久成功或 reload 失效以及客户端媒体缺图归约，职责见[存储](../../architecture/model-management/server-resources/overview.md)与[传输](../../architecture/network/implementation/asset-transfer.md#receiver-local-assembly-与-publication)。共享保留与失效、持久写入、传输 lease、客户端坏媒体与迟到缺图已有受控回归；实际 service reload 已验证目录未变时刷新外部替换的图片、旧 lease 仍可读且失败保留缓存。Windows Forge 另用固定编码字节验证了成功 service reload 会撤销保留并读取新字节、旧 lease 保持不变（但这不证明新媒体已解码）。Windows 回归覆盖 root 与 preview 目录的 junction 拒绝，热缺图回归覆盖交付期间通知撤销旧 token。真实双端坏图与替换交错、长期保留及性能收益尚未验证。
- **实体安装恢复与 Cleaner 回收**：实体安装的 Pending、迟到、未命中、I/O 错误、拒绝、失败记忆与显式 reload 恢复已有受控验证，但准入与实体表现、迟到传输以及游戏连接保留仍缺联合实机验收。恢复规则见[安装失败记忆](../../architecture/animation/entity-lifecycle.md#失败记忆与恢复)与[资源取得失败](../../architecture/model-management/client-resources/overview.md#取得失败与纹理回退)。此外，Cleaner 不保证回收时限；低内存、进程内退休和宿主设施销毁仍需验证 render 线程销毁与 native 或 buffer 回收不会触发双重释放（JVM 退出不要求模型运行资源完成清理）。

## 模型加载与图片交接

- **eager 回调与批量关闭**：eager 完成回调与 batch close 已共用同一套交付裁决。受控回归验证了回调等待关闭裁决、关闭先胜出时释放过期图片、独立像素保留以及同步回调重入关闭，原实现已在裁决顺序断言下复现失败。测试未直接冻结原 `closed.get` 与结果完成之间的漏释放窗口，也不证明真实 GL 与全部页面退出交错；责任见[页面结果所有权](../../architecture/client-presentation/overview.md#运行作用域与所有权)。
- **`DerivedStorageWork` 首次接纳**：首次接纳已把 worker 构造与启动纳入同一边界，失败时恢复未接纳状态。构造 OOM、启动 OOM 与启动普通异常注入验证了零残留工作、原异常向上传播、等待与关闭可正常返回以及后续独立提交成功，已接纳任务的取消与提交终态回归也保持通过。但实际操作系统线程资源耗尽尚未测试；责任见[存储工作寿命](../../architecture/model-management/storage/overview.md#精确缓存与持久工作)。
- **展示用独立图缓存**：独立 preview、前景图、背景图与作者头像已改用资源侧内容证明和驻留引用计数。受控回归覆盖了热命中零重复工作、多消费者、替换、reload、成员或连接失效及失败收敛；但真实宿主中的全部来源、注册存活、替换与 reload 及世界退出交错和长期回收尚未联合验收，[宿主入口](../../tools/benchmark/README.md#模型列表真实宿主)的分页计数不能单独关闭这些缺口。
- **移除客户端非音频字节额度**：客户端的表达式、派生写入与 preview 字节额度政策已删除。定向回归覆盖了 16 项 LRU 与写入饱和、513 MiB 写入输入、192 MiB 实际像素、取消与最后借用释放。Winefox 01 的完整 MAIN 超过旧计价阈值仍可内存复用并提交；新 JVM 以独立预期重开时，439 个动画与 artifact 内容一致且未触发编译。音频活动与保留额度以及服务端 chunk 与 preview 额度在相同输入的前后版本、同 JVM owner 对照中保持不变，但该对照不代表真实 integrated 游戏。方法见[保留与交接验证](../../tools/benchmark/README.md#证据半径)。新政策下的真实 GL 大图、integrated 游戏联合场景与长期 RSS 尚未验证。
- **派生工作域与非阻塞安装**：派生工作域的真实 staging 写入、校验、flush、原子替换故障、容量拒绝、双进程竞争与异常退出重启已有回归。GUI、FULL 以及非玩家载具（boat）在表达式动画或几何 staging 阻塞期间仍能完成实际安装。发生 local 到 global 的区域切换后，旧工作仍提交到原区域，Ready 与 cache-only 复用不会启动新生产者。页面或世界退出、宿主阻塞时关闭 strict export 以及放行后无迟到提交已有 Windows Forge 观察，但不覆盖全部网络远端故障组合。
- **冷编译去重与音频首块**：原始冷编译对象的重复产物校验计数为零，公开构造、恢复与环境反例仍走对应检查。几何的普通或 cache-only 命中只读取并校验本次消费的数据。声音的批量读取、不同媒体约束、独立 target 与播放以及取消已有真实 codec 与 channel 计数。真实 GUI 悬停（hover）已触发 `MinecraftAudioHost` 接纳并消费 PCM，页面关闭后播放仍持续；但不覆盖全部音频设备、听觉效果及完整动画与声音矩阵。
- **预览图交接与加载反馈**：预览媒体资格、staging 副本一致性、最终编码像素与上传借用已有回归。真实 GL 延迟上传、页面与世界退出、注册拒绝、借用内失败和独立 reload 已验证像素回读一致及最终释放；客户端像素字节额度虽已删除，但保留了原操作数量与串行 reload 门禁（旧额度样本不能证明当前保留峰值或 RSS 回落）。eager 无图卡片在实时准备期间保持加载反馈，真实逐帧未出现有效展示下的中间问号。内置默认 MAIN 与 arm 的主体均为 `LivingEntity`；真实页面手动动画浏览、非默认纹理以及第一与第三人称画面已有固定样本。Detached 默认 body 与必要 PBR 的实际构造和 GL 上传已验证，但不裁决 shader pack 的最终材质效果或全部动画与联动视觉。
- **固定内容对照结论**：固定内容对照已覆盖 GUI 单卡与第一页、FULL 以及换纹理在 `eager=true` 与 `eager=false` 下的表现，并记录了暖几何与图片、进程工作集及主循环分布。但第一页 `eager=false` 的首帧收益未复现，Private Bytes 增量与帧耗时 P99 在两组配对中均上升，且缺少完整图片 blit 成本。整体性能收益和主循环尾延迟尚未通过当前验收，局部去重改善不能抵消这些风险。

## 导出与管理补图

- `/ysm export` 与 `/ysm export-all` 的环境与权限门禁、单项与批量完整导出已有实机证据，导出的成品也已独立重开校验。但作者图、混合批次、各阶段文件或宿主故障、活动世界停止、长期缓存以及最坏合法图片与 target 成本尚未验证。职责见[转换与导出](../../architecture/asset-pipeline/implementation/conversion-and-export.md#preview-取得与显式-export)。
- 执行管理补图时，builtin `wine_fox/18_wedding` 报告 `Expression compiler is unavailable`，其余项目继续完成；该模型的表达式接纳与编译可用性尚未闭环。单份 raw fixture 的图片命中、Ready 与冷路径以及玩法内送达，不能证明大目录、双管理员并发、帧时间或操作保留峰值达标。

## 外部音频后端的取消交接

Runtime 已将外部取消拆分为入口封口、通知后端取消和待交付读取收尾三步，已交付的 chunk 由后端在最后一次借用结束后关闭。结果接纳与封口共用同一裁决顺序，通知在门禁外发出，后端终态覆盖已接纳请求和迟到结果的收尾。

28 项 runtime 回归与 8 项读取回归全部通过、无跳过。新增回归穿过真实 runtime，覆盖待交付读取的失败回调等待取消信号时的 author stop、runtime close、接纳后发现 runtime 已关闭以及 cancel 抛错；修复前，前两项均因取消完成超时而失败。既有回归覆盖真实 chunk 跨停止与替换保持可读、成功交付回调不占用封口门禁、未交付取消、重入、迟到终态以及 restart。上述证据只针对已确认的取消等待环，不保证任意第三方回调立即返回，也未观测 Cleaner 物理释放、复现 UAF 或验证真实第三方后端与音频设备。责任见[后端能力与播放终态](../../architecture/model-management/audio/overview.md#后端接管与播放终态)。
