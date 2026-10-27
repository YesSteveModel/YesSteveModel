# 实体模型使用与生命周期

本页细化[动画的结束与复用](overview.md#实体使用的结束与复用)。实体根协调指派、安装、求值与同步借用；[客户端资源](../model-management/client-resources/overview.md)只交付 Ready 和独立 lease，不替实体发布私有状态。

## 对象寿命与模型使用

对象可达、宿主活跃、已有指派、资源 Ready、完整安装与允许求值是不同事实。一个 `AnimatableEntity` 可以先后拥有多次模型使用；同一 subject 的多个 owner 不共享可变状态。

| Owner 用途 | 上级生命周期 | 私有状态与结束 |
|---|---|---|
| 世界玩家、投射物、载具 | Entity 的 capability/provider；宿主属于当前 level | Controller、组件、表达式实例、Roaming、输出和自身 lease；宿主失活或卸载结束使用 |
| GUI 卡片、中央预览、生成预览 | 页面或单次 preview 操作 | 自己的动画 owner 与创建时的 FakePlayer；页面、操作或世界结束时退休 |
| 第一人称手臂和背景 | 客户端共享 renderer，绑定当前 LocalPlayer | 独立动画状态与三个输出；玩家替换、退出或 renderer 替换时释放 |

模型只读资产和 module 可以经 lease 共享。多个输出槽只隔离 Extract 结果，不建立独立播放时间线。通用展示实体缓存不等于模型卡预览池，辅助保留见[页面资源 owner](../client-presentation/implementation/page-runtime.md#页面与资源-owner)。

## 指派、请求与安装关系

```mermaid
flowchart TD
    U["未指派：保留宿主"] -->|"领域显式指派"| Q["当前请求"]
    Q -->|"完整安装成功"| M["Matching：匹配当前请求"]
    Q -->|"等待或失败且旧安装合法"| R["Retained：世界继续旧使用"]
    Q -->|"humanoid 无合法旧安装"| D["DefaultSubstitute：默认替代"]
    Q -->|"scoped replacement 无可用安装"| H["保留或恢复宿主"]
    M -->|"请求变更"| R
    R -->|"最新请求完整安装成功"| M
    D -->|"最新请求完整安装成功"| M
```

图中的边表示安装关系变化，不是统一资源状态机。LocalPlayer 构造时显式建立 default 指派；远端只有 model section 建立指派。动画 DELTA 不建立指派，Ready 不补发错过的播放。

请求模型、请求纹理与实际安装分别保存。A → B → C 时，合法 A 可继续 Retained；迟到 B 只结束自己的候选，不能覆盖 C。当前请求就是 default 且安装匹配时为 Matching；DefaultSubstitute 仅表示 default 替代另一个请求。

目录移除或未来读取失败不撤销合法旧安装。GUI 只用 Matching 3D 表示当前选择；世界可以继续 Retained。[客户端展示](../client-presentation/overview.md#选择与资源需求)决定图片、占位与点击资格。

## 完整安装与替换

候选应验证当前 request/profile、真实 subject，并完整构造组件、表达式 root、controller、processor、资源 holder 与实际变体。全部成功后，在同一 owner barrier 发布新安装；先撤销旧安装报告资格，再结束旧状态和自身 lease。

```mermaid
flowchart LR
    Ready["Ready 与接收者独立 lease"] --> Candidate["私有完整候选"]
    Candidate -->|"失败或激活前过期"| Reject["只关闭候选"]
    Candidate -->|"验证并在 barrier 发布"| Current["当前完整安装"]
    Current --> Borrow["同步 prepare / consume 借用"]
    Current --> Seal["撤销效果与输出资格"]
    Seal --> End["任务终态且最外层借用退出"]
    End --> Release["释放私有状态与自身 lease"]
```

私有准备失败保留健康旧安装与历史。候选期查询只见完整旧安装或 Unbound；同 owner 切模、reset、retire 和递归求值明确拒绝。发布后的外部绘制回调可改变请求或封口，不能递归安装；通知异常不回滚已发布安装。

安装入口不等待动画 worker。任务、借用或候选窗口占用时返回 Busy，不记录内容失败。协调方保留自己的 Ready anchor，下次 tick 再尝试；纹理导航则直接拒绝本次进入，不保留导航意图。

仅纹理变化时，兼容性应同时满足精确内容、逻辑 target、bake profile、binding、purpose、同一准备结果与 main/arm module 对象身份。旧安装还应健康、属于同一 owner 且无活动借用。兼容候选先私有验证几何与资源关联；激活先撤旧输出，再通知实际 target、刷新资源视图并转移状态关闭权。

兼容激活已修改共享状态，失败或取消应结束旧使用，不能回滚半修改 holder。成功替换保留组件初始化、采样历史、播放、controller、Roaming、物理与主体时间线；resource resolver 不保留旧 target。新模型则重建模型域，只承接同主体仍适用的位置历史，不复制任意组件。

## 暂停、reset 与 retire

| 事件 | 当前使用 | 后续资格 |
|---|---|---|
| 本机 body 屏蔽绘制 | 保持 producer 逻辑推进 | 绘制许可独立变化 |
| 纯消费者暂停 | 结束 demand、任务、安装与私有输出 | 保留指派和网络投影，解除全部限制后按最新请求重建；旧播放不排队 |
| reset | 结束当前使用 | 保留指派、请求及安装失败记忆 |
| 显式 reload | 结束当前使用 | 保留指派和请求，清除本 owner 的安装失败记忆 |
| retire | 撤销指派，结束当前使用 | 重复调用不重复释放；新显式指派可复用对象 |
| 异步宿主失活 | 移除预调度登记并 retire | 重新指派不自动恢复已移除登记 |

仍活跃的异步 owner 在显式 retire 后保留原登记；重新指派后可继续调度。同步 owner 不依赖预调度器回收资源。

封口先撤销效果与输出资格。任务真实结束且最外层同步借用退出后，实体根才清理私有状态与自身 lease。等待中断、清空 Future 或 GC 都不证明 worker 已退出。回调内封口不等待自身退出；后续重建仍经过同一串行边界。

显式路径在安全边界执行 `onModelEnded()`；组件没有 reset/retire hook，真实换模创建新 holder。单项清理失败不跳过后续清理，既有主失败保留，清理失败作为 suppressed。

## 失败记忆与恢复

实体的同步求值或异步任务发生 OOM 时，本次执行不交付准备结果；owner 消费任务失败，后续独立执行仍可进入。私有候选安装的 OOM 只拒绝本次安装，不写入 exact content 的永久失败记忆。已发布安装和独立 lease 保留；required default 失败仍传播。共享 holder 激活已改变状态时，仍按原规则结束旧使用。

严重 non-null 返回违约或组件生命周期失败结束 exact installation。Holder 先完成已成功 begin 的全部配对 end，再由根保存拒绝事实并封口；资格丢失按取消归约，不伪造组件故障。普通安装在后续协调机会采用 default 或宿主，不在 hook 或清理栈内递归安装。

新 holder 构造或首次 target hook 失败只拒绝候选。兼容替换开始修改共享 holder 后失败，结束旧使用；拒绝事实仍属于候选 exact request/content。失败只约束该 owner，不改变全局声明接纳或将内容标为全局损坏。

安装失败按 exact request/content 记忆。切走切回、普通 reset 或时间经过不解除记忆。内容身份变化后重新评估；显式 reload 只清本 owner 的记忆，不清共享资源失败资格。Default 候选失败也先记录拒绝再传播原异常，不递归降级或自动重建。

模型会话进入 intrinsic-default-only 后，普通安装失去求值与消费资格；下一次同步边界结束旧使用。Humanoid 采用 default，scoped replacement 恢复宿主。该输入不等于目录缺项，不撤销指派、Minecraft 连接或仍合法的新需求；会话裁决见[模型会话](../model-management/sessions/overview.md)。

## 查询与借用

事实查询只描述当前指派、请求、安装、关系、许可与工作。能力查询分别判断播放、求值、准备或精确结果消费的当前资格；不取得资源、不推进动画、不汇合任务、不执行 hook，也不预留未来资格。

查询在 client/render owner 串行窗口执行，worker 只查自己持有的窗口。调用方在显式业务入口完成同步，执行入口仍复核资格。GUI 的准备和消费另要求 Matching；hook 改变选择或许可后旧结果立即失效。宿主取消与实际消费共享本次准备结果，规则见[渲染交接](../rendering/overview.md#三阶段契约)。

未终结工作包括本 owner 的任务和尚未移交的 attempt/lease，不包括已结束的失败或 miss。取消自己的需求不等待共享 Flight 物理终结；自己的工作结束也不证明 target、GPU 或 allocator 已回收。

## 显式释放与 GC 回收

资源 owner 的 Cleaner 兜底物理释放，不执行实体或组件回调，也不保证及时取消网络需求。清理状态不保留生成 Root、component 或实体，防止反向保活。Compiled instance 释放 generation lease，短期 session 释放 program lease 与配额；模型 lease 和 native 输出沿用各自的一次性释放能力。

显式卸载、替换和退休尽早结束当前占用。共享 target 的关闭与宿主纹理回收仍由[客户端资源](../model-management/client-resources/overview.md)定义；独立接纳的播放按[音频终态](../model-management/audio/overview.md)结束。

当前 barrier 和任务映射见[实体实现](implementation/entity-and-frame-state.md)。真实退出重进、联动宿主和失败组合的证据见[动画问题](../../status/known-issues/animation.md)与[模型问题](../../status/known-issues/model-management.md)，本页不提供 GC 时限。
