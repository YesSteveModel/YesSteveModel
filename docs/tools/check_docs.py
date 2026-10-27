#!/usr/bin/env python3
"""Check the standalone Markdown tree using only the Python standard library."""

from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from collections import defaultdict, deque
from pathlib import Path
from urllib.parse import unquote, urlsplit

from check_complexity_relationships import run as check_complexity_relationships


REQUIRED_DIRECTORIES = {
    "governance",
    "standards/model-schema",
    "standards/protocol-v1",
    "architecture",
    "verification",
    "future",
    "status/known-issues",
    "tools",
}
REQUIRED_FILES = {
    "README.md",
    "architecture/README.md",
    "governance/architecture-docs.md",
    "verification/model-management.md",
    "verification/molang-declarations.md",
    "migration-overview.md",
    "glossary.md",
    "governance/documentation-policy.md",
    "status/support-and-verification.md",
    "status/known-issues/format-and-schema.md",
    "status/known-issues/network.md",
    "status/known-issues/model-management.md",
    "status/known-issues/animation.md",
    "status/known-issues/rendering.md",
}
LINK_RE = re.compile(r"!?\[[^\]]+\]\(\s*(?P<target><[^>]+>|[^\s)]+)")
HEADING_RE = re.compile(r"^#{1,6}\s+(.+?)\s*#*\s*$", re.MULTILINE)
LOCAL_PATH_RE = re.compile(
    r"(?ix)(?:^|[\s`(\[])"
    r"(?:[a-z]:[\\/]|\\\\(?:\?\\)?[^\\/\s]+[\\/]|"
    r"/(?:Users|home)/[^/\s]+(?:/|$)|/mnt/[a-z](?:/|$)|~[\\/]|"
    r"%(?:USERPROFILE|HOMEPATH)%[\\/]|\$HOME[\\/])"
)
LOCAL_DIRECTORY_ALIAS_RE = re.compile(
    r"(?i)(?<![\w-])(?:ysm-ng|ysm-native|YesSteveModel)(?![\w-])"
)
LOCAL_METADATA_RE = re.compile(
    r"(?im)^\s*(?:[-*]\s*)?(?:文档编写时间|文档更新时间|最后更新|提交时间|"
    r"核查时间|验证时间|commit\s*(?:time|时间))\s*[:：]"
    r"|^\s*(?:[-*]\s*)?(?:created|updated|last\s+updated|authored)\s*[:：]"
    r"\s*20\d{2}[-/]\d{1,2}[-/]\d{1,2}"
    r"|^\s*(?:[-*]\s*)?(?:编写于|更新于|核查于|验证于)\s*20\d{2}[-/]\d{1,2}[-/]\d{1,2}"
)
VCS_METADATA_RE = re.compile(
    r"(?i)(?:\bcommit\s*(?:id|hash|sha)?\s*[:=]?\s*[0-9a-f]{7,40}\b|"
    r"提交\s*(?:哈希|版本)?\s*[:：]?\s*[0-9a-f]{7,40}\b)"
)
CORRECTION_HISTORY_RE = re.compile(
    r"(?i)(?:原始\s*(?:prompt|提示词|要求|文本|说法)|prompt\s*原文|"
    r"修正前(?:的)?(?:prompt|提示词|要求|文本|说法)|(?:修正|更正|纠正)原因|"
    r"用户(?:修正|更正|纠正))"
)
REPOSITORY_CONTEXT_RE = re.compile(
    r"(?i)(?:\b(?:checkout|worktree)\b|本地仓库|仓库中存在|仓库内源码)"
)


def markdown_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*.md") if ".git" not in path.parts)


def mask_code(text: str) -> str:
    text = re.sub(r"```.*?```|~~~.*?~~~", "", text, flags=re.DOTALL)
    return re.sub(r"`[^`\n]*`", "", text)


def slug(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).strip().lower()
    value = re.sub(r"<[^>]+>", "", value)
    value = re.sub(r"[^\w\-\u4e00-\u9fff ]", "", value)
    return re.sub(r"[-\s]+", "-", value).strip("-")


def headings(path: Path) -> set[str]:
    return {slug(match.group(1)) for match in HEADING_RE.finditer(mask_code(path.read_text("utf-8")))}


OVERVIEW_SECTIONS = (
    "职责与边界", "运行作用域与所有权", "数据流与交接",
    "生命周期与失败", "关键契约与设计取舍", "实现与验证入口",
)
PROJECTION_DIRECTORIES = {"flows", "views"}


def local_links(path: Path, text: str) -> list[tuple[Path, str]]:
    links = []
    for match in LINK_RE.finditer(mask_code(text)):
        parsed = urlsplit(match.group("target").strip("<>"))
        if not parsed.scheme and not parsed.netloc:
            target = (path.parent / unquote(parsed.path)).resolve() if parsed.path else path.resolve()
            links.append((target, unquote(parsed.fragment)))
    return links


def check_architecture(root: Path) -> list[str]:
    """Check explicit structure and evidence links, not architectural meaning."""
    root = root.resolve()
    architecture = root / "architecture"
    errors = []
    if not architecture.is_dir():
        return errors
    for retired in ("design", "implementation", "maintenance.md", "overview.md", "runtime-model.md"):
        if (architecture / retired).exists():
            errors.append(f"architecture/{retired}: retired architecture entry is present")
    for directory in sorted(path for path in architecture.rglob("*") if path.is_dir()):
        relative = directory.relative_to(architecture)
        if "design" in relative.parts:
            errors.append(f"{directory.relative_to(root)}: design topics must be siblings of overview.md")
        if "implementation" in relative.parts or relative.parts[0] in PROJECTION_DIRECTORIES:
            continue
        if not (directory / "overview.md").is_file():
            errors.append(f"{directory.relative_to(root)}: subsystem overview.md is missing")
    for path in markdown_files(architecture):
        relative = path.relative_to(architecture)
        text = path.read_text("utf-8")
        links = local_links(path, text)
        if path.name == "README.md" and path != architecture / "README.md":
            errors.append(f"{path.relative_to(root)}: only the system entry uses README.md")
        if "implementation" in relative.parts:
            if path.name == "overview.md":
                errors.append(f"{path.relative_to(root)}: implementation overview is forbidden")
            intro = text.split("\n## ", 1)[0]
            if not any(fragment and target.is_relative_to(architecture)
                       and "implementation" not in target.relative_to(architecture).parts
                       and target.suffix == ".md" and target.is_file()
                       and target.relative_to(architecture).parts[0] not in PROJECTION_DIRECTORIES
                       and slug(fragment) in headings(target)
                       for target, fragment in local_links(path, intro)):
                errors.append(f"{path.relative_to(root)}: implementation needs an introductory design-section link")
        elif relative.parts[0] in PROJECTION_DIRECTORIES:
            if path.name == "overview.md":
                errors.append(f"{path.relative_to(root)}: projection overview is forbidden")
            if not any(fragment and target.is_relative_to(architecture)
                       and "implementation" not in target.relative_to(architecture).parts
                       and target.suffix == ".md" and target.is_file()
                       and target.relative_to(architecture).parts[0] not in PROJECTION_DIRECTORIES
                       and slug(fragment) in headings(target)
                       for target, fragment in links):
                kind = "view" if relative.parts[0] == "views" else "flow"
                errors.append(f"{path.relative_to(root)}: {kind} needs a design-section link")
        elif path.name == "overview.md":
            sections = re.findall(r"^## (.+)$", text, re.MULTILINE)
            if sections != list(OVERVIEW_SECTIONS):
                errors.append(f"{path.relative_to(root)}: overview needs the six ordered core sections")
            parent = path.parent.parent
            parent_entry = parent / ("README.md" if parent == architecture else "overview.md")
            if not any(target == parent_entry and fragment and target.is_file()
                       and slug(fragment) in headings(target) for target, fragment in links):
                errors.append(f"{path.relative_to(root)}: overview needs a parent-contract section link")
    return errors


def run(root: Path) -> list[str]:
    root = root.resolve()
    errors: list[str] = []
    for relative in REQUIRED_DIRECTORIES:
        if not (root / relative).is_dir():
            errors.append(f"{relative}: required directory is missing")
    for relative in REQUIRED_FILES:
        if not (root / relative).is_file():
            errors.append(f"{relative}: required file is missing")
        if relative.startswith("architecture/") and relative.endswith("/overview.md"):
            retired = Path(relative).with_name("README.md")
            if (root / retired).exists():
                errors.append(f"{retired.as_posix()}: overview must be named overview.md")

    errors.extend(check_architecture(root))
    retired_directories = [root / "concepts"]
    for directory in retired_directories:
        if directory.exists():
            errors.append(f"{directory.relative_to(root)}: retired directory is present")
    if (root / "architecture/runtime-model.md").exists():
        errors.append("architecture/runtime-model.md: retired page is present")

    files = markdown_files(root)
    for path in files:
        if path.is_relative_to(root / "architecture") and path.name == "design-rationale.md":
            errors.append(f"{path.relative_to(root)}: rationale belongs with its design")
    entry = (root / "README.md").resolve()
    graph: dict[Path, set[Path]] = defaultdict(set)
    for path in files:
        text = path.read_text("utf-8")
        visible = mask_code(text)
        if LOCAL_PATH_RE.search(text) or "file://" in text.lower():
            errors.append(f"{path.relative_to(root)}: absolute local path is forbidden")
        if LOCAL_DIRECTORY_ALIAS_RE.search(text):
            errors.append(f"{path.relative_to(root)}: local project directory alias is forbidden")
        if LOCAL_METADATA_RE.search(text) or VCS_METADATA_RE.search(text):
            errors.append(f"{path.relative_to(root)}: local revision or timestamp metadata is forbidden")
        if CORRECTION_HISTORY_RE.search(text):
            errors.append(f"{path.relative_to(root)}: prompt correction history is forbidden")
        if REPOSITORY_CONTEXT_RE.search(text):
            errors.append(f"{path.relative_to(root)}: local repository context is forbidden")

        for match in LINK_RE.finditer(visible):
            raw = match.group("target").strip("<>")
            parsed = urlsplit(raw)
            if parsed.scheme in {"http", "https", "mailto"}:
                continue
            if parsed.scheme or raw.startswith(("/", "//")):
                errors.append(f"{path.relative_to(root)}: invalid link {raw}")
                continue
            destination = (path.parent / unquote(parsed.path)).resolve() if parsed.path else path.resolve()
            if destination.is_dir():
                destination /= "README.md"
            try:
                destination.relative_to(root)
            except ValueError:
                errors.append(f"{path.relative_to(root)}: link escapes repository: {raw}")
                continue
            if not destination.exists():
                errors.append(f"{path.relative_to(root)}: broken link {raw}")
                continue
            if destination.suffix.lower() == ".md":
                graph[path.resolve()].add(destination)
                if parsed.fragment and slug(parsed.fragment) not in headings(destination):
                    errors.append(f"{path.relative_to(root)}: broken anchor {raw}")
                elif parsed.fragment:
                    matches = [slug(item.group(1)) for item in HEADING_RE.finditer(
                        mask_code(destination.read_text("utf-8")))]
                    if matches.count(slug(parsed.fragment)) > 1:
                        errors.append(f"{path.relative_to(root)}: ambiguous anchor {raw}")

    reachable = {entry}
    queue = deque([entry])
    while queue:
        for target in graph.get(queue.popleft(), set()):
            if target not in reachable:
                reachable.add(target)
                queue.append(target)
    for path in files:
        if path.resolve() not in reachable:
            errors.append(f"{path.relative_to(root)}: orphan Markdown page")
    return sorted(set(errors))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    root = parser.parse_args().root
    errors = run(root)
    errors.extend(f"complexity-map: {error}" for error in check_complexity_relationships(root))
    errors = sorted(set(errors))
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        print(f"FAILED: {len(errors)} documentation error(s)")
        return 1
    print("OK: documentation tree is self-contained")
    return 0


if __name__ == "__main__":
    sys.exit(main())
