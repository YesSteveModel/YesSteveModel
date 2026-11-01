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
        fields = "\n\n".join(f"### {title}\n\nLocal obligation." for title in check_docs.CONTRACT_FIELDS)
        sections = "\n\n".join(f"## {title}\n\n" + (fields if title == "Seam Contract" else "Local design.")
                                for title in check_docs.OVERVIEW_SECTIONS)
        self.write(f"architecture/{directory}/overview.md",
                   f"# Subsystem\n\n上级契约：[Parent]({parent})。\n\n{sections}\n\n{extra}\n")

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
        path.write_text(path.read_text("utf-8").replace("## Seam Contract", "## Other"), "utf-8")
        self.assertTrue(any("four ordered" in error for error in self.errors()))

    def test_implementation_needs_design_section(self):
        path = self.root / "architecture/a/overview.md"
        path.write_text(path.read_text("utf-8") + "\n[Mapping](implementation/mapping.md)\n", "utf-8")
        name = "architecture/a/implementation/mapping.md"
        self.write(name, "# Mapping\n\n[Design](../overview.md)\n")
        self.assertTrue(any("introductory design-section" in error for error in self.errors()))
        self.write(name, "# Mapping\n\nImplements [handoff](../overview.md#结果与保证).\n")
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
        self.write(name, "# View\n\nProjects [ownership](../a/overview.md#所有权与时序).\n")
        self.assertEqual([], self.errors())

    def test_single_page_subsystem_uses_the_same_contract(self):
        overview = self.root / "architecture/a/overview.md"
        original = overview.read_text("utf-8")
        overview.write_text(original + "\n[Leaf](leaf.md#seam-contract)\n", "utf-8")
        leaf = self.root / "architecture/a/leaf.md"
        leaf.write_text(original.replace("../README.md#contract", "overview.md#seam-contract")
                        .replace("[B](b/overview.md)", ""), "utf-8")
        self.assertEqual([], self.errors())
        leaf.write_text(leaf.read_text("utf-8").replace("### 失败与拒收", "### Other"), "utf-8")
        self.assertTrue(any("leaf.md: contract needs six ordered fields" in error for error in self.errors()))
        leaf.write_text(leaf.read_text("utf-8").replace("## Seam Contract", "## Other contract"), "utf-8")
        self.assertTrue(any("leaf.md: subsystem needs one Seam Contract entry" in error for error in self.errors()))

    def test_contract_fields_cannot_be_empty_or_reordered(self):
        path = self.root / "architecture/a/overview.md"
        original = path.read_text("utf-8")
        path.write_text(original.replace("### 前置条件与输入\n\nLocal obligation.",
                                         "### 前置条件与输入\n\n#### Empty detail"), "utf-8")
        self.assertTrue(any("contract field 前置条件与输入 is empty" in error for error in self.errors()))
        path.write_text(original.replace("### 前置条件与输入", "### TEMP")
                        .replace("### 结果与保证", "### 前置条件与输入")
                        .replace("### TEMP", "### 结果与保证"), "utf-8")
        self.assertTrue(any("six ordered fields" in error for error in self.errors()))

    def test_duplicate_contract_entry_is_rejected(self):
        path = self.root / "architecture/a/overview.md"
        path.write_text(path.read_text("utf-8") + "\n## Seam Contract\n\nDuplicate.\n", "utf-8")
        self.assertTrue(any("one Seam Contract entry" in error for error in self.errors()))

    def test_separate_contract_has_one_owner_and_complete_fields(self):
        path = self.root / "architecture/a/overview.md"
        original = path.read_text("utf-8")
        before, rest = original.split("## Seam Contract\n", 1)
        fields, after = rest.split("## 设计取舍\n", 1)
        self.write("architecture/a/seam-contract.md", "# Contract\n" + fields.replace("### ", "## "))
        external = before + "## Seam Contract\n\n[Contract](seam-contract.md)\n\n## 设计取舍\n" + after
        path.write_text(external, "utf-8")
        self.assertEqual([], self.errors())
        path.write_text(external.replace("[Contract](seam-contract.md)",
                                         "Publication details are in the contract.\n\n[Contract](seam-contract.md)"), "utf-8")
        self.assertEqual([], self.errors())
        path.write_text(original.replace("## Seam Contract\n", "## Seam Contract\n\n[Contract](seam-contract.md)\n"), "utf-8")
        self.assertTrue(any("authorities cannot coexist" in error for error in self.errors()))

    def named_contracts(self, name="architecture/a/overview.md", external=False):
        path = self.root / name
        original = path.read_text("utf-8")
        before, rest = original.split("## Seam Contract\n", 1)
        _, after = rest.split("## 设计取舍\n", 1)
        fields = "\n\n".join(f"**{title}**\n\nLocal obligation." for title in check_docs.CONTRACT_FIELDS)
        second = "See [publication](publication.md#commit)." if external else fields
        path.write_text(before + "## Seam Contract\n\n"
                        "[Acquire](#acquire) / [Publish](#publish)\n\n"
                        "### 共同规则\n\n适用范围：Acquire and Publish inherit the shared identity.\n\n"
                        "### Acquire\n\nInherits [identity](#共同规则).\n\n" + fields +
                        "\n\n### Publish\n\n" + second + "\n\n## 设计取舍\n" + after, "utf-8")
        if external:
            self.write("architecture/a/publication.md", "# Publication\n\n"
                       "## Context\n\nDesign explanation.\n\n## Commit\n\n" + fields)
        return path

    def test_multiple_inline_contracts_with_scoped_common_rules(self):
        self.named_contracts()
        self.assertEqual([], self.errors())

    def test_each_named_contract_needs_its_own_fields(self):
        path = self.named_contracts()
        text = path.read_text("utf-8")
        before, second = text.split("### Publish", 1)
        path.write_text(before + "### Publish" + second.replace("**失败与拒收**", "**Other**"), "utf-8")
        self.assertTrue(any("six ordered fields" in error for error in self.errors()))

    def test_common_rules_need_scope_and_cannot_replace_all_seams(self):
        path = self.named_contracts()
        original = path.read_text("utf-8")
        path.write_text(original.replace("适用范围：", "Shared: "), "utf-8")
        self.assertTrue(any("explicit scope" in error for error in self.errors()))
        before, rest = original.split("### Acquire", 1)
        _, after = rest.split("## 设计取舍", 1)
        path.write_text(before + "## 设计取舍" + after, "utf-8")
        self.assertTrue(any("include a seam" in error for error in self.errors()))

    def test_named_contract_requires_navigation(self):
        path = self.named_contracts()
        path.write_text(path.read_text("utf-8").replace("[Acquire](#acquire) / ", ""), "utf-8")
        self.assertTrue(any("Acquire needs an entry navigation link" in error for error in self.errors()))

    def test_inline_and_topic_contracts_can_share_one_entry(self):
        self.named_contracts(external=True)
        self.assertEqual([], self.errors())

    def test_external_contract_must_be_complete_and_have_one_owner(self):
        self.named_contracts(external=True)
        topic = self.root / "architecture/a/publication.md"
        original = topic.read_text("utf-8")
        topic.write_text(original.replace("**失败与拒收**\n\nLocal obligation.", ""), "utf-8")
        self.assertTrue(any("six ordered fields" in error for error in self.errors()))
        topic.write_text(original, "utf-8")
        other = self.root / "architecture/a/b/c/overview.md"
        text = other.read_text("utf-8")
        before, rest = text.split("## Seam Contract\n", 1)
        _, after = rest.split("## 设计取舍\n", 1)
        other.write_text(before + "## Seam Contract\n\n[Owned](../../publication.md#commit)\n\n"
                         "## 设计取舍\n" + after, "utf-8")
        self.assertTrue(any("exactly one subsystem owner" in error for error in self.errors()))

    def test_cannot_claim_another_subsystems_inline_contract(self):
        self.named_contracts()
        other = self.root / "architecture/a/b/c/overview.md"
        text = other.read_text("utf-8")
        before, rest = text.split("## Seam Contract\n", 1)
        _, after = rest.split("## 设计取舍\n", 1)
        other.write_text(before + "## Seam Contract\n\n[Owned](../../overview.md#acquire)\n\n"
                         "## 设计取舍\n" + after, "utf-8")
        self.assertTrue(any("exactly one subsystem owner" in error for error in self.errors()))

    def test_named_contract_dependency_does_not_claim_ownership(self):
        path = self.named_contracts(external=True)
        path.write_text(path.read_text("utf-8").replace("**证据与依赖**\n\nLocal obligation.",
                         "**证据与依赖**\n\nUses [publication](publication.md#commit)."), "utf-8")
        self.assertEqual([], self.errors())

    def test_fenced_labels_do_not_supply_missing_fields(self):
        path = self.named_contracts()
        text = path.read_text("utf-8")
        before, second = text.split("### Publish", 1)
        second = second.replace("**失败与拒收**\n\nLocal obligation.",
                                "```markdown\n**失败与拒收**\nExample.\n```")
        path.write_text(before + "### Publish" + second, "utf-8")
        self.assertTrue(any("six ordered fields" in error for error in self.errors()))

    def test_single_page_parent_is_not_inferred_from_directory(self):
        overview = self.root / "architecture/a/overview.md"
        original = overview.read_text("utf-8")
        overview.write_text(original + "\n[Leaf](leaf.md#seam-contract)\n", "utf-8")
        leaf = original.replace("../README.md#contract", "overview.md#seam-contract").replace("[B](b/overview.md)", "")
        self.write("architecture/a/leaf.md", leaf + "\n[Child](child.md#seam-contract)\n")
        self.write("architecture/a/child.md", leaf.replace("overview.md#seam-contract", "leaf.md#seam-contract"))
        self.assertEqual([], self.errors())

    def test_topic_and_asset_directory_do_not_declare_subsystems(self):
        overview = self.root / "architecture/a/overview.md"
        overview.write_text(overview.read_text("utf-8") + "\n[Topic](topic.md)\n", "utf-8")
        self.write("architecture/a/topic.md", "# Topic\n\n## 职责与边界\n\nOnly a design topic.\n")
        self.write("architecture/a/images/diagram.svg", "<svg/>")
        self.assertEqual([], self.errors())

    def test_dependency_contract_link_does_not_create_another_owner(self):
        path = self.root / "architecture/a/b/c/overview.md"
        original = path.read_text("utf-8")
        before, rest = original.split("## Seam Contract\n", 1)
        fields, after = rest.split("## 设计取舍\n", 1)
        self.write("architecture/a/b/c/seam-contract.md", "# Contract\n" + fields.replace("### ", "## "))
        path.write_text(before + "## Seam Contract\n\n[Contract](seam-contract.md)\n\n## 设计取舍\n" + after, "utf-8")
        parent = self.root / "architecture/a/b/overview.md"
        parent.write_text(parent.read_text("utf-8").replace("### 证据与依赖\n",
                          "### 证据与依赖\n\n[Dependency](c/seam-contract.md)\n"), "utf-8")
        self.assertEqual([], self.errors())

    def test_fenced_headings_do_not_define_a_contract(self):
        path = self.root / "architecture/a/overview.md"
        text = path.read_text("utf-8")
        path.write_text(text.replace("### 证据与依赖\n", "### 证据与依赖\n\n```markdown\n## Seam Contract\n```\n"), "utf-8")
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


class SourceDocumentationLinks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.doc = self.root / "docs" / "contract.md"
        self.doc.parent.mkdir()
        self.doc.write_text("# Contract\n\n## Ownership\n\nBorrow until close.\n", "utf-8")
        self.source = self.root / "src" / "main" / "java" / "nested" / "Example.java"
        self.source.parent.mkdir(parents=True)

    def errors(self, source):
        self.source.write_text(source, "utf-8")
        return check_docs.check_source_doc_links(self.root, [self.source])

    def test_root_links_in_block_and_line_comments(self):
        self.assertEqual([], self.errors(
            "/** Implements [ownership](docs/contract.md#ownership). */\n"
            "// See [contract](docs/contract.md).\nclass Example {}\n"))

    def test_relative_html_and_absolute_document_links_are_rejected(self):
        for target in ("../docs/contract.md", "docs/../docs/contract.md", "/docs/contract.md",
                       "C:/docs/contract.md", "file:///docs/contract.md", "docs/%2e%2e/contract.md"):
            with self.subTest(target=target):
                self.assertTrue(any("project-root Markdown" in error for error in
                                    self.errors(f"/** [contract]({target}) */")))
        self.assertTrue(any("project-root Markdown" in error for error in self.errors(
            '/** <a href="docs/contract.md#ownership">ownership</a> */')))

    def test_missing_document_and_missing_or_duplicate_heading(self):
        self.assertTrue(any("broken source" in error for error in
                            self.errors("/** [missing](docs/missing.md) */")))
        self.assertTrue(any("documentation anchor" in error for error in
                            self.errors("/** [missing](docs/contract.md#missing) */")))
        self.doc.write_text(self.doc.read_text("utf-8") + "\n## Ownership\n", "utf-8")
        self.assertTrue(any("ambiguous" in error for error in
                            self.errors("/** [ownership](docs/contract.md#ownership) */")))

    def test_strings_examples_and_external_links_are_not_local_dependencies(self):
        self.assertEqual([], self.errors('''class Example {
    String text = "/** [missing](docs/missing.md) */";
    String block = """
        // [missing](docs/missing.md)
        """;
}
/**
 * External [API](https://example.com/docs/api.md).
 * <a href="https://example.com/docs/api.md">External API</a>
 * `[example](docs/missing.md)`
 */
'''))


if __name__ == "__main__":
    unittest.main()
