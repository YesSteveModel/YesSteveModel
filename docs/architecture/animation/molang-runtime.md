# Molang 运行设计

上级契约：[动画](overview.md#seam-contract)。

## 职责与边界

本页细化[动画求值契约](overview.md#seam-contract)的准备产物、实体表达式执行与效果交接。

Molang 是模型创作与导入语言。持久 target 在准备期完成 frontend、静态闭合与 AOT 生成，执行期使用已验证 module 和实体私有 instance。源字段见 [Model Schema](../../standards/model-schema/assets-and-validation.md)，YSM 定型与 Java 接入分别由[静态语言](molang-static-language.md)和[声明契约](molang-declarations.md)定义。

## Seam Contract

| 消费者问题 | 契约 |
|---|---|
| 资源准备方何时能交付共享 module，实体绑定还需什么？ | [共享产物准备与准入](#共享产物准备与准入) |
| 实体求值轮如何借用输入、绑定调用并提交效果？ | [Invocation 与效果](#invocation-与效果) |
| command/watch/网络执行如何独立编译并交回原 owner？ | [短期执行](#短期执行) |

### 共同规则

适用范围：[共享产物准备与准入](#共享产物准备与准入)、[Invocation 与效果](#invocation-与效果)和[短期执行](#短期执行)均继承本节共同规则。共同规则包括内容信任、共享与私有状态边界、冻结预算、组件故障分类及证据限制。具体接纳、执行借用与关闭责任由各项契约裁决。

#### 编译与执行分离

内容/集合共享不可变 module，entity owner 隔离 component holder、compiled instance、Roaming 与 controller 状态。

#### 内容信任与预算

模型与远端内容只提供 source，不选择 Java 类或执行后端。Effect 权限、服务端裁决和协议输入上限共同限定能力，信任义务见[产品边界](../../product-decisions/decisions/trust-boundaries.md)。网络只投影 consumer 的共享语义，不传本地 ProgramId、SubjectBinding 或 artifact 摘要。

`ExpressionPolicy` 是宿主预算 owner，显式选择冷编译与五项执行额度。registry、unit、persistent/transient、可信 expected、decode/load 和 frame 消费同一冻结策略；模型及远端内容没有加额入口。数值属于私有调优值。

预算拒绝保留种类、配置上限、阶段与可得位置；宿主分别报告预算与 backend 失败。策略变化参与 artifact identity，新的冻结 host/factory 不继承旧策略失败记忆。

#### 组件故障分类

| 失败阶段 | 结果 |
|---|---|
| Component 构造或生命周期异常 | 拒绝私有候选或结束当前使用，再由 entity 绑定 owner 降级；不冒充 member 调用结果 |

调用恢复的实参丢弃、presence、重载与对象写语义只在[静态语言规则](molang-static-language.md)定义。Registry 冻结与类级资格见[扩展接入设计](../integration/overview.md#扩展接纳与声明发布)。

取得与取消规则见[资源所有权](../model-management/client-resources/implementation/resource-ownership.md)。

实体私有状态的独占范围与 GUI 许可由[实体模型使用契约](entity-lifecycle.md#执行权与任务交接)建立；live Minecraft 与可选 provider 输入不属于该保证。集成与成本方法见[Molang 声明验证](../../verification/molang-declarations.md)，当前线程、联动与完整宿主证据范围见[动画问题](../../status/known-issues/animation.md)。

### 共享产物准备与准入

**边界与权威**

本契约在内容/集合准备方与 target、实体绑定消费者之间交付完整共享产物。继承[共同规则](#共同规则)。

Main 与 first-person arm 各自闭合 inventory、module、普通变量和动画/controller 绑定。Common callable 在调用集合闭合，body 专属事件由 body 分发；默认补全使用独立来源命名空间，不能被本地同名函数改写。

**前置条件与输入**

所选 target/set 的完整输入均应验证，不按实体用途省略。默认资源提供的已验证定义与函数仍在当前模型组合内解析。

Artifact identity 覆盖内容/集合、配置、ABI、冻结 host 与 specialization，也覆盖声明、语言语义及生成/执行兼容内容的确定性摘要。源码摘要将转换、比较与类型合流的实现纳入 expected identity。同 source、Java 签名和版本号不代表语义未变；旧 artifact 与当前 expected 失配后沿已有缓存路径重新编译，[开发期 Molang 契约](../../product-decisions/decisions/model-compatibility.md#bcdevelopment-molang-semantics-use-current-contract)不设旧语义执行分支。

#### 展示闭包与主体特化

卡片与生成预览图选择 GUI 闭包，包含展示动画、必要初始化和完整依赖。该闭包独立定型，世界动画不承担 GUI 定型依据；无法静态确定动画依赖时保守保留全量 MAIN。中央手动动画预览使用 FULL，保留完整动画与 first-person arm 能力。普通 GUI 只准备展示闭包，不准备 first-person arm。

输入用途与根主体特化是独立维度。GUI 使用 `FakePlayer`，玩家和中央 FULL 预览使用 `AbstractClientPlayer`，intrinsic default 固定使用 `LivingEntity`；default 也保留请求用途。根类型只在语义闭合后简化 Java 调用资格，不删去静态分析或子 context 的动态资格。不同用途与绑定拥有独立准备身份，可同时驻留内存和磁盘。

GUI 的输入和特化只由自身用途决定，不随 FULL 是否存在变化。GUI/FULL 各自构建精确 expected，共享只限同用途与 binding 等价的 module/animation；每个实体仍创建私有 instance、controller 与播放状态。

**结果与保证**

每个模型独立完成函数解析、语义闭合、类型/槽位推导、链接和后端生成。完整 inventory 保留每个来源；同次编译内只有完整程序、签名、固定许可和 fallback 契约相等时才共享分析与执行 entry。ProgramId 表示原始来源，entryId 表示完整执行契约，两者均只属于当前产物。共享不缓存求值结果或实体状态。冻结 registry、host 与 binding 参与产物身份，准备成本证据见[集成验证](../../verification/molang-declarations.md#成本测量)。

Cold 路径编译并验证 artifact；warm 路径只验证、加载和绑定，不重新分析源。持久执行不解析 source 或按名字查找 builtin。

#### 完整准备与准入

Factory 完整验证、加载、绑定并检查 target 的 Roaming 并集后才交付只读准备对象。准备期将每个来源绑定为包含 sourceId、entryId、shape 与 generation 的不可变引用；冷、内存和磁盘路径使用同一关系。Baked 动画与事件位置继续保存来源编号。Entity 安装前还应实际构造当前 subject 的 component/root，避免把共享准备成功当作实体安装成功。类资格在启动冻结，owner-local 失败不修改全局声明资格。

普通模型允许带 program replacement 的完整 module；builtin 准入要求 replacement 集合为空。局部 UNSET、动态门禁 missing、合法 presence 或对象投影不属于整 program replacement。Catalog、constants、classes 与 report 来自同一有效计划，诊断文本不承担替代裁决权。

冷编译、暖加载与调试读取同一不可变 report。调试展示记忆只属于当前启用范围、选中 target 和 resource generation，不进入 artifact identity，也不持有 entity/resource。

**所有权与时序**

Pin 把共享寿命归入现有 target 保留，不新增永久缓存。

**失败与拒收**

| 失败阶段 | 结果 |
|---|---|
| missing、callable arity、首个边界参数类型不匹配 | 当前节点为普通 UNSET；废弃实参不执行，report 保留主因 |
| 独立 binding/type/shape 错误 | 持久入口使用整 program fallback，临时入口拒绝；废弃树不污染最终约束 |
| 结构损坏、编译预算、生成/backend、准备取消、artifact 或 linkage 失败 | 拒绝整个 target candidate，不发布部分资源，不伪装成模型归零 |

组件故障沿[共同分类](#组件故障分类)交给实体绑定 owner；执行期返回违约沿[Invocation 与效果](#invocation-与效果)接纳。

**证据与依赖**

具体生成、缓存、binding 和报告流程见[运行实现](implementation/molang-runtime.md)；作者声明与成本方法见[Molang 声明验证](../../verification/molang-declarations.md)。

### Invocation 与效果

**边界与权威**

本契约在实体逻辑 owner、编译入口/声明实现与领域效果接收方之间交接一轮完整求值。继承[共同规则](#共同规则)。

Entity 私有执行态遵守[执行权与任务交接](entity-lifecycle.md#执行权与任务交接)。Worker 执行期间独占 runtime；主线程只维护待交接句柄，不读取更新态。GUI 指定任务可在许可内安装与迁移；其他安装以及 reset、reload 和 retire 仍在主线程完成原任务交接后执行。引擎的 instance/access 活跃检查不替代执行权交接或跨 instance 禁止重入。

候选只构造私有 instance/组件，当前查询仍见旧安装；交接后的发布撤销旧资格。Worker 的失败不触发生命周期变化，主线程接纳失败后才结束 exact installation。同步绘制回调可撤资格并延后释放，普通求值不能重入生命周期。同 module 重装仍更换资格，兼容变体只撤销几何输出。

**前置条件与输入**

#### 调用许可与预算

每条顶层表达式先验证 context 空闲、context/binding 引用及 generation，再绑定 program、参数、位置、预算和固定 ACTION 许可。许可由当前宿主动作窗口与 entry/layout 上限求交；同条表达式的内部函数和普通 provider 均保持该许可，符合[固定表达式许可](../../product-decisions/decisions/animation-isolation.md#bcexpression-action-permission-is-fixed)。同轮 UPDATE 不授权全部表达式，后续只读入口重新绑定自己的上限；短期入口不继承前一入口许可。许可固定不冻结 subject、installation、generation、取消或最终提交资格。

**结果与保证**

声音、粒子、controller、骨骼和 sync 的声明实现先固定目标与载荷，再提交通用 `PreparedEffect`。Runtime 不按业务 symbol 或效果分类分派。Runtime 在组件生命周期外拥有整轮窗口；全部 program、必要 begin/end hook、效果验证及 owner 资格成功后才提交一次。首次失败不会被后续成功或表达式 reset 清除；最后只读入口的许可不撤销先前合法动作。失败丢弃尚未提交 batch，清理完成前不能提前提交。提交按申请顺序调用领域操作；一旦外部提交开始，失败只清除剩余引用，不承诺跨系统回滚。

**所有权与时序**

完整逻辑求值是一轮，包含组件生命周期及 pre/main/post 执行。每轮复用同一个 `MolangContext`，同时承担 context 与 frame；公共统一 view 引用同一载体，不逐表达式或逐 provider 建立 wrapper。业务 view 只同步借用 subject、只读轮输入和申请提交能力，不暴露入口执行、预算、batch 或 reset；精确签名和借用契约见[声明边界](molang-declarations.md#公共业务-view)。

Provider 不得直接或经 helper 同步重入任何 persistent/transient 入口，包括其他 instance、frame 或实体。相同 context 的重复 begin 在写入前拒绝，不取得外层清理责任。成功接纳的表达式在所有退出路径清除 program、参数、预算、递归、固定许可和临时引用；失败与逃逸载荷先形成独立来源快照。整轮输入、首次失败、效果 batch 及调度中的采样位置不由表达式 reset 清除；采样位置由 controller/animation 切换，轮结束才解除轮级宿主借用。

`defer` 是 runtime 内部调度申请，独立于普通效果提交。Runtime 复制已求值的有限 F32 与来源，按当前安装和 animation context 绑定 capture。上下文结束时按登记逆序调用 handler；每次调用建立新的顶层许可与预算窗口，产生的普通效果仍归同轮 batch。轮级累计捕获上限约束递归 defer。回调失败丢弃整轮效果，过期安装不分发 capture，轮结束不留下跨轮队列。

**失败与拒收**

#### 求值失败

| 失败阶段 | 结果 |
|---|---|
| 合法调用的 subject/ACTION 门禁 | 返回声明类型的 missing/void no-op，执行不求参，静态分析保留 scope |
| 执行预算、执行取消或运行失败 | 交接原失败后清理表达式借用，整轮不提交 batch；不伪装成正常 missing |
| non-null record/数组实际返回 null | 严重返回契约违约；结束求值、丢弃 batch，再交 entity 绑定 owner 降级 |
| 合法 missing 或普通 provider/运行异常 | 普通 typed fallback 与局部隔离，不升级严重降级或编译 replacement |
| Effect 验证或 program 失败 | 丢弃尚未提交 batch，外部效果不回滚 |

组件构造与生命周期异常使用[共同分类](#组件故障分类)，不冒充 member 调用结果。

执行诊断在 reset 前分别保留实际顶层来源、callee 来源/node，以及 owner、generation 与原始 cause，延迟消费者不从复用 context 补读旧来源。函数的非有限 float/Float 返回是正常 typed missing，不自动使此前合法动作失败；主动抛异常仍保留原 cause。任意 NPE、合法 nullable、String 归一或组件生命周期异常不能冒充严重返回违约。默认自身异常不递归降级；精确失败记忆和安全换资源见[实体绑定实现](implementation/entity-and-frame-state.md#绑定与切换)。

**证据与依赖**

实体执行权由[实体模型使用契约](entity-lifecycle.md#执行权与任务交接)建立；binding、整轮效果和 deferred capture 的落实见[运行实现](implementation/molang-runtime.md)。作者 view 的精确签名与借用责任见[声明边界](molang-declarations.md#公共业务-view)。

### 短期执行

**边界与权威**

本契约把 command、watch 和网络入口的独立编译结果交回原实体 owner，再借用当前安装执行。继承[共同规则](#共同规则)；执行期还依赖[Invocation 与效果](#invocation-与效果)的同轮借用和失败规则。

**前置条件与输入**

Command、watch 和网络执行使用当前 target generation、SubjectBinding 与冻结 layout 的短期 lease，在同一实体执行窗口执行。临时程序不能扩展持久 slot、提升表示或反向重编模型；这类请求在调用 provider、写状态或提交动作前拒绝。

**结果与保证**

Debug/watch 与 execute 使用同一正常持久写语义。结构 read/write/call 授权和 RoamingRole 仍有效；EXECUTE 可申请 ACTION，WATCH 的 entry 上限为 CONTEXT，均与本次短期宿主窗口求交。Pre/main/post 短期任务共用所在轮的 context 与首次失败，不另开嵌套轮。CONTEXT 的随机/物理推进属于正常 owner 状态，不复制或回滚任意 component。

**所有权与时序**

主线程准备冻结编译输入并取得独立 module lease；后台只编译独立程序。主线程复核 exact installation 后才 attach access。编译结果交付与关闭 inbox 原子转移关闭责任，迟到结果应关闭。短期 program lease 与 access 各自关闭，同 hash 的新 generation 不复活旧资格。

**失败与拒收**

独立 binding/type/shape 错误按[共享准备准入的失败分类](#共享产物准备与准入)拒绝临时入口；许可、取消、运行失败及未提交效果沿[求值轮失败](#求值失败)裁决。编译成功不恢复已经失效的安装资格。

**证据与依赖**

具体入口、静态表示交接和资源上限见[短期执行实现](implementation/molang-runtime.md#privileged-ingress)；原 owner 的执行权和终态见[任务交接](entity-lifecycle.md#执行权与任务交接)。

## 设计取舍

编译下放到实体会复制生成成本、classloader 和失败状态；实例状态提升到 target 会使同模型实体互相污染。

跨纹理复用准备资源减少重复 decode、生成、load 和首次动画绑定；main/arm 的独立闭合则增加冷准备与默认驻留成本。

准备期编译、执行期使用已验证 module，减少动态查找，但不能据此推定帧预算、零分配或普遍提速。成本方法见[集成验证](../../verification/molang-declarations.md)，当前证据范围见[动画问题](../../status/known-issues/animation.md)。

GUI/FULL 用途隔离的代价是同一内容可同时冷准备和驻留两种用途。

## 实现与验证入口

[Molang 落实关系](implementation/molang-runtime.md)定位编译身份、binding、效果轮和资源上限；[短期执行落实](implementation/molang-runtime.md#privileged-ingress)定位冻结输入、独立编译与原子交付。实体生命周期失败的归约见[实体绑定](implementation/entity-and-frame-state.md#绑定与切换)。
