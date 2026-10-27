#!/usr/bin/env python3
"""Exercise architecture conventions against disposable document trees."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import check_docs


class ArchitectureChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("README.md", "# Docs\n\n[Architecture](architecture/README.md)\n")
        self.write("architecture/README.md", "# System\n\n## Contract\n\n[A](a/overview.md)\n")
        self.overview("a", "../README.md#contract", "[B](b/overview.md)")
        self.overview("a/b", "../overview.md#职责与边界", "[C](c/overview.md)")
        self.overview("a/b/c", "../overview.md#职责与边界")

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, "utf-8")

    def overview(self, directory, parent, extra=""):
        sections = "\n\n".join(f"## {title}\n\nLocal contract." for title in check_docs.OVERVIEW_SECTIONS)
        self.write(f"architecture/{directory}/overview.md",
                   f"# Subsystem\n\nRefines [parent]({parent}).\n\n{sections}\n\n{extra}\n")

    def errors(self):
        with patch.object(check_docs, "REQUIRED_DIRECTORIES", {"architecture"}), \
                patch.object(check_docs, "REQUIRED_FILES", {"README.md", "architecture/README.md"}):
            return check_docs.run(self.root)

    def test_recursive_subsystems_without_implementation(self):
        self.assertEqual([], self.errors())

    def test_operation_scope_and_sibling_topic(self):
        path = self.root / "architecture/a/b/c/overview.md"
        path.write_text(path.read_text("utf-8") + "\nNo persistent instance; the caller owns each operation.\n[Topic](handoff.md)\n", "utf-8")
        self.write("architecture/a/b/c/handoff.md", "# Handoff\n\nLocal design contract.\n")
        self.assertEqual([], self.errors())

    def test_retired_tree(self):
        self.write("architecture/design/old.md", "# Old\n")
        self.assertTrue(any("retired architecture" in error for error in self.errors()))

    def test_broken_and_ambiguous_anchors(self):
        path = self.root / "architecture/README.md"
        original = path.read_text("utf-8")
        path.write_text(original + "\n[Missing](a/overview.md#missing)\n", "utf-8")
        self.assertTrue(any("broken anchor" in error for error in self.errors()))
        path.write_text(original + "\n## Contract\n", "utf-8")
        self.assertTrue(any("ambiguous anchor" in error for error in self.errors()))

    def test_missing_parent_contract(self):
        self.overview("a/b/c", "../overview.md")
        self.assertTrue(any("parent-contract" in error for error in self.errors()))

    def test_missing_core_section(self):
        path = self.root / "architecture/a/overview.md"
        path.write_text(path.read_text("utf-8").replace("## 生命周期与失败", "## Other"), "utf-8")
        self.assertTrue(any("six ordered" in error for error in self.errors()))

    def test_implementation_needs_design_section(self):
        path = self.root / "architecture/a/overview.md"
        path.write_text(path.read_text("utf-8") + "\n[Mapping](implementation/mapping.md)\n", "utf-8")
        name = "architecture/a/implementation/mapping.md"
        self.write(name, "# Mapping\n\n[Design](../overview.md)\n")
        self.assertTrue(any("introductory design-section" in error for error in self.errors()))
        self.write(name, "# Mapping\n\nImplements [handoff](../overview.md#数据流与交接).\n")
        self.assertEqual([], self.errors())

    def test_orphan(self):
        self.write("architecture/a/unlinked.md", "# Topic\n")
        self.assertTrue(any("orphan" in error for error in self.errors()))

    def test_no_implementation_overview(self):
        self.write("architecture/a/implementation/overview.md", "# Index\n")
        self.assertTrue(any("implementation overview" in error for error in self.errors()))

    def test_flow_requires_design_section(self):
        entry = self.root / "architecture/README.md"
        entry.write_text(entry.read_text("utf-8") + "\n[Flow](flows/scenario.md)\n", "utf-8")
        self.write("architecture/flows/scenario.md", "# Flow\n\n[A](../a/overview.md)\n")
        self.assertTrue(any("flow needs" in error for error in self.errors()))
        self.write("architecture/flows/scenario.md", "# Flow\n\nUses [contract](../a/overview.md#职责与边界).\n")
        self.assertEqual([], self.errors())

    def test_view_requires_design_section_without_subsystem_template(self):
        entry = self.root / "architecture/README.md"
        entry.write_text(entry.read_text("utf-8") + "\n[View](views/resources.md)\n", "utf-8")
        name = "architecture/views/resources.md"
        self.write(name, "# View\n\n[A](../a/overview.md)\n")
        self.assertTrue(any("view needs" in error for error in self.errors()))
        self.write(name, "# View\n\nProjects [ownership](../a/overview.md#运行作用域与所有权).\n")
        self.assertEqual([], self.errors())

    def test_projections_cannot_replace_design_evidence(self):
        entry = self.root / "architecture/README.md"
        entry.write_text(entry.read_text("utf-8") + "\n[View](views/resources.md)\n"
                         "[Flow](flows/scenario.md)\n[Mapping](a/implementation/mapping.md)\n", "utf-8")
        self.write("architecture/views/resources.md",
                   "# View\n\n## Summary\n\n[A](../a/overview.md#职责与边界)\n")
        flow = "architecture/flows/scenario.md"
        mapping = "architecture/a/implementation/mapping.md"
        self.write(flow, "# Flow\n\n[View](../views/resources.md#summary)\n")
        self.write(mapping, "# Mapping\n\n[View](../../views/resources.md#summary)\n")
        errors = self.errors()
        self.assertTrue(any("flow needs" in error for error in errors))
        self.assertTrue(any("introductory design-section" in error for error in errors))
        self.write(flow, "# Flow\n\n## Scenario\n\n[A](../a/overview.md#职责与边界)\n")
        self.write("architecture/views/resources.md", "# View\n\n[Flow](../flows/scenario.md#scenario)\n")
        self.write(mapping, "# Mapping\n\n[Flow](../../flows/scenario.md#scenario)\n")
        errors = self.errors()
        self.assertTrue(any("view needs" in error for error in errors))
        self.assertTrue(any("introductory design-section" in error for error in errors))

    def test_projection_has_no_overview(self):
        for directory in ("views", "flows"):
            with self.subTest(directory=directory):
                self.write(f"architecture/{directory}/overview.md",
                           "# Index\n\n[A](../a/overview.md#职责与边界)\n")
                self.assertTrue(any(f"{directory}/overview.md: projection overview" in error.replace("\\", "/")
                                    for error in self.errors()))


if __name__ == "__main__":
    unittest.main()
