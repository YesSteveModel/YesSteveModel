# Forge 客户端物理整帧对照

夹具经 `runClient` 启动独立 Forge 客户端，在平坦单人世界中创建真实的 `RemotePlayer` 实例。实体通过正常的能力绑定、资源加载、动画调度、JNI 提取和渲染路径运行。Gradle init script 只在这次启动中加载观测程序。游戏目录与本次启动专用的 `YSM_HOME` 位于结果目录，Gradle 构建使用另外指定的目录。

## 运行

在项目根目录执行，分别指定不存在的结果目录、可复用的 Gradle 输出目录和已构建的本机 JNI 库。夹具在指定的 Gradle 输出目录下保留独立项目缓存，以免其他构建切换输出目录时清理正在运行的客户端类。窗口保持在前台并维持大小。进入游戏后，脚本控制相机和动作，完成后退出客户端。

```powershell
python src/verification/physics-frame-host/run_host.py `
  --output C:/bench/physics-run `
  --build C:/bench/physics-gradle `
  --native C:/native/ysm.dll
```

默认比较 1、50、100 个常规接触实体及 1 个高密度接触实体。每个规模交错运行三种配置，每次依次采集在视野内、视野外、返回原视野的窗口。重复时轮换配置和初始视野。每个窗口先运行 25 个 warmup tick，随后采集 60 个 tick。相机保持位置和俯仰不变，以转向切换视野。可使用 `--case dense_1 --repetitions 1 --sample-ticks 20` 快速检查入口。

需要实测骑乘时，在独立运行中加 `--riding`。夹具让每个玩家乘上一个使用内置载具模型的船，在玩家和载具完成模型安装后确认生产调度已为全部骑手发布预采样值。`samples.json` 和 `windows.csv` 会额外记录 `samplePassengers` 耗时，骑乘轮次包含额外的载具更新与绘制。

合成模型包含 15 个动态体、2 个身体代理和 2 个动画锚点。每个动态体有一个胶囊，每个代理有一个盒，锚点无图元。两种物理版本的几何、约束和身体碰撞相同。关闭片间接触时，动态体各占一个集合，代理加入所有这些集合。完整互挡版再给所有动态体加入同一个集合。高密度版缩小发束间距并增大胶囊半径。可用 `--case dense_1 --repetitions 1 --contact-check` 单独运行调试快照见证，读取实际接触节点对数。

## 更换作品

通过 `--models` 指向包含三个变体的资产根目录，通过 `--plan` 指定配置。模型名取相对于资产根目录的 raw 目录或 `.mxc` 文件。各变体使用相同几何、纹理和动作。纯动画版仅去掉物理声明。关闭片间接触版保留身体代理及原有回正目标。可为 `animation` 填入模型的额外动作名，它会随转身回放触发。空字符串沿用模型的默认动画。

```json
{
  "repetitions": 3,
  "sampleTicks": 60,
  "cases": [
    {"name": "hair_1", "entities": 1, "models": ["hair_animation", "hair_no_interpart", "hair_full"], "texture": "main", "animation": "turn"},
    {"name": "hair_50", "entities": 50, "models": ["hair_animation", "hair_no_interpart", "hair_full"], "texture": "main", "animation": "turn"},
    {"name": "hair_100", "entities": 100, "models": ["hair_animation", "hair_no_interpart", "hair_full"], "texture": "main", "animation": "turn"}
  ]
}
```

单实体的近景接触对照同时加 `--contact-check --witness`。对每件作品，完整互挡版先以约 60 Hz 推进，再沿同一动作重放 10 次约 100 ms 主步和一次 249 ms 主步，另用一次恰好 250 ms 的跨度检查清零复位。纯动画与关闭片间接触版沿用同一近景时间表。第四组用真实 10 FPS 运行 30 个游戏 tick，再将帧率上限恢复到 260，沿用游戏时钟。计划可用 `witnessAngle` 指定根朝向偏移。

在受控回放中，宿主于渲染开始时根据动作时间写入当前与前一身体朝向。验证 agent 在创建动画事件时写入同一时间对应的 `renderTicks`，物理时间由生产入口照常读取。完整互挡近景组选择存在身体接触且可用原始 float32 时钟精确表示 250 ms 差值的起点。近景和长步在起点、长步末、恢复后及静止末尾共享精确动作时刻。近景参考的区间长度在这些时刻之间平均分配。

验证 agent 在正文绘制借用中记录每个已接受主样本的接触及姿态。接触监听保持开启，PNG 只绘制作品轮廓。`clockSamples` 以 `(visit, sample)` 关联接触记录与宿主纳秒计时。`renderNs` 取 Forge `RenderTickEvent.START` 到 `END`，包含同帧动画、物理与调试快照采集，不含帧间限频。`demandJoinNs` 和 `drainJoinNs` 分别记录绘制需求点与世界渲染收尾的任务等待。截图读取和 PNG 写入在 `END` 截止点之后执行，耗时另存为 `screenshotNs`。用只包含成品文件的 `--models` 目录和对应计划运行独立 MXC 回放。测量稳态整帧预算时关闭该观测模式。

如需测量充分预热后的同屏配对，在计划中设置 `"pairing": true`，并给出正整数 `warmupTicks`、`settleTicks`、`sampleTicks` 和 `repetitions`。夹具先为每件作品、每种数量及每种配置执行一次只热身的访问，再同屏执行对称三联。纯动画与两个物理配置分别按 ABA 和 BAB 取样。每个窗口在 `settleTicks` 后开始计时，分析按各窗口实际采样中点，由两侧窗口插值比较中间窗口。保持宿主空闲，完成整轮后查看端点差值区间和基线波动。

## 读取输出

| 文件 | 内容 |
|---|---|
| `run.json` | 启动命令、源码状态、JNI 文件哈希及客户端退出结果 |
| `assets.json` | 各配置实际安装的模型 ID、每体图元数和碰撞集合 |
| `samples.json` | 逐帧数据、配置顺序和实际 framebuffer 尺寸 |
| `windows.csv` | 各窗口帧数、百分位、可见与替换计数、世界存续、主步成功和拒绝数 |
| `comparison.json` | 完整帧 p50/p95/p99 的配对差值及纯动画窗口波动。稳态模式另记录各个三联的中点插值和两侧端点差值 |
| `contacts.json` | 普通接触见证的逐配置样本数与片间、身体接触峰值 |
| `clock.json` | 近景见证的接受数、时间轴、受载条件、边界复位和恢复核对 |
| `box-overlap.json` | 用作品盒与逐主样本姿态执行分离轴测试得到的几何深度 |

完整帧取相邻 Forge `RenderTickEvent.START` 的差，包含绘制、交换、帧间限制和游戏 tick。另记 START 到 END 的渲染阶段。观测 agent 分别计时渲染线程接管后运行任务、绘制请求处的 `FutureTask.get()` 及世界渲染收尾的 `FutureTask.get()`。`get()` 耗时包含已完成任务的调用成本。记录以完整窗口为单位剔除视野覆盖不符、物理拒绝步进或缺失主步的对照。

参考设备的最终预算裁决需带上主机配置、实际帧数和纯动画窗口波动。此入口的网络覆盖范围是本地一侧的资源和实体调度，客户端创建玩家的步骤在窗口外完成。
