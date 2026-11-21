# trust-boundaries

- Requirement: [REQ.preserve-gameplay-and-availability](../requirements/req-preserve-gameplay-and-availability.md#reqpreserve-gameplay-and-availability)
- Select: 客户端输入校验、可信服务端的能力底线
- Landing:
  - [standard](../../standards/protocol-v1/README.md#安全与能力边界)
  - [design](../../architecture/animation/molang-runtime.md#内容信任与预算)
  - [design](../../architecture/animation/molang-runtime.md)

## DD.validate-client-before-effect

- Constraints: [CON.client-input-untrusted](../shared.md#conclient-input-untrusted)
- Claim: 任何客户端输入必须先通过与其作用相称的结构、身份、权限和业务校验，之后才能影响服务端或其他玩家。
- Rationale: 客户端只能请求自己获准的操作，不能自行成为身份或游戏事实的权威。如果等到状态已经变更或资源已经投入后再校验，拒绝就无法隔离非法输入的影响。

### BC.invalid-client-input-has-no-effect

- Claim: 服务端对客户端网络包及其他输入检查边界、数值、操作主体、目标、权限和上下文。主动选模时，完整初始 roaming 与模型、纹理、权限必须全部合法才会生效；服务端同时应拒绝旧选择的迟到报告。非法请求不得修改权威状态，也不得在拒绝前的处理过程中造成无界资源占用或影响其他玩家。

## DD.trusted-server-with-capability-floor

- Constraints: [CON.server-trust-has-hard-limits](../shared.md#conserver-trust-has-hard-limits)
- Claim: 客户端按可信服务端处理普通模型业务，同时严禁服务端内容获取高敏感数据或突破游戏进程边界执行危险操作。
- Rationale: 常规联机依赖服务端的判定与分发，全面对抗恶意服务端不是默认业务模型；但玩家凭据与进程外安全属于底线，不属于模型功能可交换的代价。

### BC.server-content-cannot-exfiltrate-or-escape

- Claim: 模型、脚本、媒体和网络内容都不得泄露玩家凭据等高敏感数据，也不得让内容处理风险逸出游戏进程；默认信任服务端不能豁免这些限制。服务端不提供任意 Molang 源码执行命令或专用源码下发通道，仅保留客户端本地求值以及模型内的 sync 与 form 机制。
