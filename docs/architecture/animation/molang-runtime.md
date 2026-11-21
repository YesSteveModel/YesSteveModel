# Molang 运行设计

上级契约：[动画](overview.md#seam-contract)。

## 职责与边界

本页细化[动画求值契约](overview.md#seam-contract)中的产物准备、实体表达式求值与效果交接。

Molang 是模型的创作与导入脚本语言，客户端按用途分为两条执行路径：普通 GUI 独立准备并解释执行当前实际消费的表达式；FULL 世界实体、第一人称手部与中央完整动画预览则完成全量静态检查并生成 AOT 字节码。两条路径共用同一套 `MolangContext`、冻结声明和实体效果出口，实例状态按实体严格隔离。源字段定义见 [Model Schema](../../standards/model-schema/assets-and-validation.md)；AOT 静态定型、GUI 动态语义与 Java 扩展接入分别由[静态语言](molang-static-language.md)、[解释语义](molang-interpreter.md)和[声明契约](molang-declarations.md)定义。

## Seam Contract

| 消费者问题 | 契约 |
|---|---|
| 资源准备方何时能交付共享 store，实体绑定还需什么？ | [共享产物准备与准入](#共享产物准备与准入) |
| 实体求值轮如何借用输入、绑定调用并提交效果？ | [Invocation 与效果](#invocation-与效果) |
| command、watch 或网络执行如何独立编译并交回原 owner？ | [短期执行](#短期执行) |

### 共同规则

适用范围：[共享产物准备与准入](#共享产物准备与准入)、[Invocation 与效果](#invocation-与效果)和[短期执行](#短期执行)均继承本节的共同规则，涵盖内容信任、共享与私有状态边界、固定许可、函数深度终止、组件故障分类及证据限制。具体接纳、执行借用与关闭责任由各契约分别规定。

#### 编译与执行分离

`AnimationStore` 统一持有共享的动画数据、定义以及 AOT 或解释运行资源；实体从选定的 store 获取执行借用，同时在自身隔离组件、执行实例、Roaming 值与成员及 controller 状态。解释环境只共享只追加的名称目录，安装时从所属环境创建 Roaming 状态，再与同环境的程序和实例配对。共享资源的唯一所有权与退休见[客户端资源](../model-management/client-resources/overview.md#动画资源所有权与退休)。

#### 内容信任与预算

模型与远端内容只能提供脚本文本来源，不能指定要加载的 Java 类或执行后端。Effect 权限、服务端权威判断与协议输入上限共同约束脚本能力，产品信任边界见[产品边界](../../product-decisions/decisions/trust-boundaries.md)。网络只同步消费者共享语义，不传输本地 `ProgramId`、`SubjectBinding` 或 artifact 摘要。

`ExpressionPolicy` 冻结语言选项、固定许可和用户函数深度上限。编译链不再对输入大小、分析节点数、函数版本数、生成体积或 artifact 数量设置人工硬额度，但编码格式、类型、实际 JVM 表示和取消信号仍严格校验；代码生成器自行负责大方法、初始化、dispatch 与 helper 的物理拆分。运行时只保留默认 32 层的活跃用户函数深度限制：`LOOP` 每次独立四舍五入并截断至 1024 次，`FOR_EACH` 不截断。GUI 直接解释时的深输入与取消边界见[解释语义](molang-interpreter.md#实例驻留与失败)。

函数深度终止时会保留上限值（maximum）、阶段（phase）和顶层来源；真实的后端或表示失败仍拒绝整个候选。语言选项、调用 scope、引用 ABI、可空子环境、root 与 current 语义、资源绑定及 `LOOP` 语义均计入兼容摘要，旧产物一旦与当前策略失配即触发重建。

#### 组件故障分类

| 失败阶段 | 结果 |
|---|---|
| Component 构造或生命周期异常 | 拒绝私有候选或结束当前使用，再交由实体绑定 owner 降级；不冒充成员调用结果 |

调用局部恢复、presence、重载和对象写入分别由[AOT 静态语言](molang-static-language.md)与[GUI 动态语义](molang-interpreter.md)规定，公共作者值域与权限由[共同声明](molang-declarations.md)定义，声明注册表冻结与类级资格见[扩展接入设计](../integration/overview.md#扩展接纳与声明发布)。

资源获取与取消规则见[资源所有权](../model-management/client-resources/implementation/resource-ownership.md)。实体私有状态的独占范围与 GUI 许可由[实体模型使用契约](entity-lifecycle.md#执行权与任务交接)确立，实时 Minecraft 与可选模组 provider 的外部输入不在该独占保证范围内。集成与成本测量方法见[Molang 声明验证](../../verification/molang-declarations.md)，当前线程、联动与实机验证范围见[动画问题](../../status/known-issues/animation.md)。

### 共享产物准备与准入

**边界与权威**

本契约在内容与动画集准备方和 target、实体绑定消费者之间交付共享产物，继承[共同规则](#共同规则)。下述完整程序清点（inventory）、静态检查、module、report 与 replacement 规则适用于 AOT 路径；GUI 的单表达式准备、来源获取和动态失败由[解释语义](molang-interpreter.md#独立准备与来源)负责。

Main 与第一人称 arm 各自独立完成程序清点、module 编译、普通变量布局以及动画与 controller 绑定。公共函数在所属调用集合内解析，body 专属事件由 body 分发；默认补全使用独立的来源命名空间，不会被模型本地的同名函数覆盖。

**前置条件与输入**

AOT 路径必须完整校验所选 target 或集合的全部输入，不会按实体用途省略；GUI 路径则校验实际消费程序的全部结构，未消费的函数体按需获取。默认资源提供的已验证定义和函数仍在当前模型组合内参与解析。

Artifact 身份（artifact identity）覆盖内容与动画集、配置、ABI、冻结的 host 与特化信息，还包含声明、语言语义以及生成和执行兼容内容的确定性摘要。源码摘要会把类型转换、比较和类型合流的实现一并纳入预期身份：即使脚本源码、Java 签名和版本号都未变化，只要语义实现变了，旧 artifact 就会失配并沿既有缓存路径重编。[开发期 Molang 契约](../../product-decisions/decisions/model-compatibility.md#bcdevelopment-molang-semantics-use-current-contract)不维护旧语义的兼容执行分支。

#### 执行用途与主体资格

模型卡片、纹理候选和预览图生成采用独立解释路径，保留 GUI 初始化、controller、展示动画与 fallback 选择。GUI 可按名称读取所属动画集的输入，实际执行仍受展示规则约束；它不做编译闭包或整集定型，也不准备第一人称 arm。中央完整动画预览、世界玩家与第一人称手部继续使用 FULL 与 AOT 路径。

执行用途和主体资格是两个正交维度：GUI 卡片等使用 `FakePlayer`，世界玩家和中央 FULL 预览使用 `AbstractClientPlayer`，intrinsic default 固定使用 `LivingEntity` 且仅驻留 FULL（普通 `builtin:default` 仍有独立的 GUI 路径）。两条路径使用同一份冻结声明与主体门禁，解释准备不会放宽资格。AOT 可以基于已知的根主体类型在编译期简化静态调用，但箭头进入的子主体（child）仍保留动态资格检查。

GUI 路径的选择不因 FULL 资源是否已加载而变化。GUI 直接消费独立动画输入并在内存中建立程序，不会去探测或借用 FULL 的字节码、inventory、实例或私有状态；两者只按各自身份共享底层动画输入。AOT 绑定与解释绑定各自独立，GUI 不生成 AOT 产物，也不设持久化解释缓存分区。每个实体始终创建私有的实例、controller 和播放状态。

**结果与保证**

每个模型独立完成函数解析、静态语义推导、类型与槽位布局、链接和后端代码生成。完整 inventory 逐一保留每个程序来源；在同一次编译中，只有完整程序、签名、固定许可和 fallback 契约完全一致的入口才会共享分析与执行 entry。`ProgramId` 标识原始来源，`entryId` 标识完整执行契约，两者都仅在当前产物内有效。这种共享只复用编译代码，不缓存求值结果或实体状态。冻结的 registry、host 与 binding 均进入产物身份，准备成本测量见[集成验证](../../verification/molang-declarations.md#成本测量)。

冷路径（cold）负责编译并验证 artifact；暖路径（warm）只验证、加载和绑定，不再重新分析源码。AOT 持久执行阶段不会再解析源码或按名字查找内建声明。

#### 完整准备与准入

Factory 必须完成验证、加载、绑定并核查整个 target 的 Roaming 声明并集后，才会交付只读的准备对象。准备阶段将每个来源绑定为包含 `sourceId`、`entryId`、`shape` 和 `generation` 的不可变引用，冷编译、内存缓存和磁盘加载走同一套流程；烘焙后的动画与事件位置继续保留来源编号。实体安装前仍须针对真实 subject 构造组件与根实例，不能把共享准备成功等同于实体安装成功。类级声明资格在启动时冻结，单个 owner 的本地失败不会修改全局资格。Intrinsic default 的[函数投影](../model-management/client-resources/default-model.md#边界与权威)同时约束 module 绑定与短期编译；普通模型的默认补全在接收环境中编译，不会从源码中删掉被排除的函数引用。

普通模型允许使用包含 program replacement 的完整 module；而内建模型（builtin）接纳时要求 replacement 集合为空。局部 `UNSET`、动态门禁返回的 missing、合法的 presence 观察或对象投影都不算整程序 replacement。Catalog、常量表、生成类与 report 来自同一份有效编译计划，诊断文案本身不作为替代与否的判定依据。

冷编译、暖加载和调试视图读取的是同一份不可变 report。调试面板的展示记忆只绑定当前启用范围、选中 target 和资源 generation，既不进入 artifact identity，也不反向持有实体或资源引用。

**所有权与时序**

共享准备 pin 将 GUI 与 FULL 各自的 store 纳入 target 保留范围；再次访问同一个 store 时直接复用已准备资源。实体只负责归还自己的执行借用，共享资源的统一退休由[动画资源所有权](../model-management/client-resources/overview.md#动画资源所有权与退休)决定。

**失败与拒收**

| 失败阶段 | 结果 |
|---|---|
| 符号缺失、callable 参数数量不符、首个边界参数类型不匹配 | 当前节点局部恢复为普通 UNSET；废弃实参不执行，report 保留主因 |
| 独立的 binding、类型或 shape 错误 | 持久入口改用整程序 fallback，临时入口直接拒绝；废弃子树不污染最终约束 |
| 结构损坏、实际表示超限、生成或后端异常、准备取消、artifact 或 linkage 失败 | 拒绝整个 target 候选，不发布残缺资源，也不伪装成模型归零 |

组件故障按[共同分类](#组件故障分类)交给实体绑定 owner 处理；执行期的返回违约由[Invocation 与效果](#invocation-与效果)接纳。

**证据与依赖**

具体的代码生成、缓存、绑定与报告流程见[运行实现](implementation/molang-runtime.md)，作者声明验证与成本测量见[Molang 声明验证](../../verification/molang-declarations.md)。

### Invocation 与效果

**边界与权威**

本契约协调实体逻辑 owner、AOT 或解释执行入口、声明实现与领域效果接收方之间的一轮完整求值，继承[共同规则](#共同规则)。

实体私有执行态严格遵守[执行权与任务交接](entity-lifecycle.md#执行权与任务交接)。Worker 运行期间独占 runtime，主线程只持有待交接句柄而不读取更新态。GUI 指定任务可以在许可内执行内部安装与迁移；其余安装以及 reset、reload、retire 仍必须在主线程完成原任务交接后执行。引擎内部的 instance 或 access 活跃检查只作防御，不能替代宿主执行权交接或跨实例禁止重入的约束。

安装候选阶段只构造私有实例和组件，外部查询仍看到旧安装；交接后正式发布才会撤销旧资格。Worker 里的执行失败不会直接改变实体生命周期，必须由主线程接纳失败后再结束该精确安装。同步绘制回调允许立即撤销消费资格并延后释放，但普通求值过程禁止重入任何生命周期入口。即使是同一个 module 的重新安装也会更换安装资格，仅兼容纹理替换才会保留执行态并只撤销几何输出。

**前置条件与输入**

#### 调用许可与预算

实体安装与执行权共同保证 `context`、`binding`、`generation`、`shape` 和参数的正确配对。每条顶层表达式开始前绑定自身 program 与位置、重置函数深度，并把当次宿主动作窗口与 entry 或 layout 上限取交集，作为本次固定的 ACTION 许可。被调的内部函数与 Java provider 共用这份许可和函数深度，不重复核验生命周期。后续的只读入口会重新绑定自己的上限；短期入口也不会继承前一入口的许可。许可产品契约见[固定表达式许可](../../product-decisions/decisions/animation-isolation.md#bcexpression-action-permission-is-fixed)。

**结果与保证**

声音、粒子和骨骼修改会直接提交给对应领域 owner；`ysm.sync` 则在当前借用退出后于本地排空执行一次，再独立尝试网络上报。后续表达式失败不会回滚已经发生的动作。四个 controller 控制动作仅在 `CodedAnimationController.process` 的同步选择链内、player 更新前生效，且窗口在 player 消费前关闭。本地 sync 登记时会复制参数、来源和精确安装身份，等待源 context 与组件借用全部退出后，再按入队顺序以新的 ACTION 入口交付。函数深度终止不会丢弃已登记的正常载荷，但普通实例异常、严重退出或安装被拒会清空未交付载荷。

Roaming 赋值不受阶段或 ACTION 许可限制，即使在 observation 或禁止 ACTION 的入口中也能本地写入（被拒的 ACTION 调用仍不求参）。Producer 写入仅标记 dirty，采样和同步消费沿[Roaming 同步](implementation/state-inputs-and-sync.md#roaming)的既有窗口进行。

**所有权与时序**

一次完整的逻辑求值称为一轮（round），包含组件生命周期回调以及 pre、main、post 阶段的表达式执行。源轮复用同一个 `MolangContext` 和执行帧（frame）；解释实例复用根求值外壳，每条表达式结束时清除临时借用与词法状态，同时保留普通变量、Roaming、主体样本及动画与 controller 选择。箭头派生出的子环境只切换查询对象，公共业务 view 直接借用静态选定的源载体或当前载体，不逐条表达式或逐个 provider 创建 wrapper。业务 view 只同步借用 subject、只读轮输入和专用 `defer` 登记能力，不暴露入口执行、预算或 reset，签名与借用约束见[声明边界](molang-declarations.md#公共业务-view)。

Provider 不得直接或经 helper 同步重入任何持久或临时执行入口（包括其他实例、其他帧或其他实体）。实体执行权已经保证同一 owner 串行，生产环境的 context 不维护线程检查、活跃位或入口配对防御。成功进入的表达式在 `finally` 中清除许可和函数深度，再解除 program 绑定。若发生失败，先组装独立的顶层来源与原 `Throwable`；整轮输入与采样位置由轮级 owner 在结束时释放。

`defer` 通过专用登记入口复制已求值的有限 F32 参数和来源，并绑定当前安装及动画上下文。上下文退出时按登记逆序逐个调用 handler，每个 handler 各自建立新的许可和函数深度：遇到函数深度终止只跳过当前 handler，遇到普通异常则中止剩余 handler 并丢弃未交付载荷。Handler 不继承 coded-controller 窗口。单轮累计最多登记 64 次以限制递归登记，超限按实例异常终止。

**失败与拒收**

#### 当前主体与源执行

`SUBJECT`（当前主体）与 `ORIGIN`（源主体）的归属在冷编译期即固定，源安装负责建立源 context、binding 与精确 receiver。有效箭头派生子环境时，捕获一次目标实体的完成样本和远端玩家投影，右侧表达式（RHS）全程复用这组快照。源变量槽、用户函数、ACTION 许可、函数深度、执行位置、`defer` 和诊断信息都不迁入目标。子环境不会访问目标的 capability、runtime、component 或 hook，也不会等待目标任务。

每次入口在 producer 与 `beginEval` 完成后、首条表达式运行前绑定本轮私有输入；主轮在推进原历史之后捕获。根环境读取本轮私有样本，自指向箭头（self-arrow）也读取上一轮完成样本。缺失样本的历史查询返回 missing，而直接的装备查询不要求目标安装模型。整轮结束在 `finally` 中统一解除 context、binding、样本、投影、view 与延后借用。样本的发布与清理见[完成样本](entity-lifecycle.md#完成样本的发布与结束)。

#### 求值失败

| 失败阶段 | 结果 |
|---|---|
| 合法调用被 subject 或 ACTION 门禁拒绝 | 返回声明类型的 missing 或 void no-op，不求参，静态分析保留调用 scope |
| 活跃用户函数深度超限 | 截断当前顶层表达式，四种 shape 分别返回 `0`、`false`、`UNSET` 或 `void`；重置函数深度后继续下一条表达式以及正常的 sync 与 defer |
| 其他普通执行异常 | 结束当前实例本轮求值，清理未交付载荷，交由原实体 owner 降级；已发生的动作保留 |
| 合法 missing、可空或装箱 `null`、`String` `null` 归一 | 属于正常值语义，不触发实例失败或编译 replacement |
| 严重 Error 或必要清理失败 | 沿用既有严重错误分类。AOT 的用户函数与入口清理保留原有优先级，不让普通主失败遮蔽 `VirtualMachineError` 或 `ThreadDeath`，原主失败作为 suppressed 保留；解释路径的必要窗口由 `finally` 退出，清理失败按退出顺序直接传播，不构造聚合协议 |

组件构造与生命周期异常走[共同分类](#组件故障分类)，不冒充成员调用结果。

执行期诊断只保留顶层来源、owner、generation 与原 `Throwable`，AOT 与解释路径均不维护精确的被调函数或 AST 节点轨迹。解释路径内部直接展开异常，宿主在顶层出口统一报告一次并分类。函数深度诊断保留上限值和阶段信息，不会写成实例终结。普通异常统一归类为 `INSTANCE_EXECUTION`，宿主不扫描异常 cause 链，也不为单条表达式建立黑名单。生产代码信任 provider 关于 non-null、有限值和不可变的承诺，出现任意 NPE 均走普通异常出口。默认模型自身异常不递归降级，OOM 维持既有隔离边界，失败记忆与安全交接见[实体绑定实现](implementation/entity-and-frame-state.md#绑定与切换)。

**证据与依赖**

实体执行权由[实体模型使用契约](entity-lifecycle.md#执行权与任务交接)确立，binding、即时动作与延迟捕获的落实见[运行实现](implementation/molang-runtime.md)，作者 view 的精确签名与借用责任见[声明边界](molang-declarations.md#公共业务-view)。

### 短期执行

**边界与权威**

本契约规范客户端本地 command 与 watch 如何将独立编译出的短期程序交回原实体 owner，再借用当前安装执行。本契约继承[共同规则](#共同规则)，执行期同样遵守[Invocation 与效果](#invocation-与效果)的同轮借用和失败规则。

**前置条件与输入**

本地 command 与 watch 依赖当前 target generation、`SubjectBinding` 以及冻结布局的短期 lease，并在同一实体执行窗口内运行。临时程序不能扩展持久变量槽、提升静态表示或反向重编模型，这类请求在调用 provider、写入状态或提交动作前即被拒绝。

**结果与保证**

Debug、watch 与 execute 均沿用正常的持久写语义，结构读写与调用授权以及 `RoamingRole` 依然有效。`EXECUTE` 可申请 `ACTION` 权限，`WATCH` 的入口上限为 `CONTEXT`，两者均与本次短期宿主窗口取交集。Pre、main、post 阶段的短期任务共用所在求值轮的 context 与首次失败状态，不再开启嵌套轮。在 `CONTEXT` 下触发的随机或物理推进属于正常 owner 状态变化，不会复制或回滚任意组件。

**所有权与时序**

主线程通过当前 store 的执行借用冻结编译输入，并为可能越过当前安装寿命的编译工作取得独立保护；后台线程仅基于冻结输入编译独立程序。主线程在下一次准备窗口复核当前精确安装仍有效后，才挂载（attach）access。编译结果交付与关闭 inbox 时以原子方式转移关闭责任，迟到结果由编译方自行关闭。短期 program lease 与 access 各自独立关闭；即使新 generation 的内容哈希相同，也不能复活旧资格。

**失败与拒收**

独立的 binding、类型或 shape 错误按[共享准备准入的失败分类](#共享产物准备与准入)直接拒绝临时入口；权限与运行时失败沿[求值轮失败](#求值失败)处理。后台编译成功本身不会恢复已经失效的安装资格。

**证据与依赖**

具体入口、静态表示交接与资源上限见[短期执行实现](implementation/molang-runtime.md#privileged-ingress)，原 owner 的执行权与终态见[任务交接](entity-lifecycle.md#执行权与任务交接)。

## 设计取舍

- **在 target 层共享编译产物，在实体层隔离运行实例**：如果把编译下放到每个实体，会成倍复制代码生成、classloader 和失败状态开销；反过来如果把实例状态提升到 target，同模型的实体又会互相污染。
- **跨纹理复用准备资源，但 main 与 arm 各自独立闭合**：跨纹理复用省去了重复解码、AOT 编译和类加载开销，而 main 与 arm 分离会增加冷准备和默认模型的驻留成本。解释路径只绑定实际消费的表达式，相应代价是名称目录驻留、私有数组扩容与局部清理、动态分派以及首次读取函数体的开销，见[解释语义](molang-interpreter.md)。
- **准备期编译、执行期直接运行已验证 module**：这消除了热路径的动态符号查找，但不能仅凭架构推断帧预算、零分配或必然提速，实际成本仍需按[集成验证](../../verification/molang-declarations.md)实测，当前证据范围见[动画问题](../../status/known-issues/animation.md)。
- **保持 GUI 与 FULL 用途隔离**：同一模型可能同时存在解释与 AOT 两种执行绑定；两者只共享底层独立动画输入，不合并执行状态或驻留寿命。

## 实现与验证入口

[Molang 落实关系](implementation/molang-runtime.md)定位编译身份、binding、效果轮和资源上限；[短期执行落实](implementation/molang-runtime.md#privileged-ingress)定位冻结输入、独立编译与原子交付。实体生命周期失败的收尾逻辑见[实体绑定](implementation/entity-and-frame-state.md#绑定与切换)。
