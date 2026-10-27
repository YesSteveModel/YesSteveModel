# image-policy

- Requirement: [REQ.portable-model-artifact](../requirements/req-portable-model-artifact.md#reqportable-model-artifact)
- Select: 整体视觉保真、输入兼容与处理成本
- Needs:
  - 判断移动平台范围: [BC.minimum-platform-baselines](platform-baselines.md#bcminimum-platform-baselines)
  - Android 运行前提: [BC.android-launcher-runtime-prerequisite](platform-baselines.md#bcandroid-launcher-runtime-prerequisite)
  - 受限设备成本: [DD.high-end-quality-with-basic-availability](device-quality.md#ddhigh-end-quality-with-basic-availability)
  - 格式/编码变化影响既有作品: [DD.semantic-freeze-at-export](model-compatibility.md#ddsemantic-freeze-at-export)
- Landing:
  - [standard](../../standards/model-schema/assets-and-validation.md#image-与-pbr-texture)
  - [status](../../status/known-issues/format-and-schema.md#codec导入与平台)
  - [implementation](../../architecture/asset-pipeline/implementation/conversion-and-export.md#图像处理的位置)
  - [design](../../architecture/asset-pipeline/overview.md)

## DD.compression-preserves-model-meaning

- Claim: 压缩降低制品与分发成本，同时不改变作品整体视觉效果。
- Rationale: 同一资产会向多位玩家反复分发，体积优化收益显著；小图和体素模型的小纹理中少量像素就承担显著艺术信息，失真可能直接破坏作品，因此整体视觉保真优先于更高压缩率。

### BC.asset-compression-keeps-content

- Claim: 压缩和媒体处理不得丢弃模型需要的内容，必须保持整体视觉效果，尤其应保留小图和体素模型纹理中的色彩、形状与透明边界；不要求恢复输入中已丢失的信息。

### BC.nontexture-images-may-use-lossy-encoding

- Claim: GUI 前景和背景、预览图、icon、作者头像及其他展示图允许使用有损表示，前提是保持整体视觉效果；允许有损不等于允许小图出现显著失真。

## DD.common-image-inputs-remain-usable

- Claim: 接纳创作者常用图片输入，并维持适配平台已有格式的解码能力。
- Rationale: 要求手动转码增加普遍创作成本；本机选择哪种编码优化与能否使用既有作品是两个边界。

### BC.png-and-jpeg-inputs-are-accepted

- Claim: 合法的 PNG、JPEG 图片可以作为模型图片资产进入处理流程，不能仅因后续采用无损处理就拒绝 JPEG 输入。

### BC.mobile-keeps-webp-avif-decoding

- Claim: 满足本树平台与 Android 启动器前提的移动环境仍保留 WebP 和 AVIF 解码能力，不能因本机处理策略不同拒绝这些模型资产；格式兼容不扩大平台范围。

## DD.constrained-media-processing

- Claim: 在视觉保真前提下控制媒体处理成本，优先复用可直接使用的内容，并为受限设备保留基础可用性。
- Rationale: 重复处理可能累积失真并浪费时间；压缩率的额外收益不能使受限设备失去基本模型使用能力。

### BC.media-processing-avoids-unnecessary-work

- Claim: 已满足质量与使用要求的合法图片可以直接复用，不要求为统一表示重复处理；受限设备可以采用较低成本的处理方式，但不降低既有作品的解码兼容与整体视觉保真要求。
