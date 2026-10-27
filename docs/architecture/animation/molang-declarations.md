# 共同 Molang 声明契约

本页拥有 builtin/API 作者签名与公共值规则；调用解析由[静态语言](molang-static-language.md#调用解析与局部恢复)拥有，类级资格与冻结由[扩展设计](../integration/overview.md#扩展资格与冻结)定义，实例安装由[实体 owner](implementation/entity-and-frame-state.md#绑定与切换)拥有。公共 Java 表面位于 `api.annotation`、`api.molang`。

## 作者签名

公共声明使用 `@YsmEntityComponent(value, modId, dependencies, mode)` 和 `@MolangFunction(value, namespace, effect, minArgs, maxArgs)`。Builtin 使用编译期的 `@BuiltinComponent(value, dependencies)` 和同参数的 `@BuiltinFunction`。两种函数注解默认 CONTEXT，arity 的 `-1` 表示未指定。声明只导出函数；外部函数的无括号访问由[静态语言](molang-static-language.md#调用解析与局部恢复)归一。

成员 namespace 默认取类 `value`，非空成员 `namespace` 覆盖默认值。短名默认取 Java 方法名按 `Locale.ROOT` 转小写，不插入下划线；非空成员 `value` 覆盖短名。`weapon` 类中的 `isReady` 导出 `weapon:isready`，显式 `value="is_ready"` 导出 `weapon:is_ready`。脚本使用 `weapon.is_ready`。

namespace 应匹配 `[a-z][a-z0-9_]*` 且不超过 64 bytes。短名应匹配 `[a-z_][a-z0-9_]*` 且不超过 128 bytes。显式值不修剪或纠正大小写，也不拆分完整 symbol。公共类默认及成员覆盖不能使用 `ysm`、`math`、`query`、`ctrl`、`tlm`、`fn`、`v`、`c`、`t`。Builtin 的可信资格来自内部来源，不来自名字。

namespace 仅决定导出名，不决定 mod 或组件身份。`modId` 默认空，表示由接纳器从实际来源文件推导。一个声明类可以导出多个 namespace；同一实体 owner 仍只拥有该类的一个组件实例。不同声明类即使共享 namespace，也不共享组件状态。

公共 static 函数不要求 component 或构造器。成员声明来自 `EntityComponent<E>`；完整继承泛型替换后，view 的 E 与 component E 应精确相等。Static 独立从 view 推导 subject。同类可混合 static 和成员方法，但接纳资格按整类闭合。

成员类提供 `public Foo(AnimatableEntity<E> owner)`。Processor 校验替换后的 owner 类型；运行时核验真实构造与 helper。不提供 no-arg 或 subject 构造回退。Static-only 类不实例化；同一 subject 的两个 owner 各自构造组件。

| 形状 | context 后的 Java 参数 | 脚本 arity |
|---|---|---|
| FIXED | 固定参数 | `min=max=F` |
| OPTIONAL | `int argc`、固定参数及连续尾部默认参数 | `min=F-D`、`max=F` |
| VARIADIC | `int argc`、固定前缀及唯一 typed tail | 省略 min 时取 F；显式 `min>=F`；显式有限 `max>=min` |

F 为固定脚本参数数，D 为尾部默认参数数。显式 FIXED/OPTIONAL arity 只能等于推导值。VARIADIC 不与默认参数混用。除 `-1` 外的负数、矛盾范围、无界 tail 和 `max>255` 均拒绝。`argc` 为实际 supplied 数，不计 context、argc 或补齐默认；合法空 tail 供应空数组。

Fixed 参数与 tail 元素只允许 Java boolean/int/float/String，不接纳 boxed、record、语言数组、Object、union 或 SoundId。默认 literal 在声明期检查类型和有限性，int 默认保留精确 int。仅 omitted 参数补默认，supplied UNSET 按 Java 消费规则转换。

同一声明类内的同 symbol 方法组成重载组。组共享 subject、精确 view、static/member、effect、逻辑结果与可能缺值事实。float/Float 均为可能缺值的 F32；int 为必有值 F32，Integer 为可能缺值 F32。int/float 规范化重复参数签名拒绝。跨类同 symbol 按整类冲突拒绝，不构成重载。

冷编译先匹配精确实参类型，再匹配合法 scalar 转换；最佳集合并列时选择 canonical exact JVM member 最小者。default/tail 重叠和零实参没有额外优先级。预期返回、运行时值、门禁和发现顺序不选择方法。String 只接受 String 或普通 UNSET 的 Java 消费。

## 公共业务 view

`MolangContextView<E extends Entity>` 提供 subject、轮输入、查询及 owner 局部推进。`MolangActionView<E>` 继承 ContextView，并提供声音、controller、骨骼呈现、sync、particle 和 defer 动作。CONTEXT 首参应精确为 ContextView；ACTION 可使用任一 view。ACTION 使用 ContextView 不降低声明 effect。

首参不接纳 raw、wildcard、未闭合泛型、作者子接口或内部 context 类。两个 view 不暴露权限、预算、program、batch、组件目录、processor、资源句柄、execute/evaluate 或生命周期控制，也不继承 AutoCloseable。subject 只读属于作者契约；接口不是 JVM 沙箱。

一轮中的两种 view 引用同一状态载体。生成 helper 直接把同一引用交给方法，不构造 adapter。Provider 仅在本次同步调用内借用 view，不保存、不异步使用、不强转获取另一 scope。整轮仍存活不延长 provider 使用权。

CONTEXT 可读取和推进 owner 局部状态。ACTION 还受表达式活跃状态、固定入口许可及提交资格限制。公共动作方法在产生效果前核验许可。无活跃表达式或被禁止的 Java 直接动作调用属于 API 误用，应失败且不记录效果；正常 ACTION symbol 的门禁拒绝仍返回 typed missing 或 no-op。

## 组件生命周期

| hook | 调用契约 |
|---|---|
| `setRenderTarget(target)` | 首次绑定及实际 target 对象改变时更新资源关联；不清历史，不关闭 target。候选使用显式参数，owner getter 仍可能指向旧安装。 |
| `tick(previousTick, currentTick)` | 首次零跨度；以后仅 owner 接纳新实体 tick 时调用一次，跨多个 tick 不补调。 |
| `update(previousTime, currentTime, partialTicks)` | 首次零跨度；以后仅主动画时间前进时调用，时间单位为 tick，限频后消费累计跨度。 |
| `beginEval(event)` | 每次完整求值借用 event，包括 observation；不推进持久历史或消费事件。 |
| `endEval()` | 对成功 begin 恰好调用一次，清除临时借用；不表示成功，不提交效果。 |

Owner 在原串行窗口内同步调用 hook，不承诺 Minecraft tick 线程。组件不异步保存 event，不修改 event，也不把可变 `codedController` 缓存为整个求值的固定输入。Begin 自身失败时，由组件清理尚未移交的借用；holder 仅对成功前缀逆序 end。构造或 hook 失败按安装隔离，不撤销全局声明资格；结束规则见[实体 owner](implementation/entity-and-frame-state.md#绑定与切换)。

## 值与 presence

| Java 返回 | 值规则 |
|---|---|
| boolean / int | 必有值 BOOL/F32，包括 false、零 |
| float / Float | 有限值为有值 F32，保留 ±0；NaN、±Infinity 和 Float null 为 typed missing |
| Boolean / Integer | null 为该 scalar 的 typed missing，非 null 保留值 |
| String | Java null 归一为空串，其他内容原样保留 |
| 精确平面 record R | 默认 non-null；仅专用 `@MolangNullable` 允许 null 为同 schema missing |
| `List<String>` / `List<R>` | 精确元素 schema 的 readonly 数组；容器、元素和字段非 null |
| `it.unimi.dsi.fastutil.floats.FloatList` | 唯一数值数组载体 |
| void | 不物化结果 |

record 应 public、非泛型，字段仅 primitive boolean/int/float 或非 null String；构造器/accessor 是稳定无副作用的数据操作，不要求接受全零构造。不接纳嵌套 record/数组字段、`List<Float/Integer/Boolean>`、原生数组返回、其他集合、raw/wildcard/未闭合 List、long/double、自定义 codec、可选依赖自有 carrier。Optional、OptionalInt、OptionalFloat、OptionalBoolean 不属于声明边界。

专用注解是 `com.elfmcys.ysm.api.annotation.MolangNullable`（RUNTIME、METHOD），只适用于精确 record 返回。同名/第三方 Nullable 不授权；给其他结果种类加此注解拒绝接纳。它不允许可空字段、元素或参数。

空列表、全零 record 和零向量仍 present。String 内容 presence 与语言投影由[静态语言](molang-static-language.md)拥有；空串是合法 Java String。missing 与普通 UNSET 消费为 Java boolean/int/float/String 时分别供应 false、0、+0 和空串，provider 正常调用。non-null record/数组实际返回 null 是严重违约，丢弃结果也检查。boxed/Nullable null 和 String null 归一保持原规则。

## 门禁默认表

合法 Published 调用先完整闭合类型、实参、effect 和存储约束。运行时按生命周期/identity、ACTION、subject、exact receiver、实参/default/tail 顺序执行。ACTION 或 subject 拒绝返回声明类型的 missing；void 为 no-op。拒绝不求参、不构造 tail，不产生静态恢复或门禁诊断。

| 结果 | 拒绝结果 |
|---|---|
| primitive / boxed scalar | 同一 BOOL/F32 的 typed missing，presence 与真实 false/零分开 |
| String | String schema 的 missing，与有值空串分开 |
| record / vector / readonly 数组 | 保留 schema 的 missing，与零 record、零向量、空列表分开 |
| void | no-op |

声明不配置 fallback。optional default 仅补 omitted 参数；supplied UNSET 不读取 default，argc 保留 supplied 数。literal 在声明期验证类型和有限性，业务默认由模型 `??` 表达。适用且获准调用缺 receiver 在求参前失败，不能归为正常拒绝。

## 数值与发布后不可变

有限 F32→Java int 按 Java narrowing 向零截断并在溢出时饱和；int→F32 允许舍入，例如 16777217→16777216f。int 默认 literal 保留精确 int，不先转 F32。float 保留 F32 精度与有值 ±0；非有限 literal 拒绝声明/输入。

声音方法以 int/String 重载接收 ID：数值 3.9 经 narrowing 为 3，数字 0 为匿名，字符串 "0" 是独立命名空间。空串仍选择 String 方法，是否接纳由声音业务决定。内部声音 key 不公开为表达式类型/codec。vector 的 x/y/z 为 F32。

函数的 float/Float 返回只在 Java 调用边界归一一次；规则不传播到 record field、getter、conversion、真实存储或底层数学。Provider 保证复合内容有限，并保证 record、容器、元素和底层存储发布后不可变；readonly 接口本身不证明这一点。内容 equality 按语言字段/元素比较，不调用作者 `equals/toString`。可写对象的隔离见[静态语言](molang-static-language.md#对象隔离与字段写)。

声明验证与借用审计见[集成验证](../../verification/molang-declarations.md#provider-契约审计)。

Java 结果是 immutable Foreign 来源，Molang 对象写的 Managed/COW 隔离由[静态语言](molang-static-language.md)和[执行架构](implementation/molang-runtime.md)拥有。开发验证用于定位典型非有限、非法 null、发布后列表修改和借用误用，不构成对任意恶意 Java 的沙箱。实际 provider 分配、列表构建和真实动作成本仍属于执行成本。
