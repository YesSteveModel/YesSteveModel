# 因果缺口与差异

本页记录 NR 子系统尚未确认的 allocator 规避前提与烘焙缓存身份边界；涉及产品层面的决策仍以[产品决策树](../../product-decisions/README.md)为 Root Authority。

## Q-02

**Allocator 退出期规避措施的现实前提尚未证实。** `NativeLibUtil.loadLibraryOnLifetimeThread` 为了配合 mimalloc，会让加载线程常驻存活；native 侧 `SystemAllocator` 的注释也说明它是为了绕开 mimalloc 的 TLS 生命周期，并用于部分 TLS 和渲染状态。虽然这些设计意图有直接注释作证，但目前没有足够证据把当前 allocator 版本、线程退出顺序与可复现的崩溃串成完整因果链。

- 待补事实：当前环境下是否仍然存在必须规避的加载线程退出或 TLS 析构失败。需要明确给出复现触发条件，或明确决定将其作为长期保留边界。
- 当前判断：**Uncertain**。既不能定性为历史遗留（Historical），更不能仅凭“看起来多余”就直接删除。
- 支点与成本：如果证实前提已经消失，可以评估移除常驻加载线程或收拢 allocator 实现；但 JNI 注册、调用期所有权保活和跨线程对象关闭仍必须保留。验证成本主要集中在加载线程退出、短命 worker、TLS 析构和进程退出场景，误判可能导致 native 崩溃。

## Q-03

**Baked cache 的 option 身份依赖隐含的调用前提。** [派生 cache](../../architecture/model-management/storage/derived-artifacts.md#独立身份与原子集合)要求按精确的实际输入、构建选项以及 ABI 与 profile 隔离缓存。目前 `BakedModelCache.bakeHash` 直接纳入了 container、resource name 和 texture hash，路径与 profile 再区分平台、SIMD、bake version 和 cache ABI；而 `originVersion`、`forceCulling`、`forceTranslucent`、`hasPbr` 并没有逐一进入 hash。由于当前生产调用中这些值都是常量或由容器派生，目前没有证据表明日常路径存在错误复用。

- 待明确的接口前提：这些 option 是永久限定为 container、resource 和 profile 的派生结果，还是允许调用者独立修改。
- 可选支点：如果确定它们不可独立变化，可以收窄接口并固定内部推导逻辑，避免外部自由组合；如果需要支持独立配置，则必须把它们显式纳入 cache 身份，并承担缓存失效与迁移成本。仅在文档里宣称“已覆盖”无法消除这一前提缺口。
- 最小验证：保持其余输入不变，逐一改动实际可变的 option，核对烘焙产物是否变化，以及对应的 key 或 profile 是否随之变化。现有名称包含 `AndOptions` 的测试并未覆盖全部 option 维度。

## 跨子系统缺口

- 当帧 native fork/join 是否符合主循环约束，见 [AN Q-01](../java-animation-and-integration/open-questions.md#q-01)。
- Legacy 图片策略与 raw 转换共同受用途策略约束，其效果和成本见 [AP Q-04](../java-assets-and-presentation/open-questions.md#q-04)。
- 全平台、各 codec、SIMD 指令集、真实 GPU 与驱动以及真实导入语料仍未完成验证；这些缺口不能靠静态代码索引或单元测试存在来视作通过。
