# 当前支持状态

下表中的“已接线”仅说明生产链路已打通。自动化测试、实机运行、跨平台与跨实现证据各自独立判断；精确版本要求见[标准入口](../standards/README.md)，具体证据边界由各专题问题页维护。

| 能力 | 当前结论 | 主要缺口与证据入口 |
|---|---|---|
| Asset Container / Model Schema | Java 侧已接入当前容器格式、Schema、规范身份、直接与 zstd 存储及分层校验；不支持 ED25519 verification | 缺少跨实现的一致性验证；见[格式问题](known-issues/format-and-schema.md) |
| Protocol | Forge 侧已接入 model session、强类型 publication、按需分发、管理补图、玩家与实体状态同步及全局调度 | 完整双端授权、版本不匹配拒绝、消息交错与长期资源回收尚未验证；见[网络问题](known-issues/network.md) |
| 玩家状态与 roaming | 已接入 F32 数值状态、安装准备期映射、命名偏好及安装前交错回放。引擎 427 项测试、宿主 362 项测试及成品冷暖加载均通过；Windows Forge 双端已验证数值布局下的初值与 flags、sync 与 defer 顺序以及三类等待期清理，正常退出无残留 | 消费中途退休、容量溢出、handler 故障、完整多人场景、Replay 与跨平台尚未验证；本轮性能仅为单版本微基准；见[验证入口](../verification/player-state-network.md) |
| 客户端资源调度 | 已接入本地玩家隔离容量、共享工作提权与带预算的发布；功能回归与受控 JMH 已覆盖 | 后台吞吐存在容量代价，真实游戏收益尚未证实；见[调度证据](known-issues/model-management.md#调度与平台) |
| 启动模型一次性预热 | 37 项策略测试、真实 `ClientInputs` 启动组合、独立缓存矩阵、持久队列阻塞下的 Ready 交付，以及 137 项既有偏好、缓存与资源生命周期回归均通过。Windows Forge 主菜单的 AUTO 与 LOCAL 普通 builtin 选择（含非默认 `blue` 纹理）均经真实 GPU 发布取得一次 FULL Ready 并正常释放；Pending 显式关闭，以及进入 integrated 世界后原 Flight 被真实 session 请求替换的预期取消均通过。各场景保持单次取得、原 lease 与订阅各关闭一次并清空引用，断线恢复 LOCAL、受控 service 关闭与 JVM 正常退出均通过 | 主菜单帧时间收益、缓存持续驻留及其他平台尚未证明；见[验证方法](../verification/model-management.md#启动模型一次性预热) |
| Java 线程策略与启动 | 自建 Java 后台线程统一为 daemon 并接入 OS 调度策略；普通目录在 required 资源发布后异步激活；JVM 退出时直接放弃运行资源与未提交工作 | Windows JNI 与隔离 Forge 已验证部分生命周期，阻塞 worker 的子进程自然退出及活动 GUI 下的宿主退出均通过；其他平台、完整退出交错与性能收益仍有缺口；见[调度证据](known-issues/model-management.md#调度与平台) |
| 全局目录启动 | 已接入不可变目录配置、整组能力探测、平台候选路径与缓存受检路径门面；多根、完整地址、首个完整接纳、独立 pack 与消费者均已接通 | 平台策略、Windows symlink 与 junction、断链、ACL 与启动隔离已有测试，标准库迁移回归覆盖 Windows 与 WSL 原生 ext4；macOS 真实查询、其他平台和启动器以及完整游戏尚未验证；见[存储与平台边界](known-issues/model-management.md#目录存储与安装) |
| 共享模型存储与偏好 | 新缓存命名空间采用短整数文件 ID、独立 Bake 索引与 CACHE 根连续消费者保护；两池维护按逐根 72 小时及持久下次启动请求执行；玩家偏好保留既有 epoch 与顺序机制 | Windows 真实 JNI 与多进程测试已覆盖深路径、两池失败与崩溃、日期提交及请求交错；其他平台、有限成本与剩余缺口见[存储证据边界](known-issues/model-management.md#目录存储与安装) |
| 有界文件读取 | 用户来源、converted、remote、动画与几何、图片以及 raw 单流均按实际阶段关闭；动画 discovery 与实体同步 pass 复用精确来源 | Windows 真实 Java 句柄、失败与取消及并发借用已有定向验证；缺少 `fileKey` 的替换项、Forge 特有 provider、其他平台及 native archive 内部句柄不在证据范围内；见[读取验证](../verification/model-management.md#有界文件读取) |
| Java 模型管理 | 已接入 Catalog、精确存储、远端激活、共享 lease、preview 与导出及集中 demand 调度 | 功能回归与有限宿主观察未能覆盖全部取消分支、Busy 帧成本与长期物理回收；见[模型管理问题](known-issues/model-management.md) |
| Local Catalog 进度 | HUD 与页面每次绘制读取同轮扫描的阶段与输入计数，分别呈现发现、最终交接与空闲无活动状态 | Windows 真实 Forge 单人与多人已验证逐项文字、右对齐深灰显示，以及 Catalog 空闲时 FULL 排队、真实远端激活与 metadata 传输的隐藏对照；OOM 终态已有领域注入测试，尚未做 Forge OOM 注入；见[进度验证](../verification/model-management.md#构建与进程-ownership) |
| 已有 Java OOM 边界 | 实体执行、候选安装、资源取得与 Catalog 沿原 owner 终结受影响操作，内部不新增全入口 wrapper | Windows 定向注入已覆盖已生效动作保留、借用清理、旧内容和旧安装保留、异步终态及下一项独立工作；不证明全入口或真实堆耗尽后仍可存活；见[验证范围](../verification/model-management.md#输入与证据门禁) |
| 模型冷加载与图片交接 | 已实现内存 Ready 与可选持久化分离、原始冷编译证明、共享声音首块及一次图片解码交接；已移除客户端非音频字节额度政策，保留数量与借用保护 | 超旧额度输入、完整 MAIN 新 JVM 重开及额度隔离已有定向证据；真实 integrated 联合场景、GL 大图和长期 RSS 仍未验证，固定内容性能收益尚未通过验收；见[交接验证边界](known-issues/model-management.md#模型加载与图片交接) |
| Java 动画与模型声音 | 已接入 Controller、强类型 AOT、固定许可、实体显式交接与来源缓存；JNI 及 Forge 的正常与受控提前返回、捕获失败后返回及退出账本已有局部证据 | 可选模组注入组合、游戏性能、双端同步、音频设备播放与长期回收尚未完成验证；见[实体所有权验证](../verification/entity-animation-ownership.md)、[动画问题](known-issues/animation.md) |
| 默认模型双角色 | 同一真实默认源同时以内置默认（intrinsic）与普通 builtin 两种身份运行；领域、JNI 与 Windows Forge 已验证双角色、初始选择、等待页面、基础动作及受控 Molang 故障后的替代；headless 已验证切断模型 I/O 后的安装、动画及多块 PCM，并测量了完整准备与私有实例保留成本 | 音频设备播放、GPU 与原生独占内存及帧成本、未列出的可选模组组合仍未验证；见[默认源边界](known-issues/animation.md) |
| Molang 重复编译与宿主预算 | 已接入完整入口契约的共享分析、source 与 entry 分离以及显式宿主策略；三档规模、冷暖来源与资格及代表模型 AOT 完整准备已有既有证据（旧 GUI AOT 证据不覆盖当前解释路径） | Windows Forge 已观察代表模型的冷暖预览、选用及第一与第三人称；完整动作、逐骨骼视觉、声音及净性能收益仍未验证；见[复现与边界](../verification/animation-bake-benchmark.md#模型输入与共享规模) |
| Molang 性能收敛 | 已接入 AST-only、可信调用、Resource 自有租约、常量归约与 Roaming 静态写；引擎组合回归与有效主体受控三轴均通过 | 原始测量存在预热长尾，完整游戏收益尚未证明；宿主完整测试在 1 GiB 测试堆下通过（跳过项另计）；见[当前限制](known-issues/animation.md#molang-与同步) |
| Native CPU renderer | Bake、extract、render、SIMD 与并行顶点生成已接入生产入口；hermetic clang Release 聚焦回归通过 | Cube-info Java view 已改为使用返回数组，首次使用与扩容回归仍需确认；MSVC 构建、完整游戏视觉及其他平台尚未通过验收；见[渲染问题](known-issues/rendering.md)、[构建问题](known-issues/format-and-schema.md#codec导入与平台) |
| GUI 模型准备与取景 | `AnimationStore` 统一持有共享资源，兼容切换纹理时原位保留执行态；定向 CLI、真实 60 秒同 store 重访及真正退休后新建已有证据；三模型联合 retained 实测增加 604.2 KiB | 单进程配对与独立 heap 快照不构成统计性性能结论；真实 Ctrl 点击、剪贴板与文件管理器响应、完整模型和动画矩阵及输入往返尚未验证；见[取景边界](known-issues/rendering.md#gui-取景)、[冷准备性能](known-issues/animation.md#gui-与模型冷准备) |
| 第一人称与重复消费 | 标准与 Superb Warfare 手部及初始背景已接入持久局部输出；受控 JNI 与部分实机路径通过 | 完整资源生命周期、动态渲染结果对照（oracle）、native 成本与用户视觉尚未验证；见[第一人称证据](known-issues/rendering.md#重复消费与第一人称) |
| 图像 codec | 解码支持 PNG、JPEG、WebP、AVIF、ZTX；编码支持 WebP、AVIF、ZTX；不支持 JPEG XL | 各平台与各 codec 的完整组合尚未系统验证；见[格式问题](known-issues/format-and-schema.md#codec导入与平台) |
| 平台入口 | Windows x64、GNU/Linux x64 与专用 Android arm64 环境已有产品入口；x64 要求 SSE4.1 | Windows 7、x86-64-v1、macOS 及其他架构尚不可用；见[平台需求](../product-decisions/requirements/req-platform-availability.md) |
| Legacy v1/v2/v3 | 已接入向当前容器的单向导入；Windows x64 真实语料已完成重开验证 | 远端传输、实际游戏播放、headless 转换、其他平台与听测尚未验证；见[格式问题](known-issues/format-and-schema.md#codec导入与平台) |
| 可选扩展接入 | 已接入类级资格检查与 handler 准备 | Event bus 注册失败时的半注册回滚尚未完全闭环；见[接入问题](known-issues/integration.md) |
| 独立 Backend / 外部模型源 / GPU renderer | 尚未实现 | 见[Backend](../future/independent-backend.md)、[外部模型源](../future/external-model-sources.md)、[GPU renderer](../future/gpu-compute-renderer.md) |
