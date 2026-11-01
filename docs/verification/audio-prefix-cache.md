# 音频 PCM 缓存验证

本页维护默认后端的验证入口和证据边界。缓存职责见[PCM 保留与发布](../architecture/model-management/audio/overview.md#pcm-保留与工作集)，播放终态见[播放终态](../architecture/model-management/audio/overview.md#播放终态)。

## 正确性

| 入口 | 已覆盖的契约 |
|---|---|
| Native `OpusCodecTest` | mono/stereo、gain、非零 origin、未完成 pre-skip、跨页 partial packet；续接与连续解码逐字节一致；同一 checkpoint 的两个恢复实例独立；拒绝未 drain、半页、EOS 和失败状态 |
| `NativeObjectAdoptionIntegrationTest` | checkpoint 与恢复 decoder 的原始 handle 在采用失败时回收；同一 checkpoint 可重复恢复 |
| `PlaybackAudioStreamTest` | 按 chunk 数分类；零需求、缓存足量和恰好用尽不恢复；首次缺口、水位独立、跨轮消费；尾块延迟时，prepared 前重复消费与宿主补给均等待原请求；cold/hot/loop PCM 与连续解码一致，host format 为 mono；调度分配失败归还 encoded 与解码数据预算 |
| `AudioRetentionCacheTest` | 分段范围读取、只读结果、前缀帧数、checkpoint 计价、TTL/LRU、receipt 和活动强引用 |
| `ClientAudioRuntimeIntegrationTest` | 每次触发重新授权、恢复失败、发布和宿主提交与 stop 的原子边界、提交期间同步关闭、候选预算不足及尾部失败隔离；真实 JNI restore 内暂停后取消，覆盖 prepared 前和宿主提交后，无迟到提交且活动账本归零 |
| `SoundResourceAccessIntegrationTest` | 驻留首块 token、顺序尾块、延迟完成、取消和 loop restart |

Java 测试加载与源码配对的 DLL。先重建 native DLL 和 `ysm-test`，再运行 Opus CTest 与聚焦 Java 回归：

```powershell
ctest --test-dir <native-build> --output-on-failure -R Opus
$env:YSM_NATIVE_PATH='C:/absolute/path/to/ysm.dll'
.\gradlew.bat test --tests 'com.elfmcys.ysm.model.resource.client.audio.*' --tests 'com.elfmcys.ysm.natives.NativeObjectAdoptionIntegrationTest'
```

测试使用真实 libopus/JNI，受控 worker 和 headless host。原始 handle 的故障采用走现有 `NativeObject` 回收边界；恢复失败和暂停入口穿过真实 JNI。

音频样本及生成参数由测试资源中的 `audio-playback/manifest.json` 维护。首 chunk 在首个有效 audio page 结束，未合并多张 page 来凑准备阈值。单 chunk 样本覆盖 4、4.9 和 6.000354 秒；多 chunk 样本覆盖首块不足 4 秒、4.4935 秒和 5.9935 秒。

测试与 benchmark 共用 `src/verification/shared/java` 中的夹具。Benchmark 直接复用测试资源，不保留媒体副本。

## 驻留性能入口

`AudioPrefixBenchmark.consume` 复用[持续性能基建](../tools/benchmark/README.md)。Trial setup 连续解码作为 oracle，再让真实冷播放按 8192 bytes 批次完整消费并发布分段缓存。后续热路径使用该生产值。

| phase | 计时阶段 |
|---|---|
| `cachedOnly` | 从缓存 cursor 起点供给 N bytes 并完成 host read，无 decoder |
| `coldPrepared` / `hotPrepared` | stream 激活至 prepared；后续补至高水位和清理仍计入主 score |
| `firstGap` | 先耗尽前缀，计量下一次 N bytes 请求，包括恢复及第一个尾部 page 的解码 |
| `tail` | 先恢复并消费尾部 2 bytes，计量剩余全部尾部至 EOF；每次请求 N bytes |
| `loop` | 先消费完整一轮，计量跨 EOF 后下一轮的第一个 N bytes 请求 |
| `coldCycle` / `hotCycle` | 从激活到完整单轮 EOF；包含本轮候选发布、供给和 host read |

`media=single` 是单 chunk；`ordinary`、`shortPrefix`、`overFive` 分别是首块 4.4935 秒、不足 4 秒和超过 5 秒的多 chunk。单 chunk 不运行 `firstGap` / `tail`。

```powershell
.\gradlew.bat benchmark '-PbenchmarkInclude=.*AudioPrefixBenchmark.consume' '-PbenchmarkArgs=-p media=ordinary,shortPrefix,overFive -p phase=cachedOnly,coldPrepared,hotPrepared,firstGap,tail,loop -p requestedBytes=8192 -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/audio-prefix-multi.json'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*AudioPrefixBenchmark.consume' '-PbenchmarkArgs=-p media=single -p phase=cachedOnly,coldPrepared,hotPrepared,loop -p requestedBytes=8192 -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/audio-prefix-single.json'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*AudioPrefixBenchmark.consume' '-PbenchmarkArgs=-p media=single,ordinary,shortPrefix,overFive -p phase=coldCycle,hotCycle -p requestedBytes=8192 -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/audio-prefix-cycle.json'
```

主 score 和 `gc.alloc.rate.norm` 覆盖整个操作，包括阶段前置消费与清理。EVENTS counter 是迭代累计值。阶段均值按所有测量迭代的 `sum(stageWallNs) / sum(operations)` 计算，CPU 同理。create/restore 也须除以 operations；它们覆盖整个操作，包含阶段前置消费。

输入全驻留，输入 executor 同步交付；播放 executor 由 benchmark 线程推进。因此准备阶段不包含真实 worker 排队、网络或设备调度。N 为 8192 bytes，即 4096 个 mono 帧。`cachedOnly` 的首次请求落在单个分段内，不能外推任意跨段读取成本。

`observedDecodedPeakBytes / operations` 是操作内多个快照中最大值的均值。快照会遗漏消费调用内部的 reservation，不能当作精确账本峰值或 RSS。共享 entry 的 PCM 和 checkpoint 大小单独报告；GC profiler 只覆盖 Java heap。源码、fixture 与 DLL hash、JDK/JVM、硬件、电源状态和原始结果保存在同一生成报告目录，不写入架构正文。

## 样本结果

Windows x64、Release DLL 的驻留采样覆盖 30 个组合。每组合单线程、两个独立 fork；每 fork 预热 2×1 秒，测量 3×1 秒，启用 GC profiler。N 固定为 8192 bytes。以下数值是当前输入的描述性结果，不是历史版本对照。不同媒体之间的耗时不能用来比较缓存收益。

| media | 总帧数 | 缓存有效秒数 | checkpoint 计价 bytes | entry 计价 bytes |
|---|---:|---:|---:|---:|
| single，mono | 288017 | 6.000354 | 0 | 576034 |
| ordinary，stereo | 576017 | 4.493500 | 27476 | 458852 |
| shortPrefix，stereo | 576017 | 0.993500 | 27476 | 122852 |
| overFive，stereo | 576017 | 5.993500 | 27476 | 602852 |

准备阶段的 wall/CPU 单位均为 ms/op。`热操作主 score` 还包含 prepared 后补至高水位及清理。

| media | 冷 prepared wall / CPU | 热 prepared wall / CPU | 热操作主 score |
|---|---:|---:|---:|
| single | 3.335 / 3.295 | 0.001 / 0.001 | 0.002 |
| ordinary | 4.502 / 4.324 | 0.117 / 0.081 | 4.469 |
| shortPrefix | 6.454 / 6.358 | 5.136 / 4.938 | 6.449 |
| overFive | 5.930 / 5.830 | 0.062 / 0.061 | 0.065 |

ordinary 的 prepared 由缓存满足，但补至 5 秒会恢复 decoder；其整个热准备操作记录一次 restore。shortPrefix 也记录一次；single 与 overFive 为零。热路径均没有普通 create。冷路径每次操作记录一次普通 create。

下表的尾部时间覆盖**全部剩余尾部**，不是单个 8192 bytes 请求。`firstGap` 包括恢复和整张输入 page 产生 PCM 的工作，因此恢复推迟后仍有毫秒级供给成本。

| media | cachedOnly µs | firstGap ms | 剩余尾部 ms / 请求数 / bytes | 下一轮首个请求 µs |
|---|---:|---:|---:|---:|
| ordinary | 5.637 | 4.383 | 3.499 / 89 / 720656 | 32.278 |
| shortPrefix | 5.755 | 1.259 | 13.512 / 130 / 1056656 | 55.066 |
| overFive | 5.631 | 5.810 | 0.608 / 72 / 576656 | 23.986 |

三个媒体的 firstGap、tail 和 loop 整操作都记录一次 restore。loop 的该次恢复来自上一轮前置消费；下一轮首个请求只消费缓存，不能据整操作计数推断它再次恢复。single 的 cachedOnly 和下一轮首个请求分别为 5.044、5.574 µs，全程没有 create/restore。极短阶段的线程 CPU 计数可能受计时粒度影响，零值不表示没有 CPU 成本。

完整单轮包含到 EOF 的供给；主 score 另含清理。下表各项均按“冷 → 热”排列。CPU 是激活至 EOF 的阶段值，heap 是整操作值。最后一列仍是快照观察值。

| media | 主 score ms/op | 阶段 CPU ms/op | heap KiB/op | 已观测 decoded bytes |
|---|---:|---:|---:|---:|
| single | 4.133 → 0.314 | 3.978 → 0.287 | 1721.7 → 595.1 | 581632 → 576034 |
| ordinary | 14.896 → 9.675 | 14.291 → 9.440 | 2744.5 → 2309.9 | 434176 → 467044 |
| shortPrefix | 18.535 → 16.767 | 17.905 → 16.008 | 2534.4 → 2434.1 | 98304 → 131044 |
| overFive | 12.409 → 6.476 | 12.284 → 6.330 | 2885.5 → 2320.6 | 581632 → 611044 |

这些样本支持保留 Complete 与 Prefix 路径：single、ordinary 和 overFive 的整轮时间与 CPU 均下降，未只把 prepared 的工作推后。代价包括共享 entry、checkpoint 和每次供给的复制。Prefix 跨分段读取还会产生一次临时拼接；当前结果没有要求增加预热或额外调度机制。

shortPrefix 的整轮主 score 误差较大：冷为 18.535 ± 1.504 ms，热为 16.767 ± 5.365 ms（JMH 默认置信界）。该样本尚不能确认净提速。checkpoint 本身占其前缀 PCM 的约 29%，短前缀的保留成本不能忽略。上述有限采样不覆盖多声音竞争、长期内存、异步排队或任意编码参数。

完整 JSON、日志、输入摘要和计量元数据分别保存在生成报告目录的 `audio-prefix-{multi,single,cycle}.json`、对应 `.log`、`audio-prefix-summary.json` 和 `audio-prefix-metadata.json`。

## 宿主边界

受控 host 回归覆盖提交、失败、停止和迟到结果。它不覆盖真实 Forge/OpenAL 的可听延迟、设备容量、音量、once-only 世界触发或长期物理回收。驻留 JMH 的 prepared 只表示媒体储备充足，不能解释为立即可听。
