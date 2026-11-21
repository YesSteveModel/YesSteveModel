# mod-integration

- Requirement: [REQ.integrate-mod-content](../requirements/req-integrate-mod-content.md#reqintegrate-mod-content)
- Select: TaCZ、SlashBlade、背包、Curios、Oculus 与 Iris 联动及未来桥接 API
- Needs:
  - 界定外部玩法职责: [DD.rendering-only-replacement](visual-gameplay-boundary.md#ddrendering-only-replacement)
  - 处理高风险侵入或例外: [DD.intrusive-change-needs-adjudication](integration-risk.md#ddintrusive-change-needs-adjudication)
  - 可选依赖不兼容: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
  - 外部模型附着: [DD.preserve-vanilla-attachments](attachments.md#ddpreserve-vanilla-attachments)
  - 外部动作适配: [DD.model-controller-overrides-builtins](bone-and-controller.md#ddmodel-controller-overrides-builtins)
  - 状态读取与输入: [DD.client-evaluates-visible-state](animation-sampling.md#ddclient-evaluates-visible-state)
- Landing:
  - [Molang declarations](../../architecture/animation/molang-declarations.md)
  - [future](../../future/mod-animation-integration.md)
  - [status](../../status/known-issues/animation.md#联动与验证)

### BC.integration-does-not-redefine-gameplay

- Decision: [DD.rendering-only-replacement](visual-gameplay-boundary.md#ddrendering-only-replacement)
- Claim: 持枪、开枪、匍匐、连招及装备展示等联动表现，不得自行改变武器伤害、攻击判定、装备能力或其他模组的玩法状态。

## DD.compose-external-models-and-animation

- Claim: 外部模组提供的模型与特效可以接入 YSM 角色呈现，并由 YSM 提供所需的角色动作适配。
- Rationale: 枪械、武器和装备已有成熟的美术内容，但角色模型系统被替换后，原有的附着关系与动作会随之失效。接入已有绘制并提供对应的角色动画，可以尽量补回这些损失，同时避免重制资产带来的成本与表现偏差。

### BC.weapon-and-equipment-integration-scope

- Claim: 已明确的武器与装备联动按以下分工形成玩家可见的组合表现。

| 联动内容 | 外部内容职责 | YSM 职责 |
|---|---|---|
| TaCZ | 枪械模型、特效及枪械玩法 | 接入枪械渲染，提供持枪、开枪、匍匐等角色动画 |
| SlashBlade | 太刀模型、攻击特效及武器玩法 | 提供连招动画 |
| Sophisticated Backpacks | 背包模型及背包玩法 | 接入背包渲染 |

## DD.expose-integration-state-to-creators

- Claim: 创作者可以通过脚本读取联动内容中与模型表现相关的状态。
- Rationale: 饰品等装备信息能让创作者重新适配作品的外观与动作，而不同作品需要的表现各不相同。开放状态查询能为修复和扩展这些关联表现留出空间，无需 YSM 为每种作品硬编码一套动作。

### BC.script-can-query-curios-equipment

- Claim: Curios 联动向模型脚本提供查询玩家所佩戴饰品类型的能力；读取结果仅用于表现，不取代饰品系统自身的状态与玩法判定。

## DD.use-provider-render-capabilities

- Claim: YSM 通过渲染联动向创作者开放宿主组合提供的高级材质能力。
- Rationale: 高级视觉效果需要与当前渲染管线协同工作。复用提供方的既有能力既可以提升作品表现，又能避免另建全局管线而破坏其他内容。

### BC.oculus-iris-exposes-pbr

- Claim: YSM 向创作者开放对应加载器下兼容提供方的 PBR 能力：Forge 对接 Oculus，Fabric 对应 Iris。两者是不同环境下的对应提供方，不要求同时安装。

当前 Forge 1.20.1 主线的渲染联动对象是 Oculus，提及 Iris 只说明 Fabric 下的对应关系，不扩大当前主线的平台迁移范围。上述分工不承诺兼容任意提供方版本；联动缺失或版本不匹配时，遵循共享的可选联动降级契约。

## DD.open-integration-to-external-adapters

- Horizon: future
- Claim: 提供公共 API，使其他模组既可以直接适配 YSM 系统，也可以由独立桥接模组实现双方联动。
- Landing: 当前仅交付[统一 Molang 声明与业务 view](../../architecture/animation/molang-declarations.md)这一子范围；模型、渲染与完整适配仍属于 future。
- Rationale: 系统替换所影响的内容组合超出 YSM 自身能逐一维护的范围。让内容提供方与第三方参与适配，可以进一步降低替换带来的破坏，并分担持续增长的联动维护成本。

### BC.current-molang-api-supports-direct-and-bridge-adaptation

- Claim: 当前 Molang 子范围允许内容提供方或独立桥接模组声明函数，但必须遵守公共值约束、owner 与 effect 边界以及类级失败隔离；该接口不授予 gameplay、模型资源或渲染所有权。
- Landing: [声明契约](../../architecture/animation/molang-declarations.md)、[接纳](../../architecture/integration/implementation/extension-loading.md#扩展兼容检查与注册)

### BC.future-api-supports-direct-and-bridge-adaptation

- Horizon: future
- Claim: 规划中的公共适配 API 必须既允许其他模组对接 YSM 模型与动画系统，也允许第三方作为桥梁组合双方能力；外部适配仍服从无关内容保护与联动失败降级边界。
