# Shared

PG = 产品目标；SCN = 场景；CON = 跨域约束。业务正文仅在此定义，按引用读取。

## Goals

### PG.model-replacement

- Claim: YSM 显式替换原版模型与动画系统以增强视觉表现，覆盖玩家、投射物和载具，并接受这项系统替换对相关内容造成的兼容损失。

### PG.creator-controlled-content

- Claim: 玩家和创作者能够添加、迭代、选择、分享和调试自定义模型，并保留作品的来源创作者关联。

### PG.shared-multiplayer-presentation

- Claim: 服务端或联机主机可以分享模型，玩家能够看到自己和其他玩家的模型，并尽力保持模型选择与动画状态接近。

### PG.preserve-host-gameplay

- Claim: YSM 的正常使用不损害游戏进程、主逻辑、整体画面、数据一致性及无关原版或其他模组内容。

### PG.maintainable-open-mainline

- Claim: 模型行为能够被开放协作理解和验证，并在控制复杂度与兼容成本的前提下随 Minecraft 和平台演进。

### PG.compose-mod-presentation

- Claim: YSM 通过与武器、装备、饰品及渲染模组联动，尽可能补回系统替换造成的相关内容损失，并接纳明确的新增表现能力，同时保持各自的玩法职责。

## Scenarios

### SCN.replacement-breaks-dependent-content

- Claim: 原版模型与动画系统被替换后，依赖其模型部件、姿态和附着流程的原版或其他模组内容会失效，导致相关游戏内容缺失或视觉效果错误。
- Motivation: [PG.model-replacement](#pgmodel-replacement)

## Constraints

### CON.normal-use-isolation

- Claim: 正常使用中的任何模组错误都不得破坏游戏正常运行，必要时降级相关功能；默认模型的指定例外不能扩展为普通内容或联动的失败许可。

Java OOM 沿已有防御的操作边界终结，不以局部零值或坏内容继续成功；不扩建全入口防护。具体范围见[失败隔离](decisions/failure-isolation.md#bcordinary-failure-keeps-game-running)。

### CON.visual-only-scope

- Claim: 显式替换原版模型与动画系统及其已接受的关联兼容损失属于当前授权范围；这种授权不扩展为任意修改非视觉玩法规则，也不放宽对游戏主逻辑、数据一致性和无关原版或其他模组内容的保护。

### CON.client-input-untrusted

- Claim: 服务端默认不信任任何客户端输入，尤其网络包，必须严格校验后才允许其产生作用。

### CON.server-trust-has-hard-limits

- Claim: 客户端默认信任服务端的普通业务内容，但绝不允许泄露玩家凭据等高敏感数据或使风险逸出游戏进程。

### CON.default-failure-exception

- Claim: 默认模型及其回退路径的失败必须在开发期间严格排除，并尽量在 startup 提前暴露；阻断性初始化失败视为模组 startup 失败，运行时默认兜底仍失败则不属于正常使用，不承担再恢复宿主的义务。该例外不扩展到普通模型本身的失败。
