# Native 运行能力

本页定位[ABI 准入](../overview.md#native-abi-准入)、[调用线程调度](../overview.md#调用线程调度)和[设备默认](../overview.md#设备配置默认)的代码落实。

## 加载与一次性绑定

`NativeLibUtil.loadLibraryOnLifetimeThread()` 在专用的守护驻留线程上加载并初始化 native 库，成功后保持该线程存活以满足底层分配器的生命周期要求。这条线程不承接模型任务队列，也不参与游戏退出协调。

`NativeRuntime.load()` 先通过临时 JNA 句柄查询全局 ABI 导出符号，校验通过后再调用 `System.load()` 执行 JNI 注册；取得 JVM 引用后随即关闭临时句柄。`BindEntry` 只允许绑定一次，遇到空表、重复注册或绑定失败都会直接终止准入，不建立可重试的注册表。整体加载顺序见[运行设计](../../runtime/overview.md)，符号协议见[ABI 维护](../../../abi/maintenance.md#native-符号门禁)。

## 能力分工

Java 能力门面各自返回独立调用结果，不在 native 侧引入会话或缓存权威。

| 当前入口 | 交接边界 |
|---|---|
| `Blake3`、`Zstd` | 纯字节计算；身份输入选择与结果提交归调用方 |
| `Image`、`ImageEncoder`、`NativeArchive` | 媒体与归档来源字节；用途判断、捕获与宿主纹理由 Java 接纳 |
| `AudioNormalizer`、`OpusDecoder` | 音频规范化候选、增量 PCM 解码与共享 checkpoint；内存计价与播放归音频负责方 |
| `NativeBakedModel`、`NativeModelState`、`NativeRenderer` | 烘焙模型对象、帧状态与顶点字节；姿态求值与宿主提交归 Java |
| `NativeLegacyImporter` | 同步转换旧格式为强类型 Schema 数据；校验、staging 与发布归 Java |

这些入口分别服务于[资产管线](../../asset-pipeline/overview.md)、[渲染](../../rendering/overview.md)和[音频](../../model-management/audio/overview.md)，共享同一个 JNI 通道并不意味着共享业务状态。

## macOS 用户目录查询

`NativeRuntime.macUserDirectories()` 通过 JNI 调用 Foundation 查询当前用户的 `Application Support` 与 `Caches` 目录。Objective-C++ 入口只负责复制查询到的目录路径，不拼接 YSM 子目录，也不做存储可用性探测。平台候选排序、独立缓存目录缺失时的回退、整组目录探测与进程级固定责任仍归 Java 侧，策略见[平台目录](../../../product-decisions/decisions/platform-baselines.md#bcglobal-model-directory-selection)。

这项目录查询不意味着扩大整体 macOS native 移植范围。当前的加载接入与平台验证缺口见[存储与平台状态](../../../status/known-issues/model-management.md#目录存储与安装)。

## 失败如何返回

通用 JNI 入口包装器会将 C++ 的错误状态或异常映射为各入口约定的 `false`、`null` 或 fallback 返回值。Java 包装层先解释返回值再决定是否推进后续流程；通用入口不承诺保留完整的细粒度错误分类。旧格式导入与音频生成则使用各自的结构化结果对象，保留调用方决策所需的失败来源信息。

例如，`NativeRenderer.render()` 返回成功后，Java 才允许推进 direct 顶点区间或向 fallback consumer 回放数据。Native 计算成功不代表模型 Ready 或纹理已发布，后两者仍需通过对应 Java 负责方的准入门禁。

## 调用线程调度

`ThreadScheduling.entry` 在自有平台线程的 worker 循环入口处调用 `NativeRuntime.applyCurrentThreadPolicy`，随后执行一次原始任务体。`NORMAL` 策略不会主动恢复 OS 线程状态；低档策略入口只读取已发布的 runtime 初始化结果，不会反向触发 native 库加载。

`ThreadSchedulingResult` 将执行结果、生效属性和各步骤错误拆开记录。读回缺失时绝不当成默认值处理：`APPLIED` 要求目标设置与必要读回均成立，`DEGRADED` 与 `PARTIAL` 则如实保留实际回退或部分修改状态。Java 侧把“入口不可用”与“报告结构非法”单独归类上报，不与 OS 错误码混在一起。诊断日志本身如果失败，不会吞掉原 worker 任务体；重复日志抑制也不会跳过后续新建线程的策略尝试。

Windows 下的可选系统符号全部采用动态解析；Linux 入口避免依赖较新 glibc 才提供的线程查询符号。Java 与 native 之间的报告格式随全局 ABI 一并演进，不单独做版本协商。平台实现细节和局部参数调优不构成新的业务调度权。

## 可用核心数

`NativeRuntime.availableProcessors()` 在初始化完成后缓存 native 探测到的系统物理核数，每次调用时将其与 JVM 可用处理器数取较小正值。如果尚未初始化、探测失败或入口缺失，则直接返回 JVM 的结果，不触发库加载。该数值只用于估算工作池容量，既不能替代设备默认分档所用的完整硬件事实，也不代表进程一定能拿到对应的 CPU 份额。

## 设备配置默认

`NativeRuntime.deviceQuality()` 借助 Java 静态初始化 holder 只探测并缓存一次结果，两份配置共享该快照。正常初始化顺序依次为：加载 native 库、初始化 CPU 能力、配置 runtime、发布可用性标志，最后构造配置默认值；用户显式填写的配置项仍由 Forge spec 正常解析。

Native 侧的 `ClassifyDevice` 只负责对输入硬件事实分档，线上探测与单元测试共用同一个分类入口。设备总拓扑探测不复用渲染调度所用的 `DetectCpuTopology` 过滤逻辑，Android 已知低配路径也不会追加多余探测。系统会区分“未知或探测失败”与“合法低配”，但两者都只启用保守默认；只有明确判为合法高配的设备才会开启高配默认。Java 缓存结果后不会在后台重新探测，native 侧也不保存第二份档位。

## Android 启动器接入

`MOD_ANDROID_RUNTIME` 消费启动器提供的运行前提，产品边界见[平台基线](../../../product-decisions/decisions/platform-baselines.md#bcandroid-launcher-runtime-prerequisite)。外部 [FAQ](https://ysm.cfpa.team/en/wiki/faq/) 仅作接入导航，不扩大[当前支持范围](../../../status/support-and-verification.md)。
