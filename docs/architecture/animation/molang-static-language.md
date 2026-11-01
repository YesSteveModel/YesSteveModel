# Molang 静态语言规则

本页拥有 YSM 的定型、调用恢复、presence 与对象写语义；Java 作者边界由[共同声明](molang-declarations.md)拥有。规则由 compilation policy 显式启用，不改变模型 wire 或其他宿主的默认语义。

## 对象 presence

对象包括 String、平面 record（含 vector）和 readonly 数组。空串、空数组和全零 record 都是有值对象；空串仍保留 String 类型、内容 equality 和 Java String 传参资格。

已确定 F32/BOOL 目标的消费采用下表。转换路径在编译期按来源类型选择，执行期只检查固定 carrier 的值。来源只求值一次；投影不改写来源的类型、内容或缺值事实。

| 合法来源 | F32 消费 | BOOL 消费 |
|---|---|---|
| 有限 F32 `x` | 保留原 bits，含 ±0 | `x != 0` |
| BOOL false / true | +0 / 1 | 原值 |
| 普通 unset、脚本 null、typed missing | +0 | false |
| 空 String、零长度 readonly 数组 | +0 | false |
| 非空 String、非空 readonly 数组、其他非缺值对象 | 1 | true |

String 不解析数字，`"0"`、`"false"` 和空白串都转为 1/true。数组只检查长度，不递归检查元素；全零 record 和零向量转为 1/true。规则覆盖算术、排序、逻辑、条件、循环次数、索引、入口结果、用户函数消费和 Java scalar 参数。

F32/BOOL 混合 `==` / `!=` 按数值比较。对象与 scalar 比较时先按上表将对象投影为 F32，数值侧保值；不限制原始 literal，左右顺序和数值来源不改变规则。例如 `2 == true` 为 false，非空对象与 1 相等、与 2 不等。同类型对象按字段/元素内容比较，不调用作者的 `equals/toString`；不同对象 schema 不因可转换为 scalar 而可比较。已闭合的 F32、BOOL、String 同类型比较分别把 missing 按 0、false、空串比较，missing 与 missing 相等；比较不写回默认值。

`??` 只测试缺值。0、false、空串和空数组均保留左侧，右侧不求值。被缺值观察覆盖的读取与内部复制保留 presence；实际 scalar 消费物化有值 +0/false。

缺值观察需求从 `??` 左侧沿槽复制、赋值结果、分支及用户函数参数/结果反向传播。被覆盖的变量区分初始化与未初始化；其余变量不记录该状态，写入前读取及缺值写入均使用 +0/false/null/空串/空数组。VALUE 出口、roaming、冻结 transient 边界和 record 字段仍保留各自的观察契约。临时 watch 使用冻结的模型表示，不能反向启用模型未保留的初始化状态。分析覆盖全部有效写入，不按具体控制路径证明初始化。

已确定的对象目标接收原始 F32 零或 BOOL false literal 时执行同 schema 清空。未保留 presence 的槽将缺值归一为默认值；赋值结果与实际写入一致。这条清空规则不授权不同对象类型互转。同类型对象赋值、成员和索引仍保留原隔离与访问规则。

## Literal 边界与定型

Frontend 把 `null` 编码为 unset literal，不查找宿主符号。

Literal 资格取自 frontend 的原始 canonical 节点，在折叠和 conversion 前判定。括号不产生节点，`(0)` 仍是 literal；`+0`、`-0`、`1-1`、调用、变量、赋值、条件和顺序表达式都不是，即使结果为 0、1 或 false。F32 的 ±0 payload 均按 equality 视为零；非有限 literal 非法。不通过执行 provider 或 runtime 数值 guard 扩大接纳。

F32 零 literal 写入未定型位置时是弱依据：同一约束流的对象依据可以使它表示对象缺值；最终只有弱零且允许默认时，类型为 F32、有值 0，`??` 不取右侧。BOOL false 确定未定型位置为 BOOL；仅当目标已由其他依据确定为对象时才表示清空。算得零的非 literal 提供普通 F32 依据。

未声明类型的持久槽、局部位置、分支结果和同一用户函数版本的返回，只允许 F32/BOOL 异型合流为 F32。BOOL 路径产生 0/1，F32 路径保留数值；单纯 BOOL 流可保持 BOOL。缺值不是类型候选，合流本身不提前物化缺值。对象与 scalar、不同对象 schema 仍为候选冲突，不按可转换性归约。

完整 target 跨入口、subject/权限计划统一持久 slot 类型。用户函数按调用点独立闭合，静态实参类型、供参形状和消费需求可产生有限版本；一个调用的弱零不能从另一调用借到对象依据。同版本内的参数、返回和局部位置固定，不按 runtime 值、presence 或 entity 身份生成版本。对象来源经赋值、转发和返回传播，投影不反向改变来源类型；缺值贯通内部函数，最终 scalar 消费才投影为 0/false。

递归组入口固定各函数签名，回边在允许的静态转换后应匹配；多态递归是回源静态错误，不等待运行深度或版本预算耗尽。恢复后从有效树重建版本、持久布局和效果，无效版本不能导出给临时编译。

## 调用解析与局部恢复

完整输入先经过语法/编码、节点/深度及资源预检。随后按源码顺序解析 symbol/access form；真正 missing 直接普通 UNSET，已知种类用错仍为独立错误。callable 数量不符直接 UNSET；数量正确时从左到右分析实参，首个有独立依据的边界类型不匹配使整个调用 UNSET，停止分析后续实参。先遇到的独立字段、访问或存储错误不能被吞为 mismatch。

已解析到外部函数时，裸读取与空括号均为零实参调用：`f` 与 `f()` 共用重载、default、argc/tail、effects、门禁和失败来源，不缓存结果。最低参数数大于零时，两种形式均局部 arity 恢复，provider 不执行。函数返回 record/数组后可按正常规则访问字段或索引；函数本身不是赋值地址。真实变量、context 属性、用户函数和 `math.pi` 不因该规则改变种类。

Java 调用先闭合实参，再按[重载规则](molang-declarations.md#作者签名)选 exact member。固定 scalar 参数、补齐后的实际参数和 variadic 元素共用上表；候选合流与已确定参数目标的转换分别闭合。合法调用保留全部参数约束，即使运行时 ACTION/subject 会拒绝；局部 missing/arity/type 恢复才删除调用 scope。Java 缺值消费见[声明边界](molang-declarations.md#值与-presence)，通用语言 conversion 与 `??` 的缺席规则保持独立。

函数 float/Float 返回的有限值保留原 bits，含有值 ±0；非有限值及 Float null 为 F32 typed missing，`??` 才选择右侧。该事实与 primitive carrier 分离，可信纯调用仍可使用 primitive 优化目标且保留 presence。它只归一函数 Java 返回边界，不递归改变字段、getter、conversion、存储或底层 MATH/表达式算术；具体 Java carrier 规则由[声明边界](molang-declarations.md#值与-presence)拥有。

类型依据来自有效 producer、合法 caller 或函数体消费约束，循环不能自行证明类型；相互冲突且没有独立依据时不选先遍历到的一方。共享函数版本使用所在顶层表达式的固定许可，不新建许可或预算窗口。源语言数学函数走同一调用规则，`math.pi` 仍为 literal；损坏的底层 MATH 节点不获得局部恢复。合法调用保持左到右、一次求值；实参失败阻止 provider，已发生的实参/外围状态变更不回滚，未提交效果仍受[整轮封口](molang-runtime.md#invocation-与效果)约束。

纯数学调用即使使用优化路径，也应保留 registry 资格、局部恢复、实参顺序、缺值消费、provider 预算与原失败来源；有状态函数不能按纯函数执行。既有 lowered `MATH` 不经 registry 或 provider 预算，不能替代源语言数学调用。执行成本的证据边界见[运行实现](implementation/molang-runtime.md#bounds-与热路径)。

## 对象隔离与字段写

Java 返回值、只读数组及其元素不可变。Molang 写入可写 record 时隔离原值，别名不能观察到半写。静态表示和复制位置见[对象表示实现](implementation/molang-runtime.md#静态表示与对象写)。

普通字段写先捕获位置和接收者，再执行 RHS/转换，最后检查缺值、复制一个平面 record 并发布新值。RHS 建立的别名仍看见旧值；RHS 重绑 slot 不改变已捕获接收者；失败不发布半写。

临时 Java 返回值的字段写仅消去 store，仍依次执行接收者/provider、必要返回检查、RHS 和赋值转换。普通 missing 字段写在 RHS 后失败，不与临时写规则混同。数组只读，元素进入可写 record 位置后才归一化。

## 领域边界与失败

`ysm.defer` 的 Java 参数为 F32，固定参数和 tail 共用统一 scalar 消费，接纳 BOOL、String、record 和 readonly 数组。声音通过 int/String 重载选择精确方法，再交给内部声音 key；字符串 `"0"` 不变成数字 ID。

持久 target 的独立 binding/type/shape 错误按 program role 使用已声明 fallback：F32 为 +0、BOOL 为 false、VOID 为 no-op，用户函数替代后重新闭合替代涉及的版本及 caller。节点恢复与整 program replacement 分开；最终入口仍非法才替换整入口。失败 primary 的类型、常量、effect 和签名不遗留到 artifact。

规则、声明事实、全部静态版本及生成/执行兼容内容摘要参与 expected artifact identity。按[开发期 Molang 契约](../../product-decisions/decisions/model-compatibility.md#bcdevelopment-molang-semantics-use-current-contract)，同 source 与 Java 签名的旧语义产物也应失配并重建；不设旧转换、裸访问或权限行为分支。运行时只执行已经闭合的类型与源码控制流，不按动态值选择类型。Artifact 的表示与编译事实见[运行实现](implementation/molang-runtime.md#源码解析与缓存)。
