# 共同 Molang 声明契约

本页定义内建与公共 API 的 Molang 扩展声明签名、调用归属与公共值规则，涵盖 `api.annotation` 与 `api.molang` 下的公共 Java 表面。下游各阶段由对应专题分别负责：AOT 调用解析归[静态语言](molang-static-language.md#调用解析与局部恢复)，GUI 动态筛选归[解释语义](molang-interpreter.md#冻结-java-调用)，类级资格与冻结归[扩展设计](../integration/overview.md#扩展接纳与声明发布)，组件实例安装归[实体 owner](implementation/entity-and-frame-state.md#绑定与切换)。

## 作者签名

公共扩展使用 `@YsmEntityComponent(value, modId, dependencies, mode)` 声明组件类，用 `@MolangFunction(value, namespace, alwaysOrigin, action, minArgs, maxArgs)` 声明函数。内建实现使用 `com.elfmcys.ysm.expression.annotation` 包中的编译期注解 `@BuiltinComponent(value, dependencies)` 和 `@BuiltinFunction`。`@BuiltinFunction` 必须指定 `value`。两种函数注解的 `alwaysOrigin` 和 `action` 都默认为 `false`，分别对应当前主体和普通上下文调用。参数个数限制中的 `-1` 表示未指定。声明只导出函数，脚本中对外部函数的无括号访问由[静态语言](molang-static-language.md#调用解析与局部恢复)统一处理。

### 命名与身份

- **命名空间与短名**。成员函数默认继承类的 `value` 作为 namespace，填写非空的 `namespace` 可以覆盖它。内建类按职责组织，归并跨命名空间的函数时用成员级 `namespace` 保留原有的脚本导出名。内建函数的短名取自 `@BuiltinFunction` 的 `value`。公共扩展省略 `value` 时，短名取 Java 方法名按 `Locale.ROOT` 转为小写（不插入下划线）。例如 `weapon` 类中的 `isReady` 默认导出为 `weapon:isready`，使用 `@MolangFunction("is_ready")` 则导出为 `weapon:is_ready`，脚本调用写作 `weapon.is_ready`。
- **格式与保留字**：namespace 须匹配 `[a-z][a-z0-9_]*`，短名须匹配 `[a-z_][a-z0-9_]*`。显式值不会自动去空格、纠正大小写或拆分完整符号。公共类的默认和成员覆盖均不得使用保留前缀 `ysm`、`math`、`query`、`ctrl`、`tlm`、`fn`、`v`、`c`、`t`。内建声明的可信资格来自内部来源，而不是名字本身。
- **与组件身份解耦**：namespace 只决定导出名，不代表模组或组件身份。`modId` 留空时由接纳器从来源文件自动推导。同一个声明类可以导出多个 namespace，同一实体 owner 下仍只持有该类的一个组件实例；反过来，不同声明类即使共用同一个 namespace，也不会共享组件状态。

### 类、构造器与参数形状

公共静态函数不要求实现组件接口或提供构造器。成员声明则必须来自 `EntityComponent<E>`，且在完整继承泛型替换后，上下文 view 的泛型 `E` 必须与组件的 `E` 精确相等；静态函数独立从 view 推导 subject 类型。同一个类里可以同时声明静态方法和成员方法，但接纳资格按整类统一判定。

导出实例函数的类需要为每个匹配的 owner 创建组件实例。内建类即使只导出静态函数，只要覆写了任一 `EntityComponent` 生命周期钩子，也会创建组件实例，以便为静态查询发布采样。继承的覆写同样生效。公共扩展按是否导出实例函数决定是否实例化。

需要实例化的类必须提供 `public Foo(AnimatableEntity<E> owner)` 构造器。注解处理器会校验泛型替换后的 owner 类型，运行时也会核验真实构造器与 helper。同一个 subject 若有两个 owner，各自独立构造组件实例。

| 形状 | 可选 context 后的 Java 参数 | 脚本 arity |
|---|---|---|
| FIXED | 固定参数 | `min=max=F` |
| OPTIONAL | `int argc`、固定参数及连续尾部默认参数 | `min=F-D`、`max=F` |
| VARIADIC | `int argc`、固定前缀及唯一类型化尾数组 | 省略 min 时取 F；显式 `min>=F`；显式有限 `max>=min` |

上表中 `F` 表示固定脚本参数个数，`D` 表示尾部默认参数个数，并遵守以下限制：

- 显式声明的 FIXED 或 OPTIONAL arity 只能等于推导值；VARIADIC 不能与默认参数混用；除 `-1` 外的负数、自相矛盾的范围或形状都会被拒绝。
- VARIADIC 未指定 `max` 时使用实际参数表示，不以 255 作为脚本参数上限，但真实的 JVM 参数槽限制仍独立校验。
- `argc` 表示脚本实际传入的参数个数，不计入 view、`argc` 自身或补齐的默认值；合法的空变长尾部会传入空数组。

固定参数与变长尾元素的 Java 载体只允许 `boolean`、`int`、`float` 和 `String`，不接受装箱类型、record、语言数组、`Object`、union 或 `SoundId`。脚本侧的值按[统一标量消费](molang-static-language.md#对象-presence)转换到 `boolean`、`int` 或 `float`（AOT 在编译期固定转换路径，解释路径在实际消费时转换）；放宽脚本来源接纳不会扩大 Java 载体集合。默认字面量在声明期校验类型和有限性，`int` 默认值保留精确整数。只有被省略的参数才会补默认值，显式传入的 `UNSET` 按 Java 消费规则转换。

### 重载与候选选择

同一个声明类里的同名符号组成一个重载组，组内共享 scope、subject、静态或成员属性、effect、逻辑结果类型以及可能缺值标记。未标注的 `float` 和 `int` 视为必有值 F32；标注 `@MolangNullable` 的 `float` 以及 `Float`、`Integer` 视为可能缺值的 F32。View 注入不计入脚本参数签名，不同参数签名的重载可以混用有或无 view。如果 `int` 与 `float` 规范化后出现重复参数签名，直接拒绝。跨类的同名符号按整类冲突拒绝，不构成重载。

重载选择在两条路径上采用一致的优先级：AOT 冷编译先匹配精确的实参类型，再匹配合法的标量转换；逐个实参比较时，精确匹配优于转换，如果某个候选在所有参数上都不逊于另一个候选且至少在一项上更优，就淘汰较差者；互有优势的候选视为并列，并列时选取规范 JVM 成员签名最小者。默认值与变长尾的重叠以及零实参都没有额外优先级，预期返回类型、参数具体数值、权限门禁和声明发现顺序也不参与重载选择。解释路径在同一份冻结候选内按当前实际值类型筛选，逐参数失败及无类型缺值规则见[动态调用](molang-interpreter.md#冻结-java-调用)。`String` 参数只接受 `String` 或普通 `UNSET` 的 Java 消费。

## 调用归属

函数注解上的 `alwaysOrigin` 决定主体资格和执行环境。

- `alwaysOrigin = false` 对应 `SUBJECT`。只接纳静态方法，按当前目标 subject 判断资格并注入当前查询环境。
- `alwaysOrigin = true` 对应 `ORIGIN`。接纳静态方法和成员方法，按源实体 subject 判断资格并使用源 receiver 与源上下文。成员方法 view 的泛型 `E` 仍须与组件的 `E` 精确匹配。

`action = true` 对应 `ACTION`，要求本次入口授予动作许可。`action = false` 对应 `CONTEXT`。

调用按固定顺序执行：先检查固定 ACTION 权限和选定 scope 的 subject 资格，通过后再在当前环境中从左到右求参，最后执行目标方法（AOT 在冷编译期已定死成员，解释路径在冻结候选中按当前实参类型选定）。权限或资格被拒时直接返回缺值或 no-op，不求参、不构造变长尾数组，也不调用 provider。在箭头表达式内部调用 `ORIGIN` 动作只会修改源 owner，但实参表达式仍可读取箭头目标。目标子环境（child）的资格由源冻结声明环境针对目标判定，不会借用目标的 binding 或组件。

按职责划分，普通实体属性、装备、附魔和直接的可选模组查询属于 `SUBJECT`；采样位置、方向、control 和 Melody 查询读取当前选定的完成样本，远端玩法值读取目标投影；模型与时间线、用途、随机、物理、骨骼以及动作则属于 `ORIGIN`。公共 view 不会暴露目标的执行权、资源句柄或写能力。

## 公共业务 view

声明方法可以省略上下文参数。如果需要注入，首参必须精确声明为 `MolangContextView<E extends Entity>`，不接受 raw 类型、通配符、未确定边界的泛型、作者自定义子接口或内部 context 类。对于 OPTIONAL 与 VARIADIC 方法，`int argc` 紧跟在可选 view 之后；FIXED 方法则直接接收脚本参数。无 view 的静态方法默认 subject 为 `Entity`，成员方法的 subject 仍为组件泛型 `E`。省略 view 不会改变 `CONTEXT` 或 `ACTION` 权限、subject 校验或 receiver 门禁，也不赋予函数纯性。

View 只暴露四项受限能力：`subject()`、只读 `subjectSample()`、`inputs()` 以及专用的 `defer(float delay, float... values)`。其中 `MolangInputs` 始终来自源实体（origin），提供采样时间、controller 与动画的只读值以及不透明的 target 身份（只读 target 不授予修改 controller 的权限）。View 不暴露 processor、资源服务、program、预算或生命周期控制。

查询与动作的业务实现归属于声明类和原领域 owner。声明类可以在 `beginEval` 借用 event，并在 `endEval` 解除借用；新增查询或动作无需去扩展公共 context 方法、中央符号表或效果分类表。随机、物理、骨骼和声音状态继续由各自的既有资源 owner 持有。

普通动作由声明类直接调用对应领域 owner。如果有工作需要逃逸出本次同步调用，必须复制载荷并取得自身所有权，不能继续持有借用的 context 或 event。`defer` 专门用于登记复制后的有限 F32 参数与来源，其逆序排空和安装拒收规则由[运行设计](molang-runtime.md#invocation-与效果)定义。

AOT helper 与解释调用计划在同一轮求值中将同一个载体直接交给 provider，不逐次构造 adapter。Provider 只能在本次同步调用内借用 view，不得缓存、跨线程异步使用，也不得强转获取内部实现。保持 subject 只读属于作者契约，Java 接口本身不充当 JVM 沙箱。

ACTION 权限由 AOT 生成代码或解释调用计划在求参前统一核查（含固定入口许可与 subject 资格），被拒时返回对应类型的 missing 或 no-op。安装阶段保证实际适用调用拥有精确匹配的 receiver，因此生产热路径不再重复做 receiver 防御检查；`defer` 专用登记入口则保留 ACTION 检查。生产读取同样信任同步借用契约，不逐次检查线程或活跃位，由开发期审计捕获借用误用。

## 数学 intrinsic

`MathDeclarations` 维护 14 个脚本可调用的数学函数名与引擎闭集运算之间的映射。参数个数、参数消费方式和结果类型均直接由闭集运算派生，registry、补全发现和默认源验证共用同一份声明。数学函数没有 Java provider 声明，也不参与组件资格判定。随机函数仍作为普通声明存在，`math.pi` 仍作为常量处理。

合法的数学调用在完成局部恢复后会被降级（lower）为 intrinsic：实参依然保持从左到右、求值一次以及原有的标量消费规则。数学运算本身不增加 context-state effect，也不经过 subject、ACTION 或 provider 门禁。运算保证返回有限且必有值的 F32，定义域外或溢出时归为有值的 `+0`，有限结果保留原始 bit 位。数学结果不会触发 `??` 右侧；实参求值失败或链接失败仍按入口失败规则隔离。

Intrinsic 资格完全取决于是否属于引擎闭集，与方法是否 static、所在 namespace 或注解无关；普通声明省略 view 也不会变成 intrinsic。底层 Portable MATH 的结构错误与数值边界维持原规则。

## 组件生命周期

| hook | 调用契约 |
|---|---|
| `setRenderTarget(target)` | 首次绑定及实际 target 对象改变时更新资源关联；不清历史，不关闭 target。候选阶段必须使用显式传入的 `target` 参数，此时 owner getter 仍可能指向旧安装。 |
| `tick(previousTick, currentTick)` | 首次调用使用零跨度；之后仅在 owner 接纳新实体 tick 时调用一次，跳过多个 tick 时不补调。 |
| `update(previousTime, currentTime, partialTicks)` | 首次调用使用零跨度；之后仅在主动画时间前进时调用，时间单位为 tick，限频恢复后一次消费累计跨度。 |
| `beginEval(event)` | 每次完整求值（包括 observation）前借用 event，不得在此推进持久历史或消费事件。 |
| `endEval()` | 对每个成功 `beginEval` 的组件恰好调用一次，清除临时借用；调用它不代表求值成功，也不负责提交效果。 |

Owner 在原有的串行执行窗口内同步调用上述 hook，不承诺运行在 Minecraft tick 线程。组件不得异步保存 event、修改 event，也不得把可变的 `codedController` 缓存为整轮求值的固定输入。若 `beginEval` 自身抛出异常，组件必须自行清理尚未移交的借用；holder 只会对已成功的前缀按逆序调用 `endEval`。构造器或 hook 失败按安装隔离，不会撤销类的全局声明资格，具体结束流程见[实体 owner](implementation/entity-and-frame-state.md#绑定与切换)。

## 值与 presence

| Java 返回 | 值规则 |
|---|---|
| `boolean` / `int` | 必有值 BOOL 或 F32，包括 `false` 和零 |
| `float` | 默认保证有限且有值，保留 ±0；标注 `@MolangNullable` 后 `NaN` 与 ±Infinity 视为类型化 missing |
| `Float` | 有限值为有值 F32，保留 ±0；`NaN`、±Infinity 和 `null` 视为类型化 missing |
| `Boolean` / `Integer` | `null` 视为对应标量的类型化 missing，非 `null` 保留原值 |
| `String` | Java `null` 归一为空串，其余内容原样保留 |
| 精确平面 record `R` | 默认 non-null；仅标注专用 `@MolangNullable` 时允许 `null` 作为同 schema 的 missing |
| 精确类型 `Entity` | 身份引用；标注专用 `@MolangNullable` 时允许 `null`，否则作者保证 non-null |
| `List<Entity>` | 只读引用列表；容器与元素均不可为 `null`，发布后不可修改 |
| `List<String>` / `List<R>` | 精确元素 schema 的只读数组；容器、元素及记录字段均不可为 `null` |
| `it.unimi.dsi.fastutil.floats.FloatList` | 唯一的数值数组载体 |
| `void` | 不产生返回值 |

返回类型还须满足以下限制：

- **平面 record**：必须是 `public`、非泛型，字段只允许原始 `boolean`、`int`、`float` 或非 `null` 的 `String`；构造器和 accessor 必须是稳定且无副作用的数据操作，不要求支持全零构造。
- **不接纳的类型**：嵌套 record、record 内的数组字段、`List<Float>`、`List<Integer>`、`List<Boolean>`、Java 原生数组返回、其他集合类型、raw、通配符或未确定边界的 `List`、`long`、`double`、自定义 codec、可选依赖的自有载体，以及 `Optional`、`OptionalInt`、`OptionalFloat`、`OptionalBoolean`。
- **专用可空注解**：只有 `com.elfmcys.ysm.api.annotation.MolangNullable`（`RUNTIME`、`METHOD`）有效，且仅允许用于原始 `float`、精确 record 或精确 `Entity` 返回值。同名或第三方 `@Nullable` 无效；给其他返回种类加此注解直接拒收；该注解也不允许修饰字段、集合元素或参数。

在语义上，空列表、空串、全零 record 和零向量都属于“有值”（present）：空列表和空串转为标量时得 `0` 或 `false`，全零 record 和零向量转为标量时得 `1` 或 `true`。Missing 与普通 `UNSET` 消费为 Java `boolean`、`int`、`float`、`String` 时，分别传入 `false`、`0`、`+0` 和空串。生产代码信任作者关于 non-null、有限值和不可变的承诺，即便调用方丢弃结果也不会额外插入非空检查（解释路径中间算术与实际数值消费的差异见[解释语义](molang-interpreter.md#数值消费)）。装箱类型或 `@MolangNullable` 的 `null` 以及 `String` 的 `null` 维持上述归一规则。脚本层的完整投影规则见[静态语言](molang-static-language.md)。

## 门禁默认表

对于合法的已发布调用，AOT 会在编译期完整确定类型、实参、effect 与存储约束；解释路径在准备期固定声明与权限，实际值类型在消费时核对。运行时严格按 ACTION、subject、求参（含默认值和变长尾）的顺序推进。只要 ACTION 或 subject 门禁拒绝，就按声明返回类型直接返回对应 missing（`void` 为 no-op），不求参、不构造变长数组，也不产生静态恢复或门禁诊断。Context 与 binding、生命周期及精确 receiver 由安装与宿主执行权保障。解释执行中声明方法抛出的真实异常以原 `Throwable` 向上展开到顶层，报告与实例后果按[求值失败](molang-runtime.md#求值失败)处理。

| 结果 | 拒绝结果 |
|---|---|
| 原始或装箱标量 | 同类型 BOOL 或 F32 的类型化 missing，与真实 `false` 或零区分 |
| `String` | String schema 的 missing，与有值的空串区分 |
| record、vector、只读数组或 Entity 引用 | 保留对应 schema 的 missing，与全零 record、零向量、空列表区分 |
| `void` | no-op |

声明本身不配置 fallback。可选参数的默认值只补给脚本中省略的位置；显式传入 `UNSET` 不会触发默认值，`argc` 也保留实际传入数。默认字面量在声明期检查类型与有限性，业务层面的兜底值由模型脚本用 `??` 表达。已获准调用却缺失 receiver 属于宿主违约，不承诺稳定的错误形式。

## 数值与发布后不可变

有限 F32 转为 Java `int` 时采用 Java narrowing 规则（向零截断，溢出时饱和）；`int` 转为 F32 允许正常精度舍入（例如 `16777217` 变为 `16777216f`）。`int` 类型的默认字面量保留精确整数，不先转 F32。`float` 保留 F32 精度和有值的 ±0；非有限字面量在声明和输入阶段直接拒绝。

声音接口通过 `int` 和 `String` 重载接收 ID：数值 `3.9` 经 narrowing 转为 `3`，数字 `0` 表示匿名播放，字符串 `"0"` 则属于独立的字符串命名空间。传入空串仍会命中 `String` 重载，是否接纳由声音业务决定。内部声音 key 不暴露为表达式类型或 codec。向量的 `x`、`y`、`z` 分量均为 F32。

`@MolangNullable` 标注的 `float` 以及 `Float` 会在 Java 调用边界把非有限值解码为类型化 missing；未标注的 `float` 返回则不生成有限性检查。这条规则只作用于函数返回边界，不会传播到 record 字段、getter、类型转换、变量存储或后续算术。Provider 须保证未标注的 `float` 和复合内容有限，并保证发布的 record、容器、元素及底层存储不可再被修改——只读接口类型本身无法证明底层不被篡改。`String`、record 和数组按字段或元素内容判等，`Entity` 按对象身份判等，均不调用作者实现的 `equals` 或 `toString`。可写对象的隔离分别见[AOT 字段写](molang-static-language.md#对象隔离与字段写)和[动态结构体](molang-interpreter.md#平面结构体与字段写)。

在合法 subject、有限实参和已接纳依赖的前提下，provider 应正常返回，正常的业务缺席通过声明的值域（如 missing）表达。随机区间端点相等时返回该边界，反向区间自动交换；整数随机范围在 narrowing 之后计算，宽区间不得因跨度计算而溢出。位置越轴、速度转换溢出和物理发散保持 missing；装备或方块的非法槽位、越界索引或非法资源 ID 按各入口约定返回 missing，而合法的空值、`false` 和零维持有值。作者抛出的异常与真实依赖故障交由框架失败边界处理。

声明校验与借用审计见[集成验证](../../verification/molang-declarations.md#provider-契约审计)。

Java 返回值一律视为不可变的外部来源（Foreign）：AOT 下的对象写隔离由[静态语言](molang-static-language.md)和[执行架构](implementation/molang-runtime.md)实现，GUI 下的平面 Map 值隔离由[解释语义](molang-interpreter.md#平面结构体与字段写)实现。开发期审计用于及早暴露典型的非有限值、非法 `null`、发布后篡改列表及借用误用，并不构成对抗任意恶意 Java 代码的沙箱。Provider 自身的内存分配、列表构造和业务动作开销仍计入实际执行成本。
