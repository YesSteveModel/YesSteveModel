# 模型管理已知问题

正常职责见[模型管理架构](../../architecture/model-management/overview.md#实现与验证入口)，验证入口见[模型管理验证](../../verification/model-management.md)。

## 调度与平台

- **已知权衡**：本地玩家资源隔离队列缩短了排队等待时间，但以降低后台 CPU 吞吐为代价；OS 线程优先级调整尚未证明额外帧时间、声音补给或 GC/safepoint 收益（见[调度基准](../../tools/benchmark/README.md#本地玩家资源优先调度)、[线程策略](../../architecture/runtime/implementation/execution.md#线程策略)）。
- **未实现范围**：macOS 运行时与 `NativeLibUtil` 加载尚未接通（仅 native 库编译链接通过）；平台与指令集支持范围见[平台需求](../../product-decisions/requirements.md#reqplatform-availability)。
- **未验证缺口**：低核负载下的真实实体安装、GPU 与帧时间收益；Linux、Android 与 macOS 的完整运行时验证。

## 目录、存储与安装

### 已知局限

- Windows JDK provider 缺少目录 `fileKey`，受检路径无法保证检测到所有同路径同类型的外部替换（依赖[操作期目录结构稳定](../../product-decisions/decisions/artifact-storage.md#bccache-maintenance-does-not-own-sources)）。
- 玩家偏好内存覆盖层允许未提交的近期值丢失，不保证跨查询快照或多进程读到最新 donor；原位首次初始化不保证并发双方同时成功。
- Cleaner 不保证物理回收时限；外部音频后端的取消交接不保证任意第三方回调立即返回（职责见[音频终态](../../architecture/model-management/audio/overview.md#后端接管与播放终态)）。

### 未验证缺口

- 网络共享文件系统、未列出 FS provider、任意超长根目录与多启动器环境下的目录探测与两池清理。
- 完整配置授权矩阵（含强选无绕过、已接纳传输保留、配置与目录扫描交错及 OS watcher 自动投递）。
- 新 typed 存储下的 `selection` 与 `core` 宿主联调分支、真实在线登录与全部首次继承/存储失败组合。
- Linux DrvFS 下针对统一 `models` 目录的配置授权、raw 内部 junction 拒绝与显式恢复。
- 大规模目录的多进程两池维护窗口、真实双端坏图/独立图替换交错、低内存与进程内退休下 render 线程销毁防双重释放、真实第三方音频后端与 OpenAL 设备，以及完整 v3/丢失通知/在线 delta/动画资产的远端联合实机验收。

## 模型加载、展示与图片交接

### 已知缺陷与风险

- 固定内容对照中，`eager=false` 首帧收益未稳定复现，Private Bytes 增量与帧耗时 P99 存在上升风险，冷加载整体性能收益尚未通过验收。
- 发布各阶段的自然取消、Busy 帧成本与冷资源期限分布尚未独立审查。

### 未验证缺口

- GUI 解释路径在真实列表中的完整缓存到期与重取、悬停/聚焦往返、完整卡片名称终态像素及全部模型/shader 组合（见[动画问题](animation.md#gui-与模型冷准备)、[展示实现](../../architecture/client-presentation/implementation/page-runtime.md)）。
- 移除客户端非音频字节额度后的真实 GL 大图、integrated 游戏联合场景与长期 RSS/VRAM 占用。
- 展示用独立图缓存跨全部来源、替换/reload 与世界退出交错的长期回收，以及跨 GPU/Linux 环境的独立 lease 隔离与 `DerivedStorageWork` 在真实 OS 线程耗尽下的表现。

## 导出与管理补图

### 已知缺陷

- 执行管理补图时，builtin `wine_fox/18_wedding` 报告 `Expression compiler is unavailable`（其余项目继续完成），该模型的表达式接纳与编译可用性尚未闭环。

### 未验证缺口

- `/ysm export`、`/ysm export-all` 与管理补图在作者图、混合批次、各阶段文件或宿主故障、活动世界停止、大目录、双管理员并发及最坏合法图片成本下的表现（职责见[转换与导出](../../architecture/asset-pipeline/implementation/conversion-and-export.md#preview-取得与显式-export)）。
