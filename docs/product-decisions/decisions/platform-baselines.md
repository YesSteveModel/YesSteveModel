# platform-baselines

- Requirement: [REQ.platform-availability](../requirements/req-platform-availability.md#reqplatform-availability)
- Select: OS/ISA 基线、native 依赖、全局目录政策、社区与 Android 启动器
- Needs:
  - 普通运行内容失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [implementation](../../architecture/native-runtime/implementation/runtime-capabilities.md#android-启动器接入)
  - [implementation](../../architecture/native-runtime/implementation/runtime-capabilities.md#macos-用户目录查询)
  - [implementation](../../architecture/model-management/storage/implementation/persistence.md#固定区域与文件边界)
  - [status](../../status/support-and-verification.md)
  - [status](../../status/known-issues/format-and-schema.md#codec导入与平台)
  - [design](../../architecture/native-runtime/overview.md)

## DD.bounded-platform-support

- Claim: 主线适配支持 Minecraft Java 版的主流游戏平台，采用明确的最低平台组合；其余平台的适配由社区 fork 维护。
- Rationale: 各平台所需库统一随 YSM 模组 jar 分发，扩大平台矩阵会增加 jar 体积、验证组合与持续维护成本，而开发者精力有限。适配使用量极低且已停止支持的早期平台还会限制对较新平台能力的利用，因此不为扩大最低范围持续承担这些成本。

### BC.minimum-platform-baselines

- Claim: 主线以以下组合为最低可用性基线，运行环境还须能够运行本主线要求的 JDK、Minecraft Java 与 Forge。

| 平台 | 最低系统基线 | 最低 CPU 基线 |
|---|---|---|
| Windows | Windows 7 SP1 | x86-64-v1 |
| Linux | Ubuntu 14.04；Linux 3.13.9 / glibc 2.19 | x86-64-v1 |
| Android | Android 9.0 / API 28 | ARMv8-A |
| macOS | macOS 14.5 | Apple M1 |

这些是适配要求，实际支持与验证进度仍由下游支持状态记录；达到系统与 CPU 基线不等于任意启动器、驱动或模组组合均可用。

### BC.global-model-directory-selection

- Claim: 全局 DATA/CACHE 在启动时整组探测并固定到进程结束。任一必要位置或共享存储能力失败时，整组停用，使用实例来源和实例缓存；普通配置失败不升级为内置必需资源失败，运行中不重选根。

| 条件 | DATA | CACHE |
|---|---|---|
| 已设置 `YSM_HOME` | 变量指向的绝对目录 | DATA 下的 `cache` |
| Windows | `LOCALAPPDATA/ysm` | DATA 下的 `cache` |
| Linux | `XDG_DATA_HOME/ysm` | `XDG_CACHE_HOME/ysm/cache` |
| Android 第一候选 | `HOME/ysm` | DATA 下的 `cache` |
| Android 第二候选 | `gameDir/../../ysm`，父目录名应为 `.minecraft` | DATA 下的 `cache` |
| macOS 用户域 | `NSApplicationSupportDirectory` 查询结果下的 `ysm` | `NSCachesDirectory` 查询结果下的 `ysm/cache` |

已设置的 `YSM_HOME` 独占候选；空白、相对值或探测失败只记录本机错误并停用全局，不试平台目录。候选的逻辑位置和可解析实际位置均应在当前游戏工作目录之外；缺席尾段结合可解析祖先判断，链接和 junction 不能绕过限制。

Linux 必须同时提供绝对路径的 `XDG_DATA_HOME` 与 `XDG_CACHE_HOME`；任一个未设置、空白或为相对路径时停用全局，不从 `HOME` 构造默认路径，也不扫描 `XDG_DATA_DIRS`。Android 只在第一候选失败后尝试第二候选，不按启动器品牌分流，也不搜索更多祖先。macOS 仅在独立 CACHE 查询无结果时允许 DATA/CACHE 合并；已经取得的 CACHE 探测失败不允许合并。目录政策不扩展为整体 macOS native 移植。

DATA 下的来源入口为 `custom/auth`。创建和探测权限见[来源保护](artifact-storage.md#bclocal-catalog-input-is-never-mutated)。策略验证、真实文件系统、真实启动器及完整游戏的支持结论分别记录于[支持状态](../../status/support-and-verification.md)。

### BC.native-distribution-is-self-contained

- Claim: 所有平台的 YSM native 制品对宿主环境的外部运行库依赖仅限内核接口、libc 及其伴生系统库，如 libm、libdl。伴生库仍计入依赖，但通常由系统提供，无需额外处理；C++ runtime 及其余所需库必须由随模组 jar 分发的制品自包含提供，不依赖系统另行提供或要求玩家安装额外运行库。

macOS 同样不得依赖环境提供的动态 libc++；SDK 未提供 libc++ 静态库不构成例外，满足约束的构建方式由下游选择。

### BC.community-platform-support-boundary

- Claim: 其他非主流平台及 Windows XP、Vista、CentOS 7 等已停止支持且使用量极低的目标不属于主线适配范围，由社区 fork 维护。iOS 虽有 Minecraft Java 社区启动器，但其动态库签名机制阻碍本模组的 native 适配，因此也不列入主线支持范围。

### BC.native-provider-api-is-future-capability

- Horizon: future
- Claim: 后续将开发 API，允许其他模组提供 native 库以承接社区平台适配；该能力不视为当前已实现，也不将社区平台纳入主线维护与可用性承诺。

## DD.android-via-community-launchers

- Claim: YSM 在满足前置条件的 Android 社区启动器环境中运行，不把任意移动启动器都列为适配目标。
- Rationale: JDK、Minecraft Java 及 Forge、Fabric、NeoForge 虽未官方支持移动设备，社区适配已形成多个开箱即用且稳定性合格的启动器；复用这些环境可以提供移动基础可用性，无需 YSM 自行承担整个游戏与 Java 运行环境的移植。

### BC.android-launcher-runtime-prerequisite

- Claim: Android 启动器必须配置 `MOD_ANDROID_RUNTIME`，指向用于存放 YSM native 文件且与 `libjvm.so` 同属一个 native library namespace 的目录，使模组库能够被 JVM 正常加载；未满足这一前提的启动器不在适配承诺内。

社区对 Fabric、NeoForge 的移动适配只解释运行环境来源，本决策仍以 Minecraft 1.20.1 Forge 主线为范围。
