# 因果缺口与差异

本页维护 NR 子系统尚未闭合的 allocator 与 cache identity；产品判断仍以[产品决策树](../../product-decisions/README.md)为 Root Authority。

## Q-02

**Allocator 退出期规避措施的现实前提未闭合。** `NativeLibUtil.loadLibraryOnLifetimeThread` 为 mimalloc 保持 loader thread 存活；native `SystemAllocator` 注释说明避开 mimalloc TLS 生命周期，应用于部分 TLS/渲染状态。意图有直接注释证据，但没有在本图证据内找到当前 allocator 版本、线程退出顺序与可复现失效之间的完整链。

- 待补事实：是否仍有必须规避的加载线程退出或 TLS 析构失败；需要给出触发条件，或明确接受的长期保留边界。
- 当前判断：**Uncertain**，不能标成 Historical，更不能仅凭“看起来多余”删除。
- 支点与成本：若前提被证实消失，可评估删除常驻 holder 或收敛 allocator 族；JNI 注册、调用期 owner 保活和跨线程对象关闭仍然必须存在。测试成本集中于加载线程退出、短命 worker、TLS 析构和进程退出，错误判断可能造成 native 崩溃。

## Q-03

**Baked cache 的 option 身份依赖隐含调用前提。** [派生 cache](../../architecture/model-management/storage/derived-artifacts.md#独立身份与原子集合)要求精确实际输入、选项和 ABI/profile 隔离。`BakedModelCache.bakeHash` 当前直接覆盖 container、resource name、texture hash，路径/profile 再区分平台、SIMD、bake version 与 cache ABI；`originVersion`、`forceCulling`、`forceTranslucent`、`hasPbr` 没有逐一进入 hash。当前生产调用有常量或容器派生来源，因此未证明日常路径存在错误复用。

- 待明确的接口前提：这些 option 是否永久被限定为 container/resource/profile 的函数，还是调用者可独立改变它们。
- 可选支点：收窄入口并固定推导，可减少外部自由组合；若需独立选项，则显式加入 cache identity 并承担缓存失效迁移。只改文档宣称“已覆盖”不解决前提缺口。
- 最小验证：保持其余输入不变，逐一改变实际可变 option，核对产物是否变化、key/profile 是否随之变化；现有名称含 `AndOptions` 的测试并未证明全部 option 维度。

## 跨子系统缺口

- 当帧 native fork/join 是否符合主循环约束见 [AN Q-01](../java-animation-and-integration/open-questions.md#q-01)。
- Legacy 图片策略与 raw 转换共同受用途策略约束，效果和成本见 [AP Q-04](../java-assets-and-presentation/open-questions.md#q-04)。
- 完整平台、codec、SIMD、GPU/driver 和真实导入语料仍未验证；这些缺口不能由静态索引或单元测试存在补成通过。
