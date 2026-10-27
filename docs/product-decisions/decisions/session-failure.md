# session-failure

- Requirement: [REQ.bounded-model-distribution](../requirements/req-bounded-model-distribution.md#reqbounded-model-distribution)
- Select: unstable/stable 协议兼容边界与 Minecraft 连接保留
- Needs:
  - 目标分类降级: [DD.default-model-is-reliability-baseline](model-fallback.md#dddefault-model-is-reliability-baseline)
  - 游戏连接与错误隔离: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
  - 区分协议互通与作品语义兼容: [DD.explicit-support-is-stable](model-compatibility.md#ddexplicit-support-is-stable)
- Landing:
  - [implementation](../../architecture/network/implementation/transport-and-session.md)
  - [design](../../architecture/network/overview.md)

## DD.protocol-compatibility-has-bounded-cost

- Claim: 服务端模组版本与客户端模组版本的网络互通，在 unstable 阶段要求两端使用同一版本的同一构建；进入 stable 后只承诺不刻意打破已有兼容性，不承诺为旧版本刻意维护兼容。
- Rationale: 每个 YSM 模组 jar 同时包含服务端和客户端代码，两端使用同一制品即可获得配套实现。因此，兼容性对应关系是服务端安装的模组版本与客户端安装的模组版本之间的互通关系。为不同模组版本维护旧版读取、兼容适配和混用恢复，会持续扩大版本配对的验证组合与维护成本。Unstable 阶段要求两端使用同一版本的同一构建；stable 阶段避免有意破坏既有版本配对的互通，但不为跨模组版本互通承担专门维护义务。

### BC.unstable-protocol-requires-matched-builds

- Claim: 当前 unstable 阶段服务端与客户端必须安装同一模组版本的同一构建；模型协议版本兼容判断服从 [BC.development-version-qualifiers-are-exact](model-compatibility.md#bcdevelopment-version-qualifiers-are-exact)，不匹配时明确拒绝建立模型会话，不猜测旧版本语义，失败仍按本包的游戏连接保留契约处理。

### BC.stable-protocol-avoids-deliberate-breakage

- Horizon: future
- Claim: 进入 stable 阶段后，模组演进不刻意打破服务端模组版本与客户端模组版本之间已有的网络互通，但不承诺刻意维持、适配或恢复旧版本配对，也不保证任意不同模组版本可以联机。此边界只约束网络互通，不放宽已明确支持的模型可观察语义。

### BC.version-failure-is-session-local

- Decision: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Claim: 模型会话的版本或完整会话验证失败后，客户端按目标类别使用默认模型或宿主表现继续游戏，不主动断开 Minecraft 连接。
