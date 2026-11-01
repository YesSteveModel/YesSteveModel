# animation-isolation

- Requirement: [REQ.evaluate-entity-animation](../requirements/req-evaluate-entity-animation.md#reqevaluate-entity-animation)
- Select: 实体状态隔离、多 pass 副作用去重与单动画失败
- Needs:
  - 跨客户端协调状态: [DD.script-selects-sync-values](script-sync.md#ddscript-selects-sync-values)
  - 模型绑定与资源生存期: [DD.single-current-catalog](catalog-publication.md#ddsingle-current-catalog)
  - 单动画失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [implementation](../../architecture/animation/implementation/entity-and-frame-state.md#实体级所有权)
  - [implementation](../../architecture/animation/controllers-and-playback.md#副作用阶段)
  - [design](../../architecture/animation/overview.md)

## DD.animation-state-is-entity-local

- Claim: 动画求值中的可变状态按实体隔离。
- Rationale: 多个玩家可以使用同一模型但执行不同动作，复用模型资产不意味着共享动作进度或脚本运行状态；混用这些状态会使一个玩家改变另一个玩家的表现。

### BC.entity-isolates-animation-state

- Claim: 一个实体的控制器进度、脚本内存、随机结果和骨骼姿态不直接成为另一实体的可变状态；跨客户端需要协调的数据只能通过明确同步语义传播。

## DD.render-observation-does-not-repeat-actions

- Claim: 对同一次逻辑推进结果增加观察或渲染 pass，不得重新执行已经发生的动作。
- Rationale: 主时间线推进可能随帧率和调度频率变化，但界面、世界或额外 pass 对已有结果的重复观察不是新的播放生命周期，不能由此重复发送事件、播放声音或消耗副作用资源。

### BC.animation-effects-follow-logical-actions

- Claim: 同一次逻辑推进的脚本动作、粒子、同步事件和声音不会因重复观察再次触发；此约束不把主时间线的推进频率或动作次数固定为帧率无关。

### BC.expression-action-permission-is-fixed

- Claim: 每条顶层表达式在入口固定本次动作许可；内部函数和 Java provider 调用沿用该许可。Provider 的声明 effect 只决定该次调用是否获准，不为嵌套调用建立收窄或恢复窗口。重复观察仍不产生新的动作，实体状态隔离和全部求值成功后才提交效果的边界保持有效。

### BC.animation-failure-is-local

- Decision: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Claim: 单个动画的确定性读取、解析或绑定失败只影响该动画，不使其他已验证动画失效；只有错误进一步阻断整体模型逻辑时才进入严重模型错误降级。
