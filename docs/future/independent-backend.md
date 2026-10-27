# 独立 Backend

独立 Backend 尚未实现。当前只有权威选择与部分 transport 抽象；入站、握手、玩家状态和多数控制逻辑仍需脱离 Forge 编排。

## 目标边界

Backend 可以成为当前游戏连接的唯一远端玩家状态权威，但不能与游戏服务端联合裁决状态。状态权威与[外部模型源](external-model-sources.md)是正交能力：Backend 可以只同步状态，也可以另行提供目录与资产；成为权威不会自动使它成为模型源。

Backend 应从当前协议中复用仍适用的玩家状态和控制语义，并定义独立 transport binding；不得恢复已删除的资产分片协议，也不得依赖 Forge、Minecraft 连接对象、JNI 或客户端的 native 能力实现。

`AUTO` 依次选择 Game Server、Backend、Local Only；`BACKEND` 只尝试 Backend，`LOCAL_ONLY` 禁止远端权威。权威只在连接建立期选择，不能在同一连接内热切换。

## 最小接入条件

1. **认证连接**：建立、关闭、超时和重连具有独立身份与 generation，凭据绑定 transport peer。
2. **双向 transport**：覆盖发送、入站分派、关闭通知、背压和明确的 completion。
3. **握手编排**：协商版本、feature、限制与状态策略，双方验证后原子激活。
4. **单一权威**：分离用户偏好与实际权威，拒绝旧连接结果和跨权威热切换。
5. **主体映射**：写入主体来自已认证 peer；player ID 只用于路由，不能替代认证。
6. **中立入口与生命周期**：抽离 Forge 回调，确保取消、错误、迟到结果和断开关闭全部会话资源。

若 Backend 同时分发模型，还必须闭合 catalog revision / resync、权限与凭据、资产背压/取消，以及 publication 与会话的原子绑定；不能发布客户端无法取得的悬空目录项。

## 实施顺序

1. 补齐 transport-neutral 入站、关闭、握手与会话编排；
2. 完成认证后的状态权威和旧连接隔离；
3. 再独立接入目录与资产，不与外部模型源混为一轮；
4. 通过 Protocol conformance、恶意输入、取消、重连和多人验证后再标记为 current。

当前网络边界见 [网络架构](../architecture/network/overview.md#职责与边界)，协议要求见 [当前网络协议](../standards/protocol-v1/README.md)，实现状态见 [当前支持状态](../status/support-and-verification.md)。
