# Molang 运行设计

上级契约：[动画](overview.md#seam-contract)。

## 职责与边界

本页细化[动画求值契约](overview.md#seam-contract)中的产物准备、实体表达式求值与效果交接。

Molang 客户端按用途分为两条执行路径：普通 GUI 独立准备并解释执行当前消费的表达式；FULL 世界实体、第一人称手部与中央完整动画预览执行全量静态检查并生成 AOT 字节码。两条路径共用同一套 `MolangContext`、冻结声明与实体效果出口，实例状态按实体隔离。源字段定义见 [Model Schema](../../standards/model-schema/assets-and-validation.md)；AOT 静态定型、GUI 动态语义与 Java 扩展接入分别见[静态语言](molang-static-language.md)、[解释语义](molang-interpreter.md)和[声明契约](molang-declarations.md)。

## Seam Contract

| 消费者问题 | 契约 |
|---|---|
| 资源准备方何时能交付共享 store，实体绑定还需什么？ | [共享产物准备与准入](#共享产物准备与准入) |
| 实体求值轮如何借用输入、绑定调用并提交效果？ | [Invocation 与效果](#invocation-与效果) |
| command、watch 或网络执行如何独立编译并交回原 owner？ | [短期执行](#短期执行) |

### 共同规则

适用范围：[共享产物准备与准入](#共享产物准备与准入)、[Invocation 与效果](#invocation-与效果)和[短期执行](#短期执行)。

#### 编译与执行分离

`AnimationStore` 持有共享动画数据、定义及 AOT/解释运行资源；实体从选定 store 获取执行借用，同时在自身隔离组件、执行实例、Roaming 值/成员及 controller 状态。解释环境只共享只追加的名称目录，安装时从所属环境创建 Roaming 状态再与同环境程序和实例配对。共享资源所有权与退休见[客户端资源](../model-management/client-resources/overview.md#动画资源所有权与退休)。

#### 内容信任与预算

模型与远端内容仅提供脚本文本，不能指定加载的 Java 类或执行后端；产品信任边界见[产品边界](../../product-decisions/decisions/trust-boundaries.md)。网络只同步消费者共享语义，不传本地 `ProgramId`、`SubjectBinding` 或 artifact 摘要。

`ExpressionPolicy` 冻结语言选项、固定许可和用户函数深度上限。编译链不对输入大小、分析节点数、函数版本数、生成体积或 artifact 数量设人工硬额度（代码生成器负责物理拆分），但仍校验编码格式、类型、实际 JVM 表示与取消信号。运行时保留默认 32 层活跃用户函数深度限制：`LOOP` 每次独立四舍五入并截断至 1024 次，`FOR_EACH` 不截断。GUI 深输入与取消见[解释语义](molang-interpreter.md#实例驻留与失败)。

函数深度终止保留上限值、阶段与顶层来源；真实后端或表示失败拒绝整个候选。语言选项、调用 scope、引用 ABI、可空子环境、root/current 语义、资源绑定与 `LOOP` 语义均计入兼容摘要，失配时触发重建。

#### 组件故障分类

| 失败阶段 | 结果 |
|---|---|
| Component 构造或生命周期异常 | 拒绝私有候选或结束当前使用，交实体绑定 owner 降级；不冒充成员调用结果 |

调用局部恢复与声明注册见[AOT 静态语言](molang-static-language.md)、[GUI 动态语义](molang-interpreter.md)、[共同声明](molang-declarations.md)及[扩展接入](../integration/overview.md#扩展接纳与声明发布)。资源获取见[资源所有权](../model-management/client-resources/implementation/resource-ownership.md)；实体独占执行权见[实体模型使用](entity-lifecycle.md#执行权与任务交接)（不覆盖实时 Minecraft 与可选模组 provider）。成本测量见[Molang 声明验证](../../verification/molang-declarations.md)，当前验证缺口见[动画问题](../../status/known-issues/animation.md)。

### 共享产物准备与准入

**边界与权威**

本节的完整程序清点（inventory）、静态检查、module、report 与 replacement 规则适用于 AOT 路径；GUI 单表达式准备与动态失败见[解释语义](molang-interpreter.md#独立准备与来源)。

Main 与第一人称 arm 各自独立完成程序清点、module 编译、普通变量布局以及动画与 controller 绑定。公共函数在所属调用集合内解析，body 专属事件由 body 分发；默认补全使用独立来源命名空间，不被模型本地同名函数覆盖。

**前置条件与输入**

AOT 路径完整校验所选 target 或集合的全部输入；GUI 路径校验实际消费程序的全部结构，未消费函数体按需获取。默认资源提供的定义与函数参与当前模型解析。

Artifact 身份覆盖内容与动画集、配置、ABI、冻结 host/特化信息，以及声明、语言语义和生成/执行兼容内容的确定性摘要。源码摘要将类型转换、比较和类型合流的实现一并纳入：即使脚本源码、Java 签名和版本号未变，语义实现变化也会触发缓存重编（[开发期 Molang 契约](../../product-decisions/decisions/model-compatibility.md#bcdevelopment-molang-semantics-use-current-contract)不维护旧语义兼容分支）。

#### 执行用途与主体资格

模型卡片、纹理候选和预览图生成采用独立解释路径，不做编译闭包、整集定型或第一人称 arm 准备；中央完整动画预览、世界玩家与第一人称手部走 FULL / AOT 路径。

执行用途与主体资格正交：GUI 卡片用 `FakePlayer`，世界玩家与中央 FULL 预览用 `AbstractClientPlayer`，intrinsic default 固定 `LivingEntity` 且仅驻留 FULL（普通 `builtin:default` 仍有独立 GUI 路径）。两条路径共享同一冻结声明与主体门禁；AOT 可基于已知根主体静态简化调用，箭头子主体（child）仍保留动态资格检查。

GUI 路径不探测或借用 FULL 的字节码、inventory、实例或私有状态，两者仅按各自身份共享底层动画输入；GUI 不生成 AOT 产物也不设持久化解释缓存。

**结果与保证**

每个模型独立完成函数解析、静态推导、布局、链接与代码生成。完整 inventory 逐一保留每个程序来源；同一次编译内仅在完整程序、签名、固定许可与 fallback 契约完全一致时共享分析与执行 entry（`ProgramId` 标识原始来源，`entryId` 标识执行契约，仅在当前产物内有效；只复代码不缓存求值结果）。冷路径编译并验证 artifact；暖路径只验证、加载和绑定；AOT 持久执行期不再解析源码或按名查找内建声明。

#### 完整准备与准入

Factory 验证、加载、绑定并核查整个 target 的 Roaming 声明并集后交付只读准备对象，将每个来源绑定为含 `sourceId`、`entryId`、`shape` 和 `generation` 的不可变引用（冷编译、内存缓存和磁盘加载走同一流程）。实体安装前仍须针对真实 subject 构造组件与根实例。Intrinsic default 的[函数投影](../model-management/client-resources/default-model.md#边界与权威)同时约束 module 绑定与短期编译；普通模型的默认补全在接收环境中编译。

普通模型允许含 program replacement 的完整 module；内建模型（builtin）接纳要求 replacement 为空（局部 `UNSET`、动态门禁 missing、presence 观察或对象投影不算整程序 replacement）。冷编译、暖加载与调试视图读取同一不可变 report；调试面板展示记忆仅绑定启用范围、选中 target 和 generation，不进入 artifact identity 也不反向保活资源。

**所有权与时序**

共享准备 pin 将 GUI 与 FULL 各自的 store 纳入 target 保留范围；实体只归还自身执行借用，统一退休见[动画资源所有权](../model-management/client-resources/overview.md#动画资源所有权与退休)。

**失败与拒收**

| 失败阶段 | 结果 |
|---|---|
| 符号缺失、callable 参数数量不符、首个边界参数类型不匹配 | 当前节点局部恢复为普通 UNSET；废弃实参不执行，report 保留主因 |
| 独立的 binding、类型或 shape 错误 | 持久入口改用整程序 fallback，临时入口直接拒绝；废弃子树不污染最终约束 |
| 结构损坏、实际表示超限、生成或后端异常、准备取消、artifact 或 linkage 失败 | 拒绝整个 target 候选，不发布残缺资源 |

**证据与依赖**

代码生成、缓存、绑定与报告实现见[运行实现](implementation/molang-runtime.md)，声明验证与成本测量见[Molang 声明验证](../../verification/molang-declarations.md)。

### Invocation 与效果

**边界与权威**

实体私有执行态依赖[执行权与任务交接](entity-lifecycle.md#执行权与任务交接)：worker 运行期间独占 runtime，主线程仅持有待交接句柄；GUI 指定任务可在许可内执行内部安装与迁移，其余生命周期变更在主线程交接后执行。引擎内部的 instance/access 活跃检查仅作辅助防御，不替代宿主执行权交接。

Worker 内的执行失败不直接变更实体生命周期，由主线程接纳后结束该精确安装。同步绘制回调可立即撤销消费资格并延后释放；普通求值过程不重入生命周期入口。

**前置条件与输入**

#### 调用许可与预算

实体安装与执行权保证 `context`、`binding`、`generation`、`shape` 与参数配对。每条顶层表达式开始前绑定 program 与位置、重置函数深度，并把当次宿主动作窗口与 entry/layout 上限取交集作为本次固定 ACTION 许可（见[固定表达式许可](../../product-decisions/decisions/animation-isolation.md#bcexpression-action-permission-is-fixed)）。被调内部函数与 Java provider 共用该许可和函数深度，不重复核验生命周期；后续入口重新绑定各自上限。

**结果与保证**

声音、粒子与骨骼修改直接提交对应领域 owner；`ysm.sync` 在当前借用退出后本地排空执行一次，再独立尝试网络上报。后续表达式失败不回滚已发生动作。四个 controller 控制动作仅在 `CodedAnimationController.process` 同步选择链内、player 更新前生效。本地 sync 登记时复制参数、来源和精确安装身份，待源 context 与组件借用全部退出后按入队顺序以新 ACTION 入口交付（函数深度终止保留已登记载荷；普通实例异常、严重退出或安装被拒清空未交付载荷）。

Roaming 赋值不受阶段或 ACTION 许可限制，observation 或禁 ACTION 入口均可本地写入（被拒 ACTION 调用仍不求参）；producer 写入仅标记 dirty，同步消费见[Roaming 同步](implementation/state-inputs-and-sync.md#roaming)。

**所有权与时序**

一轮（round）完整求值包含组件生命周期回调与 pre/main/post 阶段表达式。源轮复用同一 `MolangContext` 与执行帧；解释实例复用根求值外壳，每条表达式结束清临时借用与词法状态。箭头子环境只切查询对象，公共业务 view 直接借用源载体或当前载体（不逐次建 wrapper），仅同步借用 subject、只读轮输入和 `defer` 登记，见[声明边界](molang-declarations.md#公共业务-view)。

**关键并发前提**：当前生产环境的 context 依赖单实体串行执行权且不维护线程检查、活跃位或重入防御，因此 provider 不能直接或经 helper 同步重入任何持久或临时执行入口（包括其他实例、帧或实体）；若未来需要支持重入，需在 context 层补齐帧栈与重入保护。成功进入的表达式在 `finally` 中清许可和函数深度再解除 program 绑定；整轮输入与采样位置由轮级 owner 在结束时释放。

`defer` 复制已求值有限 F32 参数和来源并绑定当前安装与动画上下文；上下文退出时按登记逆序逐个调用 handler，各自建立新许可与函数深度（函数深度终止只跳过当前 handler，普通异常中止剩余 handler 并丢弃未交付载荷；不继承 coded-controller 窗口；单轮累计上限 64 次，超限按实例异常终止）。

**失败与拒收**

#### 当前主体与源执行

`SUBJECT` 与 `ORIGIN` 归属在冷编译期固定。有效箭头派生子环境时捕获一次目标实体完成样本和远端玩家投影，右侧表达式（RHS）全程复用该快照；源变量槽、用户函数、ACTION 许可、函数深度、执行位置、`defer` 与诊断信息不迁入目标，子环境也不访问目标 capability/runtime/component/hook 或等待目标任务。

主轮推进历史后在首条表达式运行前绑定本轮私有输入；根环境读本轮私有样本，自指向箭头（self-arrow）读上一轮完成样本。缺失样本的历史查询返回 missing，直接装备查询不要求目标安装模型。整轮结束在 `finally` 中统一解除全部借用。样本发布与清理见[完成样本](entity-lifecycle.md#完成样本的发布与结束)。

#### 求值失败

| 失败阶段 | 结果 |
|---|---|
| 合法调用被 subject 或 ACTION 门禁拒绝 | 返回声明类型的 missing 或 void no-op，不求参，静态分析保留调用 scope |
| 活跃用户函数深度超限 | 截断当前顶层表达式，四种 shape 分别返回 `0`、`false`、`UNSET` 或 `void`；重置深度后继续下一条表达式及正常 sync/defer |
| 其他普通执行异常 | 结束当前实例本轮求值，清理未交付载荷，交原实体 owner 降级；已发生动作保留 |
| 合法 missing、可空/装箱 `null`、`String` `null` 归一 | 正常值语义，不触发实例失败或编译 replacement |
| 严重 Error 或必要清理失败 | AOT 清理保证 `VirtualMachineError`/`ThreadDeath` 优先于普通主失败（原主失败作 suppressed）；解释路径 `finally` 按退出顺序直接传播 |

执行期诊断仅保留顶层来源、owner、generation 与原 `Throwable`，不维护精确被调函数或 AST 节点轨迹。普通异常统一归类为 `INSTANCE_EXECUTION`，不扫描 cause 链也不建单表达式黑名单；生产代码信任 provider 的 non-null、有限值与不可变承诺，NPE 走普通异常出口。默认模型异常不递归降级，失败记忆与交接见[实体绑定实现](implementation/entity-and-frame-state.md#绑定与切换)。

**证据与依赖**

依赖[实体模型使用契约](entity-lifecycle.md#执行权与任务交接)，落实见[运行实现](implementation/molang-runtime.md)与[声明边界](molang-declarations.md#公共业务-view)。

### 短期执行

**前置条件与输入**

客户端本地 command 与 watch 依赖当前 target generation、`SubjectBinding` 及冻结布局的短期 lease，在同一实体执行窗口内运行。临时程序不能扩展持久变量槽、提升静态表示或反向重编模型，违约在调用 provider、写状态或提交动作前被拒绝。

**结果与保证**

Debug、watch 与 execute 沿用正常持久写语义及 `RoamingRole`：`EXECUTE` 可申请 `ACTION`，`WATCH` 上限为 `CONTEXT`，均与本次宿主窗口取交集。Pre/main/post 短期任务共用所在求值轮 context 与首次失败状态，不开启嵌套轮；`CONTEXT` 下触发的随机或物理推进属正常 owner 状态变化。

**所有权与时序**

主线程通过当前 store 执行借用冻结编译输入，并为可能跨安装寿命的编译工作建立独立保护；后台线程仅基于冻结输入编译。主线程在下一次准备窗口复核当前精确安装仍有效后挂载（attach）access。交付与关闭 inbox 以原子方式转移关闭责任，迟到结果由编译方自行关闭。短期 program lease 与 access 各自独立关闭，新 generation 内容哈希相同也不复活旧资格。

**失败与拒收**

独立 binding/类型/shape 错误直接拒绝临时入口；权限与运行时失败沿[求值失败](#求值失败)处理。入口与上限细节见[短期执行实现](implementation/molang-runtime.md#privileged-ingress)。

## 设计取舍

- **在 target 层共享编译产物，在实体层隔离运行实例**：如果把编译下放到每个实体，会成倍复制代码生成、classloader 和失败状态开销；反过来如果把实例状态提升到 target，同模型的实体又会互相污染。
- **跨纹理复用准备资源，main 与 arm 各自独立编译**：跨纹理复用省去重复解码、AOT 编译和类加载开销，main 与 arm 分离则增加冷准备和默认模型驻留成本。解释路径只绑定实际消费的表达式，代价是名称目录驻留、私有数组扩容与局部清理、动态分派以及首次读取函数体的开销，见[解释语义](molang-interpreter.md)。
- **准备期编译、执行期直接运行已验证 module**：消除热路径动态符号查找；实际帧成本与分配开销以[集成验证](../../verification/molang-declarations.md)实测为准，当前证据范围见[动画问题](../../status/known-issues/animation.md)。
- **保持 GUI 与 FULL 用途隔离**：同一模型可能同时存在解释与 AOT 两种执行绑定；两者只共享底层独立动画输入，不合并执行状态或驻留寿命。

## 实现与验证入口

[Molang 落实关系](implementation/molang-runtime.md)定位编译身份、binding、效果轮和资源上限；[短期执行落实](implementation/molang-runtime.md#privileged-ingress)定位冻结输入、独立编译与原子交付。实体生命周期失败的收尾逻辑见[实体绑定](implementation/entity-and-frame-state.md#绑定与切换)。
