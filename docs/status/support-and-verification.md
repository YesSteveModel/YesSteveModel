# 当前支持状态

“已接线”只表示生产链路存在。自动化、实机、跨平台和跨实现证据分别判断；精确版本见[标准入口](../standards/README.md)，证据边界由下列问题页维护。

| 能力 | 当前结论 | 主要缺口与证据入口 |
|---|---|---|
| Asset Container / Model Schema | Java 已接入当前容器、Schema、canonical identity、direct/zstd 与分层验证；ED25519 verification 不支持 | 缺跨实现 conformance；[格式问题](known-issues/format-and-schema.md) |
| Protocol | Forge 已接入 model session、typed publication、按需分发、管理补图、玩家/entity 状态和全局调度 | 完整双端授权、版本失败、交错与长期回收未验；[网络问题](known-issues/network.md) |
| 客户端资源调度 | 本地玩家隔离容量、共享工作提权与有预算发布已接入；功能回归和受控 JMH 已覆盖 | 有后台容量代价，真实游戏收益未证实；[调度证据](known-issues/model-management.md#调度与平台) |
| Java 线程策略与启动 | 专属后台线程已接入 OS 策略；普通目录在 required 发布后异步激活 | Windows JNI 与隔离 Forge 已验证部分生命周期；其他平台与性能仍有缺口；[调度证据](known-issues/model-management.md#调度与平台) |
| 全局目录启动 | 不可变目录配置、整组能力探测、平台候选与缓存受检路径门面已接入 | 平台策略、Windows symlink/junction、断链、ACL 与启动隔离已有测试；标准库迁移回归覆盖 Windows 和 WSL 原生 ext4。多根、完整地址、首个完整接纳、独立 pack 与消费者已接通；macOS 真实查询、其他平台/启动器及完整游戏仍未验证；[存储与平台边界](known-issues/model-management.md#目录存储与安装) |
| 共享模型存储 | 内容绑定接入 converted、geometry、expression、preview、remote 与 export；converted 按 DATA／完整转换版本分区，文件锁统一使用 JDK | Windows 与 WSL ext4 回归覆盖同 key 完整提交、索引合并、消费者保护和残留恢复；Linux 私有 bind mount 另验证父目录别名下的重入、取消与消费者锁保留。来源切换及跨实例复用已有组合测试；网络共享 FS、其他 provider、Android/macOS 和完整游戏仍未验证；[存储证据边界](known-issues/model-management.md#目录存储与安装) |
| 有界文件读取 | 用户来源、converted、remote、动画/几何、图片与 raw 单流已按实际阶段关闭；动画 discovery 与实体同步 pass 复用精确来源 | Windows 真实 Java 句柄、失败/取消及并发借用有定向验证；缺少 fileKey 的替换项、Forge 特有 provider、其他平台与 native archive 内部句柄不在该证据范围；[读取验证](../verification/model-management.md#有界文件读取) |
| Java 模型管理 | Catalog、精确存储、remote activation、共享 lease、preview/export 和集中 demand 已接入 | 功能回归与有限宿主观察不覆盖全部取消、Busy 帧成本及长期物理回收；[模型管理问题](known-issues/model-management.md) |
| Local Catalog 进度 | HUD 与页面各次绘制读取同轮阶段/输入计数；发现、最终交接与无活动分别呈现 | Windows 真实 Forge 单人/多人已验证逐项文字、右对齐深灰，以及 Catalog 空闲时 FULL 排队、真实远端 activation／metadata transfer 的独立隐藏对照；OOM 终态另有领域注入，未作 Forge OOM 注入；[进度验证](../verification/model-management.md#构建与进程-ownership) |
| 已有 Java OOM 边界 | 实体执行、候选安装、资源取得与 Catalog 沿原 owner 终结受影响操作；内部不增加全入口 wrapper | Windows 定向注入覆盖整轮效果丢弃、旧内容/安装保留、异步终态和下一项独立工作；不证明全入口或真实堆耗尽存活；[验证范围](../verification/model-management.md#输入与证据门禁) |
| 模型冷加载与图片交接 | 内存 Ready 与可选持久化分离、原始冷编译证明、共享声音首块及一次图片解码交接已接入；客户端非音频字节政策已删除，数量与借用保护保留 | 超旧额度输入、完整 MAIN 新 JVM 重开及额度隔离已有定向证据；真实 integrated 联合场景、GL 大图和长期 RSS 仍未验证，固定内容收益仍未通过；[交接验证边界](known-issues/model-management.md#模型加载与图片交接) |
| Java 动画与模型声音 | Controller、typed AOT、固定许可、实体显式交接与来源缓存已接入；JNI 及 Forge 正常／受控提前返回、捕获失败后返回和退出账本有局部证据 | 可选模组注入组合、游戏性能、双端同步、设备声音和长期回收未完成验证；[实体所有权验证](../verification/entity-animation-ownership.md)、[动画问题](known-issues/animation.md) |
| Molang 重复编译与宿主预算 | 完整入口契约共享分析、source／entry 分离和显式宿主策略已接入；三档规模、冷暖来源／资格及代表模型 GUI/FULL 完整准备通过 | Windows Forge 已观察该模型冷暖预览、选用与第一／第三人称；完整动作、逐骨骼视觉、声音及净性能收益仍未验证；[复现与边界](../verification/animation-bake-benchmark.md#模型输入与共享规模) |
| Native CPU renderer | Bake/extract/render、SIMD 与并行顶点生成已有生产入口；hermetic clang Release 聚焦回归通过 | Cube-info Java view 已改用返回数组，首次使用与扩容回归仍需确认；MSVC、完整游戏视觉及其他平台未通过验收；[渲染问题](known-issues/rendering.md)、[构建问题](known-issues/format-and-schema.md#codec导入与平台) |
| GUI 模型准备与取景 | 完整闭包、主体特化、独立图驻留与每 render 帧卡片交接已接入；容量、所有权和 native 等价有局部回归；当前卡片与生成图已有有限像素验证；完整地址、Ctrl 分派和跨根 pack 导航有局部测试 | 真实 Ctrl 点击、剪贴板和文件管理器响应、完整模型/动画矩阵、输入往返与最终主循环成本未验；[取景边界](known-issues/rendering.md#gui-取景)、[冷准备性能](known-issues/animation.md#gui-与模型冷准备) |
| 第一人称与重复消费 | 标准/SW 手部和初始背景已接入持久局部输出；受控 JNI 与部分实机路径通过 | 完整资源生命周期、动态 renderer oracle、native 成本与用户视觉未验；[第一人称证据](known-issues/rendering.md#重复消费与第一人称) |
| 图像 codec | 解码支持 PNG、JPEG、WebP、AVIF、ZTX；编码支持 WebP、AVIF、ZTX；不支持 JPEG XL | 平台与 codec 组合未完整验证；[格式问题](known-issues/format-and-schema.md#codec导入与平台) |
| 平台入口 | Windows x64、GNU/Linux x64、专用 Android arm64 环境有产品入口；x64 要求 SSE4.1 | Windows 7、x86-64-v1、macOS 和其他架构尚不可用；[平台需求](../product-decisions/requirements/req-platform-availability.md) |
| Legacy v1/v2/v3 | 已接入 current container 单向导入；Windows x64 真实语料已重开验证 | Remote、游戏播放、headless、其他平台与听测未验；[格式问题](known-issues/format-and-schema.md#codec导入与平台) |
| 可选扩展接入 | 类级资格与 handler 准备已接入 | Event bus 注册失败的半注册回滚未闭环；[接入问题](known-issues/integration.md) |
| 独立 Backend / 外部模型源 / GPU renderer | 未实现 | [Backend](../future/independent-backend.md)、[外部模型源](../future/external-model-sources.md)、[GPU renderer](../future/gpu-compute-renderer.md) |
