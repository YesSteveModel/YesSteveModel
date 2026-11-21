# Bake 与派生身份

本页落实[三阶段契约](../overview.md#三阶段契约)与[几何与可见面](../overview.md#几何与可见面)，说明不可变几何、cache 资格和逻辑分区的当前实现边界。

## 三种表示

`BakeModel` 生成不可变的 `BakedModel` 以及对应的骨骼映射。三层表示各有独立边界：Model Schema 是公开语义规范，`BakedModel` 是内部运行期对象，serialized cache 则是依赖当前机器能力和 renderer ABI 的可删除派生物。Cache 既不是运行期内存映像，也不是公开 Schema 或 GPU 缓冲格式。

Renderer 只提供 bake 版本、native ABI、SIMD 能力、UV 约定与烘焙选项，精确的内容身份由[模型存储](../../model-management/storage/overview.md)解释。Java 侧几何描述与 native 载荷共用同一份 cache 身份；读取 cache 时一旦校验失败，就拒绝发布模型，也不会尝试迁移旧格式。

## 烘焙与交付

`NativeBakedModel.bake` 与 `NativeBakedModel.read` 产出同一套模型对象与骨骼映射载体。`serialize` 同步读取存活中的模型并产出独立拥有的字节数组，模型对象与序列化字节各自独立保活，互不撤销生命周期。采用失败时的回收规则见[JNI 资源交接](../../native-runtime/implementation/jni-and-memory.md#opaque-handle)。

骨骼的稳定先序遍历（preorder）同时约束 Java 属性映射与 native 骨骼层级。每个 `CubeGroup` 只归属一个骨骼和一个逻辑分区，`RenderSchedule` 保持这一划分边界。读取 cache 时会重新验证骨骼层级、索引、容量和分区一致性，不会仅凭文件存在就判定 Ready。

## 四逻辑分区

透明语义与面剔除资格共同决定几何块落入哪个逻辑分区。注意内部的 `cutout` 表示不透明（opaque），不等同于纹理 cutout，也不决定 Java 侧的 `RenderType`。

| 分区 | 当前映射 |
|---|---|
| `cutout` | 不透明且允许背面剔除，直接写入不透明输出 |
| `cutout_no_culling` | 不透明但不满足剔除条件，保留正反两面 |
| `translucent` | 存在透明洞、半透明 alpha 或强制透明，保留正反两面 |
| `translucent_culling` | 透明且允许剔除，保留烘焙绕序（winding）下的反向完整 cube 语义 |

Alpha 分类按基础纹理的 UV 覆盖区域采样决定：全透明面可直接省略，二值透明（全透与不透混合）也归入透明分区。版本化 UV 在分类前即已确定，分区本身不会重定义模型法线或绕序。可见面的产品保证范围仍由[渲染设计](../overview.md#几何与可见面)界定。

## 能力与输出语义

Bake、cache 读取与顶点输出共用进程启动时选定的同一套 SIMD 能力，能力不匹配的 cache 不会复用。分组对齐产生的 padding 只占位计入容量，不增加额外几何。

PBR 切线（tangent）基于最终 UV 的几何语义计算；最终矩阵变换、镜像和背面修正留到顶点输出阶段执行。遇到退化 UV 时产出确定结果，Bake 阶段不预设绘制矩阵。当前缺口与验证证据见[渲染问题](../../../status/known-issues/rendering.md)。
