# 资产管线

## 职责与边界

资产管线把来源转为可验证的当前容器，再提供领域层使用的模型视图。Java 解释格式、组装语义、计算 canonical identity 并写出；native 提供 archive、hash、压缩、媒体和历史解码能力。管线不裁决授权，也不把解析成功直接发布为 Ready target。

本页细化[系统架构](../README.md#系统边界)中的来源转换与验证交接。

## 运行作用域与所有权

解析、转换和验证没有独立全局运行实例，由调用方的有限工作作用域承载。输入 owner 保活来源；capture 保有本次冻结输入，writer 只借用存储 owner 提供的 channel。转换可以在 client 或 server 工作中执行，不因使用媒体 codec 而要求客户端 Minecraft。

Preview operation 持有自己的容量、interest 和终态；export batch 属于具体 integrated server environment。Catalog、backing、GPU 和持久存储仍由接收方拥有，格式 writer 不取得发布权。

## 数据流与交接

图中边表示制品转换与验证交接，各出口仍需自己的发布门禁。

```mermaid
flowchart LR
    RAW["Raw directory / archive"] --> CAP["Frozen capture"]
    CAP --> WRITE["Current container"]
    OLD["Legacy source"] --> PROJECT["One-way projection"]
    PROJECT --> WRITE
    DIRECT["Direct container"] --> READ["Container / identity / schema validation"]
    WRITE --> READ
    READ --> ADMIT["Actual-source admission"]
    ADMIT --> CATALOG["Catalog candidate"]
    READ --> EXPORT["Explicit export"]
```

### 冻结输入与单向转换

同一次 raw 转换的身份与写出消费同一份冻结输入。Capture 复制实际解析依赖的文件与目录观察，再适配回同一 parser 的 scan/compile 路径。身份计算后不再读取可变来源，也不维护第二套 parser；代价是 capture 需要保有本次输入闭包。

Raw 与 legacy v1/v2/v3 在输入边缘统一为当前容器。容器提供随机访问与前缀准入，统一运行制品避免复制三套存储和验证规则；统一格式不要求落盘，可以直接形成内存 backing。

历史适配只投影可确定解释的内容，不带回 legacy cache、session 或模型管理 owner。识别后的格式、投影或结果协议错误结束该 source，不能改走另一种格式。转换成功也不证明远端取得、host adoption 或设备播放通过。兼容承诺如何约束转换见[模型兼容](model-compatibility.md)。

Legacy 导入以[调用期间来源稳定](../../product-decisions/decisions/model-compatibility.md#bclegacy-source-stability-is-caller-owned)为前提。导入器不增加平台锁、重复扫描或变化检测；这些机制增加协调与 I/O 成本，却不改善稳定来源的迁移结果。实际发现的 I/O、格式和校验错误仍拒绝该 source。

### 分层验证与发布

容器结构、精确身份、Schema 引用和实际 payload 分层验证。前一层只提供下一层输入：解压成功不证明 Schema 有效，metadata 可读不证明 model chunks 齐全，bake 成功不证明 Ready。

View 负责描述与验证，内容来源提供 bytes，模型 backing 保活。不可变 Manifest 是模型视图的唯一事实；lookup 只建立可重建索引，不复制另一棵 metadata。实现层的 reader 与 decode seam 见[分层验证](implementation/container-and-validation.md)。

Writer 形成 bytes，storage owner 提交已验证制品，Catalog owner 发布内容。Raw/legacy staging 重开通过后才交给存储；失败清理本次候选，不覆盖来源或已有有效产物。公共字段仍以 [Asset Container](../../standards/asset-container.md)和 [Model Schema](../../standards/model-schema/README.md)为准。

Direct 成品应声明并完整解码内嵌 preview；raw、builtin 和 legacy 的内部转换容器可以缺图。这是实际来源准入，不改变 Schema 对 unspecified 的合法解释。来源 kind 来自 resolver 调用链，不能由扩展名、metadata、cache 位置或调用方标记伪造；独立图片也不能补足 direct 准入。

## 生命周期与失败

每次转换在调用期持有输入与候选；验证完成后才向存储转移结果。拒绝或失败关闭本次候选与临时产物，已有有效内容不随候选失败撤销。Source read、格式/身份无效与基础设施失败保留各自分类，不用一次解析结果推断未读取资源有效。

Preview 与 export 各有独立操作终态；取消不回滚已提交文件，也不终结其他消费者。原环境提交门禁及停止偏序见[预览与导出](#预览与导出)。

## 关键契约与设计取舍

### 图像与声音转换

Java 选择用途策略，native 执行媒体编码。图像策略按以下顺序裁决，产品依据见[图像决策](../../product-decisions/decisions/image-policy.md)。

| 输入与用途 | 选择 |
|---|---|
| 已有合法有损表示 | 复用，避免重复压缩 |
| 模型纹理且格式在 PNG/zst 之外 | 复用合法受支持表示 |
| 其余模型纹理 | 以输入像素为基准无损处理 |
| 其余非纹理图片，低于分辨率阈值或适用受限设备策略 | 无损处理 |
| 其余非纹理图片 | 允许有损处理 |

小图与体素纹理经 YUV 4:2:0 和有损编码会损失色彩、边缘及透明细节，因此选择无损。阈值和 codec 属于实现参数；受限设备降低编码成本，但保留 WebP/AVIF 解码。策略只在来源转入当前容器前执行，不改写已有容器；当前 PNG 路径及效果证据缺口见[转换实现](implementation/conversion-and-export.md#图像处理的位置)。

声音转换沿同一 raw/legacy 管线进入当前媒体表示。Required 与 optional 候选的失败、完整制品计价和不支持媒体边界应分别保持；算法见[音频转换](implementation/conversion-and-export.md#音频内容转换)，媒体语义见[标准](../../standards/model-schema/assets-and-validation.md#common-stringssettings-与音频)。Current export 复制已验证声音，不重新编码。

### 预览与导出

Preview 取得、target Ready 与 export 完成是三个边界。图片命中可以直接返回 encoded bytes，不要求 model body 或 GPU；真实缺图生成则消费同一 loader、bake 和 host 路径，不能拿占位图当生成结果。

一个 preview operation 从 probe 起持续拥有容量和终态责任，跨 worker、target 与 host 只传 owned fact。Worker 不等待宿主。取消只撤自身 interest/pin，迟到资源由原 owner 关闭；其他消费者与已验证 cache 保留。

Export 归具体 integrated server environment，选集与权限由[导出决策](../../product-decisions/decisions/export-preprocessing.md)定义。环境与权限检查先于来源打开和输出创建。单项与批量均从已发布 catalog 捕获 exact input；批次先冻结路径与精确身份，再逐项核查当前 catalog。后续读取不按 path 静默换成新表示，也不搜索未发布候选。已发布的 raw／legacy 内部容器允许缺图，export 仍负责补齐图片。

环境以单批次、串行单项限制 retained input、target 和输出候选，不为导出建立另一套并发资源调度。GUI 关闭不影响批次；环境结束终止余项，不保存续跑状态。该选择限制并行吞吐，并要求用户在环境恢复后重新发起未完成部分；BUSY、容量和逐项处置见[导出实现](implementation/conversion-and-export.md#preview-取得与显式-export)。

Writer 复制已验证内容，保留作品语义与已有作者信息，补齐适用 preview 后重算容器身份并重开验证。在原 environment gate 仍有效时才原子替换；成功提交不因反馈或 cleanup 失败改为失败。停止先 seal 提交资格，只等待不依赖 tick/host 的自有阶段，不保存续跑状态。具体时序和容量见[预览与 export 实现](implementation/conversion-and-export.md#preview-取得与显式-export)。

格式实现与标准的差异见[格式问题](../../status/known-issues/format-and-schema.md)，不能由管线描述推定一致性验收通过。

生产转换的临时输出由存储 owner 在复核目录后创建，格式层只借用 channel；其提交、清理与平台边界见[存储设计](../model-management/storage/overview.md#精确身份与原子提交)。

## 实现与验证入口

[容器与分层验证](implementation/container-and-validation.md)定位 reader、view、bytes 与 backing 的责任。[转换与导出](implementation/conversion-and-export.md)定位 capture、staging、环境门禁与结果交接。公开语义见[模型兼容](model-compatibility.md)，支持和证据见[格式问题](../../status/known-issues/format-and-schema.md)。
