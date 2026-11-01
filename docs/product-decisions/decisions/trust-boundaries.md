# trust-boundaries

- Requirement: [REQ.preserve-gameplay-and-availability](../requirements/req-preserve-gameplay-and-availability.md#reqpreserve-gameplay-and-availability)
- Select: 客户端输入校验、可信服务端的能力底线
- Landing:
  - [standard](../../standards/protocol-v1/README.md#安全与能力边界)
  - [design](../../architecture/animation/molang-runtime.md#内容信任与预算)
  - [design](../../architecture/animation/molang-runtime.md)

## DD.validate-client-before-effect

- Constraints: [CON.client-input-untrusted](../shared.md#conclient-input-untrusted)
- Claim: 任何客户端输入必须先通过与其作用相称的结构、身份、权限和业务校验，才能影响服务端或其他玩家。
- Rationale: 客户端只能请求自己获准的操作，不能自行成为身份或游戏事实的权威；如果校验发生在状态应用或资源投入之后，拒绝已经无法隔离非法输入的影响。

### BC.invalid-client-input-has-no-effect

- Claim: 服务端对客户端网络包及其他输入检查边界、数值、主体、目标、权限和上下文；非法请求不得修改权威状态，也不得借拒绝前的处理造成无界资源占用或影响其他玩家。

## DD.trusted-server-with-capability-floor

- Constraints: [CON.server-trust-has-hard-limits](../shared.md#conserver-trust-has-hard-limits)
- Claim: 客户端按可信服务端处理普通模型业务，同时禁止服务端内容取得高敏感数据或游戏进程之外的危险能力。
- Rationale: 常规联机依赖服务端裁决与分发，全面对抗恶意服务端不是默认业务模型；玩家凭据和进程外安全却不属于模型功能可交换的代价。

### BC.server-content-cannot-exfiltrate-or-escape

- Claim: 模型、脚本、媒体和网络内容不得泄露玩家凭据等高敏感数据，也不得使内容处理风险逸出游戏进程；默认信任服务端不能豁免这些限制。
