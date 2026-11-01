# 模型管理已知问题

正常职责见[模型管理架构](../../architecture/model-management/overview.md#实现与验证入口)。以下区分当前缺口、已覆盖范围与证据限制。

## 调度与平台

- 本地玩家资源隔离队列在固定两个活动 worker、驻留 FULL 的受控 JMH 中缩短排队等待，代价是后台 CPU 工作吞吐约减半；未证明线程优先级还有额外收益。功能回归覆盖共享工作提权、准入、发布、取消和 required bootstrap。样本使用当前代码，不能代表历史对照或尾延迟；真实实体安装、GPU、帧时间和低核负载仍未验。方法见[调度基准](../../tools/benchmark/README.md#本地玩家资源优先调度)。
- OS 线程策略的四组固定负载、帧时间、声音补给及 GC/safepoint 收益未验证。Windows 真实 JNI 覆盖两种 JVM priority policy、属性读回、替补 worker，native 注入覆盖部分应用与 rollback；跨进程 gate 取消和隔离 Forge 启动、重连、退出已有证据。Linux/Android 仅 JNI 库与 native tests 编译链接通过；macOS 仅新增调度文件编译通过，完整构建仍受依赖与链接工具链阻塞。职责见[线程策略](../../architecture/runtime/implementation/execution.md#线程策略)。

## 展示与需求准备

- GUI 与 FULL 用途隔离已有两模型六缓存组合的独立 JVM 验证：GUI warm 只从自身分区恢复，FULL MAIN／FP_ARM／Ready 不替代 GUI，cache-only miss 不编译。输入闭包、实际文件读取与同目标独立准备已有定向证据；方法见[用途缓存矩阵](../../tools/benchmark/README.md#gui-用途缓存矩阵)。该矩阵不包含 GPU、页面或性能收益。Windows 隔离 Forge 页面另验证缺失、选择等待、真实 Busy、纹理不匹配和接收后撤权均不开页、不准备、不发送，且无迟到导航。合法 FULL 入口已有非 GUI 循环动画的连续可见帧、native 成功输出、私有变量隔离、GUI 候选、换纹理和等待／Matching 名称截图。该夹具不代表全部模型、shader 或 GPU 外观；方法见[页面探针](../../verification/model-management.md#构建与进程-ownership)。

- 分级准入的三来源四阶段文件读取验证已通过；受控接线与证据限制见[文件读取验证](../../tools/benchmark/README.md#分级准入文件读取验证)。
- 当前展示配置已覆盖 Windows LOCAL 十卡的暖 Ready、冷本地、缺图开关、输入切换、resize 和玩家安装反馈；ACTIVE 远端缓存、撤权、表示替换、迟到结果及纹理入口门禁有宿主证据。短窗口不能证明 GPU 成本或长期内存收益，见[展示实现](../../architecture/client-presentation/implementation/page-runtime.md)。
- 展示配置改造前的 LOCAL/ACTIVE 观察只证明当时的 FULL/GPU、保旧、热点击、有限等待、迟到 authority 和远端玩家安装，不能证明当前卡片资格、交互准备或 Ready-only 导航。临时图片/FULL admission 拒绝后的恢复与不重发已有实机证据；确定性 content no-retry 仍只由受控 owner 回归证明，完整卡片名称终态像素未获清晰证据。
- 发布各阶段的自然取消、Busy 帧成本、冷资源期限分布、长期 RSS/VRAM 与物理回收仍未独立审查。Resident default 的冷/热派生物、Ready、同目标独立准备、并发与退出仅有有界 JMH；GPU/接收使用 headless 接缝，真实远端几何/动画并发也只是有限样本。无旧实现对照，不声明收益。方法见[需求准备](../../tools/benchmark/README.md#gui-与-full-需求准备)。

## 目录、存储与安装

- Java 标准库受检路径与 JDK 文件锁的迁移回归已覆盖 Windows 和 WSL 原生 ext4，包括正向提交、观察拒绝、双 JVM writer、索引合并及活跃消费者保护。当前 Windows JDK provider 不提供目录 `fileKey`，已验证健康提交与验证失败保旧，同路径替换拒绝用例按前提跳过；其他平台或属性前提不适用的用例单列。存储仍依赖[操作期目录结构稳定](../../product-decisions/decisions/artifact-storage.md#bccache-maintenance-does-not-own-sources)，不保证发现无 `fileKey` 时的同路径同类型替换。Unix 非法单段与健康层级的 Catalog/pack 发布已有独立回归。方法见[缓存与名称段验证](../../verification/model-management.md#catalog-链接与显式-reload)。
- Linux 私有 bind mount 验证了父目录别名下的同线程锁重入、取消别名等待者后跨 JVM 互斥仍有效，以及关闭一个消费者后另一消费者仍阻止清理。覆盖范围是稳定的父目录别名，不包含人为单独硬链接锁文件；网络共享文件系统、其他 provider、Android/macOS 及完整游戏仍未验证。
- macOS 用户目录查询已由 Foundation Objective-C++ 经 `NativeRuntime` JNI 返回，Java 保留候选与探测。新增查询文件通过参考 macOS 工具链的独立 CMake 目标编译；Windows native 构建及 JNI 绑定验证通过。整体 native 的 CMake 配置受既有 Conan 包缺少 mimalloc 库阻挡。`NativeLibUtil` 尚未接通 macOS 加载，完整链接、真实目录查询及 native/游戏运行未验证；整体移植不属于本次目录迁移范围。

- Catalog、精确存储、lease/LRU、converted 消费登记和 Cleaner 已有自动化；Windows 已覆盖 2,000 个有效 ModelId、坏来源隔离、显式 reload 与完整目录交接。更大目录、生产多进程 prune、长期资源/图片回收及 reload 组合仍未验。
- 链接来源的发现/逻辑位置、完整验证/转换、Catalog/index/pack 发布和已验证字节读取已覆盖 Windows Java 17 与 WSL 原生 Linux 文件系统。Linux JVM 还通过 DrvFS 读取 Windows 创建的根 junction，覆盖 CUSTOM/AUTH 两种创建政策、完整发布、raw 内部 junction 拒绝与显式恢复。其余受控临时目录位于 Linux 文件系统；不将整套测试记作 DrvFS 覆盖。方法见[链接验证](../../verification/model-management.md#catalog-链接与显式-reload)。
- Windows Forge 已覆盖显式扫描、远端 authority、断连恢复最新 local、dedicated 服务重建、integrated 单次触发及正常关闭。来源替换后，两个独立 FULL lease 的 GPU 像素保持；关闭一个不影响另一个。此证据不覆盖 Linux GPU、其他显卡、长期保留或物理回收。
- Remote catalog、资产下载和 preview cache 已有缺图读取、管理补图、图片复用、通知、重连与专服重启证据；受限 AUTH 补图与普通取得拒绝已分别观察。Pack cover 已有双端下载、解码、render-thread 纹理发布及释放证据。完整 v3、丢通知、迟到结果、共享 target demand、在线 delta 和动画资产仍缺联合实机证据。
- 普通独立图已接入共享服务端字节保留、持久成功/reload 失效与客户端媒体缺图归约，职责见[存储](../../architecture/model-management/server-resources/overview.md)和[传输](../../architecture/network/implementation/asset-transfer.md#receiver-local-assembly-与-publication)。共享保留与失效、持久写入、传输 lease、客户端坏媒体与迟到缺图已有受控回归；实际 service reload 已验证目录未变时刷新外部替换的图片、旧 lease 继续可读及失败保留缓存。Windows Forge 另以固定 encoded bytes 验证成功 service reload 撤销保留并读取新 bytes，旧 lease 保持；这不证明新媒体已解码。Windows 回归覆盖 root 与 preview 目录的 junction 拒绝；热缺图回归覆盖交付期间通知撤销旧 token。真实双端坏图与替换交错、长期保留及性能收益尚未验证。
- 实体安装的 Pending、迟到、miss/I/O/拒绝、失败记忆与显式 reload 恢复已有受控验证。准入与实体表现、迟到传输、游戏连接保留仍缺联合实机验收。恢复规则见[安装失败记忆](../../architecture/animation/entity-lifecycle.md#失败记忆与恢复)与[资源取得失败](../../architecture/model-management/client-resources/overview.md#取得失败与纹理回退)。
- Cleaner 不保证回收时限。低内存和退出过程仍需验证 render-thread 销毁与 native/buffer 回收不会双重释放。

## 模型加载与图片交接

- `ClientAssetRepository` 的 eager 完成回调没有与 batch close 共用交付锁。回调先观察未关闭、close 再读取空结果、回调交付成功、close 取消失败的交错可遗漏新结果的释放。源码级交错已确认，尚未运行竞态复现；不能据普通完成/取消回归声明全部迟到结果已闭环。责任见[页面结果所有权](../../architecture/client-presentation/overview.md#运行作用域与所有权)。
- `DerivedStorageWork` 在 worker 启动前已入队并设置 worker；若 `Thread.start` 失败，没有恢复这些状态，close 可能等待永不运行的 worker 清除自身。属于线程启动失败的条件路径，未注入验证，不等同于普通 I/O 失败；责任见[存储工作寿命](../../architecture/model-management/storage/overview.md#精确缓存与持久工作)。

独立 preview、前景、背景与作者头像已使用资源侧内容证明和驻留 use。受控回归覆盖热命中零重复工作、多消费者、替换、reload、成员/连接失效及失败收敛。真实宿主中的全部来源、注册存活、替换/reload/世界退出交错和长期回收尚未联合验收；[宿主入口](../../tools/benchmark/README.md#模型列表真实宿主)的分页计数不能单独关闭这些缺口。

- 客户端 expression、派生写入与 preview 的字节政策已删除。定向回归覆盖 16 项 LRU／写入饱和、513 MiB 写入输入、192 MiB 实际像素、取消和最后借用释放。Winefox 01 的完整 MAIN 超过旧计价阈值仍可内存复用并提交；新 JVM 以独立 expected 重开，439 个动画和 artifact 内容相同且未编译。音频活动／保留与服务端 chunk／preview 额度在相同输入的前后版本、同 JVM owner 对照中保持；该对照不代表真实 integrated 游戏。方法见[保留与交接验证](../../tools/benchmark/README.md#证据半径)。新政策下的真实 GL 大图、integrated 游戏联合场景和长期 RSS 尚未验证。

- 派生工作域的真实 staging 写入、验证、flush、原子替换故障、容量拒绝、双进程竞争和异常退出重启已有回归。GUI／FULL 与非玩家 boat 在 expression-animation／geometry 的 staging 阻塞期间完成实际安装。实际 local custom→global custom 切换后，旧工作仍提交原区域；Ready/cache-only 复用无新 producer。页面／世界退出、host 阻塞时 strict export 关闭及放行后无迟到提交已有 Windows Forge 观察；不覆盖全部网络 remote 故障组合。
- 原始冷编译对象的重复产物验证计数为零；公开构造、恢复和环境反例仍走适用检查。Geometry 普通／cache-only 命中只取得并验证本次消费的数据。声音的批量读取、不同媒体约束、独立 target/playback 与取消已有真实 codec／channel 计数。真实 GUI hover 已触发 MinecraftAudioHost 接纳并消费 PCM，页面关闭后播放仍存活；不覆盖全部音频设备、听觉效果及完整动画／声音矩阵。
- 预览媒体资格、staging 副本一致性、最终编码像素与上传借用已有回归。真实 GL 延迟上传、页面／世界退出、注册拒绝、borrow 内失败和独立 reload 已观察像素回读一致及最终释放；客户端像素字节额度已删除，保留原 operation 数量和串行 reload 门禁；旧额度样本不证明当前保留峰值或 RSS 回落。
- eager 无图卡片在实时准备期间保持加载反馈，真实逐帧未出现有效展示下的中间问号。实际 intrinsic default MAIN／arm 的主体均为 LivingEntity；真实页面手动动画浏览、非默认纹理与第三／第一人称画面已有固定样本。Detached 默认 body 与必要 PBR 的实际构造／GL 上传已验证，不裁决 shader pack 的最终材质效果或全部动画／联动视觉。
- 固定内容对照已覆盖 GUI 单卡／第一页、FULL 和换纹理的 eager true/false，并报告暖 geometry／图片、进程工作集及主循环分布。第一页 eager=false 的首帧收益未复现，Private Bytes 增量与 frame P99 两对均增加；完整图片 blit 成本仍缺。整体收益和主循环尾延迟未通过当前验收，局部去重改善不抵消这些风险。

## 导出与管理补图

- `/ysm export` 与 `/ysm export-all` 的环境/权限门禁、单项与批量完整导出已有实机证据，生成制品已独立重开。作者图、混合批次、各阶段文件/host 故障、活动世界停止、长期 cache 及最坏合法图片/target 成本仍未验。职责见[转换与导出](../../architecture/asset-pipeline/implementation/conversion-and-export.md#preview-取得与显式-export)。
- 管理补图时，builtin `wine_fox/18_wedding` 报告 `Expression compiler is unavailable`，其他项目继续完成；该模型的 expression admission/编译可用性尚未闭环。单 raw fixture 的图片命中、Ready/cold 路径和 gameplay 送达不能证明大目录、双管理员、帧时间或 operation retention 峰值达标。

## 外部音频后端的取消交接

外部后端 cancel 当前只明确承诺返回前 seal 新输出；runtime 随即关闭 chunk capability，包括已交付 chunk。若后端解码 worker 尚未结束对 chunk bytes 的借用，现有 SPI 没有明确的借用结束交接。默认后端的 worker 清理机制不能替外部后端证明此条件。应先闭合[后端能力与播放终态](../../architecture/model-management/audio/overview.md#后端接管与播放终态)的责任；尚未验证 backing 物理存活或复现内存错误，不能直接定性为 UAF。
