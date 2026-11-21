# GPU Compute Renderer

GPU Compute Renderer 尚未实现；当前 CPU 渲染器的数据布局、缓存格式和调度契约都不构成未来的 GPU ABI。

## 目标

- 在 Minecraft 与光影包（shader pack）的约束内，保持姿态、面片与 UV、剔除、法线与切线以及透明层次等语义不变，并以 Blockbench 的导出表现为基准；
- 将静态几何存储、逐帧骨骼变换、面剔除和顶点生成迁往 GPU，降低 CPU 内存带宽占用与调度尾延迟；
- Java 侧继续掌握模型选择、纹理、`ResourceLease`、`RenderType`、CPU 回退路径和资源生命周期；
- GPU 侧的上传格式、dispatch 参数和排序策略独立做版本控制，不冻结 CPU 缓存格式或 AoSoA 布局。

## 可继承的语义

| 阶段 | 稳定语义 |
|---|---|
| bake / upload | 骨骼层级、透明与双面分类、反向几何、顶点位置、UV、法线、切线与手性 |
| extract / frame update | 可见骨骼、层级姿态矩阵、法线姿态矩阵、附着点与渲染上下文状态 |
| render / dispatch | 绘制矩阵、颜色、光照、PBR 属性、pass 选择、背面与透明深度语义 |

CPU 与 GPU 管线可以采用不同的数据布局和浮点中间表示，但可见面、UV、双面渲染与排序语义必须一致，连续数值需要定义明确的误差容限。附着点仍需回传 Java 可直接消费的小型结果。

## GPU 专属契约

- 上传格式、dispatch 参数和资源身份必须独立版本化，并覆盖设备特性、shader variant 与 pass 能力；不得沿用 `CubeGroup`、`RenderSchedule`、`VertexKind` 或 native CPU 借用布局。
- Java 侧的 render target 拥有 GPU 几何、管线和纹理绑定，由 `ResourceLease` 负责保活。发生资源重载、光影重载或设备丢失时，必须先撤销发布，等 GPU 队列执行完毕后再回收旧资源。
- 逐帧上传和输出缓冲区必须有容量上界；compute 与 draw 之间插入 barrier，跨帧复用缓冲区使用 fence 同步。透明排序留在 GPU 内完成或明确走回退路径，不能逐帧全量读回 CPU。
- 遇到不支持的 pass 或运行失败时，必须原子切换回当前 CPU renderer，不能把两条管线的半成品混在一起绘制。

## 进入 current 的条件

- 资源管理、能力探测、dispatch、同步、失效、回收和 CPU 回退全部贯通；
- 普通与反向 cube、透明面、非均匀缩放、PBR、世界与 GUI 渲染上下文，以及原版、Iris 与阴影 pass 均通过 CPU 对照和 Blockbench 语义验收；
- 性能表现与显存/内存上限经过真实负载验证，且没有把 CPU 派生缓存格式固化成 GPU ABI。

当前 CPU 渲染语义见[渲染架构](../architecture/rendering/overview.md)。
