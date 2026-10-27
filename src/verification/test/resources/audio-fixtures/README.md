# Audio fixtures

`manifest.json` 记录 14 个合成 Vorbis/Opus 输入、编码 SHA-256 和独立解码的 frame 数。Java 测试只使用编码文件；PCM hash 记录生成器的 FFmpeg 参照解码结果。

在项目根目录执行 `python docs/tools/generate_audio_fixtures.py --ffmpeg <ffmpeg> --ffprobe <ffprobe>` 可重新生成。运行测试无需 FFmpeg。`YSM_AUDIO_FIXTURE_DIR` 可覆盖默认 fixture 目录。
