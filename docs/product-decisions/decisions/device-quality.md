# device-quality

- Requirement: [REQ.platform-availability](../requirements/req-platform-availability.md#reqplatform-availability)
- Select: 受限设备基础可用性、高端效果、帧率边界、设备配置默认
- Needs:
  - 判定最低适配范围: [BC.minimum-platform-baselines](platform-baselines.md#bcminimum-platform-baselines)
  - 评估工作量与性能目标: [DD.main-loop-has-priority](workload-budget.md#ddmain-loop-has-priority)
- Landing:
  - [verification](../../verification/policy.md#性能验证)
  - [status](../../status/support-and-verification.md)
  - [status](../../status/known-issues/rendering.md#并发与性能)
  - [design](../../architecture/native-runtime/overview.md#设备配置默认)
  - [implementation](../../architecture/native-runtime/implementation/runtime-capabilities.md#设备配置默认)
  - [implementation](../../architecture/model-management/client-resources/implementation/resource-ownership.md#完成与采用)

## DD.high-end-quality-with-basic-availability

- Claim: 在选定平台范围内，优化优先发挥高端设备的性能与视觉能力，同时为达到最低基线的老旧和移动等受限设备保留基础可用性。
- Rationale: 高端设备承载优质视觉效果的主要优化价值，受限设备则需要能参与基本模型玩法；两者不必承担相同效果与处理成本，基础可用性与高端优化都应保留各自价值。

### BC.basic-device-reference

- Claim: 基础体验观测设备参考为 Intel 第八代桌面 i5、核显和 4 GB 内存，用于比较性能与优化效果；实际最低平台要求由平台基线定义，受限设备保留基本模型使用能力即可。

### BC.basic-availability-preserves-high-end-capabilities

- Claim: 最低基线保障基础可用性，不承诺所有模组内容、高级效果均可用或性能达标；不得为统一到最低基线而削减高端设备的内容呈现能力或性能，也不得阻止高端设备利用较新的平台能力。

### BC.frame-rate-depends-on-content

- Claim: 实际客户端帧率受模型规模、同屏玩家数量与运行环境影响，YSM 不作严格帧率承诺；性能目标用于优化，不能作为任意内容和规模下的帧率保证。

性能观测的软约束与服务端优化目标见[保持游戏正常运行](../requirements/req-preserve-gameplay-and-availability.md)。

### BC.device-derived-configuration-defaults

- Claim: Native 会结合设备硬件信息与本次 JVM 最大堆容量给出统一分档。分档只用来决定缺失配置的默认值，不会覆盖两个配置各自的合法显式值。当关键信息未知、探测失败或 native 不可用时，一律采用低配默认。
- Rationale: 自动展示与未使用模型保留分别消耗计算和内存。统一的保守默认可以降低受限设备的初始成本，独立的显式配置则保留玩家自主选择；档位本身不构成帧率保证。

| 判据 | 低配条件 |
|---|---|
| 平台 | Android、Windows 7，或平台不能可靠确定 |
| 总物理内存 | 不大于 8 GiB；不以空闲内存或 swap 替代 |
| JVM 最大堆 | 不大于 4 GiB；不以当前提交量、空闲堆或 RSS 替代 |
| x86 SIMD | 检测到的 SIMD 类型不是 AVX2 或 AVX512 |
| x86 总物理核 | 少于 6 个；SMT 逻辑线程不重复计数，P 核与 E 核均计入，不采用经 affinity、parked 或 allocated 过滤后的调度核数 |
| 拓扑及其他适用事实 | 拓扑、内存、最大堆、架构、平台或适用 SIMD 未知 |

`GiB = 2^30 byte`。只有全部适用判据信息均已知且通过检查时，才归为高配。ARM 平台不适用 x86 SIMD 和六核判据，但仍要求 CPU 拓扑已知。命中任一低配条件后允许提前返回；分档不探测主频、GPU 或实时负载，也不改变 renderer 调度。

| 配置 | 高配缺失默认 | 低配缺失默认 |
|---|---|---|
| `general.ModelScreenEagerLoading` | `true` | `false` |
| `general.ModelRetentionCount` | `30` | `10` |

两个配置各自独立生效。`ModelRetentionCount` 的合法范围为 0 至 `Integer.MAX_VALUE`。该数量仅限制当前没有消费者的完整 Ready target，不限制活跃使用中的数量、内存字节数、空闲时间或瞬时峰值。超出上限时，只撤销最旧的 cache 引用，默认模型仍由其必需持有方（required owner）保活。旧配置键 `general.UnusedModelRenderTargetLimit` 不读取也不迁移；仅存在旧键时，按新键缺失处理。
