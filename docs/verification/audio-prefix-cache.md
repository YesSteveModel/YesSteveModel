# 音频 PCM 缓存验证

本页记录默认音频后端的 L1 正确性回归与 L2 驻留性能基准入口。缓存与播放设计见[PCM 保留与工作集](../architecture/model-management/audio/overview.md#pcm-保留与工作集)和[播放终态](../architecture/model-management/audio/overview.md#播放终态)。

## L1 正确性回归

测试依赖与当前源码配对的 native DLL 和 `ysm-test`，需先完成 native 构建，再依次运行 Opus CTest 与 Java 回归：

```powershell
ctest --test-dir <native-build> --output-on-failure -R Opus
$env:YSM_NATIVE_PATH='<path/to/ysm.dll>'
.\gradlew.bat test --tests 'com.elfmcys.ysm.model.resource.client.audio.*' --tests 'com.elfmcys.ysm.natives.NativeObjectAdoptionIntegrationTest'
```

| 套件 | 覆盖范围 |
|---|---|
| Native `OpusCodecTest` | 声道/gain/pre-skip/跨页分包续接、checkpoint 独立恢复及非法状态拒收 |
| `NativeObjectAdoptionIntegrationTest` | 原始 handle 采用失败回收与 checkpoint 重复恢复 |
| `PlaybackAudioStreamTest` | 单/多 chunk 分类、恢复触发时机、水位独立、跨轮/循环一致及预算归还 |
| `AudioRetentionCacheTest` | 分段范围读、只读视图、checkpoint 计价、TTL/LRU 与活动强引用 |
| `ClientAudioRuntimeIntegrationTest` | 授权、发布/提交/停止原子边界、JNI restore 中途取消与尾部失败隔离 |
| `SoundResourceAccessIntegrationTest` | 驻留首块 token、顺序尾块、延迟完成、取消与循环重启 |

- **测试夹具**：音频样本与生成参数维护在测试资源的 `audio-playback/manifest.json`（覆盖单 chunk 与不同首块时长的多 chunk 样本）；测试与 benchmark 共用 `src/verification/shared/java` 夹具，不另存媒体副本。

## L2 驻留性能基准

`AudioPrefixBenchmark.consume` 复用[持续性能基建](../tools/benchmark/README.md)，按 `media` 与 `phase` 组合测量冷/热准备、分段消费、恢复与整轮周期：

```powershell
.\gradlew.bat benchmark '-PbenchmarkInclude=.*AudioPrefixBenchmark.consume' '-PbenchmarkArgs=-p media=ordinary,shortPrefix,overFive -p phase=cachedOnly,coldPrepared,hotPrepared,firstGap,tail,loop -p requestedBytes=8192 -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/audio-prefix-multi.json'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*AudioPrefixBenchmark.consume' '-PbenchmarkArgs=-p media=single -p phase=cachedOnly,coldPrepared,hotPrepared,loop -p requestedBytes=8192 -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/audio-prefix-single.json'
.\gradlew.bat benchmark '-PbenchmarkInclude=.*AudioPrefixBenchmark.consume' '-PbenchmarkArgs=-p media=single,ordinary,shortPrefix,overFive -p phase=coldCycle,hotCycle -p requestedBytes=8192 -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc' '-PbenchmarkResult=build/reports/benchmark/audio-prefix-cycle.json'
```

- **参数说明**：`media` 取 `single`（单 chunk）或 `ordinary` / `shortPrefix` / `overFive`（多 chunk，`single` 不跑 `firstGap` 与 `tail`）；`phase` 覆盖 `cachedOnly`、`coldPrepared`/`hotPrepared`、`firstGap`、`tail`、`loop`、`coldCycle`/`hotCycle`；`requestedBytes` 默认 8192 bytes。
- **产物位置**：JMH 结果与汇总元数据输出在 `build/reports/benchmark/audio-prefix-*.json`（连同 `.log`、summary 与 metadata）。

## 验证边界

L1 与 L2 使用内存常驻输入、同步/受控 worker 与 headless 宿主，不覆盖真实 Forge 与 OpenAL 下的可听延迟、设备容量、网络排队或长期物理回收；基准中的 `prepared` 仅表示解码储备达到水位，`observedDecodedPeakBytes` 为快照观察值而非精确 RSS。
