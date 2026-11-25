# 动画已知问题

正常职责见[动画架构](../../architecture/animation/overview.md)；验证入口见[实体所有权验证](../../verification/entity-animation-ownership.md)与[Molang 声明验证](../../verification/molang-declarations.md)。

## 求值与语义

### 已知缺陷

- `isMoving` 存在逻辑错误，影响依赖它的 `CodedAnimationController`。
- LOOP 播放单次跨越多个完整周期时，当前事件推进只处理旧周期尾部与最终采样周期，中间周期可能漏发离散指令（需对照[主时间线因果](../../architecture/animation/controllers-and-playback.md#指令区间推进)明确多周期边界并补测）。
- 骨骼基准快照（snapshot）误用 `children_hidden` 初始化 `cubes_hidden`，可能混淆两个可见性通道。
- Controller 旋转混合保留不完整历史行为；当前 Schema 已移除 `blend_via_shortest_path`，读取器固定按 `false` 处理，模型无法表达该选项。

### 未实现范围

- 正常 Arrow `Vehicle` 仍缺少精确默认动画域，真实目录匹配尚未闭环。

### 未验证缺口

- 模型声音在真实 Forge 与 OpenAL 下的 once-only 触发、音量、设备拒绝/替换、设备容量与停止时序（路由规则见[声音触发](../../architecture/animation/controllers-and-playback.md#声音触发与宿主播放)）。
- 代表性复杂模型（如花嫁酒狐）正常选模后的声音、连招、reload 及 Better Combat 联动环境。
- `ysm.projectile_owner` 的装备观察目前限于 Render 线程，未扩展 live API 的线程安全承诺。

## Context、线程与生命周期

### 已知缺陷与局限

- `AnimationProcessor` 原地修改本 owner 快照与属性，无事务暂存或骨骼回滚；Extract 失败仅撤销输出，不恢复骨骼旧值，不保证任意 controller 异常后无损继续。
- 部分核心声明仍间接调用 ParCool、CosmeticArmor、ElytraSlot 和 SlashBlade，尚未完成与外部故障及组件生命周期的隔离。

### 未验证缺口

- 声明为 live 的世界与可选模组查询仍直接读取 `Entity`、`level`、`Minecraft` 或第三方 API，后台 worker 线程上的并发安全与同次读取一致性尚未验证。
- 真实低内存 OOM 场景、内置默认自身故障与重试、全部实体 profile 与可选模组注入组合（实机仅局部覆盖过 Curios、Geckolib 与 Superb Warfare）。
- 同次逻辑推进次数、脚本与声音/粒子/同步事件联动、多 `RenderContext` once-only、多实体与 UI/导出并发、reload/重进世界/断 I/O 下的长期回收。
- 远端安装期暂存消费中途退休、容量溢出、handler 故障与完整帧期成本（协议边界见[状态同步](../../architecture/animation/implementation/state-inputs-and-sync.md)，网络侧缺口见[网络问题](network.md)）。
- 输出复用与第一人称的实机缺口见[渲染问题](rendering.md#重复消费与第一人称)。

## GUI 与模型冷准备

### 已知局限

- GUI 解释路径与统一 `AnimationStore` 驻留模型会增加堆内存保留（替代原短命编译闭包），首次开页仍可能出现帧耗时峰值，卡片偏出画框问题见[GUI 取景](rendering.md#gui-取景)。
- 历史冷准备与源准备优化未能证实稳定净收益（默认源事实缓存已撤回），完整玩家安装与主循环不退化尚未闭环。

### 未验证缺口

- 完整模型/动画矩阵下的 GUI 真实输入变化、optional provider 线程边界、长期局部引用、物理 GC 及 native/GPU 内存占用。
- Molang 复杂表达式、完整安装及真实游戏帧期的净性能收益（受控微基准、标量分配与三轴收敛样本不能直接外推为整帧 FPS；复现见[编译基准](../../verification/animation-bake-benchmark.md)与[成本测量](../../verification/molang-declarations.md#成本测量)）。

## 联动与验证

### 未实现范围

- 女仆模组联动已移除（模型中的 `tlm` 动画集读取与内置动画资源暂留，不再提供实体接入或脚本接口）。
- 战斗、移动、载具和装备适配器仅部分接线，仍有大量目标尚未迁入统一声明框架。

### 未验证缺口

- 已接线适配器的统一输入边界线程审计与实机验收，当前不视为正式支持。
- 新音频链路的 headless raw/legacy 转换、显式受控关闭以及音频设备可听延迟（JNI/缓存入口见[音频缓存验证](../../verification/audio-prefix-cache.md)）。
- `BoneAttribute` 与 `BakedModel` 的前序遍历、可见性与 locator 缺少独立跨语言 golden，重复布局定义存在漂移风险。
- 真实实体与 controller/配置联动、Immersive Melodies 生命周期、多人远端同步、模型热切换、多 pass、第一人称共享、第三方声音后端与长期回收的联合验收。
