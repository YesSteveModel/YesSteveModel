# 动画已知问题

正常职责见[动画架构](../../architecture/animation/overview.md)。局部自动化和宿主观察只证明各自覆盖的边界。

## 求值与语义

- `isMoving` 有逻辑错误，影响依赖它的 `CodedAnimationController`。
- 骨骼基准 snapshot 错用 `children_hidden` 初始化 `cubes_hidden`，可能混淆两个可见性通道。
- Controller 旋转混合仍保留不完整的历史行为。当前 Schema 已无 `blend_via_shortest_path`，reader 固定按 `false` 处理，模型不能表达该选择。
- 旧 `ysm.bone_absolute_pivot` 不会解析为当前 `ysm.bone_pivot_abs`，依赖旧名的行为缺口仍在。
- 模型声音已有内容、渐进消费、pending/submitted stop 和外部后端拒绝的局部自动化；真实 Forge 的 once-only、音量、OpenAL 拒绝/替换、设备容量与停止时序仍未验收。路由与 ownership 见[声音触发](../../architecture/animation/controllers-and-playback.md#声音触发与宿主播放)。
- 花嫁酒狐 main/arm 完整静态集合已在原 limits 内通过 headless 冷编译；原控制器经生产 frontend/coded-controller 在独立 cold/disk-warm JVM 中核对分支、命令和返回值。旧资格 family 的预算失败已不适用，但正常选模后的声音、连招、reload 与 Better Combat 环境仍未验。制品与 oracle 见[集成验证](../../verification/molang-declarations.md)。

## Context、线程与生命周期

<a id="default-startup"></a>

- 完整 default 冷准备的 expected 稀疏传播已有工作量账本；冻结输入与环境的冷暖对照样本不足以形成预定置信界，耗时和分配的联合收益仍无结论。新 JVM 暖路径的 Program decode、semantic、source generation、backend compilation 均为零；分配只是在 prepareSet span 内的 JFR 采样估计。方法见[动画编译基准](../../verification/animation-bake-benchmark.md)。
- 隔离 Forge 的 LocalPlayer/RemotePlayer 已消费代表性默认程序，验证状态及 reset/close 隔离、首次消费不新增编译/解码，以及 required 读取失败不发布 partial service。完整双实体动作、视觉、多 pass、reload/断 I/O 与长期回收仍缺联合证据。
- 异步求值没有不可变输入快照，会直接读取 live `Entity`、`level`、`Minecraft` 和可选模组 API；同次求值可能混入不同时间点数据或违反外部 API 的线程限制。
- Controller、instruction、defer/event、config 与真实队列/watch 已有 cold/warm 值和 effect oracle；Forge 已观察消费与最终 framebuffer。同次逻辑推进次数、脚本/声音/粒子/同步事件和多 `RenderContext` once-only 仍未端到端联合验证。
- 兼容纹理变体、双 owner 状态隔离、候选/清理失败及 worker 换模/退休已有受控回归。内容级准备复用的 pin、取消、默认初始化、跨 set 拒用和借用来源已有局部验证；真实 reload、重进、多实体/UI/export、联动组合、长期收敛及完整准备净成本仍未验。
- 实体任务交接、完成未 get、Extract 普通失败隔离与动作／投影保留、不同输入和种子的串并行参考、联合 body／vehicle／arm 投影及 transient 关闭账本已有受控回归。真实 Forge 已观察正常与受控提前 `renderLevel` 返回、捕获提交／归约失败后正常返回、独立 compiler 跨返回，以及页面／world／旧 arm 退出；可选模组注入组合仍未验。方法见[实体所有权验证](../../verification/entity-animation-ownership.md)。
- 组件五 hook、owner 时间裁决、六阶段故障、异步终态及 builtin-only default 已通过受控 owner 回归；打包矩阵和独立 JVM 冷/暖加载通过。资源消费阶段仍有替身，真实 Forge 的视觉、多 pass 和可选模组线程安全未验。入口见[组件生命周期验证](../../verification/molang-declarations.md#组件生命周期)。
- 历史冷准备对照在共同保留 Janino linkage 与 BindingPlan 复用后，单消费者 MAIN 的耗时、CPU 和分配仍出现超过噪声的退化。多纹理/多消费者收益不能抵消该负样本；绕过 prepared target admission 的 Factory fixture 不能代表完整 startup 或活跃 target 复用。历史对照已结束，完整帧期性能仍无结论。
- `AnimationProcessor` 原地修改本 owner 的 snapshot/attribute，没有事务 staging 或骨骼回滚。提取失败撤销输出，不恢复全部旧骨骼值，也不证明任意 controller 异常后可无损续用。

输出复用与第一人称的实机范围见[渲染问题](rendering.md#重复消费与第一人称)。

## GUI 与模型冷准备

GUI 闭包、用途/主体隔离、玩家 MAIN 借用和 cache-only 门禁已有功能回归。默认源事实缓存已撤回：完整冷准备对照未证实净收益，且缓存增加驻留与匹配责任；Prepared/指纹复用保留分配收益证据。

早期 GUI 像素耗时下降不能替代 FULL 不退化；随后 FULL 对照同时改变主体 binding 与编译器，不能单项归因。固定编译器、主体、输入与身份后的消融及实际撤回对照仍未确认完整冷准备提速。最终单边整页样本窗口不同，最后的 cache-only 门禁仅做功能验证；完整玩家安装和最终主循环不退化尚未闭环。方法见[源准备成本](../../verification/molang-declarations.md#成本测量)，当前画面范围见[GUI 取景](rendering.md#gui-取景)。

受检路径的重复复核已收敛，机制见[存储边界](../../architecture/model-management/storage/implementation/persistence.md#固定区域与文件边界)。Windows Winefox 单卡的独立进程冷加载 A/B 已复现延迟下降；动画与几何均为 COLD，GUI 闭包输入一致。目录深度成本由[路径基准](../../tools/benchmark/README.md#受检路径访问)单独测量。现有 FULL 冷准备基准的 native 提取输出保持一致，发布使用 headless 接缝。卡片测量止于非零顶点帧，模型偏出画框仍未修复；帧期观察仅覆盖短窗口。

## Molang 与同步

- Typed AOT、统一声明、固定许可、同轮共享状态、完整 builtin 静态计划与独立发布组合已有自动化。普通受控标量、同轮数量斜率和轮固定成本已有旧新配对样本；复杂表达式、完整安装和游戏帧期仍无净收益结论。旧数组夹具不满足当前缺值初始化规则，不能作为共同基线。
- 普通 typed primitive、拒绝门禁、内部函数和准备后 transient 的稳态分配证据不包括 binding 构造、Object 观察装箱或真实载荷。Tail、record、效果、失败与单骨骼 literal 插值由独立探针单列；这些样本不代表完整姿态调度。冷编译/暖解码、制品、特化与进程峰值清单不能外推游戏常驻内存、动作、帧预算或长期回收。入口见[成本测量](../../verification/molang-declarations.md#成本测量)。
- Java owner 严重返回违约已有事务丢弃、降级、隔离、失败记忆及内容变化恢复回归。Forge 已观察 command/watch 的 UNSET/0/false、ACTION 抑制、声音 handle/控制和重连拒绝旧连接；普通/shared-player/default 的完整 entity 路由、真实严重失败/default 故障与 retry、跨 owner/controller 声音 scope、听测和玩法权威快照仍未验。Test-only 开发审计不代表生产插桩。无过滤 Java 套件仍有修改前基线也能复现的资源聚合边界失败，不能用局部通过代替全量通过。见[宿主验证](../../verification/model-management.md)。
- Roaming 已接入 full/delta，但 inactive storage 仍按模型派生的 32-bit 短键分组，碰撞与无变量删除语义尚未解决。
- LocalPlayer 恢复已有 NBT、先上报／先安装、临时实例、清空、卸载和玩家实例转移的受控回归。JNI 投影和第一人称 owner 回归通过。2026-10-30，Windows／Forge 47.4.3 的酒狐存档副本完成轮盘变身、退出主菜单和重进。重进首轮 FULL 含 2 个 roaming 变量，Watch 的 `a=b=1` 与小狐狸形态保持。两次保存后的 NBT 均保留 `a=b=1`。真实多人和故障交错矩阵未验。
- Body dirty 与 arm 非消费快照已有局部回归，真实空手第一人称和 body/vehicle 发送、服务端接收已有轨迹。完整时钟、presence、失败/交错矩阵及帧期成本仍未闭环，见[Roaming 验证](../../verification/molang-declarations.md#准备复用与-roaming)。
- `ysm.sync` 没有目标 identity 或独立频率限制；实体缺失、generation/model 或会话变化会丢弃事件。真实双端 relay/disconnect 尚未验证，正常协议边界见[状态同步](../../architecture/animation/implementation/state-inputs-and-sync.md)。

## 联动与验证

- 战斗、移动、载具和装备 adapter 仅部分接线，大量目标未迁入；已接线者也未完成统一输入边界线程审计与实机验收，不能视为正式支持。
- 无 optional provider 的 Windows Forge dedicated 已物化 resident default 并启动；新音频链路的 headless raw/legacy 转换与 graceful shutdown 仍未验。缺依赖发布矩阵不证明 optional-present 模组可用。
- 仍缺完整真实 entity/controller/config、IM 生命周期、多人远端、模型热切换、多 pass、第一人称共享、第三方声音后端和长期 reference convergence 验收。JNI 音频续接、取消、预算与驻留证据见[音频缓存验证](../../verification/audio-prefix-cache.md)，headless 结果不证明设备可听延迟。
- `BoneAttribute` 与 `BakedModel` 的 preorder、可见性和 locator 跨语言契约缺独立 golden，重复布局定义仍有漂移风险。

女仆模组联动已移除；模型中的 `tlm` 动画集读取与内置动画资源暂时保留，不提供女仆实体接入或脚本接口。
