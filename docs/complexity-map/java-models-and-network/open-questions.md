# 因果缺口与差异

本页维护 MN 子系统需要产品裁决或运行证据的缺口；产品判断仍以[产品决策树](../../product-decisions/README.md)为 Root Authority。

## Q-09

**强制选择能否形成独立资产访问特权。** 当前 `ServerModelSession.selectForced/canRequest` 对被强制选择的当前 ModelId 使用私有 `ignoreGrants`，测试明确要求它只绕过自身 chunks 而不改 grants。Protocol 与架构能证明此设计存在，但[选择与内容权限](../../product-decisions/decisions/model-authorization.md)没有给出此模式相对 `RESTRICTED_AUTH` 的例外理由或优先级。

- 待裁决：保留独立特权，还是管理员操作也只经明确的授权规则；若保留，应定义可发起者、受限访问、再次选择/切纹理、撤权、切模和重连的行为。
- 不能推定“被强制设置模型”等同“有权自行选择”，也不能仅凭此差异断言存在越权漏洞。
- 若取消，MN-15 的私有模式及保持/清除/持久恢复分支可收缩；直接发普通 grants 又可能新增自行选择权，并非自动等价。中等迁移成本集中于命令、capability、session 和 admission；最小验证为受限访问×强制选择×撤权×重连矩阵。

## Q-10

**共享派生 cache 的 writer 协调收益尚不能整体归约。** MN-23 的 JVM key lock、进程文件锁、取锁后重验和临时对象提交都有源码依据；NR-05 几何 baked cache 与 MN-27 的 animation-set cache 都使用这条 seam。后者将动画 chunks 与 expression artifact 原子提交到同一文件，仍以自身 specialization/ABI identity 管理；Converted 已改由 MN-24 的活跃消费者登记和一次保守 prune 管理，remote、preview、direct source 也不使用该协议。相同物理目录不能据此合并生命周期、key 或删除条件。

- 待补证据：baked 现在与 remote 同处用户级 cache root，同一用户的多个实例可以命中同一 key 与同一锁目录；串行 materialize 减少的重复生产是否抵偿锁、重验与文件管理成本仍未测量。GitNexus 的有界邻接仅帮助选入口，缺边不证明没有消费者。
- 归约候选：比较保持共享、按进程隔离、或复用更小的同 key materialization owner。允许新增统一 owner 来承接重复协调，但要计入第二套缓存、文件量、重复编译与迁移成本，不能只比较锁数量；MN-24 的 consumer/prune authority 不随之合并。
- 最小验证：从 baked 与 remote 的实际入口启动两个 producer，覆盖同 key、坏对象替换、写失败和 owner 关闭；核验单次交付、各自 identity 与 ABI/profile 隔离。MN-20 的模型级失败记忆另有构造/调用/请求范围，不能顺手合成全局失败表。

## 其他验证差异

真实 Forge 双端、持续拥塞、重连、host publication 峰值以及 converted/shared-cache 的多进程行为仍未实机闭合。它们限制性能与可靠性声明，不构成新增 generation、history、全局 cache owner 或恢复队列的自动理由。
