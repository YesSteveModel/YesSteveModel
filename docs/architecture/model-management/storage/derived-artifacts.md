# 派生物身份与加载

上级契约：[存储](overview.md#seam-contract)。

## 职责与边界

本级细化[存储的精确身份](overview.md#精确身份与原子提交)，负责几何、未绑定动画输入（动画数据 + 未绑定程序载荷）与 AOT 制品的身份划分、缓存复用、原子提交与加载校验。三类派生物解耦独立生成与持久化；构建 FULL 候选时由构造方核对动画输入与 AOT 的组合一致性。

## Seam Contract

### 边界与权威

本地暖加载信任机制仅适用于本地生成的表达式制品，不扩展到外部字节、模型 chunk 或几何缓存，也不防备本地制品与摘要同时被外部篡改。

### 前置条件与输入

#### 独立身份与原子集合

派生键不含业务 `ModelId`，只覆盖精确容器内容与产物实际依赖的输入；渲染器只提供技术 profile。三类派生物各自独立建立身份：
- **动画输入**：独立 expected 覆盖精确内容、逻辑目标与动画集、来源位置、实际默认补全来源、源转换选项、语言输入格式与动画布局；每动画独占数据块，非动画程序按需读取，查名称索引不解码函数体。
- **AOT 制品**：在动画输入之上封闭执行用途、主体 binding、冻结声明与编译环境、编译后端及版本；仅 Java 绑定、主体计划或后端变化时原动画输入仍有效。两者拥有独立文件、内存缓存与原子提交。
- **组合校验**：FULL 候选发布前按受检程序描述符逐一核对来源、角色、内容、AOT 导出、实际默认补全来源与接收模型选项；不提前解码全部动画，延迟块在消费时校验。
- **ABI 与版本边界**：宿主动画/表达式契约用全局动画 ABI，引擎/后端/内部制品用独立引擎 ABI（见[ABI 维护规则](../../../abi/maintenance.md#缓存与表达式制品)）；实际引擎包名写入 AOT 索引以隔离开发环境与重定位包。unstable/dev/snapshot 派生物仅按完整原始版本字符串精确复用，不迁移旧 ABI 产物。

#### GUI 与 cache-only

AOT 缓存按根主体 binding、用途与编译环境分区，专供 FULL、手部与中央预览使用（分区仅做廉价快速排除，命中后仍校独立 expected）。GUI 无 AOT 也不设持久化解释分区，直接获取共享未绑定动画与程序输入，按实际消费解码绑定表达式（函数体首次调用时读）；准备结果中已保留与描述符配对的源程序时直接复用内存副本（仍保留缓存与动画块校验），绑定结果随[AnimationStore](../client-resources/overview.md#动画资源所有权与退休)存活。用途划分见[执行用途与主体资格](../../animation/molang-runtime.md#执行用途与主体资格)。

Cache-only 允许从已有本地输入建内存解释程序，不执行 AOT、不联网、不新写盘，输入不足返回未命中；远端 cache-only 只校验目标实际需要的 chunk（普通 offline 打开仍核验全部 lazy chunk）；离线未命中不演变为普通失败记录。

### 结果与保证

每实际 CACHE 区域维护一个 `Bake` 索引，几何、动画输入与 AOT 共用，同区域内本地/builtin/remote 共享派生物。`Bake` 只记录关联来源 CID 不存引用计数，存在未知覆盖时不判为无引用。

完整 `CACHE_GEOMETRY` / `CACHE_ANIMATION` 直接作为分区 `cacheVersion`；动画输入与 AOT 共用 `ANIMATION` 类别并以内部私有布局域隔离（旧合并布局域按未命中处理：普通路径冷重建，cache-only 零写入未命中）。持久化先预留整数 ID 与 token，经 `f/b/s/<id>` 原子发布到 `f/b/<id>`（16 位小写十六进制），再短事务更新 `Bake` 索引；并发 writer 生成独占文件互不覆盖，索引写失败不撤销已交付内存结果。

#### 暖加载信任

expected 一律从已验证输入现场重建，不信任制品文件头自带期望值：
- **本地暖加载表达式制品**：先核对版本、长度、完整摘要与独立重建 expected，再进 loader 完整校验类闭包、ABI 与内部完整性。
- **冷编译直交 loader**：刚冷编译产出的原始不可变制品在 registry、`BindingPlan` 与限制参数匹配时，loader 可跳过重复语义检验（仍必校独立 expected、parent、真实 `Class` 身份与可见性，并执行类定义、Root ABI/工厂解析、链接与常量构造）。普通构造、字节复制或磁盘恢复对象不继承该豁免。
- **几何缓存命中**：普通与 cache-only 共用同次读取，经固定 channel 完整校验 manifest、独立 expected 与 payload 后交 native 解码器（打开后磁盘路径被替换不影响本次数据）。冷烘焙先构运行几何对象，再异步提交独立 metadata 与共享 native 引用。

### 所有权与时序

每存储区域分别维护不可变动画输入缓存与原始 AOT 缓存；AOT 准备直接向动画输入存储申请，不自建动画 producer。后台持久化任务各自保活输入并独立落盘，不回头替换已交付内存结果；一次上层请求退出不取消其他并发请求或已接纳存储任务。[启动预热](../../runtime/overview.md#启动模型一次性预热)复用同一路径，Ready 后不阻塞等三类持久化落盘。

`BakedAnimationSet` 长期 source 只存受检 opener 与 expected；Controller 发现与实体同步动画各自持有局部批次视图（同能力本地与 fallback 共用一 channel，实体在同步入口临时绑定并于 `finally` 撤销）。每次实际读取仍校验长度、描述符、摘要与身份；普通读失败时批次 owner 关当前阶段、局部上下文开新阶段让 fallback 与健康动画继续（关闭失败、取消与 OOM 不恢复）。staging 校验器借用 writer 的 channel 不取得关闭权。

索引命中交付前及预留 staging 时均向实际 CACHE 根登记保留 `(pool, fileId, instance)`（持续至 JVM 退出，请求取消不提前释放）；延迟打开与数据库操作借用根消费登记，显式关闭先封口再等物理工作返回。缓存淘汰只撤 store 引用，不影响已交付消费者。

### 失败与拒收

普通准备仅初始缓存读失败转冷准备；编译、烘焙或运行对象构造失败直接拒绝候选。三类派生物任一持久化失败/取消只终止该类持久任务，不撤销已交付内存候选。

### 证据与依赖

依赖[客户端资源](../client-resources/overview.md#用途与派生物)与[执行用途与主体资格](../../animation/molang-runtime.md#执行用途与主体资格)；验证与限制见[模型管理验证](../../../verification/model-management.md)和[模型管理问题](../../../status/known-issues/model-management.md)。

## 设计取舍

- **复用本地 producer 的内部证明**：省去重复语义重验，代价是限定 producer 资格并在每次交付时守住加载校验。
- **拆分动画输入与 AOT 制品**：Java 绑定或编译环境变化时仍可复用已提取的动画输入，代价是 FULL 候选构造方需独立核对两者组合一致性。

## 实现与验证入口

`CacheRegion` 分别持有 `AnimationInputStore` 和 `ExpressionArtifactStore`，由 `ContentStorageBinding` 按实际存储区域对外提供。前者负责未绑定动画输入的查询、生成、内存复用与持久提交，后者专门负责 AOT 制品。`RenderTargetExpressionModuleFactory` 负责获取这两类产物并在发布前做组合校验。

`BakeIndexStore` 将带类型信息的精确键映射到整数文件 ID，物理文件名本身不编码 CID、ABI、binding 或用途。AOT 的廉价快速排除依赖按类型划分的分区查询，GUI 只查询未绑定输入；当 Bake 库尚不存在时，只读打开不会凭空建库。执行绑定的内存复用仍严格匹配实际环境与用途；两套新布局既不读取也不迁移旧的合并文件。

各类缓存文件的池路径与索引归属如下：
- 独立预览图使用 `f/p/<id>`，由 `Catalog` 与 `Remote` 分别保存所属预览图的整数定位。
- Remote metadata 与 chunk 使用 `f/r/<id>`，metadata 与完整来源身份在同一个 SQLite 事务中发布。
- `direct` 容器的目录证明保存在 `Catalog` 的纯 metadata 表中，不分配文件 ID。

`AnimationInputStore` 与 `ExpressionArtifactStore` 各自默认保留 16 项内存结果，并分别按 LRU 淘汰。
