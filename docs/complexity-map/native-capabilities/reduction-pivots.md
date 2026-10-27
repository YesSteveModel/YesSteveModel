# 归约支点

本页保留由 NR 子系统主导的 cache、archive 和未证明补偿归约支点。

| 支点 | 最小改变 → 可减少部分 | 剩余义务 / 语义、性能和可靠性成本 / 最小验证 |
|---|---|---|
| V-07 简化能力相关派生物 | 对 resident bake 直接交付对象，或读取后再布局化 → 局部 serialize→read、能力 cache 分裂可缩减。 | NR-05 磁盘 cache 和校验仍有消费者；可能新增结果形状、schema 迁移或加载重排成本，并非把复杂度归零。中高成本；先量化默认初始化转换开销及跨宽度等价，不改公开作品身份。 |
| V-08 显式拥有 archive bytes | 将 A-ARCHIVE 的跨语言结果改 owning → “下次读取前必须 copy”的跨域时间耦合可消失。 | AP-01 的一致输入与独立捕获仍需要；allocation/copy 可能仅从 Java 移到 native，7z block cache 也仍需要。中成本；连续两次读取后旧 bytes 不变，比较复制次数/驻留后再判断净收益。 |
| V-09 收敛未经证明的补偿 | 先闭合 Q-02；前提确无独立价值后再删除 holder/allocator 分支。Java 额外 fence 已随 owner 汇合收敛而删除。 | 加载/TLS 与跨线程完成关系分别验证。低中实现成本、证据成本可能高；错误删除会崩溃或引入可见性错误。历史混合 AN-08 另需 Q-06 的作品兼容裁决，不从“未记录理由”推导可删。 |

不建议把 legacy 直接写当前容器迁到 native 来“少一次验证”：NR-13 的协议形状、投影保真、staging 重开分别检查不同失败，迁移还会复制当前 Java 格式 authority。

同帧计算调度与双输出路径由 [AN 归约支点](../java-animation-and-integration/reduction-pivots.md)主导；改变 Java 调度不自动删除 NR-08/09 的输出隔离和完成义务。
