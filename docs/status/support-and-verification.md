# 当前支持状态

下表汇总各子系统的当前支持度与主要缺口；精确版本与规范要求见[标准入口](../standards/README.md)，自动化与手工验证分层见[验证边界](../verification/policy.md#验证分层架构)。

| 能力 | 当前支持度 | 主要缺口与入口 |
|---|---|---|
| Asset Container / Model Schema | 已接入当前容器格式、Schema、规范身份、直接/zstd 存储与分层校验；不支持 ED25519 verification | 缺少跨实现可移植二进制 golden，Raw 转换存在包围盒偏差；见[格式问题](known-issues/format-and-schema.md) |
| 图像 Codec 与 Legacy 导入 | 解码支持 PNG/JPEG/WebP/AVIF/ZTX，编码支持 WebP/AVIF/ZTX（不支持 JPEG XL）；已接入 Legacy v1/v2/v3 单向导入 | `ZTX` 未冻结，跨平台编解码与 Legacy 远端/游戏内/听测未闭环；见[Codec 与平台](known-issues/format-and-schema.md#codec导入与平台) |
| 平台入口 | Windows x64、GNU/Linux x64（需 SSE4.1）与专用 Android arm64 已有产品入口 | Windows 7、x86-64-v1、macOS 及其他架构不可用；见[平台需求](../product-decisions/requirements.md#reqplatform-availability) |
| 网络协议与玩家状态 | 已接入 model session、强类型 publication、按需分发、管理补图、数值 F32 roaming、安装期交错回放与全局调度 | 完整双端授权/版本不匹配/多人交错、消费中途退休及连接建立异常隔离未闭环；见[网络问题](known-issues/network.md)、[状态网络验证](../verification/player-state-network.md) |
| 目录、共享存储与偏好 | 已接入不可变全局目录、短整数 ID 四池缓存、逐根 72 小时两池维护、有界文件读取与版本化 SQLite 偏好 | 跨平台文件系统与启动器、完整配置授权矩阵、超长路径及长期回收未闭环；见[目录存储与安装](known-issues/model-management.md#目录存储与安装)、[模型管理验证](../verification/model-management.md) |
| 客户端调度、预热与冷加载 | 已接入本地玩家隔离队列、OS 线程策略、启动模型一次性预热、内存 Ready 与可选持久化分离、一次图片解码交接与 OOM 边界隔离 | 固定内容整帧性能与尾延迟未达验收，真实 GL 大图与长期 RSS/VRAM 待验证；见[模型管理问题](known-issues/model-management.md)、[启动预热验证](../verification/model-management.md#启动模型一次性预热) |
| Java 动画与默认模型双角色 | 已接入 Controller、强类型 AOT、统一 `AnimationStore`、实体显式交接与默认源双角色（intrinsic + 普通 builtin） | `isMoving`/多周期事件/快照初始化存在已知缺陷，live 查询并发安全与完整实机联动未闭环；见[动画问题](known-issues/animation.md)、[实体所有权验证](../verification/entity-animation-ownership.md) |
| Molang 引擎与 GUI 解释 | 已接入 AST-only AOT、共享编译分析、主体引用箭头与 GUI 绑定求值树解释路径 | 真实游戏帧期净收益、完整模型/动画矩阵与可选模组线程边界尚未证实；见[Molang 与 GUI](known-issues/animation.md#gui-与模型冷准备)、[编译基准](../verification/animation-bake-benchmark.md)、[声明验证](../verification/molang-declarations.md) |
| 模型声音与 PCM 缓存 | 已接入 Opus 解码续接、前缀/完整分段保留缓存、预算与播放终态管理 | 真实 Forge/OpenAL 下的可听延迟、once-only、设备容量与第三方后端未验收；见[音频缓存验证](../verification/audio-prefix-cache.md)、[动画问题](known-issues/animation.md#联动与验证) |
| Native CPU Renderer 与第一人称 | 已接入 bake/extract/render、SIMD 并行顶点生成、标准与 Superb Warfare 手部局部输出及 GUI 交接取景 | MSVC 构建失败，GUI 卡片构图与部分附着层未闭环，完整视觉与性能验收待补齐；见[渲染问题](known-issues/rendering.md) |
| 可选扩展与模组联动 | 已接入类级资格检查、事件 handler 准备与部分战斗/装备适配器 | 事件总线回滚失败边界与完整 Forge 启动扫描未闭环，`RegisterRenderStateModifierEvent` 未接通；见[接入问题](known-issues/integration.md)、[联动规划](../future/mod-animation-integration.md) |
| 独立 Backend / 外部模型源 / GPU Renderer | 尚未实现 | 见[独立 Backend](../future/independent-backend.md)、[外部模型源](../future/external-model-sources.md)、[GPU Renderer](../future/gpu-compute-renderer.md) |
