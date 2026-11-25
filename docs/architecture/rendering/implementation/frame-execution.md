# 帧状态、发布与借用

本页对应[资源与状态](../overview.md#资源与状态)、[三阶段契约](../overview.md#三阶段契约)与[状态与绘制并发](../overview.md#状态与绘制并发)，列出逐帧提取、局部根输出、Java 预调度与当前并发边界的实现映射。

## 输入、输出与状态

```mermaid
flowchart LR
    Attributes["实体 BoneAttribute"] -->|"同步临时输入"| Extract["ModelState::Extract"]
    Baked["共享 BakedModel"] -->|"保留"| State["native ModelState"]
    Java["GeoModelState"] -->|"拥有"| State
    Extract -->|"完整发布"| State
    State -->|"拥有"| Pose["pose、normal 与 locator 数据"]
    Java -.->|"借用 view"| Pose
    State -->|"有效帧输入"| Render["renderer::Render"]
```

- **先撤后发**：`GeoModelState.extract()` 开始时先撤销 Java 侧消费资格，JNI 入口校验输入前同步撤销 native 侧 valid 标志，native 不保留输入 `BoneAttribute`。层级遍历、调度构建、JNI 写回及 Java 侧 view/计数/locator 后置检查全部通过后才发布结果，任一步失败均保持不可消费。
- **失效与借用有效期（内存安全边界）**：`NativeModelState.invalidate()` 同时撤销两侧消费资格并清空 Java 侧 view 与计数（可保留已分配底层缓冲）。所有 view 与 locator 消费须在下一次 Extract、`invalidate` 或 `close` 前结束——Java 引用可达不延长底层 native 地址有效期。

```mermaid
stateDiagram-v2
    [*] --> Invalid
    Invalid --> Extracting: 开始提取
    Valid --> Extracting: 新状态或 context
    Extracting --> Valid: 全部验证和发布成功
    Extracting --> Invalid: 任一阶段失败
    Valid --> Valid: draw 成功或纯输出拒绝
    Valid --> Invalid: publication 损坏或关闭
```

## 层级遍历与可见性

- Java 属性数组与 native 层级共用 `BakedModel` 固定前序骨骼顺序。Extract 按层级组合父级姿态；遇到非有限属性值、零缩放或非有限计算结果时跳过整棵子树。
- 隐藏当前几何仅影响本骨骼及其 locator；隐藏子级保留当前骨骼并跳过全部后代。颜色、透明度与 glow 不沿层级继承，非法 packed 值直接令提取失败。Locator 映射指向当前有效姿态，不做长期拷贝。通道定义见[骨骼输出](../../animation/implementation/processor-and-bone-output.md#boneattribute)。

## 局部根提取

- **`extractSubtree()`**：接收当前模型内合法根骨骼索引与完整同序属性数组，计算时忽略根外祖先的变换、隐藏标记与无效属性；负数、越界根索引或长度不匹配直接失效。提取完成后仅允许消费本次 render 与 locator 索引指向的姿态数据。
- **第一人称三槽**：针对对应 target 与 `GeoModel` 维护 `LeftArm`、`RightArm`、`Background` 三份独立输出（跨帧保留，变体切换、换模或实体退休时待借用退出后关闭）：
  - 左右手读共享纯动画属性，缺某一侧根骨骼仅影响该侧，合法空几何仍算手部消费成功；新采样或实际求值撤销左右手准备资格，损坏输出可由正常 producer 恢复（不重放已提交动作）。
  - 背景读模型声明初始属性。
  - `PreparedPart` 绑定安装、几何、部位与修订号（revision），不保活模型资源。

## Java 预调度与 context

每次绘制均刷新矩阵、光照、相机与绘制元数据，姿态复用不复用绘制输入；`RenderContext` 决定姿态语义与复用条件，与执行线程解耦：

| 路径 | 状态与执行 |
|---|---|
| 合格的 level 实体 | 写规范不可变槽（canonical immutable），由 Java worker 求值并 Extract，渲染按需汇合 |
| 未预调度的 level 实体 | 使用 immutable 槽，在当前线程同步执行 |
| inventory、paperDoll、firstPersonMod | 使用可变槽（mutable），同步求值，不覆盖 canonical 槽 |
| 本地第一人称 irisShadow | 使用 mutable 槽，与第三人称状态隔离 |
| GUI 预览 | immutable 仅限同帧复用；模型列表走专属 GUI 许可，其余 GUI 同步执行 |
| 独立 hand 负责方 | 沿正常 level immutable 时间线推进；背景不推进动画，三部分分别局部提取 |

- **Canonical 复用**：仅在完整发布后记录复用资格，复用前校验状态有效且模型、context、采样请求一致；进入新帧、触发 mutable 求值或收到不兼容请求时撤销资格。恢复输出不重置动作门禁。
- **绘制借用复核**：`GeoRenderData` 绑定实体负责方与当前安装，`consumePrepared` 在同一调用窗口完成借用。第三人称取消宿主与实际消费共用同一份结果，hook 返回后重新核验（不通过二次 update 修补）；GUI 额外核对 Matching。回调触发 reset/retire 立即撤销消费资格，物理释放延后至借用退出。

## 任务发布与并发边界

### GUI 模型列表准备任务

- 模型列表先固定本帧 `GuiFrameInput`，再为每个私有预览实例授予单次专属 GUI 任务许可，在单一执行者内连续完成绑定、动画求值与 Extract（含同卡片冷前景与背景）。
- 主线程可先执行首张卡片并注册纹理，再 `join` 其余后台任务；首卡失败仍须完整交接全部已接纳后台任务。
- 任务返回后、`join` 前不开放实例访问；提交被拒立即归还许可；页面关闭先封口，待在途任务交接完成后关闭自有输入。
- `consumePrepared` 借用本次输出并保留原有 hook 与资格复核；renderer、dispatcher、buffer、GL 调用与额外实体绘制留在主线程。该许可仅用于显式采用的模型列表 GUI 实体。

### 世界渲染异步调用范围

每次 `renderLevel` 的 `HEAD` 开启本帧实体异步范围，`RETURN` 完成全部已登记任务的交接：

- **单次登记**：预调度实体在提交前登记，同步实体在首次接纳投影前登记；单次范围内至多登记和调度同一负责方一次。提交失败、reset 或 retire 不提前撤销登记，等全部任务与最终投影交接完毕后统一结束。
- **按需单实体汇合**：渲染实体时若目标任务仍在队列未领取，渲染线程可直接就地执行；否则仅等待对应 `Future.get`，其余实体继续后台运行。范围与权限见[实体任务交接](../../animation/entity-lifecycle.md#执行权与任务交接)。
- **统一收尾与异常**：`RETURN` 完成全部剩余 `Future.get` 后归约最终 `body -> arm` 投影。`Future` 覆盖求值、Extract 与完整 `finally`；单实体失败时仍交接其余实体再传播归约失败；等待遇中断先完成全部 `get` 再恢复中断标志。主线程未捕获崩溃绕过 `RETURN` 的场景不在支持范围内。

### 并发边界与串行约束

- `renderLevel` 范围外的显式实体更新同步执行；独立 transient 编译可跨范围存活，仅产出独立程序对象。同一实体在不同 context 下串行求值。
- 共享事实读取遵循[共享事实读写窗口](../../integration/overview.md#共享事实读写窗口)：单实体按需 `get` 仅归还该实体私有访问权，全部世界读者与 GUI 读者交接完毕后才重开共享写入；等待任务期间不轮询宿主队列。
- `RenderSchedule` 划分当前帧互不重叠的输出区间；进程级 `ParallelExecutor` 仅读取已发布状态，全部任务结束并建立内存可见性后交付顶点输出。
- **当前 `NativeRenderer` 并发前提**：因当前共享了 executor、透明排序临时缓冲（scratch）与 fallback 缓冲，`renderer::Render` 目前由 Minecraft render thread 按全局串行发起（若未来拆分这些共享可变缓冲与执行态，可放开多实体并发 `Render`）。同一个 `ModelState` 的 Extract、Render、resize 与释放按串行使用；不同实体的 Java 动画求值与 Extract 并行执行。
