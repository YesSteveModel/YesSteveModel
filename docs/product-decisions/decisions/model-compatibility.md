# model-compatibility

- Requirement: [REQ.preserve-model-semantics](../requirements/req-preserve-model-semantics.md#reqpreserve-model-semantics)
- Select: 导出语义冻结、兼容层级与 raw/legacy 迁移
- Needs:
  - 迁移与导出中的署名保留: [DD.visible-creator-attribution](selection-and-attribution.md#ddvisible-creator-attribution)
  - 迁移与导出中的许可声明: [DD.mod-and-model-licenses-are-independent](content-rights.md#ddmod-and-model-licenses-are-independent)
  - 用户主动限制本地呈现: [DD.local-player-controls-presentation](local-presentation-control.md#ddlocal-player-controls-presentation)
  - 保持创作结果与输出等价: [DD.authoring-semantics-before-optimization](geometry-regions.md#ddauthoring-semantics-before-optimization)
  - 显式生成新制品: [DD.export-runs-in-integrated-environment](export-preprocessing.md#ddexport-runs-in-integrated-environment)
  - 定位组隐藏兼容: [DD.locator-hiding-is-supported-customization](attachments.md#ddlocator-hiding-is-supported-customization)
  - 默认动画兼容: [BC.default-animation-interface-is-stable](bone-and-controller.md#bcdefault-animation-interface-is-stable)
- Landing:
  - [design](../../architecture/asset-pipeline/model-compatibility.md)
  - [implementation](../../architecture/asset-pipeline/implementation/conversion-and-export.md)
  - [implementation](../../architecture/asset-pipeline/implementation/conversion-and-export.md#音频内容转换)
  - [design](../../architecture/model-management/storage/derived-artifacts.md#独立身份与原子集合)
  - [status](../../status/known-issues/format-and-schema.md#一致性与转换)
  - [standard](../../standards/asset-container.md#header)
  - [standard](../../standards/model-schema/assets-and-validation.md#animation-与-controller)
  - [standard](../../standards/model-schema/assets-and-validation.md#common-stringssettings-与音频)
  - [standard](../../standards/protocol-v1/README.md#当前网络协议)
  - [design](../../architecture/asset-pipeline/overview.md)
  - [design](../../architecture/model-management/storage/overview.md#共享存储与清理)
  - [design](../../architecture/animation/molang-runtime.md#展示闭包与主体特化)
  - [implementation](../../architecture/animation/implementation/molang-runtime.md#源码解析与缓存)
  - [ABI](../../abi/maintenance.md#开发版本的特殊门禁)

## DD.semantic-freeze-at-export

- Claim: 导出物固定当次作品语义；raw 与历史作品迁移保留可唯一解释的受支持行为。开发期 Molang 仅适用下述[有界例外](#bcdevelopment-molang-semantics-use-current-contract)。
- Rationale: 分享结果需要稳定复现，表示和转换方式不能替创作者重定义作品；缺失信息不能靠猜测补齐。Legacy 导入以稳定来源为前提，不额外承担外部并发修改的结果保证。

### BC.exported-container-is-runtime-snapshot

- Claim: 除下述[开发期 Molang 例外](#bcdevelopment-molang-semantics-use-current-contract)外，已有容器按冻结内容定义模型行为。Raw 布局、后续导出规则、缓存重建或内部格式变化不能静默改写该行为。显式生成新制品遵循[完整导出契约](export-preprocessing.md#bcexport-produces-complete-independent-artifacts)。

### BC.development-molang-semantics-use-current-contract

- Claim: 当前未发布开发期模型的 Molang source 在重新编译或加载时采用现行契约。范围限于[函数裸调用与浮点缺值](../../architecture/animation/molang-static-language.md)、[固定顶层动作许可与轮级借用](../../architecture/animation/molang-runtime.md)及[公共业务 view](../../architecture/animation/molang-declarations.md)。旧 provider 从源码重建，不保留旧注解或旧求值语义的兼容入口。此例外仅覆盖上述 Molang 变化，不放宽模型数据完整性及其他已支持行为的兼容承诺。旧派生物的身份失配沿[运行时缓存规则](../../architecture/animation/implementation/molang-runtime.md)收敛，不改变模型 source 的 wire 形状。

### BC.development-version-qualifiers-are-exact

- Claim: Container、schema、cache ABI 与 protocol 涉及开发版本时，只接受完整版本严格相等；稳定版策略不能绕过该门禁，也不据此新增跨版本兼容承诺。开发标记与比较规则由[版本门禁](../../abi/maintenance.md#开发版本的特殊门禁)定义。

### BC.inert-input-does-not-gain-future-behavior

- Claim: 历史输入中已明确不生效且不进入当前制品语义的内容可以静默省略，不为这些值产生无效警告；未来新增能力必须显式引入，不能让旧制品因运行时升级获得此前不存在的行为。

### BC.unsupported-source-media-degrades-locally

- Claim: Raw source 中无法识别的模型音频必须产生非致命用户警告并只省略该音频；已识别为受支持格式但内容损坏时，相应 source 转换失败，不能伪装成未知格式。历史容器中旧 consumer 始终无法解释且从未产生可观察效果的 inert media bytes 可以静默省略；该例外不扩展到可表示的动画 sound keyframe 或其他模型内容。

### BC.raw-and-legacy-formats-are-supported

- Claim: 当前兼容承诺包括 raw 和 legacy v1、v2、v3，不能因输入格式较旧就把已有受支持作品排除在兼容范围之外。

### BC.successful-import-sheds-legacy-authority

- Claim: 历史模型转换为当前制品后，受支持的几何、动画、脚本、纹理及声音语义仍需保留；原容器组织或编码改变不构成丢失这些行为的理由。

### BC.legacy-source-stability-is-caller-owned

- Claim: Legacy 文件导入以调用期间源文件保持稳定为前提，由调用方及用户负责。并发替换、覆写、截断或增长后的导入结果不作保证，也不承诺检测这些变化；导入器仍须拒绝实际发现的 I/O、格式和校验错误。

### BC.ambiguous-input-fails-locally

- Claim: 损坏、截断或无法唯一解释的输入必须明确拒绝相应内容，不猜测性修复，也不使普通模型迁移失败升级为游戏启动失败。

## DD.explicit-support-is-stable

- Claim: 按明确支持、未明确维护但未禁止、禁止或不推荐三个级别承担兼容责任。除上述[开发期 Molang 例外](#bcdevelopment-molang-semantics-use-current-contract)外，明确支持的可观察行为无条件维护。
- Rationale: 明确支持是创作者可依赖的作品基础；为全部偶然效果承诺无条件兼容会无限扩大成本。广泛艺术技巧值得尽力保留，典型可以单独提升为明确支持；已劝阻的行为不强迫主线长期维护。

### BC.supported-features-do-not-break

- Claim: 除上述[开发期 Molang 例外](#bcdevelopment-molang-semantics-use-current-contract)外，模组更新不得打破已明确支持的可观察模型行为；表示方式、性能优化和技术栈变化均不构成破坏该行为的理由。

### BC.unclassified-features-have-no-absolute-guarantee

- Claim: 未明确支持且未禁止的行为只受尽力维护约束；将某项技巧提升为明确支持后，才进入无破坏性变化的承诺。

### BC.discouraged-features-may-break

- Claim: 明确禁止或不推荐的特性可能在任何一次更新后失效，不能据此要求恢复该行为。

### BC.widespread-artistic-techniques-are-best-effort

- Claim: 借助优化或其他实现特性反向形成的广泛艺术技巧采用 best-effort；其中已明确提升为支持范围的典型技巧按明确支持契约维护，不能从一个典型推广到整类技巧的无条件兼容。

默认动画复用的 best-effort 边界见 [BC.default-animation-interface-is-stable](bone-and-controller.md#bcdefault-animation-interface-is-stable)，定位组隐藏的显式承诺按 Needs 读取；格式和协议仍处于 unstable 不降低已明确承诺的级别。当前几何规则与 GUI 求值范围分别见[渲染设计](../../architecture/rendering/overview.md#几何与可见面)和[展示闭包设计](../../architecture/animation/molang-runtime.md#展示闭包与主体特化)。
