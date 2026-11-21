# 音频 PCM 缓存验证

本页记录默认音频后端的验证入口与证据边界。缓存职责见[PCM 保留与发布](../architecture/model-management/audio/overview.md#pcm-保留与工作集)，播放终态见[播放终态](../architecture/model-management/audio/overview.md#播放终态)。

## 正确性

| 入口 | 已覆盖的契约 |
|---|---|
| Native `OpusCodecTest` | 单声道与立体声、gain、非零 origin、未完成 pre-skip 以及跨页 partial packet。校验续接与连续解码逐字节一致、同一 checkpoint 的两个恢复实例彼此独立，并拒绝未 drain、半页、EOS 和失败状态 |
| `NativeObjectAdoptionIntegrationTest` | 采用失败时正确回收 checkpoint 与恢复 decoder 的原始 handle，以及同一 checkpoint 可重复恢复 |
| `PlaybackAudioStreamTest` | 按 chunk 数分类；零需求、缓存足量和恰好用尽时不触发恢复；首次缺口、水位独立和跨轮消费；尾块延迟时，prepared 前的重复消费与宿主补给均等待原请求；冷播放、热播放和循环播放的 PCM 与连续解码一致，宿主格式为单声道；调度分配失败时归还编码与解码数据预算 |
| `AudioRetentionCacheTest` | 分段范围读取、只读结果、前缀帧数、checkpoint 计价、TTL 与 LRU、receipt 及活动强引用 |
| `ClientAudioRuntimeIntegrationTest` | 每次触发重新授权、恢复失败、发布及宿主提交与 stop 的原子边界、提交期间同步关闭、候选预算不足及尾部失败隔离；穿过真实 JNI 在 restore 内暂停后取消（覆盖 prepared 前与宿主提交后），确认无迟到提交且活动账本归零 |
| `SoundResourceAccessIntegrationTest` | 驻留首块 token、顺序尾块、延迟完成、取消与循环重启 |

Java 测试加载与当前源码配对的 DLL。运行前先重建 native DLL 和 `ysm-test`，再依次执行 Opus CTest 与聚焦的 Java 回归：

```powershell
ctest --test-dir <native-build> --output-on-failure -R Opus
$env:YSM_NATIVE_PATH='C:/absolute/path/to/ysm.dll'
.\gradlew.bat test --tests 'com.elfmcys.ysm.model.resource.client.audio.*' --tests 'com.elfmcys.ysm.natives.NativeObjectAdoptionIntegrationTest'
```

上述测试使用真实的 libopus 与 JNI、受控 worker 以及 headless 宿主。原始 handle 的故障采用走现有的 `NativeObject` 回收边界，恢复失败和暂停入口则穿过真实 JNI。

音频样本及生成参数统一维护在测试资源的 `audio-playback/manifest.json` 中。首 chunk 在首个有效音频页结束，未人为合并多张页去凑准备阈值。单 chunk 样本覆盖 4、4.9 和 6.000354 秒，多 chunk 样本覆盖首块不足 4 秒、4.4935 秒和 5.9935 秒。

测试与 benchmark 共用 `src/verification/shared/java` 下的夹具，benchmark 直接复用测试资源，不另存媒体副本。

## 驻留性能入口

`AudioPrefixBenchmark.consume` 复用[持续性能基建](../tools/benchmark/README.md)。Trial setup 先做一次连续解码作为真值对照，再让真实冷播放按 8192 bytes 批次完整消费并发布分段缓存，后续热路径直接消费该生产值。

| phase | 计时阶段 |
|---|---|
| `cachedOnly` | 从缓存游标起点供给 N bytes 并完成宿主读取，不涉及 decoder |
| `coldPrepared` / `hotPrepared` | 从 stream 激活到 prepared；后续补至高水位和清理仍计入主 score |
| `firstGap` | 先耗尽前缀缓存，计量下一次 N bytes 请求，包含恢复及首个尾部页的解码 |
| `tail` | 先恢复并消费尾部前 2 bytes，计量剩余全部尾部直至 EOF，每次请求 N bytes |
| `loop` | 先消费完一整轮，计量跨过 EOF 后下一轮的首个 N bytes 请求 |
| `coldCycle` / `hotCycle` | 从激活到完整单轮 EOF，包含本轮候选发布、供给与宿主读取 |

参数 `media=single` 对应单 chunk；`ordinary`、`shortPrefix`、`overFive` 分别对应首块为 4.4935 秒、不足 4 秒和超过 5 秒的多 chunk 样本。单 chunk 不运行 `firstGap` 和 `tail`。

```powershell
.\gradlew.bat benchmark '-PbenchmarkInclude=.*AudioPrefixBenchmark.consume' '-PbenchmarkArgs=-p media=ordinary,shortPrefix,overFive -p phase=cachedOnly,coldPrepared,hotPrepared,firstGap,tail,loop -p requestedBytes=8192 -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/audio-prefix-multi.json'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*AudioPrefixBenchmark.consume' '-PbenchmarkArgs=-p media=single -p phase=cachedOnly,coldPrepared,hotPrepared,loop -p requestedBytes=8192 -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/audio-prefix-single.json'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*AudioPrefixBenchmark.consume' '-PbenchmarkArgs=-p media=single,ordinary,shortPrefix,overFive -p phase=coldCycle,hotCycle -p requestedBytes=8192 -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/audio-prefix-cycle.json'
```

主 score 和 `gc.alloc.rate.norm` 覆盖整个操作，包括阶段前置消费与清理。EVENTS 计数器记录迭代累计值；各阶段均值按全部测量迭代的 `sum(stageWallNs) / sum(operations)` 计算，CPU 同理。create 与 restore 计数也须除以 operations，且同样包含阶段前置消费。

本基准中输入全部常驻内存，输入 executor 同步交付，播放 executor 由 benchmark 线程推进，因此准备阶段不包含真实的 worker 排队、网络传输或音频设备调度。N 固定为 8192 bytes（即 4096 个单声道帧）。`cachedOnly` 的首次请求落在单个缓存分段内，不能直接外推任意跨段读取的成本。

`observedDecodedPeakBytes / operations` 是单次操作内多次快照最大值的均值；由于快照会漏掉消费调用内部的瞬时预留，不能将其当作精确账本峰值或 RSS。共享 entry 的 PCM 与 checkpoint 体积单独报告，GC profiler 仅覆盖 Java 堆。源码、fixture 与 DLL 哈希、JDK 与 JVM 版本、硬件、电源状态和原始结果保存在同一生成报告目录，不写入架构正文。

## 样本结果

以下为 Windows x64、Release DLL 下的驻留采样，覆盖 30 个组合。每个组合采用单线程、2 个独立 fork，每 fork 预热 2×1 秒、测量 3×1 秒，并启用 GC profiler，N 固定为 8192 bytes。这些数值是对当前输入的描述性基线，不是历史版本对照；不同媒体之间的绝对耗时也不能直接用来比较缓存收益。

| media | 总帧数 | 缓存有效秒数 | checkpoint 计价 bytes | entry 计价 bytes |
|---|---:|---:|---:|---:|
| single（mono） | 288017 | 6.000354 | 0 | 576034 |
| ordinary（stereo） | 576017 | 4.493500 | 27476 | 458852 |
| shortPrefix（stereo） | 576017 | 0.993500 | 27476 | 122852 |
| overFive（stereo） | 576017 | 5.993500 | 27476 | 602852 |

准备阶段的 wall 与 CPU 单位均为 ms/op；“热操作主 score”还包含 prepared 后补至高水位及清理。

| media | 冷 prepared wall / CPU | 热 prepared wall / CPU | 热操作主 score |
|---|---:|---:|---:|
| single | 3.335 / 3.295 | 0.001 / 0.001 | 0.002 |
| ordinary | 4.502 / 4.324 | 0.117 / 0.081 | 4.469 |
| shortPrefix | 6.454 / 6.358 | 5.136 / 4.938 | 6.449 |
| overFive | 5.930 / 5.830 | 0.062 / 0.061 | 0.065 |

`ordinary` 的 prepared 可直接由缓存满足，但补足至 5 秒水位时会恢复一次 decoder，因此整个热准备操作记录 1 次 restore；`shortPrefix` 同样记录 1 次 restore，`single` 与 `overFive` 为 0 次。所有热路径均无普通 create，所有冷路径每次操作均记录 1 次普通 create。

下表的剩余尾部耗时覆盖**全部剩余尾部**，而不是单次 8192 bytes 请求。`firstGap` 包含恢复 decoder 以及整张输入页产出 PCM 的开销，因此恢复推迟后依然存在毫秒级的首次供给成本。

| media | cachedOnly µs | firstGap ms | 剩余尾部 ms / 请求数 / bytes | 下一轮首个请求 µs |
|---|---:|---:|---:|---:|
| ordinary | 5.637 | 4.383 | 3.499 / 89 / 720656 | 32.278 |
| shortPrefix | 5.755 | 1.259 | 13.512 / 130 / 1056656 | 55.066 |
| overFive | 5.631 | 5.810 | 0.608 / 72 / 576656 | 23.986 |

三个多 chunk 媒体的 `firstGap`、`tail` 和 `loop` 整操作均记录 1 次 restore。其中 `loop` 的那次恢复来自上一轮的前置消费，下一轮首个请求本身只消费缓存，不能凭整操作计数误判它再次触发了恢复。`single` 的 `cachedOnly` 与下一轮首个请求耗时分别为 5.044 µs 和 5.574 µs，全程无 create 或 restore。极短阶段的线程 CPU 计数受计时粒度影响，零值不代表零 CPU 成本。

完整单轮包含从激活到 EOF 的供给，主 score 另含清理。下表各列按“冷 → 热”排列；CPU 为激活至 EOF 的阶段值，heap 为整操作分配值，最后一列仍是快照观察值。

| media | 主 score ms/op | 阶段 CPU ms/op | heap KiB/op | 已观测 decoded bytes |
|---|---:|---:|---:|---:|
| single | 4.133 → 0.314 | 3.978 → 0.287 | 1721.7 → 595.1 | 581632 → 576034 |
| ordinary | 14.896 → 9.675 | 14.291 → 9.440 | 2744.5 → 2309.9 | 434176 → 467044 |
| shortPrefix | 18.535 → 16.767 | 17.905 → 16.008 | 2534.4 → 2434.1 | 98304 → 131044 |
| overFive | 12.409 → 6.476 | 12.284 → 6.330 | 2885.5 → 2320.6 | 581632 → 611044 |

这些样本支持同时保留 Complete 与 Prefix 两条路径：`single`、`ordinary` 与 `overFive` 的整轮墙钟耗时与 CPU 均明显下降，说明缓存并非仅仅把 prepared 阶段的工作推迟到后面。相应的代价包括共享 entry、checkpoint 存储以及每次供给的数据复制，Prefix 跨分段读取时还会产生一次临时拼接；当前结果不需要额外引入预热或调度机制。

`shortPrefix` 的整轮主 score 误差较大：冷路径为 18.535 ± 1.504 ms，热路径为 16.767 ± 5.365 ms（JMH 默认置信界），尚不能确认净提速。同时，checkpoint 本身约占其前缀 PCM 体积的 29%，短前缀的保留成本不可忽视。上述有限采样不覆盖多声音竞争、长期内存驻留、异步排队或任意编码参数。

完整的 JSON、日志、输入摘要与计量元数据保存在生成报告目录下的 `audio-prefix-{multi,single,cycle}.json`、对应 `.log`、`audio-prefix-summary.json` 以及 `audio-prefix-metadata.json`。

## 宿主边界

受控宿主回归覆盖了提交、失败、停止与迟到结果，但不覆盖真实 Forge 与 OpenAL 下的可听延迟、设备容量、音量、世界内 once-only 触发或长期物理回收。驻留 JMH 中的 `prepared` 只表示解码储备已满足阈值，不能等同于音频立即可听。
