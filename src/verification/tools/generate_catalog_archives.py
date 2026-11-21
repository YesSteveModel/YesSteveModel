"""Regenerate solid archive fixtures from builtin 1_alex; requires py7zr==1.1.4."""

from pathlib import Path

import py7zr


def main():
    project = Path(__file__).resolve().parents[3]
    source = project / "src/main/resources/assets/ysm/builtin/misc/1_alex"
    target = project / "src/verification/test/resources/catalog-archives"
    target.mkdir(parents=True, exist_ok=True)
    for crc in (True, False):
        with py7zr.SevenZipFile(
            target / ("solid.7z" if crc else "solid-no-crc.7z"), "w",
            filters=[{"id": py7zr.FILTER_LZMA2, "preset": 1}],
        ) as archive:
            for path in sorted(source.rglob("*")):
                if path.is_file():
                    archive.write(path, "root/" + path.relative_to(source).as_posix())
            if not crc:
                write_header = archive._write_header

                def without_crc(archive=archive, write_header=write_header):
                    streams = archive.header.main_streams.substreamsinfo
                    streams.digestsdefined = [False] * len(streams.digestsdefined)
                    return write_header()

                archive._write_header = without_crc


if __name__ == "__main__":
    main()
