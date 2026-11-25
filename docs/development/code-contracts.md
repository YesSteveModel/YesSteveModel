# 代码契约

本页定义 seam 处 Javadoc 代码契约的写法，适用范围见[实现指南](implementation.md#代码契约)与[设计指南](design.md#契约与责任)。

## 声明位置与格式

参数、返回值和异常用标准 Javadoc 标签，其余约束按以下有序段落按需补充（不适用或已由类型/签名表达清楚的段落直接省略）：

| 段落 | 内容 |
|---|---|
| `Invariant` | 实例或字段在对外可观察窗口内始终成立的不变量 |
| `Requires` | 调用方必须满足的非显然前置条件与可继承输入保证 |
| `Ensures` | 成功后的返回值、状态迁移、副作用及可见时点 |
| `Failure` | 合法业务失败与运行失败的触发条件、报告通道、提交/资源状态与恢复责任 |
| `Execution` | 执行线程、是否阻塞、返回时的完成含义、并发范围、原子性、回调重入与取消终态 |
| `Ownership` | 保留、借用窗口、所有权转移与关闭责任（区分成功、失败、取消） |
| `Seam` | 落实或依赖的架构契约链接及本类/方法承担的部分 |

`Invariant` 与 `Seam` 通常写在类型注释上，方法只写相对类型共同规则的增量。

### 关键维度速查

写契约时避免只用“线程安全”“异步执行”等笼统说法，按接口涉及点补齐具体约束：

- **输入与字段**：明确区分“调用前置条件（违约由调用方担责）”与“提供方接纳校验（按约定通道拒收）”；涉及 buffer/native 地址时写清范围、对齐、可写性、`position`/`limit` 变化与借用窗口。
- **失败状态**：区分未提交、部分提交与已提交但通知失败；同步抛异常与异步完成失败分开说明；允许重试时交代操作身份与幂等性。
- **执行与 `close()`**：状态查询须说明返回的是瞬时快照还是持续有效资格；`close()` 须分别交代对在途工作、已交付资源及后续新调用的影响；返回异步句柄须交代任务终态与无人认领结果的清理方。

## 声明示例

以下虚构接口演示注释组织方式（真实代码中 `Seam` 须使用[文档链接格式](implementation.md#源码注释的文档引用)指向具体架构小节）：

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
     * <p>Requires: the caller owns candidate exclusively and it is not
     * the currently installed instance.
     *
     * <p>Ensures: INSTALLED transfers ownership to this slot and releases
     * the previous candidate. CLOSED leaves the slot unchanged and
     * candidate owned by the caller.
     *
     * <p>Failure: a closed slot returns CLOSED.
     *
     * <p>Execution: synchronous on the render owner thread.
     *
     * @param candidate the candidate whose ownership may be transferred
     * @return the installation outcome
     */
    InstallResult tryInstall(Candidate candidate);

    /**
     * <p>Ensures: closes the slot and releases its installed candidate.
     * Repeated calls are idempotent.
     *
     * <p>Execution: synchronous on the render owner thread.
     */
    @Override
    void close();
}
```

## 与架构契约的连接

| 架构契约字段 | 代码声明落点 |
|---|---|
| 边界与权威 | 权威状态裁决与写入接口 |
| 前置条件与输入 | `Requires`、参数类型与接纳规则 |
| 结果与保证 | `Ensures`、返回分支与 `Invariant` |
| 所有权与时序 | `Ownership`、`Execution` 与生命周期方法 |
| 失败与拒收 | `Failure`、异常通道与失败后资源归属 |
| 证据与依赖 | 建立与维持机制、验证入口（测试通过状态不写进 API 注释） |

边界类型直接链接具体 seam 小节，不用子系统总览页敷衍；实现多 seam 时在各自接口或方法上分别标注。同一规则只保留一份权威定义，代码注释专注调用正确性，跨模块组合前提与权衡留在架构文档。
