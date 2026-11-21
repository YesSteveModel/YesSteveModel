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
  - [design](../../architecture/animation/molang-runtime.md#执行用途与主体资格)
  - [design](../../architecture/model-management/client-resources/overview.md#动画资源所有权与退休)
  - [design](../../architecture/model-management/demand/overview.md#用途准备与独立接收)
  - [design](../../architecture/model-management/demand/overview.md#选择反馈)
  - [design](../../architecture/model-management/sessions/overview.md#成员观察与管理提交)
  - [status](../../status/known-issues/rendering.md#gui-取景)
  - [design](../../architecture/network/player-state.md#选择请求与处置)
  - [status](../../status/known-issues/model-management.md)

## DD.visual-model-selection

- Claim: 模型选择是面向普通玩家的 GUI 能力。
- Rationale: 玩家需要直观了解当前有哪些可用模型并主动切换外观；相比要求玩家理解文件组织、内部标识或调试命令，把日常选择交给列表与界面交互更贴合这一场景。

### BC.gui-selects-available-model

- Claim: GUI 展示当前模型列表并允许玩家发起选择；多人模式的最终可用性与选择结果以服务端判定为准，不能以打开列表为由加载全部模型内容。卡片借助可用图片或 3D 内容帮助玩家识别模型。普通列表不增加来源根导航层或来源标记：不同来源根的顶层条目并排展示，用户目录保持各自完整位置；按住 Shift 时展示模型或模型包（pack）的完整公开地址。

本地（Local）模式按当前玩家 UUID 恢复本地选择，且只有玩家主动发起的本地选择会直接保存。无记录或查询失败时选择普通 builtin default；显式选择 intrinsic 时则独立保存。模型或纹理缺失时保留原有选择意图并临时显示 intrinsic。联机状态、服务端指派和失败回退均不改写本地选择记录。收藏查询失败时，本次收藏集合整体按空处理，分页中途失败也不保留部分集合，且不修改原数据库。

### BC.gui-demand-follows-current-intent

- Claim: 快速翻页或切模时，不应为已放弃的意图继续发起昂贵工作。当页面、选择操作或独立实体能够直接取得所需的完整 Ready 资源时，不再增加资源获取等待，但仅有磁盘缓存不属于该例外。选择发送与资源需求各自独立推进：联机主动选择前先取得完整合法的初始 roaming，本地查询失败时允许携带空初值继续发送，后续 scope 的持久化资格服从[本地 roaming](script-sync.md#bconly-local-player-roaming-persists)。
- Claim: `ModelScreenEagerLoading` 控制模型卡片是否持续进行实时展示。关闭时，非连续交互的卡片只显示图片或占位，不持有模型安装用途；连续悬停或键盘聚焦达到 300 ms 后才可准备 GUI 资源，离开即退出实时展示。开启时，卡片可自动准备本地完整内容，但远端缺件仍需连续交互才会触发准备。自动准备与缺图生成都只新建默认贴图的 body，但仍须满足该用途下完整的纹理与声音依赖要求。实际选择、显式换纹理与手动动画始终保持完整用途；缺图生成与缓存命中本身不授予展示资格。

普通卡片、纹理候选和补图均按 GUI 展示规则独立准备，并用解释器执行实际消费到的表达式，不以编译闭包或完整安装为前提，也不随 FULL 是否已有缓存改变路径。它们不借用 FULL Ready 资源、字节码或私有状态，但独立动画输入可以按自身身份共享。
- Claim: `ModelScreenEagerLoading` 开启时，普通模型列表不请求、不生成也不消费预览图（preview），哪怕图片已驻留内存也是如此；此时当前具备资格的匹配 3D 内容可用时直接显示 3D，否则显示占位。前景、背景与署名保持原有规则。关闭时，新请求可按原有条件获取或补图。跳过预览图既不构成合法缺图或失败，也不触发缺图指引，更不扩大远端模型的联网资格。配置变化不强制取消已接纳的补图，页面与世界取消规则继续成立。显式管理补图和 export 各自保持原有成功条件。
- Claim: 当前输入方式决定交互目标。在同一张卡片上切换鼠标与键盘，或在内容不变时触发 resize 与 init，都保留连续交互计时起点；一旦离开卡片、换卡、换页、换内容或关闭页面，则重新计时。有效配置发布后的下一次页面推进采用新值，开关切换本身不重置连续起点。
- Claim: 移出视野只结束该卡片的安装用途、私有运行状态和展示引用，不取消已经接纳的准备任务。新接纳的交互会替换旧的交互准备；同一目标的 GUI 与 FULL 选择各自独立准备，FULL 活跃不压制具备资格的 GUI，也不为未具备资格的卡片授予准备权。页面关闭只释放本页用途。交接时，接收方先取得独立使用权，来源方再结束自身用途；其他合法消费方和共享缓存各自保留原有生命周期。
- Claim: 模型、纹理与默认模型选择都应立即给出反馈，重复选择同一未完成目标时不重开等待。仅左侧名称以三点动画反映真实玩家匹配的完整安装进度，卡片名称本身保持可读；卡片图片和 GUI 加载各自表达自身状态。资源 Ready、请求被服务端接受或旧安装保留在位，都不能单独证明选择完成。失败或有限等待超时后恢复名称显示，不回滚权威状态；迟到的合法权威状态仍可应用。
- Claim: 多人选择需要等待服务端权威状态生效，不提前改写本地模型或 Roaming。当选择或实际权威变化仍在等待安装时，两条纹理入口均拒绝进入；单纯悬停或动画播放不阻止进入。
- Claim: 卡片右键指向被点击的内容，左侧入口指向主体当前合法内容。入口复核世界、授权与选择等待状态，只接受目标所选纹理已经 Ready 的 FULL 资源；普通 GUI Ready 不足以进入，其他纹理卡也不在进入条件之内。取得独立使用权失败或资源未就绪时直接拒绝本次进入，不补加载、不等待、不重试恢复；随后资源就绪也不会自动进入，需重新点击判断。成功进入后，中央预览持有 FULL 独立使用权及自身变量，可浏览完整动画；纹理候选卡只消费 GUI 资源，进入后的主动换纹理沿完整 FULL 选择流程获取资源。

快捷操作方面，Ctrl＋右键复制卡片的完整公开模型地址，无需选择权限或内容就绪；Ctrl＋左键仅在 integrated 环境且卡片具有已验证本地来源证明时，打开 raw 的逻辑入口目录或文件入口的逻辑父目录，远端发布位置不能授予本机打开能力。两种快捷动作优先消费点击，不附带选择、打开纹理页或资源准备；宿主打开失败按能力失败反馈。

命令选择器方面，单模型 set、export、玩家 grant 与 roaming 清除共用路径或 `id:<完整 ModelId>` 两种形式。使用相对路径时，先对全部已发布位置判断唯一性，再检查权限；存在歧义时要求完整地址，不按权限择首项。非法 `id:` 不回退为路径，ID 不匹配模型族（family）或自动选最新版。Set、export 和纹理补全要求指向当前模型，roaming 清除允许使用历史 ID。保留地址 `default` 只用于 intrinsic 选择与清除，不作为普通 export 或 grant 目标。命令执行与补全解析到同一目标。

模型展示、资产取得和自行选择的权限边界见[分发与选择权限](model-authorization.md#ddselection-permission-is-separate-from-asset-access)；GUI 不得把仅可浏览或已取得资产的模型误示为玩家有权选择。

### BC.gui-framing-preserves-author-pose

- Claim: GUI 展示和预览图生成不改写作者骨骼姿态，也不替作者修复几何、动画或艺术适配；[默认动画的艺术适配保证](bone-and-controller.md#bcanimation-reuse-does-not-guarantee-artistic-fit)保持不变。空几何或非法数值不要求生成可见模型，但不得污染后续正常帧或其他卡片。

### BC.gui-preview-absence-is-session-observed

- Claim: 普通 preview 请求明确区分有效图片、合法缺图和实际失败。合法缺图可来自服务端确认独立图 miss，或客户端确认完整独立图无法解码；声明的内嵌图不可读或无效属于失败，损坏的独立图按 miss 处理。缺图模型继续可用，普通浏览不触发管理补图或上传。
- Claim: 缺图判断只适用于当前连接中的对应模型内容，不能跨连接或跨内容替换沿用，避免旧缺图记录遮蔽新可用的内容。
- Claim: 无可用图片也无 3D 内容的缺图卡片显示占位图（placeholder）。当前服务端 level 2 管理员会同时看到 `/ysm model gen-preview` 与“在客户端导出 `.mxc` 后添加到服务端”两条指引，普通玩家则看到联系管理员提示。新展示读取当前权限，提示本身不授予权限。请求被拒绝、BUSY、传输或其他内容失败直接结束对应等待，不显示缺图指引，也不永久标坏模型。

## DD.visible-creator-attribution

- Claim: 模型的来源创作者信息必须随模型向玩家展示，并在模组控制的处理与呈现环节中保持完整，不被篡改或丢失。
- Rationale: 自定义内容来自独立创作者，模型分享和选择不能切断作品与创作者之间的关联；如果只有展示入口而处理过程允许署名被覆盖，作品仍会失去归属。保留并传递已有声明可以复用模型元数据，无需制作组承担作者身份认证或模组外部文件的防篡改责任。

### BC.attribution-travels-with-model

- Claim: 对输入中已有的来源创作者署名，模组控制的导入、转换、加载、导出、分享和 GUI 展示都必须保留其归属含义，不得擅自改写、替换、丢弃或让无关设置与内容覆盖；内部表示变化不能成为丢失署名的理由。

### BC.attribution-protection-has-an-input-boundary

- Claim: 署名保护以模组收到的输入声明为边界，不认证声明者身份或作品权属，也不保证阻止外部工具修改文件。输入已缺失或在模组外部被篡改时，不承诺恢复真实署名，且不得通过猜测作者或改署制作组名义来补齐信息。
