"""Build independent contract audio inputs and reference PCM, without YSM code."""

import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import wave


ROOT = Path(__file__).resolve().parents[2]


def run(args):
    return subprocess.run(args, check=True, capture_output=True).stdout


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ogg_pages(data):
    offset = 0
    while offset < len(data):
        assert data[offset:offset + 4] == b"OggS"
        count = data[offset + 26]
        end = offset + 27 + count + sum(data[offset + 27:offset + 27 + count])
        assert end <= len(data)
        yield offset, end, offset + 27 + count
        offset = end
    assert offset == len(data)


def crc32_ogg(data):
    crc = 0
    for value in data:
        crc ^= value << 24
        for _ in range(8):
            crc = ((crc << 1) ^ (0x04C11DB7 if crc & 0x80000000 else 0)) & 0xFFFFFFFF
    return crc


def patch_opus(data, input_rate=None, gain=None, origin=0):
    data = bytearray(data)
    for start, end, payload in ogg_pages(data):
        if data[payload:payload + 8] == b"OpusHead":
            if input_rate is not None:
                struct.pack_into("<I", data, payload + 12, input_rate)
            if gain is not None:
                struct.pack_into("<h", data, payload + 16, gain)
        elif origin:
            granule = struct.unpack_from("<q", data, start + 6)[0]
            if granule > 0:
                struct.pack_into("<q", data, start + 6, granule + origin)
        data[start + 22:start + 26] = b"\0" * 4
        struct.pack_into("<I", data, start + 22, crc32_ogg(data[start:end]))
    return bytes(data)


def write_source(path, rate, channels, frames):
    samples = array.array("h")
    for frame in range(frames):
        t = frame / rate
        # Distinct channels and time segments expose cursor, mixing, and loop errors.
        segment = frame // rate
        envelope = min(1.0, (frame + 1) / 64, (frames - frame) / 64)
        for channel in range(channels):
            frequency = (317 if channel == 0 else 733) + segment * (31 + channel * 17)
            value = 0.28 * math.sin(2 * math.pi * frequency * t)
            value += 0.07 * math.sin(2 * math.pi * (1171 + channel * 97) * t)
            samples.append(round(32767 * envelope * value))
    if sys.byteorder != "little":
        samples.byteswap()
    with wave.open(str(path), "wb") as output:
        output.setnchannels(channels)
        output.setsampwidth(2)
        output.setframerate(rate)
        output.writeframes(samples.tobytes())


def reference(ffmpeg, media, codec, channels, temporary):
    raw = temporary / "decoded.f32le"
    run([ffmpeg, "-v", "error", "-nostdin", "-y", "-c:a", "lib" + codec,
         "-i", str(media), "-map", "0:a:0", "-c:a", "pcm_f32le", "-f", "f32le", str(raw)])
    values = array.array("f")
    values.frombytes(raw.read_bytes())
    if sys.byteorder != "little":
        values.byteswap()
    assert len(values) % channels == 0
    output = array.array("h")
    for offset in range(0, len(values), channels):
        mono = sum(values[offset:offset + channels]) / channels
        assert math.isfinite(mono)
        output.append(max(-32768, min(32767, round(mono * 32768))))
    if sys.byteorder != "little":
        output.byteswap()
    return output.tobytes()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    parser.add_argument("--ffprobe", default=shutil.which("ffprobe"))
    parser.add_argument("--output", type=Path, default=ROOT / "src/verification/test/resources/audio-fixtures")
    args = parser.parse_args()
    if not args.ffmpeg or not args.ffprobe:
        parser.error("ffmpeg and ffprobe are required")
    output = args.output.resolve()
    if not output.is_relative_to(ROOT):
        parser.error("output must stay inside the project")
    output.mkdir(parents=True, exist_ok=True)
    cases = []
    for codec, rate in (("vorbis", 44100), ("opus", 48000)):
        for suffix, frames in (("under", 4 * rate - 1), ("exact", 4 * rate),
                               ("over", 4 * rate + 1), ("near-five", rate * 49 // 10)):
            cases.append((f"{codec}-{suffix}", codec, rate, 2, frames, {}))
        cases.append((f"{codec}-long", codec, rate, 2, 12 * rate + 17, {}))
    cases.extend([
        ("vorbis-mono", "vorbis", 44100, 1, 99225, {}),
        ("opus-input-rate", "opus", 48000, 1, 60001, {"input_rate": 44100}),
        ("opus-gain", "opus", 48000, 2, 60001, {"gain": 1536}),
        ("opus-origin", "opus", 48000, 2, 60001, {"origin": 9600}),
    ])
    manifest = {
        "contract_revision": 1,
        "reference_tool": run([args.ffmpeg, "-version"]).decode().splitlines()[0],
        "reference_tool_sha256": digest(Path(args.ffmpeg)),
        "reference_decoders": ["libvorbis", "libopus"],
        "reference_pcm": "signed s16le mono; arithmetic mean; nearest-even saturated quantization",
        "cases": [],
    }
    with tempfile.TemporaryDirectory(prefix="fixture-work-", dir=ROOT) as directory:
        temporary = Path(directory)
        for name, codec, rate, channels, frames, patch in cases:
            source = temporary / "source.wav"
            write_source(source, rate, channels, frames)
            media = output / (name + ".ogg")
            options = ["-q:a", "5"] if codec == "vorbis" else ["-b:a", "96000", "-vbr", "off"]
            run([args.ffmpeg, "-v", "error", "-nostdin", "-y", "-i", str(source),
                 "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
                 "-c:a", "lib" + codec, *options, str(media)])
            if patch:
                media.write_bytes(patch_opus(media.read_bytes(), **patch))
            pcm = reference(args.ffmpeg, media, codec, channels, temporary)
            actual_frames = len(pcm) // 2
            if actual_frames != frames:
                raise RuntimeError(f"{name}: expected {frames} frames, reference returned {actual_frames}")
            metadata = json.loads(run([args.ffprobe, "-v", "error", "-select_streams", "a:0",
                                       "-show_entries", "stream=codec_name,sample_rate,channels",
                                       "-of", "json", str(media)]))["streams"][0]
            assert metadata["codec_name"] == codec
            assert int(metadata["sample_rate"]) == rate and metadata["channels"] == channels
            pages = list(ogg_pages(media.read_bytes()))
            encoded = media.read_bytes()
            pre_skip = struct.unpack_from("<H", encoded, pages[0][2] + 10)[0] if codec == "opus" else 0
            manifest["cases"].append({
                "id": name, "encoded": media.name,
                "codec": codec, "source_channels": channels, "sample_rate": rate,
                "frames": actual_frames, "pcm_bytes": len(pcm),
                "short_eligible": actual_frames < 4 * rate,
                "pre_skip": pre_skip, "patch": patch,
                "last_granule": struct.unpack_from("<q", encoded, pages[-1][0] + 6)[0],
                "encoded_bytes": len(encoded), "encoded_sha256": digest(media),
                "reference_sha256": hashlib.sha256(pcm).hexdigest(), "source_sha256": digest(source),
            })
            print(f"{name}: {actual_frames} frames, {rate} Hz, short={actual_frames < 4 * rate}", flush=True)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
