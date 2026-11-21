# content-rights

- Requirement: [REQ.understand-content-rights](../requirements/req-understand-content-rights.md)
- Select: 模组与模型许可独立、清单许可声明、免责声明
- Needs:
  - 展示来源创作者: [DD.visible-creator-attribution](selection-and-attribution.md#ddvisible-creator-attribution)
  - 区分联机操作权限: [DD.selection-permission-is-separate-from-asset-access](model-authorization.md#ddselection-permission-is-separate-from-asset-access)
- Landing:
  - [standard](../../standards/model-schema/manifest-and-identity.md#info-与展示信息)

## DD.mod-and-model-licenses-are-independent

- Claim: 模组与模型分别适用自身的权利归属和许可证；模型在清单中声明自己的许可，双方不因 YSM 对模型的加载、导出、展示或分发而自动改变彼此的许可。
- Rationale: 开源模组提供创作和使用工具，模型来自拥有各自授权条件的内容创作者；把二者许可绑定会误导使用者的使用、修改与分发判断，并限制独立作品的分享。缺少许可证类型时采用保留所有权利的默认值，避免把未作声明误解为开放授权。许可展示复用模型已有清单信息，不引入制作组逐件审查或代为授权的责任。

### BC.model-use-does-not-relicense-either-work

- Claim: 模组的开源许可不自动授予模型的使用、修改或再分发权，模型许可证也不反向修改模组的开源许可。该独立性不免除实际复制或改编代码、内置模型及第三方素材时原有的许可义务。

### BC.model-license-declaration-travels-with-model

- Claim: 模型清单内已有的许可声明应在导出、分享及模型信息展示中保留，不得自动套用模组许可。声明展示不证明声明者拥有相应权利，也不由制作组担保其授权有效性。

### BC.unspecified-model-license-is-all-rights-reserved

- Claim: 模型清单未显式指定许可证类型时，默认按 `All rights reserved`（保留所有权利）解释和展示；许可声明或展示元数据整体缺省时同样适用。该默认值不授予额外使用、修改或再分发权，不表示制作组认证了作品权属，也不得覆盖已有的显式许可证类型。

### BC.runtime-permission-is-not-copyright-permission

- Claim: 服务端允许选择或取得模型、模型可被技术上读取，以及模型声明免费使用，均不替代作品许可证及权利人的授权；模型许可声明也不自行授予服务端控制的选择或资产访问权限。

## DD.disclose-content-responsibility-boundaries

- Claim: 向使用者提供可阅读的免责声明，明确模组本体免费、模型保密风险、第三方模型交易与内容责任，以及制作组的内容立场。
- Rationale: 玩家需要在使用和分享模型时形成合理预期，不能把技术能力当作保密保证、交易担保或制作组对内容的认可。明确制作组提供的能力和未承担的服务，避免承诺无法控制的第三方结果；免责声明不能替代模组对正常游戏可用性的既有承诺。

### BC.disclaimer-states-service-and-content-boundaries

- Claim: 免责声明必须表达以下边界；文字可调整，但不能将责任说明扩大成对所有情形均不承担责任的保证。

| 主题 | 必须告知的边界 |
|---|---|
| 模组费用与质量 | 模组本体的下载、使用免费，制作组致力于安全、稳定的使用体验；这不表示第三方模型必须免费。 |
| 模型保密性 | 制作组不保证模型内容的保密性；容器、访问限制和其他保护能力都不构成不会被提取、复制或泄露的承诺。 |
| 模型交易 | 制作组不参与第三方模型交易，不为其提供履约或交易担保；相关交易与纠纷由相应参与方处理。 |
| 权利与内容责任 | 模型权利归相应权利人，创作者及发布者应确保内容和授权合法，并对各自行为负责；用户创作或发布的内容不代表制作组观点或认可。 |
| 内容立场 | 不得制作违反法律法规的模型；制作组反对 NSFW 或 R18 内容创作，且不参与此类内容创作。 |

### BC.disclaimer-does-not-add-content-adjudication

- Claim: 内容立场和责任告知本身不承诺模型内容自动识别、逐件审核或交易纠纷裁决，也不能据此把任意用户模型声明为制作组审核或担保的内容。
