# 派生物身份与加载

上级契约：[存储](overview.md#seam-contract)。

## 职责与边界

本级展开[存储的精确身份](overview.md#精确身份与原子提交)，承担几何、动画和 expression 产物的复用及加载。

## Seam Contract

### 边界与权威

本地加载信任只适用于本地 expression artifact，不扩展到外部 bytes、模型 chunk 或 geometry cache，也不抵抗本地产物与摘要同时被修改。

### 前置条件与输入

#### 独立身份与原子集合

派生 key 排除 `ModelId`，覆盖精确内容及实际产物输入；renderer 只提供技术 profile，不能取得容器身份。Geometry 与 animation set 分别建 key，不因共享容器就复用不同纹理、选项或 ABI 的结果。

Animation set 与 expression artifact 作为完整集合一次原子提交，绑定引用只在本产物内解释，避免动画与 bytecode 错配。Set 身份包含用途规则、实际借用默认源、冻结声明与编译环境、编译后端及其版本；不依赖 geometry、所选纹理或 consumer `ModelId`。各动画延迟解码并按原范围隔离失败；代价是静态绑定环境变化需重建集合。

宿主、引擎、编译后端和内部制品共用全局动画 ABI；兼容性变化只迭代该全局版本。实际引擎包名隔离开发与重定位产物，但目录隔离不替代精确身份与 linkage 检查。版本归属见[ABI 索引](../../../abi/current.md#五个全局-abi)。Unstable/dev/snapshot 派生物只按完整原始版本字符串复用，不扫描或迁移旧 ABI 产物。

#### GUI 与 cache-only

根主体 binding 与 purpose 分区允许 GUI、玩家与 default 同时存在。GUI 只探测自身分区；不存在分区可省去输入推导，存在仍须验证独立 expected。分区只能作廉价否定判断，不提供有效性或权限。

FULL 准备完整输入并供中央动画预览和实际实体使用；player GUI 使用展示闭包，其他 kind 保守保留完整 MAIN。GUI 不准备 FP_ARM，不读取 FULL 文件或恢复 FULL inventory。普通和 cache-only 共用 GUI 闭包及精确 expected。用途语义见[展示闭包与主体特化](../../animation/molang-runtime.md#展示闭包与主体特化)。

Cache-only 不编译、不写缓存、不联网。Remote cache-only 只核验目标实际需要的 chunks，不因无关 lazy chunk 缺失拒绝可用玩家产物；普通 offline 内容打开仍执行自身完整 lazy chunk 核验。离线 miss 不成为后续普通准备失败。

### 结果与保证

#### 暖加载信任

Expected 从已验证输入重建，artifact/header 不提供自己的期望值。本地 producer 的 expression artifact 可在版本、长度、完整 artifact hash 与独立 expected 校验后可信还原；恢复对象仍在 loader 完整验证 class closure、ABI 和内部完整性。

冷编译的原始不可变 expression artifact 可直接交给 loader。compiler 的内部证明覆盖最终 payload；loader 在 exact registry、BindingPlan 和 limits 匹配时省去同项产物复验。每次加载仍检查独立 expected、parent 和真实 `Class` identity/可见性，并执行实际 class definition、Root ABI/工厂解析、链接与常量构造。普通构造、bytes 复制和磁盘恢复不继承原始对象资格；持久化是否完成不影响资格。

Geometry 的普通与 cache-only 命中共用一次取得。固定 channel 读取 manifest、独立 expected 和 payload，完整校验后把同一 payload 交给 native decoder。路径在取得后被替换，不改变本次消费的数据。冷烘焙先构造完整运行 geometry，再提交独立的 metadata 和共享 native 引用；后台 staging 校验继续成立。

本地还原只在上述独立 expected 与 bytes 完整性检查后还原同一 payload；活跃 prepared resource 可复用已验证结果，不能用全局 decoded cache 或 trust marker 代替加载准入。

### 所有权与时序

Expression 内存复用保留原始 artifact 和完整不可变 animation set，不为复用先编码再还原。后台 worker 编码并提交完整集合，不用并发磁盘赢家替换本次原始对象。磁盘恢复对象仍走所属来源的加载验证。

`BakedAnimationSet` 的长期 source 只保留受检 opener 和已接纳 expected。Controller discovery 和实体同步 animation pass 各持局部批次视图；同能力的 local/fallback 读取共用一个 channel，不预解码未消费动画。实体只在同步入口内投影该视图，并在 finally 撤销；共享 store 的当前 source 不变。已绑定结果可在阶段结束后复用，后续未加载动画重新取得来源资格。每次读取仍验证长度、descriptor/hash 和动画身份。普通失败后，批次 owner 关闭当前阶段；局部 context 按新阶段重新取得 reader，让既有 fallback 与健康动画继续。关闭失败、取消或 OOM 不恢复；不自动重试坏动画。Staging validator 直接借 writer 的同一个 channel 验证完整集合，不取得关闭权。

淘汰只撤 store 引用，不撤消费者保留。对象大小不参与保留或可选持久接纳；格式和 compiler limits 仍独立校验。

### 失败与拒收

普通准备只有初始 cache 取得失败可转入冷准备。编译、烘焙及运行对象构造失败直接拒绝候选。完整内存候选直接交付运行；派生持久化的拒绝、失败、取消或 shutdown 不撤销有效运行结果，也不声称文件已提交。

### 证据与依赖

本级依赖已验证输入重建独立 expected；用途和 Ready 交接依赖[客户端资源](../client-resources/overview.md#用途与派生物)，动画用途依赖[展示闭包与主体特化](../../animation/molang-runtime.md#展示闭包与主体特化)。当前验证范围与限制见[模型管理验证](../../../verification/model-management.md)及[模型管理问题](../../../status/known-issues/model-management.md)。

## 设计取舍

复用 producer 的证明可省去重复语义重验，代价是限定 producer 与每次交付的加载准入。

## 实现与验证入口

`ExpressionArtifactStore` 拥有集合文件的读取、内存复用与原子提交；`ModelRenderTargetLoader` 按实际区域取得 store。

集合文件按 `bake/<animation ABI>/<container>/<binding>/<purpose rule>/<lookup>.anim.ysm-cache` 定位。内存探测也匹配 binding 与 purpose。目录与 lookup 共同区分用途，不扫描旧目录或迁移旧产物。

`ExpressionArtifactStore` 的内存复用默认每 store／区域保留 16 项，超出项数时按 LRU 淘汰。
