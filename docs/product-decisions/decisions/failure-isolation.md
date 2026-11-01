# failure-isolation

- Requirement: [REQ.preserve-gameplay-and-availability](../requirements/req-preserve-gameplay-and-availability.md#reqpreserve-gameplay-and-availability)
- Select: 普通内容失败、数据保护、startup 与连接隔离
- Needs:
  - 模型会话版本或验证失败: [BC.version-failure-is-session-local](session-failure.md#bcversion-failure-is-session-local)
  - 判断可选联动的具体降级或人工例外: [BC.incompatible-integration-is-optional](integration-risk.md#bcincompatible-integration-is-optional)
  - 处理本地来源或缓存失败: [DD.user-model-sources-are-readonly](artifact-storage.md#dduser-model-sources-are-readonly)
  - 涉及目标呈现降级: [DD.default-model-is-reliability-baseline](model-fallback.md#dddefault-model-is-reliability-baseline)
  - 涉及默认兜底本身失败: [CON.default-failure-exception](../shared.md#condefault-failure-exception)
- Landing:
  - [implementation](../../architecture/rendering/implementation/vertex-output.md)
  - [design](../../architecture/model-management/catalog/overview.md#失败与拒收)
  - [status](../../status/known-issues/rendering.md#正确性与失败处理)
  - [design](../../architecture/rendering/overview.md)
  - [design](../../architecture/model-management/sessions/overview.md#失败与恢复资格)
  - [design](../../architecture/model-management/client-resources/overview.md#取得失败与纹理回退)
  - [design](../../architecture/animation/entity-lifecycle.md#失败记忆与恢复)

## DD.local-failure-degradation

- Constraints: [CON.normal-use-isolation](../shared.md#connormal-use-isolation)
- Claim: 正常使用中的失败必须降级受影响的内容或功能，并保留游戏主逻辑的可用性；fallback 回放中的宿主顶点消费异常不属于 YSM 的降级职责，具体边界见 [BC.render-failure-does-not-corrupt-scene](#bcrender-failure-does-not-corrupt-scene)。
- Rationale: YSM 提供视觉与创作价值；一个模型或一项联动失效不应让玩家失去整个游戏、服务端或其他内容，局部降级使故障代价与失败对象相称。可选联动或模型会话的价值不抵偿整个游戏启动或连接失败；共享渲染管线服务世界与其他模组，一次绘制也不能污染后续内容。

### BC.ordinary-failure-keeps-game-running

- Claim: 普通模组错误不得造成游戏崩溃、阻断 startup 或主动终止正常 Minecraft 连接；局部拒绝、停用或默认模型回退必须保持其他游戏逻辑正常。

Java OOM 的已有防御应由所属操作的高层边界终结；内部只保留防止误判坏内容、cache miss 或局部默认值所需的传播。失败不自动重试、不永久标坏内容，也不关闭整个模组。正常清理和已提交结果保留；required default 及宿主 `VertexConsumer` 的既有例外继续成立。原本没有防御的入口不要求增建设防。

### BC.protect-game-data

- Claim: 失败与降级不得破坏内存中的游戏实时数据、磁盘存档或与 YSM 无关的内容状态。

### BC.render-failure-does-not-corrupt-scene

- Claim: 正常使用中，YSM 可控的模型提取、容量校验和 native 顶点生成失败不得提交半完成输出，或遗留会污染后续内容的错误绘制状态和失效资源；降级不能造成其他实体或世界整体的错误呈现。fallback 回放中 `VertexConsumer` 抛出的异常直接传播给宿主，YSM 不捕获、吞掉、转换为替换失败、降级或回滚，也不承诺异常后继续渲染。YSM 自身输入、容量、格式与生成结果仍必须正确；自身 finally 清理不构成 consumer 事务回滚。
