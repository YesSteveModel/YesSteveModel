# Native 运行能力

本页定位[ABI 准入](../overview.md#native-abi-准入)、[调用线程调度](../overview.md#调用线程调度)和[设备默认](../overview.md#设备配置默认)的代码落实。

## 加载与一次性绑定

`NativeLibUtil.loadLibraryOnLifetimeThread()` 在专用守护驻留线程上加载并初始化 native 库，并保持该线程存活以满足底层分配器生命周期要求；该线程不承接业务任务队列，也不参与游戏退出协调。

`NativeRuntime.load()` 先以临时 JNA 句柄校验全局 ABI 导出符号，通过后再调用 `System.load()` 完成 JNI 注册并关闭临时句柄。`BindEntry` 仅绑定一次，空表、重复注册或绑定失败直接终止准入。整体加载顺序见[运行设计](../../runtime/overview.md)，符号协议见[ABI 维护](../../../abi/maintenance.md#native-符号门禁)。

## 能力分工

Java 能力门面各自返回独立结果，native 侧不维护业务会话或缓存权威：

| 当前入口 | 交接边界 |
|---|---|
| `Blake3`、`Zstd` | 纯字节计算；身份输入选择与结果提交归调用方 |
| `Image`、`ImageEncoder`、`NativeArchive` | 媒体与归档来源字节；用途判断、捕获与宿主纹理由 Java 接纳 |
| `AudioNormalizer`、`OpusDecoder` | 音频规范化候选、增量 PCM 解码与共享 checkpoint；内存计价与播放归音频负责方 |
| `NativeBakedModel`、`NativeModelState`、`NativeRenderer` | 烘焙模型对象、帧状态与顶点字节；姿态求值与宿主提交归 Java |
| `NativeLegacyImporter` | 同步转换旧格式为强类型 Schema 数据；校验、staging 与发布归 Java |

上述入口分别服务于[资产管线](../../asset-pipeline/overview.md)、[渲染](../../rendering/overview.md)和[音频](../../model-management/audio/overview.md)。

## macOS 用户目录查询

`NativeRuntime.macUserDirectories()` 经 JNI 调用 Foundation 查询当前用户的 `Application Support` 与 `Caches` 目录。Objective-C++ 入口仅复制返回路径，子目录拼接、平台候选排序、独立缓存缺失回退与进程级路径固定均由 Java 侧承担（策略见[平台目录](../../../product-decisions/decisions/platform-baselines.md#bcglobal-model-directory-selection)）。

当前加载接入与平台验证缺口见[存储与平台状态](../../../status/known-issues/model-management.md#目录存储与安装)。

## 失败如何返回

- 通用 JNI 入口包装器将 C++ 错误或异常映射为约定的 `false`、`null` 或 fallback 返回值，由 Java 包装层解释后决定是否推进。
- 旧格式导入与音频生成使用各自结构化结果对象，保留调用方决策所需的失败来源信息。
- 例如，`NativeRenderer.render()` 返回成功后 Java 才推进 direct 顶点区间或向 fallback consumer 回放数据；模型 Ready 与纹理发布仍由各自 Java 负责方门禁判定。

## 调用线程调度

- `ThreadScheduling.entry` 在自有平台线程的 worker 循环入口调用 `NativeRuntime.applyCurrentThreadPolicy`，再执行原任务体。`NORMAL` 策略不主动恢复 OS 线程状态；低档入口仅读取已发布的 runtime 初始化结果，不反向触发库加载。
- `ThreadSchedulingResult` 分开记录执行结果、生效属性与各步骤错误：读回缺失不按默认值处理，`APPLIED` 要求目标设置与必要读回均成立，`DEGRADED` 与 `PARTIAL` 如实保留回退或部分修改状态。
- Java 侧单独归类“入口不可用”与“报告结构非法”，不与 OS 错误码混用。诊断日志失败不吞掉原 worker 任务体，重复日志抑制不影响后续新建线程的策略尝试。
- Windows 可选系统符号动态解析，Linux 避免依赖较新 glibc 专属查询符号；报告格式随全局 ABI 演进。

## 可用核心数

`NativeRuntime.availableProcessors()` 在初始化后缓存 native 探测的物理核数，调用时与 JVM 可用处理器数取较小正值；未初始化、探测失败或入口缺失时直接返回 JVM 值且不触发库加载。该值仅用于估算工作池容量。

## 设备配置默认

- `NativeRuntime.deviceQuality()` 通过静态 holder 仅探测并缓存一次，两份配置共享快照。初始化顺序为：加载 native 库 → 初始化 CPU 能力 → 配置 runtime → 发布可用性标志 → 构造配置默认值；用户显式配置仍由 Forge spec 解析。
- Native 侧 `ClassifyDevice` 统一负责硬件事实分档，线上探测与单元测试共用入口。总拓扑探测不复用渲染调度的 `DetectCpuTopology` 过滤逻辑，Android 已知低配路径跳过多余探测。
- “未知或探测失败”与“合法低配”区分记录，两者均启用保守默认；仅明确判为合法高配时启用高配默认。Java 缓存后不后台重探，native 侧不保存第二份档位。

## Android 启动器接入

`MOD_ANDROID_RUNTIME` 消费启动器提供的运行前提，产品边界见[平台基线](../../../product-decisions/decisions/platform-baselines.md#bcandroid-launcher-runtime-prerequisite)。外部 [FAQ](https://ysm.cfpa.team/en/wiki/faq/) 仅作接入导航，不扩大[当前支持范围](../../../status/support-and-verification.md)。
