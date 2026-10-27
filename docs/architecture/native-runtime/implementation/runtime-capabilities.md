# Native 运行能力

本页定位[ABI 准入](../overview.md#native-abi-准入)、[调用线程调度](../overview.md#调用线程调度)和[设备默认](../overview.md#设备配置默认)的当前落实关系。

## 加载与一次性绑定

`NativeLibUtil.loadLibraryOnLifetimeThread()` 在专用 daemon holder thread 加载与初始化 native，成功后保持线程存活以满足分配器寿命。该线程不承接模型队列或游戏退出协调。

`NativeRuntime.load()` 先用临时 JNA 句柄查询全局 ABI 符号，再 `System.load()` 执行 JNI 注册；取得 JVM 引用后关闭临时句柄。`BindEntry` 只允许一次绑定，空表、重复或绑定失败终止准入，不建立重试注册表。加载顺序见[运行设计](../../runtime/overview.md)，符号协议见[ABI 维护](../../../abi/maintenance.md#native-符号门禁)。

## 能力分工

Java 能力门面保留独立调用结果，不引入 native session 或 cache authority。

| 当前入口 | 交接边界 |
|---|---|
| `Blake3`、`Zstd` | bytes 计算；identity 输入和提交仍由调用方选择 |
| `Image`、`ImageEncoder`、`NativeArchive` | 媒体/来源 bytes；用途、capture 与宿主纹理由 Java 接纳 |
| `AudioNormalizer`、`OpusDecoder` | 规范化候选、增量 PCM、共享 checkpoint；计价与播放归音频 owner |
| `NativeBakedModel`、`NativeModelState`、`NativeRenderer` | baked 对象、状态与顶点 bytes；Java 决定求值和宿主提交 |
| `NativeLegacyImporter` | 同步 typed 投影；Java 校验结果、staging 与发布 |

这些入口分别服务于[资产管线](../../asset-pipeline/overview.md)、[渲染](../../rendering/overview.md)和[音频](../../model-management/audio/overview.md)，共享 JNI 不表示共享业务状态。

## macOS 用户目录查询

`NativeRuntime.macUserDirectories()` 通过 JNI 返回 Foundation 查询的用户域 Application Support 与 Caches 目录。Objective-C++ 入口只复制目录结果，不选择 YSM 子目录或执行存储探测。Java 保留平台候选、独立 CACHE 缺席回退、整组探测与进程固定责任；政策见[平台目录](../../../product-decisions/decisions/platform-baselines.md#bcglobal-model-directory-selection)。

目录查询不扩展整体 macOS native 移植范围。当前加载接入与平台验证缺口见[存储与平台状态](../../../status/known-issues/model-management.md#目录存储与安装)。

## 失败如何返回

通用 entry wrapper 将 C++ status/exception 映射为入口约定的 false、null 或 fallback。Java wrapper 解释结果后才推进；通用边界不保证保留完整错误分类。Legacy 与音频生成使用各自结构化结果，避免丢失调用方所需 provenance。

例如 `NativeRenderer.render()` 成功后才允许 fallback writer 或 direct vertex region 提交。Native 成功不是模型 Ready 或纹理发布，后两者仍经 Java owner 门禁。

## 调用线程调度

`ThreadScheduling.entry` 在自有 platform thread 的 worker loop 入口调用 `NativeRuntime.applyCurrentThreadPolicy`，随后执行一次原 body。`NORMAL` 不恢复 OS 状态；低档入口只消费已发布 runtime 初始化结果，不触发加载。

`ThreadSchedulingResult` 分离 outcome、有效属性与各步骤错误。缺失读回不当默认值，`APPLIED` 要求目标与必要读回成立，`DEGRADED`/`PARTIAL` 保留实际回退或部分修改。Java 分别报告入口不可用和非法报告，不混入 OS error code。诊断失败不吞 body，重复日志抑制不跳过新线程的策略尝试。

Windows 可选符号动态解析；Linux 入口不依赖较新 glibc 的线程查询符号。Java/native 的报告协议随全局 ABI 共同变更，不增加独立版本协商。平台细节和局部调优不构成新的业务调度权。

## 可用核心数

`NativeRuntime.availableProcessors()` 在初始化后缓存 native 系统物理核数，每次与 JVM 可用核数取较小正值。未初始化、查询失败或入口缺失时使用 JVM 结果，不触发加载。该值服务工作容量，不能替代设备默认使用的完整设备事实，也不承诺实际 CPU 份额。

## 设备配置默认

`NativeRuntime.deviceQuality()` 通过 Java 初始化 holder 缓存一次结果，两个配置共享。正常顺序为 load、CPU 初始化、runtime 配置、availability 发布，再构造配置默认；显式配置仍由 Forge spec 解析。

Native `ClassifyDevice` 只分类事实，生产查询与测试输入共用该边界。设备总拓扑不复用调度用 `DetectCpuTopology` 的过滤，Android 的已知低配路径不追加探测。未知/失败与合法低配可区分，但都只给保守默认；只有合法高配开启高配默认。Java 缓存结果不后台重探测，native 不保存第二份档位。

## Android 启动器接入

`MOD_ANDROID_RUNTIME` 消费启动器提供的运行前提，产品边界见[平台基线](../../../product-decisions/decisions/platform-baselines.md#bcandroid-launcher-runtime-prerequisite)。外部 [FAQ](https://ysm.cfpa.team/en/wiki/faq/)仅作接入导航，不扩大[当前支持范围](../../../status/support-and-verification.md)。
