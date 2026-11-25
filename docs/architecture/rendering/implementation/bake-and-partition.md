# Bake 与派生身份

本页对应[三阶段契约](../overview.md#三阶段契约)与[几何与可见面](../overview.md#几何与可见面)，说明不可变几何、cache 资格和逻辑分区的当前实现映射。

## 三种表示

`BakeModel` 产出不可变 `BakedModel` 与对应骨骼映射，三层表示各自独立：

- **Model Schema**：公开模型语义规范。
- **`BakedModel`**：进程内只读运行期几何对象。
- **Serialized cache**：依赖当前机器 SIMD 能力与 renderer ABI 的可删除派生物。

Renderer 仅向存储层提供 bake 版本、native ABI、SIMD 能力、UV 约定与烘焙选项，精确内容身份由[模型存储](../../model-management/storage/overview.md)管理。Java 几何描述与 native 载荷共用同一 cache 身份；读取 cache 校验失败时直接拒绝发布，不执行旧格式迁移。

## 烘焙与交付

- `NativeBakedModel.bake` 与 `NativeBakedModel.read` 产出相同的模型对象与骨骼映射载体。`serialize` 同步读取存活模型并产出独立拥有的字节数组，模型对象与序列化字节各自保活。采用失败的回收规则见[JNI 资源交接](../../native-runtime/implementation/jni-and-memory.md#opaque-handle)。
- 骨骼稳定前序遍历（preorder）同时约束 Java 属性映射与 native 骨骼层级。每个 `CubeGroup` 归属单个骨骼与单个逻辑分区，`RenderSchedule` 保持该划分。读取 cache 时重新校验骨骼层级、索引、容量与分区一致性。

## 四逻辑分区

透明语义与面剔除资格共同决定几何块落入哪个逻辑分区（内部名称 `cutout` 表示不透明 opaque，与纹理 cutout 或 Java `RenderType` 无关）：

| 分区 | 当前映射 |
|---|---|
| `cutout` | 不透明且允许背面剔除，直接写入不透明输出 |
| `cutout_no_culling` | 不透明但不满足剔除条件，保留正反两面 |
| `translucent` | 存在透明洞、半透明 alpha 或强制透明，保留正反两面 |
| `translucent_culling` | 透明且允许剔除，保留烘焙绕序（winding）下的反向完整 cube 语义 |

Alpha 分类按基础纹理 UV 覆盖区域采样决定：全透明面省略，二值透明（全透与不透混合）归入透明分区。版本化 UV 在分类前确定，可见面保证范围见[几何与可见面](../overview.md#几何与可见面)。

## 能力与输出语义

- Bake、cache 读取与顶点输出共用进程启动时选定的 SIMD 能力，能力不匹配的 cache 不复用。分组对齐 padding 仅计入容量占位。
- PBR 切线（tangent）基于最终 UV 计算，退化 UV 产出确定结果；最终矩阵变换、镜像与背面修正留到顶点输出阶段。验证缺口见[渲染问题](../../../status/known-issues/rendering.md)。
