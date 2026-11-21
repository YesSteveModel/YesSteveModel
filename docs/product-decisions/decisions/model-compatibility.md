# model-compatibility

- Requirement: [REQ.preserve-model-semantics](../requirements/req-preserve-model-semantics.md#reqpreserve-model-semantics)
- Select: 导出语义冻结、兼容层级与 raw 及 legacy 迁移
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
  - [design](../../architecture/animation/molang-static-language.md)
  - [design](../../architecture/animation/molang-interpreter.md)
  - [design](../../architecture/animation/molang-runtime.md#执行用途与主体资格)
  - [implementation](../../architecture/animation/implementation/molang-runtime.md#源码解析与缓存)
  - [ABI](../../abi/maintenance.md#开发版本的特殊门禁)

## DD.semantic-freeze-at-export

- Claim: 导出物会固定当次作品语义，raw 与历史作品迁移则保留所有可唯一解释的受支持行为。开发期 Molang 仅适用下述[有界例外](#bcdevelopment-molang-semantics-use-current-contract)。
- Rationale: 分享出的模型需要稳定复现，存储表示和转换方式不能替创作者重定义作品，缺失信息也不能靠猜测补齐。Catalog 的 raw 与 legacy 导入以转换期间来源保持稳定为前提，不额外保证外部并发修改时的结果。

### BC.exported-container-is-runtime-snapshot

- Claim: 除下述[开发期 Molang 例外](#bcdevelopment-molang-semantics-use-current-contract)外，已有容器以冻结后的内容定义模型行为。Raw 布局、后续导出规则、缓存重建或内部格式演进都不能静默改写这一行为。显式生成新制品遵循[完整导出契约](export-preprocessing.md#bcexport-produces-complete-independent-artifacts)。

### BC.development-molang-semantics-use-current-contract

- Claim: 当前未发布开发期模型的 Molang 源码在重新准备或加载时采用现行契约。普通 GUI 场景使用[动态解释语义](../../architecture/animation/molang-interpreter.md)：普通变量可以改变类型，平面脚本结构体允许动态字段，缺值保留到实际消费时处理；完整安装、手部和中央完整动画预览则继续使用静态 AOT 语义。运行时类型错误会终止当前 GUI 实例，已发生的效果不回滚。

这一契约下的[语言范围](../../architecture/animation/molang-static-language.md)涵盖函数裸调用、数学有限归零、浮点缺值、普通声明业务结果，以及变量默认初始化、缺值复制传播、scalar 消费、数值比较与 F32 和 BOOL 合流。[固定顶层动作许可与轮级借用](../../architecture/animation/molang-runtime.md)、[公共业务 view](../../architecture/animation/molang-declarations.md)，以及通用箭头、引用身份与存在性（identity、presence）、当前主体与源主体的调用归属、只读完成样本、现有运算符分组、无人工编译额度和每次独立计算 loop 次数，也都采用现行契约。旧声明直接从源码重建，不再保留旧的 scope 推断、generic child、provider 与 control 额度、整轮效果事务或执行期取消中断。普通动作按执行顺序生效，后续失败不撤销此前已发生的动作。此例外只覆盖上述 Molang 变化，不放宽模型数据完整性及其他已支持行为的兼容承诺。旧派生物的身份失配按[运行时缓存规则](../../architecture/animation/implementation/molang-runtime.md)处理，不改变模型源码的 wire 形态。

来源版本 major < 3 的模型保留表达式除以零的回退行为；major ≥ 3、版本缺失或版本非法时关闭该回退。迁移与再次导出均保留来源行为版本。默认补全、公共函数和临时表达式沿用接收模型的选项，独立 default 使用自身当前版本。具体版本识别与运算边界见[来源版本与除法](../../architecture/animation/molang-static-language.md#来源版本与除法)；此例外不恢复其他旧求值行为。

### BC.development-version-qualifiers-are-exact

- Claim: 当容器（container）、schema、缓存 ABI 或协议涉及开发版本时，只接受完整版本严格相等的组合；稳定版策略不能绕过该门禁，也不据此新增跨版本兼容承诺。开发标记与比较规则由[版本门禁](../../abi/maintenance.md#开发版本的特殊门禁)定义。

### BC.inert-input-does-not-gain-future-behavior

- Claim: 历史输入中已经明确不生效且不进入当前制品语义的内容可以直接静默省略，不为这些值产生无效警告。未来新增能力必须显式引入，不能让旧制品在运行时升级后获得此前不存在的行为。

### BC.unsupported-source-media-degrades-locally

- Claim: Raw 源码中无法识别的模型音频必须产生非致命用户警告并仅省略该音频；已识别为受支持格式但内容损坏时，对应的源码转换必须失败，不能伪装成未知格式。历史容器中旧消费方始终无法解释且从未产生可观察效果的无效媒体字节（inert media bytes）可以静默省略，但该例外不扩展到可表示的动画声音关键帧（sound keyframe）或其他模型内容。

### BC.raw-and-legacy-formats-are-supported

- Claim: 当前兼容承诺覆盖 raw 以及 legacy v1、v2、v3，不能因输入格式较旧就把已有受支持作品排除在兼容范围之外。

### BC.successful-import-sheds-legacy-authority

- Claim: 历史模型转换为当前制品后，原有的受支持几何、动画、脚本、纹理及声音语义仍须保留；容器组织形式或编码方式改变，不能成为丢失这些行为的理由。

### BC.legacy-source-stability-is-caller-owned

- Claim: Catalog 的 raw 目录、ZIP 与 7z 压缩包、v1 与 v2 以及 legacy v3 导入，均以转换期间来源保持稳定为前提，由调用方和用户负责。转换期间发生并发替换、覆写、截断或增长时，导入结果不作保证，也不承诺检测这些变化；不过导入器仍须拒绝实际发现的 I/O、格式和校验错误。

### BC.ambiguous-input-fails-locally

- Claim: 损坏、截断或无法唯一解释的输入必须明确拒绝对应内容，不做猜测性修复，也不得让普通模型迁移失败升级为游戏启动失败。

## DD.explicit-support-is-stable

- Claim: 兼容责任按三个级别划分：明确支持、未明确维护但未禁止、禁止或不推荐。除上述[开发期 Molang 例外](#bcdevelopment-molang-semantics-use-current-contract)外，明确支持的可观察行为无条件维护。
- Rationale: 明确支持的能力是创作者可以依赖的作品基础，而为全部偶然效果承诺无条件兼容会无限放大维护成本。广泛使用的艺术技巧值得尽力保留，典型用法可以单独提升为明确支持；已经明确劝阻的行为则不强迫主线长期维护。

### BC.supported-features-do-not-break

- Claim: 除上述[开发期 Molang 例外](#bcdevelopment-molang-semantics-use-current-contract)外，模组更新不得打破已明确支持的可观察模型行为；表示方式调整、性能优化和技术栈变化均不构成破坏该行为的理由。

### BC.unclassified-features-have-no-absolute-guarantee

- Claim: 未明确支持且未被禁止的行为只按尽力维护（best-effort）处理；只有将某项技巧正式提升为明确支持后，才进入无破坏性变更的承诺。

### BC.discouraged-features-may-break

- Claim: 明确禁止或不推荐的特性可能在任意一次更新后失效，不能据此要求恢复该行为。

### BC.widespread-artistic-techniques-are-best-effort

- Claim: 借助优化或其他实现特性反向形成的广泛艺术技巧按尽力维护（best-effort）处理；其中已明确提升到支持范围的典型技巧按明确支持契约维护，但不能从一个典型推广到整类技巧的无条件兼容。

默认动画复用的尽力维护边界见 [BC.default-animation-interface-is-stable](bone-and-controller.md#bcdefault-animation-interface-is-stable)，定位组隐藏的显式承诺按 Needs 中指向的条目查阅。格式与协议目前仍处于 unstable 阶段，但这一点不降低已明确承诺的兼容级别。当前几何规则与 GUI 求值范围分别见[渲染设计](../../architecture/rendering/overview.md#几何与可见面)和[GUI 执行范围](../../architecture/animation/molang-runtime.md#执行用途与主体资格)。
