# GPU Compute Renderer

## 现状

GPU Compute Renderer 尚未实现。当前 CPU 渲染器的数据布局、缓存格式与调度契约仅服务于 CPU 管线（见[渲染架构](../architecture/rendering/overview.md)），不作为未来 GPU ABI。

## 目标与核心约束

目标是将静态几何存储、逐帧骨骼变换、面剔除与顶点生成迁往 GPU 以降低 CPU 内存带宽与调度尾延迟，同时由 Java 侧继续掌握模型选择、纹理、`ResourceLease`、`RenderType`、CPU 回退与资源生命周期。

### 跨管线继承的语义

在 Minecraft 与光影包约束内，GPU 管线以 Blockbench 导出表现为基准保持以下语义一致（连续数值定义明确误差容限，附着点继续回传 Java 可直接消费的小型结果）：

| 阶段 | 稳定语义 |
|---|---|
| bake / upload | 骨骼层级、透明与双面分类、反向几何、顶点位置、UV、法线、切线与手性 |
| extract / frame update | 可见骨骼、层级姿态矩阵、法线姿态矩阵、附着点与渲染上下文状态 |
| render / dispatch | 绘制矩阵、颜色、光照、PBR 属性、pass 选择、背面与透明深度语义 |

### GPU 管线设计约束

- **独立版本化**：上传格式、dispatch 参数与资源身份独立做版本控制（覆盖设备特性、shader variant 与 pass 能力），与 CPU 的 `CubeGroup`、`RenderSchedule`、`VertexKind` 和 AoSoA 布局解耦。
- **资源寿命与同步**：Java render target 通过 `ResourceLease` 持有 GPU 几何、管线与纹理绑定；资源/光影重载或设备丢失时先撤销发布，待 GPU 队列完成后回收旧资源。逐帧缓冲设有容量上界，compute 与 draw 间以 barrier 同步，跨帧复用以 fence 同步；透明排序在 GPU 内完成或走回退路径，避免逐帧全量读回 CPU。
- **原子回退**：遇到不支持的 pass 或运行失败时，整次绘制原子切回当前 CPU renderer，避免混合两条管线的半成品。

## 演进思路

1. 建立独立版本化的 GPU 能力探测、上传格式、同步与 `ResourceLease` 回收机制，并打通原子 CPU 回退；
2. 覆盖普通/反向 cube、透明面、非均匀缩放、PBR、世界/GUI 上下文以及原版/Iris/shadow pass，对照 CPU 管线与 Blockbench 完成语义验收；
3. 在真实负载下验证性能与显存/内存上限后，再纳入正式支持范围。
