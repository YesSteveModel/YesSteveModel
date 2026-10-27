# 资源生命周期场景

本页按事件比较资格撤销、工作终结、独立保留与物理回收，不定义统一资源状态机。Owner 与配额见[资源所有权与配额](resource-ownership.md)；关闭偏序直接使用 [Runtime 结束图](../runtime/overview.md#结束与替换)。表内并列项没有隐含执行顺序。

## Catalog reload 与已有使用

| 事件 | 资格／可见性变化 | 本次结束的工作或引用 | 仍可存续 | 物理清理条件 |
|---|---|---|---|---|
| Catalog 发布新 current | 未来查询与需求观察新目录；远端 authority 生效时，local 更新不直接覆盖远端投影 | Catalog 撤被替换内容的自身引用 | 合法旧安装、target、已接纳传输及各自 lease/pin | 旧内容最后保留结束；不替消费者关闭 |
| 坏新项／不完整扫描 | 坏新项不替旧项；不完整扫描不证明缺失删除；健康项仍可发布 | 未接纳 candidate 的自身责任 | 旧 current 与合法消费者 | 各失败候选归原 owner 清理 |
| 成功显式 Catalog reload 的独立图片失效 | 撤旧 flight 加入与发布资格 | 撤服务端独立图片缓存引用 | 已接纳 waiter 与发送 lease 可完成旧快照 | 旧 worker 到真实终态；最后保留者退出后回收 bytes |
| 实体 owner 显式 reload | 结束当前使用，清本 owner 安装失败记忆 | 当前 installation 的效果、输出与使用 | 其他 owner、合法共享保留与独立 playback | 遵循下文 retire 的任务与借用边界 |

Catalog 发布与实体显式 reload 是不同事件。目录移除或未来读盘失败不追溯销毁完整合法安装；安装资格仍由安装 owner 裁决。依据见[Catalog 发布](../model-management/catalog/overview.md#逐项发布与替换)、[发布与使用](../model-management/overview.md#发布与使用的独立终点)、[安装关系](../animation/entity-lifecycle.md#指派请求与安装关系)、[图片失效](../model-management/server-resources/overview.md#图片失效与旧请求)及[实体 reload](../animation/entity-lifecycle.md#暂停reset-与-retire)。

## 连接撤销与替换

| 事件 | 资格／可见性变化 | 本次结束的工作或引用 | 仍可存续 | 物理清理条件 |
|---|---|---|---|---|
| Exact connection 撤销／替换 | 先摘旧 current，撤旧接纳与提交资格；replacement 不继承旧 fetcher | 原 owner 终结旧 transfer/assembly，撤 activation、entity 与 world 投影 | 与旧连接无关的合法资源与其他消费者 | 取消不等于终态；旧工作结束后只归还自身持有，不写 replacement |
| Client disconnect／退出世界 | 恢复最新 Local Catalog 投影，撤本次页面与实体需求 | 关闭本次 scopes，停止活动 playback | 进程 Catalog、合法 Ready、required lease、PCM cache 与已提交磁盘对象 | Scope 只撤自身 attempt/anchor；播放和网络各等自己的终态 |
| 目录失败进入 default-only | 收缩目录可用范围，不撤已握手业务资格 | 按目录失败规则结束本次候选 | Exact connection 与已建立业务资格 | 由本次候选 owner 清理；不能套用 disconnect 的清空范围 |
| Demand scope 关闭 | 撤本 scope 后续接纳资格 | 自身 attempt／anchor | 已转交用途与接收者独立 lease | 新 owner 负责已交接保留；网络不能替其他消费者关闭共享 target |

依据见[连接撤销交接](../network/overview.md#连接撤销交接)、[业务资格](../network/overview.md#exact-connection-与业务资格)、[需求取消](../model-management/demand/overview.md#需求取消与失败归属)、[用途准备](../model-management/demand/overview.md#用途准备与独立接收)与[播放终态](../model-management/audio/overview.md#播放终态)。恢复 local 与停止播放描述 disconnect，不证明 replacement 远端会话已经激活。

## 实体替换、retire 与物理回收

| 事件 | 资格／可见性变化 | 本次结束的工作或引用 | 仍可存续 | 物理清理条件 |
|---|---|---|---|---|
| 完整新安装成功发布 | 新安装替换旧安装，撤旧效果与输出资格 | 旧私有状态与旧 installation lease | 其他 lease/cache/pin 及独立 playback | 原任务真实终态与最外层同步借用退出，两个条件均满足后清理 |
| 新候选构造失败 | 不发布新安装 | 仅关闭候选 | 原完整安装 | 候选按自己的任务与借用责任释放 |
| 仅纹理兼容替换 | 成功时转移适用私有状态的关闭权 | 旧安装未转移的责任 | 接收者已接管状态 | 不能先销毁再复用；兼容激活半修改后失败应结束旧使用，不回滚半修改 holder |
| Entity retire | 撤当前指派、效果与输出资格 | 私有状态及自身 lease | 其他消费者、普通 unused cache、required default 与独立 playback | 同样等待任务真实终态与最外层借用退出 |
| Target 最后保留结束 | 不再允许借裸引用继续使用 | 原资源 owner 清理，撤准备依赖 pin，发纹理 logical release | 尚有其他 pin 的 module/animation、宿主待清理资源、allocator 空闲容量 | 各 backing 按自身协议释放；宿主队列执行 adopted texture/GPU 的物理关闭 |

完整替换与兼容替换的条件见[安装与替换](../animation/entity-lifecycle.md#完整安装与替换)，retire 义务见[暂停、reset 与 retire](../animation/entity-lifecycle.md#暂停reset-与-retire)。最后 lease 关闭不保证 target 已退出 cache；资源 cleanup、宿主物理关闭与 RSS 下降也不是同一终点，见[资源关闭](../model-management/client-resources/overview.md#引用撤销与物理关闭)及[辅助保留](../runtime/implementation/auxiliary-resources.md)。

## 服务结束与进程结束

| 事件 | 资格／可见性变化 | 本次结束的工作或引用 | 仍可存续 | 物理清理条件 |
|---|---|---|---|---|
| Game server stop | 先撤本服务接纳与提交资格 | 本服务 sessions、dispatch、export environment | 进程 Catalog、server resource cache、独立 client 状态、已提交制品 | 各 owner 结束自身工作；export 只等待不依赖 host/tick 的自有 worker |
| Client 服务关闭 | 封住 client 新接纳与提交 | Client resources、demand 与远端 owner；音频活动和 cache | 已提交磁盘对象、其他进程保留、尚待宿主执行的 GPU 清理 | 通知、计算容量及资源按 Runtime 偏序终结；活动借用不能提前释放 |
| 进程运行组合关闭 | 封住新接纳，停止 Catalog producer | 依次关闭 client/server runtime，最后释放 converted 登记等进程资源 | 用户来源、未被清理规则删除的磁盘 cache；allocator 可保留容量 | 全部使用者与借用结束后释放所属资源；不承诺容量同步归还 OS |

以上事件不能互相替代。等待应能在没有 owner tick 的情况下结束；中断请求或超时不能证明 worker 退休。成功提交的制品不因服务结束回滚。依据见[运行结束](../runtime/overview.md#结束与替换)、[预览与导出](../asset-pipeline/overview.md#预览与导出)、[音频终态](../model-management/audio/overview.md#播放终态)及[存储清理](../model-management/storage/overview.md#共享存储与清理)。

本页比较设计义务；运行验证的覆盖范围见[当前状态](../../status/support-and-verification.md)。
