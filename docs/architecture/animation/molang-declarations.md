# 共同 Molang 声明契约

本页定义内建与公共 API（`api.annotation`、`api.molang`）的 Molang 扩展声明签名、调用归属、组件生命周期与公共值规则。AOT 调用解析见[静态语言](molang-static-language.md#调用解析与局部恢复)，GUI 动态筛选见[解释语义](molang-interpreter.md#冻结-java-调用)，类级冻结见[扩展设计](../integration/overview.md#扩展接纳与声明发布)，组件安装见[实体 owner](implementation/entity-and-frame-state.md#绑定与切换)。

## 作者签名

- **注解入口**：公共扩展用 `@YsmEntityComponent(value, modId, dependencies, mode)` 与 `@MolangFunction(value, namespace, alwaysOrigin, action, minArgs, maxArgs)`；内建实现用 `com.elfmcys.ysm.expression.annotation` 中的 `@BuiltinComponent(value, dependencies)` 与 `@BuiltinFunction`（必须指定 `value`）。
- **默认值**：`alwaysOrigin` 与 `action` 均默认 `false`（对应当前主体与普通上下文）；参数个数限制的 `-1` 表示未指定。声明只导出函数，外部函数的无括号访问由[静态语言](molang-static-language.md#调用解析与局部恢复)统一处理。

### 命名与身份

- **命名空间与短名**：成员函数默认继承类 `value` 作 namespace，非空 `namespace` 覆盖之（内建类按职责组织，可用成员级 `namespace` 保留原有导出名）。内建短名取 `@BuiltinFunction.value`；公共扩展省略 `value` 时取 Java 方法名按 `Locale.ROOT` 转小写（不插下划线，如 `weapon` 类的 `isReady` 导出为 `weapon:isready`，标注 `@MolangFunction("is_ready")` 导出为 `weapon:is_ready`，脚本写作 `weapon.is_ready`）。
- **格式与保留字**：namespace 匹配 `[a-z][a-z0-9_]*`，短名匹配 `[a-z_][a-z0-9_]*`，不做去空格或大小写自动修正。公共类默认与成员覆盖均不得使用保留前缀 `ysm`、`math`、`query`、`ctrl`、`tlm`、`fn`、`v`、`c`、`t`；内建可信资格来自内部来源而非名称。
- **与组件身份解耦**：namespace 仅决定导出名，`modId` 留空时由接纳器按来源推导。单类可导出多个 namespace 且同一实体 owner 下仅持有一个组件实例；不同类共用同一 namespace 仍隔离各自组件状态。

### 类、构造器与参数形状

- **类与构造器**：静态函数无需实现组件接口或提供构造器；成员声明须来自 `EntityComponent<E>`，完整泛型替换后 view 的 `E` 须与组件 `E` 精确相等（静态函数独立从 view 推导 subject）。同类中可混写静态与成员方法，接纳资格按整类判定。
- **实例化条件**：公共扩展在导出实例函数时实例化；内建类在导出实例函数或覆写任一 `EntityComponent` 生命周期钩子（含继承覆写）时实例化。需实例化的类须提供 `public Foo(AnimatableEntity<E> owner)`，注解处理器与运行时均校验泛型替换后的构造器；同一 subject 的多个 owner 各自独立构造实例。

| 形状 | 可选 context 后的 Java 参数 | 脚本 arity |
|---|---|---|
| FIXED | 固定参数 | `min=max=F` |
| OPTIONAL | `int argc`、固定参数及连续尾部默认参数 | `min=F-D`、`max=F` |
| VARIADIC | `int argc`、固定前缀及唯一类型化尾数组 | 省略 min 时取 F；显式 `min>=F`；显式有限 `max>=min` |

上表 `F` 为固定脚本参数个数、`D` 为尾部默认参数个数：
- 显式声明的 FIXED/OPTIONAL arity 须等于推导值；VARIADIC 不可与默认参数混用；除 `-1` 外的负数或矛盾范围均拒收。
- VARIADIC 省略 `max` 时使用实际参数表示（不以 255 作脚本上限），仍受 JVM 参数槽限制独立校验。
- `argc` 为脚本实参个数，不含 view、`argc` 自身或补齐默认值；空变长尾部传空数组。
- **参数载体**：固定参数与变长尾元素仅允许 `boolean`、`int`、`float`、`String`（不接纳装箱类型、record、语言数组、`Object`、union、`SoundId`）。脚本值按[统一标量消费](molang-static-language.md#对象-presence)转换（AOT 编译期定死，解释路径消费时转换）。默认字面量在声明期校验类型与有限性（`int` 保留精确整数），仅补给被省略位置；显式传入 `UNSET` 按 Java 消费规则转换。

### 重载与候选选择

- **重载组约束**：同类同名符号组成重载组，组内共享 scope、subject、静态/成员属性、effect、逻辑结果类型及可能缺值标记（未标注 `float`/`int` 必有值；`@MolangNullable float`、`Float`、`Integer` 可缺值）。View 不计入脚本签名，不同签名可混用有无 view；`int` 与 `float` 规范化冲突直接拒收；跨类同名按整类冲突拒收。
- **选择优先级**：AOT 与解释路径共用同一排序：精确匹配优于合法标量转换；逐参比较时，在所有参数上不逊于且至少一项更优者淘汰较差者；互有优势视为并列，取规范 JVM 成员签名最小者。默认值/变长尾重叠、零实参、预期返回类型、参数数值、权限门禁与发现顺序均不参与选择。解释路径按当前实参值类型在冻结候选中筛选，详见[动态调用](molang-interpreter.md#冻结-java-调用)。`String` 仅接纳 `String` 或普通 `UNSET` 的 Java 消费。

## 调用归属

- `alwaysOrigin = false`（`SUBJECT`）：仅接纳静态方法，按当前目标 subject 判资格并注入当前环境。适用于普通实体属性、装备、附魔、直接可选模组查询；采样类查询读当前选定完成样本，远端玩法值读目标投影。
- `alwaysOrigin = true`（`ORIGIN`）：接纳静态与成员方法，按源实体 subject 判资格并用源 receiver 与源上下文（成员方法 view 的 `E` 须与组件 `E` 精确相等）。适用于模型、时间线、用途、随机、物理、骨骼与动作。
- `action = true` 对应 `ACTION`（需本次入口授权），`false` 对应 `CONTEXT`。
- **执行顺序**：检查固定 ACTION 权限与选定 scope 的 subject 资格 → 通过后在当前环境从左到右求参 → 调用目标方法。权限或资格被拒时直接返回缺值或 no-op，不求参、不构造变长数组、不调用 provider。箭头内调用 `ORIGIN` 动作仅改源 owner，实参仍可读箭头目标；子环境资格由源冻结声明环境针对目标判定。

## 公共业务 view

- **参数形态**：声明方法可省略上下文；若注入，首参须精确为 `MolangContextView<E extends Entity>`（OPTIONAL/VARIADIC 紧接 `int argc`，FIXED 紧接脚本参数）。省略 view 的静态方法默认 subject 为 `Entity`，成员方法仍为组件 `E`，不改变权限、subject 校验或纯性。
- **受限能力**：仅暴露 `subject()`、只读 `subjectSample()`、`inputs()`（始终来自 origin，提供采样时间、controller/动画只读值与不透明 target 身份）以及专用 `defer(float delay, float... values)`。
- **业务实现与逃逸**：查询与动作逻辑归声明类与既有领域 owner（随机、物理、骨骼、声音等），声明类可在 `beginEval` 借用 event、`endEval` 解除。工作若需逃逸本次同步调用，须复制载荷并取得独立所有权；`defer` 规则见[运行设计](molang-runtime.md#invocation-与效果)。
- **借用与热路径前提**：AOT 与解释路径同轮复用同一载体直接交给 provider（不逐次建 adapter）。Provider 仅在本次同步调用内借用 view 且保持 subject 只读，不缓存、不跨线程、不强转内部实现。ACTION 与 subject 资格在求参前统一核查（`defer` 保留 ACTION 检查）；生产热路径依赖安装保证精确 receiver 及同步借用契约，不逐次检查线程或活跃位，由开发期审计捕获误用。

## 数学 intrinsic

- `MathDeclarations` 维护 14 个脚本数学名到引擎闭集运算的映射，参数个数、消费方式与结果类型直接由闭集派生，供 registry、补全与默认源验证共用（无 Java provider，不参与组件资格；随机函数属普通声明，`math.pi` 为常量）。
- 合法数学调用经局部恢复后降级（lower）为 intrinsic：实参保持从左到右求值一次及原标量消费规则，不增 context-state effect，不走 subject/ACTION/provider 门禁。运算保证返回有限必有值 F32（定义域外或溢出归为 `+0`，有限值保留原 bit 位），不触发 `??` 右侧；实参或链接失败仍按入口失败隔离。
- Intrinsic 资格仅取决于是否属于引擎闭集，与方法是否 static、namespace 或注解无关。

## 组件生命周期

| hook | 调用契约 |
|---|---|
| `setRenderTarget(target)` | 首次绑定及实际 target 对象变更时更新资源关联；不清空历史、不关闭 target。候选阶段须使用显式传入的 `target` 参数（此时 owner getter 仍可能指向旧安装）。 |
| `tick(previousTick, currentTick)` | 首次调用零跨度；之后仅在 owner 接纳新实体 tick 时调用一次，跳 tick 不补调。 |
| `update(previousTime, currentTime, partialTicks)` | 首次调用零跨度；之后仅在主动画时间前进时调用（单位为 tick），限频恢复后一次消费累计跨度。 |
| `beginEval(event)` | 每次完整求值（含 observation）前借用 event，不在此推进持久历史或消费事件。 |
| `endEval()` | 对每个成功 `beginEval` 的组件恰好调用一次，清除临时借用；不代表求值成功，也不负责提交效果。 |

Owner 在单实体串行执行窗口内同步调用上述 hook（不绑定 Minecraft tick 线程）。组件不异步保存或修改 event，也不将可变 `codedController` 缓存为整轮固定输入。若 `beginEval` 自身抛异常，组件自行清理未移交借用；holder 对已成功前缀逆序调用 `endEval`。构造器或 hook 失败按安装隔离，不撤销类全局声明资格，详见[实体 owner](implementation/entity-and-frame-state.md#绑定与切换)。

## 值与 presence

| Java 返回 | 值规则 |
|---|---|
| `boolean` / `int` | 必有值 BOOL 或 F32，含 `false` 与零 |
| `float` | 默认有限且有值，保留 ±0；标注 `@MolangNullable` 时 `NaN` 与 ±Infinity 视为类型化 missing |
| `Float` | 有限值为有值 F32，保留 ±0；`NaN`、±Infinity、`null` 视为类型化 missing |
| `Boolean` / `Integer` | `null` 为对应类型化 missing，非 `null` 保留原值 |
| `String` | Java `null` 归一为空串，其余原样保留 |
| 精确平面 record `R` | 默认 non-null；仅标注 `@MolangNullable` 时允许 `null` 作同 schema missing |
| 精确类型 `Entity` | 身份引用；标注 `@MolangNullable` 时允许 `null`，否则保证 non-null |
| `List<Entity>` | 只读引用列表；容器与元素均不可为 `null`，发布后不可变 |
| `List<String>` / `List<R>` | 精确元素 schema 只读数组；容器、元素及记录字段均不可为 `null` |
| `it.unimi.dsi.fastutil.floats.FloatList` | 唯一数值数组载体 |
| `void` | 无返回值 |

- **平面 record**：须为 `public` 非泛型，字段仅限原始 `boolean`、`int`、`float` 或非 `null` `String`，构造器与 accessor 稳定无副作用（不要求全零构造）。
- **不接纳返回类型**：嵌套 record、record 内数组字段、`List<Float/Integer/Boolean>`、Java 原生数组、其他集合、raw/通配符/未确定边界 `List`、`long`、`double`、自定义 codec、可选依赖自有载体、`Optional*`。
- **专用 `@MolangNullable`**：仅 `com.elfmcys.ysm.api.annotation.MolangNullable`（`RUNTIME`、`METHOD`）有效，仅允许修饰原始 `float`、精确 record 或精确 `Entity` 返回值；同名第三方注解无效，修饰其他位置直接拒收。
- **有值与消费归一**：空列表、空串、全零 record 和零向量均属有值（present）：空列表/空串转标量得 `0`/`false`，全零 record/零向量得 `1`/`true`。Missing 与普通 `UNSET` 消费为 Java `boolean`/`int`/`float`/`String` 时分别传 `false`/`0`/`+0`/`""`。生产代码信任作者关于 non-null、有限值与不可变的承诺（解释路径中间算术差异见[数值消费](molang-interpreter.md#数值消费)）；完整脚本投影见[静态语言](molang-static-language.md)。

## 门禁默认表

AOT 编译期确定类型、实参、effect 与存储约束；解释路径准备期固定声明与权限，消费时核对值类型。运行时按 ACTION → subject → 求参（含默认值与变长尾）顺序执行，任一门禁拒绝时按声明返回类型返回下表结果，不求参、不产生诊断。解释中声明方法抛出的异常以原 `Throwable` 展开到顶层，见[求值失败](molang-runtime.md#求值失败)。

| 结果 | 拒绝结果 |
|---|---|
| 原始或装箱标量 | 同类型 BOOL 或 F32 的类型化 missing，与 `false`/零区分 |
| `String` | String schema 的 missing，与有值空串区分 |
| record、vector、只读数组或 Entity 引用 | 同 schema missing，与全零 record、零向量、空列表区分 |
| `void` | no-op |

声明不配置业务 fallback（脚本侧用 `??` 表达）；可选默认值仅补给脚本省略位置，显式 `UNSET` 不触发默认值且计入 `argc`。已获准调用缺失 receiver 属宿主违约。

## 数值与发布后不可变

- **整型与浮点转换**：有限 F32 转 Java `int` 按 Java narrowing（向零截断，溢出饱和）；`int` 转 F32 允许正常精度舍入；`int` 默认字面量保留精确整数；`float` 保留精度与 ±0，非有限字面量拒收。
- **声音 ID 与向量**：声音接口经 `int` 与 `String` 重载区分 ID（`3.9` narrowing 为 `3`，数字 `0` 为匿名播放，字符串 `"0"` 与空串走字符串命名空间，内部 key 不暴露为表达式类型）。向量 `x/y/z` 均为 F32。
- **边界有限性与发布不可变**：`@MolangNullable float` 与 `Float` 在函数返回边界将非有限值解码为 missing（未标注 `float` 不插检查，且该规则不传播到 record 字段、存储或算术）。Provider 保证未标注 `float` 及复合内容有限，发布的 record、容器、元素及底层存储不可再修改。`String`/record/数组按内容判等，`Entity` 按对象身份判等，均不调作者 `equals`/`toString`。
- **业务缺席与外部来源**：合法输入下正常缺席返回声明值域的 missing（随机反向区间自动交换、宽整型跨度防溢出；位置越轴、物理发散、非法槽位/索引/资源 ID 按入口约定返回 missing；合法空值/`false`/零保持有值），异常与真实故障交框架失败边界。Java 返回值一律视为不可变外部来源（Foreign）：写隔离分别见[AOT 字段写](molang-static-language.md#对象隔离与字段写)、[运行实现](implementation/molang-runtime.md)与[动态结构体](molang-interpreter.md#平面结构体与字段写)。开发期校验与借用审计见[provider 契约审计](../../verification/molang-declarations.md#provider-契约审计)。
