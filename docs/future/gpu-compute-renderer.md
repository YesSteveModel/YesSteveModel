# GPU Compute Renderer

GPU Compute Renderer 尚未实现；当前 CPU 数据布局、cache 与调度契约都不是 GPU ABI。

## 目标

- 在 Minecraft 与 shader pack 约束内保持姿态、face / UV、剔除、normal / tangent 和透明层次语义，以 Blockbench 为 authoring 基准；
- 将静态几何、逐帧骨骼变换、面剔除和顶点生成迁到 GPU，降低 CPU 带宽与调度尾延迟；
- Java 继续拥有模型选择、纹理、lease、`RenderType`、CPU fallback 和资源生命周期；
- GPU 格式、dispatch 与排序独立版本化，不冻结 CPU cache 或 AoSoA。

## 可继承的语义

| 阶段 | 稳定语义 |
|---|---|
| bake / upload | 骨骼层级、透明与双面分类、反向几何、position、UV、normal、tangent / handedness |
| extract / frame update | 可见骨骼、层级 pose、normal pose、附着点与 context 状态 |
| render / dispatch | draw 矩阵、颜色、光照、PBR、pass、背面与透明 depth |

CPU 与 GPU 可以使用不同布局和浮点中间值，但可见面、UV、双面与排序语义必须一致，连续数值需定义容差。附着点仍要形成 Java 可消费的小型结果。

## GPU 专属契约

- 上传格式、dispatch 参数和资源身份必须独立版本化，并覆盖设备、shader variant 与 pass capability；不得继承 `CubeGroup`、`RenderSchedule`、`VertexKind` 或 native 借用布局。
- Java render target 拥有 GPU geometry、pipeline 与 texture binding，并由 `ResourceLease` 保活。Reload、shader reload 或设备丢失先撤销 publication，再等待 GPU 完成后回收旧资源。
- 逐帧上传与输出必须有界；compute 与 draw 之间使用 barrier，跨帧复用使用 fence。透明排序留在 GPU 或明确回退，不能逐帧全量读回 CPU。
- 不支持的 pass 或失败路径必须原子选择当前 CPU renderer，不能混合两条管线的半成品。

## 进入 current 的条件

- 资源、capability、dispatch、同步、失效、回收与 fallback 形成闭环；
- 普通/反向 cube、透明、非均匀缩放、PBR、`level` / GUI、Vanilla / Iris / shadow 通过 CPU 对照和 Blockbench 语义验收；
- 性能与内存上限经真实负载验证，且没有把 CPU 派生格式固化为 GPU ABI。

当前 CPU 语义见[渲染架构](../architecture/rendering/overview.md)。
