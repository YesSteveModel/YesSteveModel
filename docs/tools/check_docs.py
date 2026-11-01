#!/usr/bin/env python3
"""Check the Markdown tree and Java documentation references; Git enumerates source files."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import unicodedata
from collections import defaultdict, deque
from pathlib import Path
from urllib.parse import unquote, urlsplit

from check_complexity_relationships import run as check_complexity_relationships


REQUIRED_DIRECTORIES = {
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
    "architecture/documentation-rules.md",
    "verification/model-management.md",
    "verification/molang-declarations.md",
    "migration-overview.md",
    "glossary.md",
    "verification/policy.md",
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


JAVA_COMMENT_OR_LITERAL_RE = re.compile(
    r'//[^\r\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
)
HTML_LINK_RE = re.compile(r'<a\s+href=["\']([^"\']+)["\']', re.IGNORECASE)


def check_source_doc_links(project: Path, files: list[Path] | None = None) -> list[str]:
    project = project.resolve()
    if files is None:
        if not (project / "src").is_dir():
            return []
        try:
            inventory = subprocess.check_output(
                ["git", "-C", str(project), "ls-files", "-z", "--cached", "--others",
                 "--exclude-standard", "--", "src"], encoding="utf-8", stderr=subprocess.PIPE,
            )
        except (OSError, subprocess.CalledProcessError) as failure:
            return [f"source documentation: cannot enumerate source files with Git: {failure}"]
        files = [project / name for name in dict.fromkeys(inventory.split("\0")) if name.endswith(".java")]
    errors = []
    for path in files:
        if not path.is_file():
            continue
        source = path.read_text("utf-8")
        for token in JAVA_COMMENT_OR_LITERAL_RE.finditer(source):
            if not token.group().startswith(("//", "/*")):
                continue
            comment = mask_code(re.sub(r"(?m)^\s*\* ?", "", token.group()))
            references = [(match.group("target").strip("<>"), False)
                          for match in LINK_RE.finditer(comment)]
            references += [(match.group(1), True) for match in HTML_LINK_RE.finditer(comment)]
            for raw, html in references:
                parsed = urlsplit(raw)
                if parsed.scheme in {"http", "https", "mailto"}:
                    continue
                relative = unquote(parsed.path)
                if not relative.endswith(".md") and "docs/" not in relative:
                    continue
                location = f"{path.relative_to(project)}:{source.count(chr(10), 0, token.start()) + 1}"
                if html or not relative.startswith("docs/") or "\\" in relative or any(
                        part in {".", "..", ""} for part in relative.split("/")) or parsed.query:
                    errors.append(f"{location}: use a project-root Markdown documentation link: {raw}")
                    continue
                target = (project / relative).resolve()
                if not target.is_relative_to(project / "docs") or not target.is_file():
                    errors.append(f"{location}: broken source documentation link: {raw}")
                    continue
                if parsed.fragment:
                    anchors = [slug(match.group(1)) for match in HEADING_RE.finditer(
                        mask_code(target.read_text("utf-8")))]
                    count = anchors.count(slug(unquote(parsed.fragment)))
                    if count != 1:
                        errors.append(f"{location}: missing or ambiguous source documentation anchor: {raw}")
    return errors


OVERVIEW_SECTIONS = (
    "职责与边界", "Seam Contract", "设计取舍", "实现与验证入口",
)
CONTRACT_FIELDS = (
    "边界与权威", "前置条件与输入", "结果与保证",
    "所有权与时序", "失败与拒收", "证据与依赖",
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


def sections_at(text: str, level: int) -> list[tuple[str, str]]:
    """Return same-level sections without interpreting headings inside fences."""
    visible = re.sub(r"```.*?```|~~~.*?~~~", "", text, flags=re.DOTALL)
    matches = list(re.finditer(r"^" + "#" * level + r" (.+)$", visible, re.MULTILINE))
    return [(match.group(1), visible[match.end():matches[index + 1].start()
                                   if index + 1 < len(matches) else len(visible)])
            for index, match in enumerate(matches)]


def is_contract_page(path: Path) -> bool:
    return path.name == "seam-contract.md" or path.name.endswith("-seam-contract.md")


def contract_sections(text: str, level: int) -> list[tuple[str, str]]:
    """Read fields from headings or standalone bold labels, ignoring fenced examples."""
    visible = re.sub(r"```.*?```|~~~.*?~~~", "", text, flags=re.DOTALL)
    labels = list(re.finditer(r"^\*\*([^*\n]+)\*\*\s*$", visible, re.MULTILINE))
    if labels:
        return [(match.group(1), visible[match.end():labels[index + 1].start()
                                        if index + 1 < len(labels) else len(visible)])
                for index, match in enumerate(labels)]
    return sections_at(visible, level)


def check_contract_fields(path: Path, text: str, level: int, root: Path) -> list[str]:
    sections = contract_sections(text, level)
    errors = []
    if [title for title, _ in sections] != list(CONTRACT_FIELDS):
        errors.append(f"{path.relative_to(root)}: contract needs six ordered fields")
    for title, body in sections:
        if title in CONTRACT_FIELDS and not HEADING_RE.sub("", body).strip():
            errors.append(f"{path.relative_to(root)}: contract field {title} is empty")
    return errors


def contract_reference(path: Path, body: str) -> tuple[Path, str] | None:
    """A delegation block names one authority; links inside contract fields are dependencies."""
    if sections_at(body, 3) or re.search(r"^\*\*", body, re.MULTILINE):
        return None
    links = local_links(path, body)
    return links[0] if len(links) == 1 else None


def declared_contract_targets(path: Path, text: str) -> list[tuple[Path, str]]:
    targets = []
    for title, body in sections_at(text, 2):
        if title != "Seam Contract":
            continue
        items = sections_at(body, 3)
        if not items:
            reference = contract_reference(path, body)
            if reference:
                targets.append(reference)
        elif not any(name in CONTRACT_FIELDS for name, _ in items):
            for name, item in items:
                if name != "共同规则":
                    reference = contract_reference(path, item)
                    if reference:
                        targets.append(reference)
    return targets


def referenced_contract(target: Path, fragment: str) -> tuple[str, int] | None:
    if not target.is_file() or target.suffix != ".md":
        return None
    text = re.sub(r"```.*?```|~~~.*?~~~", "", target.read_text("utf-8"), flags=re.DOTALL)
    if not fragment:
        return text, 2
    matches = list(re.finditer(r"^(#{1,6})\s+(.+?)\s*$", text, re.MULTILINE))
    found = [index for index, match in enumerate(matches) if slug(match.group(2)) == slug(fragment)]
    if len(found) != 1:
        return None
    index = found[0]
    heading = matches[index]
    level = len(heading.group(1))
    end = next((match.start() for match in matches[index + 1:]
                if len(match.group(1)) <= level), len(text))
    return text[heading.end():end], level + 1


def check_contract_reference(path: Path, reference: tuple[Path, str] | None, root: Path) -> list[str]:
    if reference is None:
        return [f"{path.relative_to(root)}: contract entry needs fields or one authority link"]
    target, fragment = reference
    architecture = root / "architecture"
    if (target == path or not target.is_relative_to(architecture)
            or "implementation" in target.relative_to(architecture).parts
            or target.relative_to(architecture).parts[0] in PROJECTION_DIRECTORIES):
        return [f"{path.relative_to(root)}: contract authority must be a distinct architecture design section"]
    section = referenced_contract(target, fragment)
    if section is None:
        return [f"{path.relative_to(root)}: contract authority section is missing or ambiguous"]
    text, level = section
    return check_contract_fields(target, text, level, root)


def check_subsystem_contract(path: Path, text: str, root: Path) -> list[str]:
    sections = sections_at(text, 2)
    errors = []
    if [title for title, _ in sections] != list(OVERVIEW_SECTIONS):
        errors.append(f"{path.relative_to(root)}: subsystem needs four ordered core sections")
    contracts = [body for title, body in sections if title == "Seam Contract"]
    if len(contracts) != 1:
        errors.append(f"{path.relative_to(root)}: subsystem needs one Seam Contract entry")
        return errors
    body = contracts[0]
    items = sections_at(body, 3)
    if not items:
        return errors + check_contract_reference(path, contract_reference(path, body), root)
    introduction = body.split("\n### ", 1)[0]
    if any(title in CONTRACT_FIELDS for title, _ in items):
        errors.extend(check_contract_fields(path, body, 3, root))
        if any(is_contract_page(target) and target.parent == path.parent
               for target, _ in local_links(path, introduction)):
            errors.append(f"{path.relative_to(root)}: inline and separate contract authorities cannot coexist")
        return errors
    names = [title for title, _ in items]
    if len(names) != len(set(names)) or names == ["共同规则"]:
        errors.append(f"{path.relative_to(root)}: named contracts must be unique and include a seam")
    navigation = {(target, slug(fragment)) for target, fragment in local_links(path, introduction)}
    for title, item in items:
        if title == "共同规则":
            if not re.match(r"\s*适用范围：\S", item):
                errors.append(f"{path.relative_to(root)}: common rules need an explicit scope")
            continue
        if (path, slug(title)) not in navigation:
            errors.append(f"{path.relative_to(root)}: named contract {title} needs an entry navigation link")
        if re.search(r"^\*\*", item, re.MULTILINE):
            errors.extend(check_contract_fields(path, item, 4, root))
        else:
            errors.extend(check_contract_reference(path, contract_reference(path, item), root))
    return errors


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
        elif is_contract_page(path):
            errors.extend(check_contract_fields(path, text, 2, root))
        elif path != architecture / "README.md" and (
                path.name == "overview.md" or any(title == "Seam Contract"
                                                 for title, _ in sections_at(text, 2))
                or re.search(r"^上级契约：", mask_code(text), re.MULTILINE)):
            errors.extend(check_subsystem_contract(path, text, root))
            parent_lines = re.findall(r"^上级契约：(.+)$", mask_code(text), re.MULTILINE)
            parent_links = local_links(path, parent_lines[0]) if len(parent_lines) == 1 else []
            if len(parent_links) != 1:
                errors.append(f"{path.relative_to(root)}: subsystem needs one explicit parent-contract section link")
            else:
                target, fragment = parent_links[0]
                valid_parent = (target != path and target.is_relative_to(architecture)
                                and target.is_file() and target.suffix == ".md"
                                and "implementation" not in target.relative_to(architecture).parts
                                and target.relative_to(architecture).parts[0] not in PROJECTION_DIRECTORIES)
                if valid_parent:
                    valid_parent = (target == architecture / "README.md"
                                    or any(title == "Seam Contract" for title, _ in sections_at(target.read_text("utf-8"), 2)))
                if not valid_parent or not fragment or slug(fragment) not in headings(target):
                    errors.append(f"{path.relative_to(root)}: invalid parent-contract section link")
    authorities: dict[tuple[Path, str], list[Path]] = defaultdict(list)
    for owner in markdown_files(architecture):
        text = owner.read_text("utf-8")
        for target, fragment in declared_contract_targets(owner, text):
            authorities[(target, slug(fragment))].append(owner)
        for title, body in sections_at(text, 2):
            if title != "Seam Contract":
                continue
            items = sections_at(body, 3)
            if any(name in CONTRACT_FIELDS for name, _ in items):
                authorities[(owner, "seam-contract")].append(owner)
            else:
                for name, item in items:
                    if name != "共同规则" and re.search(r"^\*\*", item, re.MULTILINE):
                        authorities[(owner, slug(name))].append(owner)
    for (target, fragment), owners in authorities.items():
        if len(owners) != 1:
            errors.append(f"{target.relative_to(root) if target.is_relative_to(root) else target}: "
                          f"contract {fragment or 'page'} needs exactly one subsystem owner")
    for target in markdown_files(architecture):
        if is_contract_page(target) and (target, "") not in authorities:
            errors.append(f"{target.relative_to(root)}: separate contract needs exactly one subsystem owner")
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
    errors.extend(check_source_doc_links(root.resolve().parent))
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
