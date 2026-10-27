# Bake 与派生身份

本页落实[三阶段契约](../overview.md#三阶段契约)与[几何可见面](../overview.md#几何与可见面)，定位不可变几何、cache 资格和逻辑分区的当前边界。

## 三种表示

`BakeModel` 产生不可变 `BakedModel` 与骨骼映射。Model Schema 是公开语义，`BakedModel` 是内部运行对象，serialized cache 是依赖机器能力与 renderer ABI 的可删除派生物。Cache 不是运行内存映像、Schema 或 GPU 格式。

Renderer 只提供 bake 版本、native ABI、SIMD capability、UV 约定及选项；精确内容身份由[模型存储](../../model-management/storage/overview.md)解释。Java 几何描述与 native payload 使用同一 cache 身份，读取验证失败不发布模型或迁移旧格式。

## 烘焙与交付

`NativeBakedModel.bake/read` 交付同一模型与映射载体。`serialize` 同步读取存活模型并产生独立 owning bytes；模型与 bytes 各自保留，不互相撤销寿命。采用失败由[JNI 资源交接](../../native-runtime/implementation/jni-and-memory.md#opaque-handle)定义。

骨骼稳定 preorder 同时约束 Java 属性映射与 native 层级。`CubeGroup` 属于一个骨骼和一个逻辑分区，`RenderSchedule` 保持该边界。Cache 读取重新验证层级、索引、容量与分区一致性，不凭文件存在恢复 Ready。

## 四逻辑分区

透明语义与面剔除共同决定分区。内部 `cutout` 表示 opaque，不等于纹理 cutout，也不决定 Java `RenderType`。

| 分区 | 当前映射 |
|---|---|
| `cutout` | 不透明且允许剔除，直接写 opaque 输出 |
| `cutout_no_culling` | 不透明但不满足剔除条件，保留正反面 |
| `translucent` | 透明洞、部分 alpha 或强制透明，保留正反面 |
| `translucent_culling` | 透明且允许剔除，保留 baked winding 的反向完整 cube 语义 |

Alpha 分类读取基础纹理的 UV 覆盖区域；全透明面可省略，二值透明也进入透明分区。版本化 UV 在分类前确定，模型 normal 与 winding 不由分区重新定义。可见面的保证范围仍由[渲染设计](../overview.md#几何与可见面)拥有。

## 能力与输出语义

Bake、cache read 与顶点输出使用同一次进程 SIMD 能力选择，能力不匹配的 cache 不复用。分组 padding 仅表示容量，不增加几何。

PBR tangent 基于最终 UV 的几何语义；最终变换、镜像与背面修正由输出阶段承担。退化 UV 给出确定结果，Bake 不预设 draw matrix。缺口与证据见[渲染问题](../../../status/known-issues/rendering.md)。
