# integration-risk

- Requirement: [REQ.preserve-gameplay-and-availability](../requirements/req-preserve-gameplay-and-availability.md#reqpreserve-gameplay-and-availability)
- Select: 可选联动失败、侵入范围与人工例外
- Needs:
  - 处理可选联动失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
  - 判断既有替换授权: [CON.visual-only-scope](../shared.md#convisual-only-scope)
- Landing:
  - [future](../../future/mod-animation-integration.md#适配边界)

### BC.incompatible-integration-is-optional

- Decision: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Claim: 联动缺失或版本不匹配不得阻断启动阶段；如果无法降级或降级成本过高，该组合只能提交人工决策，不能自动获得启动失败例外。

## DD.intrusive-change-needs-adjudication

- Constraints: [CON.visual-only-scope](../shared.md#convisual-only-scope)
- Claim: 对其他游戏内容的显式取代，以及无法安全降级的高风险侵入，必须具备经人工决策确认的产品依据。
- Rationale: 渲染替换可能截断原有流程并波及其他模组，局部实现便利不足以抵偿这种外部代价；人工评估时必须权衡真实玩法收益、完整故障传播、降级可行性及维护成本。

### BC.replacement-is-explicit-product-scope

- Claim: 只有已经人工明确并写入产品目标的内容才允许被修改或取代；玩家模型与动画替换的授权不扩展到无关玩法。

### BC.intrusion-exception-is-specific

- Claim: 每项无法满足默认降级原则的侵入例外必须记录触发条件、侵入点、受影响对象、完整风险传播、既有防护、降级为何不可行或成本过高、真实需求、理由、证据与人工接受范围；尚未定案不等于已接受。
