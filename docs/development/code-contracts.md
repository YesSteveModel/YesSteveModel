# 代码契约

本页是[实现指南](implementation.md#代码契约)的声明格式参考。Seam 范围、责任和接口语义由[设计指南](design.md#契约与责任)定义。

## 声明位置与格式

代码契约说明：调用方满足哪些条件，提供方就建立并维持哪些保证；正常失败和违约分别由谁处置。每项保证应有明确的对象、适用窗口和失效条件。

| 位置 | 拥有的信息 |
|---|---|
| 类型、注解、签名 | 可机器识别的范围、可空性、结果分支、只读和所有权约束 |
| seam 接口或类声明 | 实例不变量、共同生命周期与并发规则、架构条款关联 |
| seam 方法声明 | 本次调用的前置条件、结果、失败、执行及资源交接语义 |
| seam 暴露的字段或数据类型 | 字段范围、状态关联、访问权限及引用有效期 |
| 实现内部 | 建立和维持保证的机制及必要的原因注释 |

使用普通 Javadoc，参数、返回值、异常使用标准标签。额外段落按以下名称和顺序按需出现。

| 段落 | 内容 |
|---|---|
| `Invariant` | 实例或字段在指定可观察窗口内始终成立的条件 |
| `Requires` | 调用方应建立的前置条件及可继承的输入保证 |
| `Ensures` | 成功后的返回值、状态、副作用及其关联 |
| `Failure` | 合法失败的条件、通道、状态与资源后果及恢复责任 |
| `Execution` | 执行位置、阻塞、完成含义、并发粒度、原子性与重入 |
| `Ownership` | 保留、借用、转移和关闭责任，以及成功、失败、取消的区别 |
| `Seam` | 落实或依赖的精确架构条款，以及承担的责任 |

`Invariant` 与 `Seam` 通常放在类型声明。方法继承共同规则，只写增量；标准标签已表达的条件不在段落内重写。类型和签名已完整表达的条件不复述，不填空栏目。省略仅表示不适用或已有共同规则覆盖，不表示任意线程、永不失败或可长期持有。

## 输入、结果与字段

区分调用前置条件和提供方接纳规则。例如索引范围为 `[0, capacity)`：前者要求调用方保证合法；后者要求提供方裁决原始索引，按约定通道拒绝越界。前置条件本身不要求提供方增加运行时校验；违约诊断不应伪装成正常拒收协议。

| 对象 | 类型未表达时应补充的条件 |
|---|---|
| 数值 | 单位、开闭区间、特殊值；浮点数是否允许 NaN 或无穷 |
| 参数关系 | 长度与容量、身份与作用域的关系；偏移与长度的单位及无溢出的范围条件 |
| 可空性 | 参数、返回对象、元素和嵌套字段分别是否可空；空值的唯一含义 |
| 集合与视图 | 顺序、重复项、空集合含义、快照或实时视图、元素的可变性 |
| buffer 与 native 地址 | 有效范围、对齐、可写性、别名限制、position/limit 的变化及借用窗口 |
| 字段 | 合法状态中的范围和关联、写入者、读取窗口及不变量的生效时点 |

缺失值、可变输入和保证维持见[实现指南](implementation.md#数据与失败)。

字段不变量应区分内部修改窗口与对外可观察窗口。仅当字段影响 seam 消费者的调用或访问义务时，才要求声明该约束；私有字段不要求逐项填写。

## 失败与异常

每项允许失败应说明：触发条件、报告通道、状态与资源后果、恢复裁决者。

| 类别 | 声明要求 |
|---|---|
| 正常否定结果 | 缺失、Busy、过期、拒收的明确分支，以及是否发生副作用 |
| 运行失败 | I/O、设备、执行器拒收等失败的通道，以及提交与清理情况 |
| 调用违约 | 责任与诊断语义，不转换成正常缺失、cache miss 或成功 |
| 内部不变量破坏 | 指定诊断与隔离边界，不要求每个消费者增加恢复机制 |

合法调用的业务失败应有明确范围。未枚举的实现缺陷不因此成为正常可恢复结果；未声明异常也不自动构成不抛出承诺。分别声明同步抛出与异步完成失败。

失败应区分未提交、部分提交、已提交但通知失败。重试应说明重复副作用、身份和幂等性条件。

`Error`、进程级故障和隔离策略引用指定上层边界。

## 执行、并发与状态

不单独使用“线程安全”“异步执行”或“加锁保护”代替执行契约。按接口涉及的行为说明以下维度。

| 维度 | 声明内容 |
|---|---|
| 执行位置 | 调用线程、指定 owner 线程或工作线程；调用者是否负责调度 |
| 返回与完成 | 返回代表已创建请求、已接纳、已提交或已完成；完成是否包括发布或安装 |
| 阻塞 | 是否等待 I/O、其他任务或 owner 执行；热路径限制及其条件 |
| 并发范围 | 同一实例、key、资源或整个服务；哪些操作允许重叠 |
| 原子性 | 单次调用或组合操作；与关闭、替换、撤销的排序 |
| 可见性 | 从哪个交付点起能观察完整结果，后续修改如何受约束 |
| 回调 | 是否可能内联、执行线程、是否持锁、是否允许重入、异常归属 |
| 取消与终态 | 取消是请求还是已停止；何时结束借用，谁释放迟到或无人接收的结果 |

状态查询应声明返回快照还是持续有效的使用资格，注明与失效事件的排序或有效窗口。责任选择见[设计指南](design.md#权威所有权与提交)。

重复调用应说明幂等成功、正常拒绝或调用违约。`close()` 应分别说明对在途工作、已交付资源和新操作资格的影响。对象仍可合法使用，不自动意味着它仍有资格参与新操作。

返回异步句柄时，应说明任务终态、结果交付、无人接收及取消后清理责任。

## 声明示例

以下虚构接口仅展示格式，不声明当前实现能力。假定 `Candidate` 与 `InstallResult` 的类型契约已定义合法内容、非空引用和结果分支。类型的 `Seam` 段落在真实代码中应链接精确架构章节。

```java
/**
 * Owns the installed candidate.
 *
 * <p>Invariant: the slot is empty or owns one complete candidate.
 *
 * <p>Execution: confined to the render owner thread, including close().
 * Calls do not invoke external callbacks.
 *
 * <p>Seam: refines complete installation and replacement.
 */
interface CandidateSlot extends AutoCloseable {

    /**
     * Attempts to replace the installed candidate.
     *
     * <p>Requires: the caller owns candidate exclusively.
     * Candidate must not be the currently installed instance.
     *
     * <p>Ensures: INSTALLED transfers ownership to this slot and
     * releases the previous candidate. CLOSED leaves the slot unchanged
     * and candidate owned by the caller.
     *
     * <p>Failure: a closed slot returns CLOSED. A valid call has no
     * recoverable exception outcome.
     *
     * <p>Execution: completes on the calling thread before returning.
     * Requires the render owner thread.
     *
     * @param candidate the candidate whose ownership may be transferred
     * @return the installation outcome
     */
    InstallResult tryInstall(Candidate candidate);

    /**
     * <p>Ensures: closes the slot and releases its installed candidate.
     * Repeated calls leave the slot closed.
     *
     * <p>Failure: a valid call has no recoverable exception outcome.
     *
     * <p>Execution: completes before returning on the render owner thread.
     */
    @Override
    void close();
}
```

调用者不必再次检查候选完整性、先查询关闭状态或添加外部锁；仍应满足线程前置条件，并在 `CLOSED` 时承担候选的后续责任。若实现还可能因上传失败而拒收，应在交接前建立完整候选，或声明真实失败及资源后果。

## 与架构契约的连接

代码契约细化[架构契约](design.md#架构契约格式)。

| 架构字段 | 代码声明中的落实 |
|---|---|
| 边界与权威 | 裁决和写入所归属的接口或实例 |
| 前置条件与输入 | `Requires`、参数类型、接纳规则 |
| 结果与保证 | `Ensures`、返回分支、实例不变量 |
| 所有权与时序 | `Ownership`、`Execution`、状态与生命周期操作 |
| 失败与拒收 | `Failure`、异常通道及失败后的状态与资源 |
| 证据与依赖 | 建立与维持机制、验证入口；测试状态不进入 API 注释 |

边界类型应引用具体 seam 的架构章节，并说明落实或依赖哪项责任。同一类实现多个 seam 时，类型只声明共同约束，各接口或方法分别关联对应契约；不能用子系统导航入口代替所有方法的具体关联。方法继承适用的类型关联，承担其他责任时单独引用。架构页只保留少量关键责任类型，不维护全量 API 清单。

源码注释中的文档引用遵循[实现指南](implementation.md#源码注释的文档引用)。

代码注释应足以指导正确调用，跨域组合条件和非显然理由沿链接读取。同一规则只有一个权威定义；声明处可保留必要摘要和具体化，不另设第二份规范。

例如架构规定撤销缓存引用不关闭仍被消费者保留的资源。代码契约应明确哪个返回值持有 lease、谁调用 `close()`、借用值何时失效；具体保留机制归实现。若新增方法前置条件使架构原本允许的调用无法成立，应重评边界，不能仅补注释。
