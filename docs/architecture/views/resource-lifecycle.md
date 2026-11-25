# 资源生命周期场景

本页按典型事件横向对照资格撤销、工作终结、独立保留与物理回收四个维度。Owner 归属与内存配额见[资源所有权与配额](resource-ownership.md)，进程内受控关闭与 JVM 退出边界见[结束与替换](../runtime/overview.md#结束与替换)。

## Catalog reload 与已有使用

| 事件 | 资格与可见性变化 | 本次结束的工作或引用 | 仍可存续 | 物理清理条件 |
|---|---|---|---|---|
| Catalog 发布新 current | 后续查询与需求观察新目录；远端权威生效时，本地更新不覆盖远端投影 | Catalog 撤回被替换内容的自身引用 | 合法旧安装、target、已接纳传输及各自 lease/pin | 旧内容最后一份保留结束 |
| 坏新项或不完整扫描 | 坏新项不覆盖旧项；不完整扫描不证明条目缺失或删除；健康项正常发布 | 未接纳候选各自的清理责任 | 旧 current 与合法消费者 | 各失败候选由原 owner 清理 |
| 成功显式 Catalog reload 触发独立图片失效 | 撤销旧 flight 的加入与发布资格 | 撤回服务端独立图片缓存引用 | 已接纳 waiter 与发送 lease 可继续完成旧快照 | 旧 worker 达终态且最后保留者退出后回收字节 |
| 实体 owner 显式 reload | 结束当前使用，清空本 owner 安装失败记忆 | 当前安装的效果、输出与模型使用 | 其他 owner、合法共享保留与独立音频播放 | 遵循实体任务交接与绘制借用边界 |

Catalog 发布与实体显式 reload 相互独立：目录移除条目或后续读盘失败不追溯销毁已建立的合法安装。依据：[Catalog 发布](../model-management/catalog/overview.md#seam-contract)、[发布与使用](../model-management/overview.md#发布与使用的独立终点)、[安装关系](../animation/entity-lifecycle.md#指派请求与安装关系)、[图片失效](../model-management/server-resources/overview.md#图片失效与旧请求)、[实体 reload](../animation/entity-lifecycle.md#暂停reset-与-retire)。

## 连接撤销与替换

| 事件 | 资格与可见性变化 | 本次结束的工作或引用 | 仍可存续 | 物理清理条件 |
|---|---|---|---|---|
| 精确连接撤销或替换 | 摘除旧 current，撤销旧连接接纳与提交资格；替代连接不继承旧 fetcher | 原 owner 终结旧传输与组装，撤回 activation、实体与世界投影 | 与旧连接无关的合法资源及其他消费者 | 旧工作到达真实终态后仅归还自身持有，不写入替代连接 |
| 客户端断连或退出世界 | 恢复最新 Local Catalog 投影，撤销本次页面与实体需求 | 关闭本次 scopes，停止活动音频播放 | 进程 Catalog、合法 Ready 缓存、required lease、PCM cache 与已提交磁盘对象 | Scope 撤回自身 attempt/anchor；音频播放与网络各自等自身终态 |
| 目录失败进入 default-only | 收缩可用目录范围，保留已握手业务资格 | 按目录失败规则结束本次候选 | 精确连接与已建立业务资格 | 由本次候选 owner 清理 |
| Demand scope 关闭 | 撤销本 scope 后续接纳资格 | 自身的 attempt 与 anchor | 已转交用途与接收者独立 lease | 新 owner 负责已交接保留 |

依据：[连接撤销交接](../network/overview.md#连接撤销交接)、[业务资格](../network/overview.md#连接业务资格与撤销)、[需求取消](../model-management/demand/overview.md#需求取消与失败归属)、[用途准备](../model-management/demand/overview.md#用途准备与独立接收)、[播放终态](../model-management/audio/overview.md#播放终态)。

## 实体替换、retire 与物理回收

| 事件 | 资格与可见性变化 | 本次结束的工作或引用 | 仍可存续 | 物理清理条件 |
|---|---|---|---|---|
| 完整新安装成功发布 | 新安装替换旧安装，撤销旧安装效果与输出资格 | 旧私有状态与旧安装 lease | 其他 lease、缓存、pin 及独立音频播放 | 原任务达真实终态且最外层同步绘制借用退出后清理 |
| 新候选构造失败 | 保持旧安装 | 仅关闭该候选 | 原完整安装 | 候选按自身任务与借用责任释放 |
| 仅纹理兼容替换 | 原位保留私有执行态并发布新绑定 | 旧绑定的查询引用与 lease | 同一个 session、实例、controller、播放进度与事件游标 | 执行态关闭权留实体根；若激活开始改状态后失败则结束整个使用 |
| Entity retire | 撤销当前指派、效果与输出资格 | 私有状态及自身 lease | 其他消费者、普通 unused cache、required default 与独立音频播放 | 等待任务真实终态与最外层同步借用退出 |
| Target 最后一份保留结束 | 不再允许凭裸引用使用 | 原资源 owner 清理，撤回准备依赖 pin，发出纹理 logical release | 仍有其他 pin 的 module/动画、宿主待清理资源、allocator 空闲容量 | 底层资源按各自协议释放；宿主队列执行已采用纹理与 GPU 物理关闭 |

依据：[候选与兼容条件](../animation/entity-lifecycle.md#候选与兼容条件)、[暂停、reset 与 retire](../animation/entity-lifecycle.md#暂停reset-与-retire)、[引用撤销与物理关闭](../model-management/client-resources/overview.md#引用撤销与物理关闭)、[辅助保留](../runtime/implementation/auxiliary-resources.md)。最后一份 lease 关闭、target 退出缓存、宿主物理关闭与进程 RSS 下降为各自独立的终点。

## 服务结束与进程结束

| 事件 | 资格与可见性变化 | 本次结束的工作或引用 | 仍可存续 | 物理清理条件 |
|---|---|---|---|---|
| Game server stop | 撤销本服务接纳与提交资格 | 本服务 sessions、dispatch 与 export environment | 进程 Catalog、服务端资源缓存、独立客户端状态、已提交制品 | 各 owner 结束自身工作；导出模块仅等待不依赖宿主 tick 的自有 worker |
| 客户端服务显式关闭 | 封住客户端新接纳与提交 | 自有候选、资源引用、demand、远端 owner、音频与 workers | 其他消费者持有的实体执行态与 store、已提交磁盘对象、宿主资源 | 结束自有工作；实体与单个 store 沿各自 owner/pin/保护独立退休 |
| 独立 `ModelSystem` 显式关闭 | 封住本域新接纳，停止 Catalog producer | 本域客户端与服务端 runtime、存储与 CACHE 根登记 | 域外合法保留、用户来源、未被清理规则删除的磁盘 cache | 领域 owner 结束工作与借用；存储显式排空（drain）后退出根登记 |
| 整个 JVM 退出 | 进程终止服务 | 放弃运行中资源与未提交工作 | 已持久化合法值及用户来源 | 不等后台工作、不刷盘或排空数据库；由 JVM 与 OS 回收 |

进程内显式等待须在无宿主 tick 驱动下完成，中断或超时不视作 worker 终态。依据：[运行结束](../runtime/overview.md#结束与替换)、[导出终结](../asset-pipeline/overview.md#导出批次与提交)、[音频终态](../model-management/audio/overview.md#播放终态)、[存储清理](../model-management/storage/overview.md#共享存储与清理)；运行验证覆盖见[当前状态](../../status/support-and-verification.md)。
