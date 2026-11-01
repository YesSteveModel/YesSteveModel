# selection-and-attribution

- Requirement: [REQ.create-and-select-models](../requirements/req-create-and-select-models.md#reqcreate-and-select-models)
- Select: GUI 模型选择与安装反馈、作者姿态保护、缺图展示与创作者署名
- Needs:
  - 处理模型许可与内容责任: [DD.mod-and-model-licenses-are-independent](content-rights.md#ddmod-and-model-licenses-are-independent)
  - 联机浏览或选择: [DD.selection-permission-is-separate-from-asset-access](model-authorization.md#ddselection-permission-is-separate-from-asset-access)
  - 选择当前模型: [DD.single-current-catalog](catalog-publication.md#ddsingle-current-catalog)
  - 保留模型声明语义: [DD.semantic-freeze-at-export](model-compatibility.md#ddsemantic-freeze-at-export)
  - 独立图片与来源保护: [DD.user-model-sources-are-readonly](artifact-storage.md#dduser-model-sources-are-readonly)
  - GUI 工作与失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
  - 保留作者骨骼姿态: [DD.hierarchical-bone-animation](bone-and-controller.md#ddhierarchical-bone-animation)
- Landing:
  - [standard](../../standards/model-schema/manifest-and-identity.md#info-与展示信息)
  - [implementation](../../architecture/network/implementation/catalog-sync.md#selection-与-request-admission)
  - [implementation](../../architecture/client-presentation/implementation/page-runtime.md)
  - [implementation](../../architecture/model-management/demand/overview.md#实现与验证入口)
  - [implementation](../../architecture/model-management/sessions/overview.md#实现与验证入口)
  - [implementation](../../architecture/network/implementation/asset-transfer.md#receiver-local-assembly-与-publication)
  - [standard](../../standards/protocol-v1/README.md#typed-model-distribution)
  - [design](../../architecture/network/overview.md)
  - [design](../../architecture/client-presentation/overview.md)
  - [design](../../architecture/model-management/demand/overview.md#用途准备与独立接收)
  - [design](../../architecture/model-management/demand/overview.md#选择反馈)
  - [design](../../architecture/model-management/sessions/overview.md#缺图观察与管理提交资格)
  - [status](../../status/known-issues/rendering.md#gui-取景)
  - [implementation](../../architecture/network/player-state.md#权威模型)
  - [status](../../status/known-issues/model-management.md)

## DD.visual-model-selection

- Claim: 模型选择是面向普通玩家的 GUI 能力。
- Rationale: 玩家需要理解当前有哪些模型并主动切换外观；把日常选择交给直观列表和交互，比要求了解文件组织、内部标识或调试命令更符合这一使用场景。

### BC.gui-selects-available-model

- Claim: GUI 展示当前模型列表并允许玩家发起选择；多人模式的最终可用性和选择结果服从服务端裁决，不以打开列表为由加载全部模型内容。卡片使用可用图片或 3D 内容帮助玩家识别模型。普通列表不增加来源根导航层或来源标记；不同根的顶层条目并排展示，用户目录保持各自完整位置。Shift 展示模型或 pack 的完整公开地址。

### BC.gui-demand-follows-current-intent

- Claim: 快速翻页或切模不应为已放弃的意图继续发起昂贵工作。页面、选择与独立实体可直接取得所需完整 Ready 时，不增加资源取得等待；仅有磁盘缓存不属于该例外。选择发送与资源需求独立推进，不得互相阻塞。
- Claim: `ModelScreenEagerLoading` 控制模型卡的持续实时展示。关闭时，非连续交互卡片只显示图片或占位，不保有模型安装用途；连续悬停或键盘聚焦达到 300 ms 后可准备 GUI 资源，离开即退出实时展示。开启时，卡片可自动准备本地完整内容，远端缺件仍需连续交互。自动准备与缺图生成只新建默认贴图的 body，仍应满足该用途的完整纹理与声音准入。实际选择、显式换纹理与手动动画保持完整用途；缺图生成与 cache 命中不授予展示资格。普通卡片、纹理候选和补图只探测、生成并消费 GUI 闭包，不借 FULL Ready 或派生物。
- Claim: `ModelScreenEagerLoading` 开启时，普通模型列表不请求、生成或消费 preview，包括已驻留图片；当前合资格的 Matching 3D 可用时显示 3D，否则显示占位。前景、背景与署名保持原规则。关闭时，新请求可按原条件取得或补图。跳过 preview 不构成合法缺图或失败，不触发缺图指引，也不扩大远端模型联网资格。配置变化不强制取消已接纳补图；页面与世界取消规则继续成立。显式管理补图和 export 保持各自成功条件。
- Claim: 当前输入方式决定交互目标。同一卡片切换鼠标和键盘、相同内容的 resize/init 保留连续起点；离开、换卡、换页、换内容或结束页面则重新计时。有效配置发布后的下一次页面推进采用新值，开关不重置连续起点。
- Claim: 移出只结束卡片的安装用途、私有运行状态和展示引用，不取消已接纳准备。新接纳交互替换旧交互准备；同目标 GUI 与 FULL 选择各自准备，FULL 活跃不压制合资格 GUI，也不为未合资格卡片授予准备权。页面结束只关闭本页用途。接收方先取得独立使用，再结束来源用途。其他合法消费者和共享 cache 保留自身生命周期。
- Claim: 模型、纹理与默认选择立即反馈；重复选择同一未完成目标不重开等待。仅左侧名称以三点动画观察真实玩家匹配的完整安装，卡片名称保持可读；卡片图片和 GUI 加载仍表达自身状态。资源 Ready、请求被接受或旧安装保留都不能单独证明选择完成。失败或有限等待结束后恢复名称，不回滚 authority；迟到的合法权威状态仍可应用。
- Claim: 多人选择等待服务端权威生效，不先改写本地模型或 Roaming。选择或实际权威变化仍等待安装时，两条纹理入口均拒绝进入；单独悬停或动画不阻止进入。
- Claim: 卡片右键指向被点内容，左侧入口指向主体当前合法内容。入口复核世界、授权与选择等待，只接收入口目标所选纹理已 Ready 的 FULL 资源。普通 GUI Ready 不足以进入，其他纹理卡不属于准入集合。接收独立使用失败或资源未就绪时拒绝本次进入，不补加载、不等待、不重试恢复；随后 Ready 不自动进入，新点击重新判断。成功进入的中央预览持有 FULL 独立使用及自身变量，可浏览完整动画；纹理候选卡只消费 GUI，进入后的主动换纹理沿完整 FULL 选择流程取得资源。

Ctrl＋右键复制卡片的完整公开模型地址，无需选择权限或内容就绪。Ctrl＋左键仅在 integrated 环境且卡片具有已验证本地来源证明时，打开 raw 的逻辑入口目录或文件入口的逻辑父目录；远端发布位置不能授予本机打开能力。两种快捷动作优先消费点击，不附带选择、纹理页或资源准备。宿主打开失败按能力失败反馈。命令的相对地址先对全部已发布位置判断唯一性，再检查权限；歧义要求完整地址，不按权限择首项。

模型展示、资产取得和自行选择的权限见[分发与选择权限](model-authorization.md#ddselection-permission-is-separate-from-asset-access)；GUI 不得把可浏览或已取得资产的模型误示为玩家有权选择。

### BC.gui-framing-preserves-author-pose

- Claim: GUI 展示和预览图生成不改写作者骨骼姿态，也不替作者修复几何、动画或艺术适配；[默认动画的艺术适配保证](bone-and-controller.md#bcanimation-reuse-does-not-guarantee-artistic-fit)保持不变。空几何或非法数值不要求生成可见模型，但不得污染后续正常帧或其他卡片。

### BC.gui-preview-absence-is-session-observed

- Claim: 普通 preview 请求区分有效图片、合法缺图和实际失败。合法缺图可来自服务端确认独立图 miss，或客户端确认完整独立图无法解码。声明内嵌图不可读或无效属于失败；坏独立图按 miss 处理。缺图模型继续可用，普通浏览不触发管理生成或上传。
- Claim: 缺图判断只适用于当前连接中的对应模型内容，不能跨连接或内容替换沿用；新可用内容不应被旧缺图判断遮蔽。
- Claim: 无可用图片或 3D 内容的缺图卡片显示 placeholder。当前服务端 level 2 管理员同时看到 `/ysm model gen-preview` 与在客户端导出 `.mxc` 后添加到服务端两条指引；普通玩家看到联系管理员。新展示读取当前权限，提示不授予权限。拒绝、BUSY、传输或其他内容失败结束对应等待，不显示缺图指引或永久标坏模型。

## DD.visible-creator-attribution

- Claim: 模型的来源创作者信息必须随模型向玩家展示，并在模组可影响的处理和呈现范围内保持不被篡改或丢失。
- Rationale: 自定义内容来自独立创作者，模型分享和选择不能切断作品与创作者之间的关联；只有显示入口而处理过程中允许署名被覆盖，仍会使作品失去归属。保护已有声明可以复用模型信息的保留与传递，不要求制作组承担作者身份认证或模组外部文件的防篡改保证。

### BC.attribution-travels-with-model

- Claim: 对输入中已有的来源创作者署名，模组控制的导入、转换、加载、导出、分享和 GUI 展示必须保留其归属含义，不得擅自改写、替换、丢弃或让无关设置与内容覆盖；内部表示变化不能成为丢失署名的理由。

### BC.attribution-protection-has-an-input-boundary

- Claim: 署名保护以模组取得的输入声明为边界，不认证声明者身份或作品权属，也不保证阻止外部工具修改文件。输入已缺失或在模组外部被篡改时，不承诺恢复真实署名，不得以猜测作者或改署制作组来补齐信息。
