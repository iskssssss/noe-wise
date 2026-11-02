#!/usr/bin/env python3
"""候选验证清单工具的无外部依赖行为测试。"""

from __future__ import annotations

import importlib.util
import argparse
import copy
import contextlib
import io
import json
import logging
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


SCRIPT = Path(__file__).with_name("verification_manifest.py")
SPEC = importlib.util.spec_from_file_location("verification_manifest", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class VerificationManifestTest(unittest.TestCase):
    """验证分域 hash、Gate key、禁止路径和报告汇总。"""

    def setUp(self) -> None:
        """为每个测试创建独立的最小 Git 仓库。"""
        self.temporary = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary.name)
        subprocess.run(["git", "init", "-q"], cwd=self.repo, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=self.repo, check=True)
        subprocess.run(["git", "config", "user.name", "Skill Test"], cwd=self.repo, check=True)
        for directory in ("src", "docs", "schema", "gate", ".claude"):
            (self.repo / directory).mkdir()
        (self.repo / "AGENTS.md").write_text("rules\n", encoding="utf-8")
        (self.repo / "src" / "Main.java").write_text("class Main {}\n", encoding="utf-8")
        (self.repo / "docs" / "design.md").write_text("# Design\n", encoding="utf-8")
        (self.repo / "schema" / "V1.sql").write_text("select 1;\n", encoding="utf-8")
        (self.repo / "gate" / "java.txt").write_text("gate-v1\n", encoding="utf-8")
        (self.repo / ".claude" / "secret.txt").write_text("never read\n", encoding="utf-8")
        subprocess.run(["git", "add", "AGENTS.md", "src", "docs", "schema", "gate"], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.repo, check=True)
        self.skill_root = self.repo / ".agents/skills/nw-verification"
        skill_patch = patch.object(MODULE, "SKILL_ROOT", self.skill_root)
        skill_patch.start()
        self.addCleanup(skill_patch.stop)
        self.state = self.make_state()

    def make_state(self) -> dict:
        """返回绑定当前测试仓库的独立状态，各用例只覆盖实际变化字段。"""
        return {
            "schemaVersion": 1,
            "goal": "test",
            "stackItem": "TEST-1",
            "baseSha": MODULE.git_value(self.repo, "rev-parse", "HEAD"),
            "branch": MODULE.git_value(self.repo, "branch", "--show-current"),
            "worktree": self.repo.as_posix(),
            "allowedPaths": ["src/**", "docs/**", "schema/**"],
            "excludedPaths": [".claude/**", ".agent-work-state/**"],
            "governanceFiles": ["AGENTS.md"],
            "toolchains": [{"name": "python", "command": [sys.executable, "--version"]}],
            "domains": {
                "source": {"paths": ["src/**"]},
                "docs": {"paths": ["docs/**"]},
                "integration": {"paths": ["schema/**"]},
            },
            "gates": {
                "java": {
                    "kind": "command",
                    "version": 1,
                    "command": ["fake-gradle", "test"],
                    "domains": ["source"],
                    "inputPaths": ["src/**"],
                    "definitionFiles": ["gate/java.txt"],
                    "toolchains": ["python"],
                }
            },
        }

    def tearDown(self) -> None:
        """释放临时仓库。"""
        self.temporary.cleanup()

    def snapshot(self) -> dict:
        """用当前 fixture 计算候选。"""
        return MODULE.candidate_snapshot(self.repo, self.state)

    def test_docs_change_only_invalidates_docs_domain(self) -> None:
        """文档变化不得让 sourceHash 失效。"""
        before = self.snapshot()
        (self.repo / "docs" / "design.md").write_text("# Changed\n", encoding="utf-8")
        after = self.snapshot()
        self.assertEqual(before["domains"]["source"]["hash"], after["domains"]["source"]["hash"])
        self.assertNotEqual(before["domains"]["docs"]["hash"], after["domains"]["docs"]["hash"])

    def test_relocated_rules_change_context_without_invalidating_unrelated_gate(self) -> None:
        """四个技能的规则均改变治理指纹，但不使无关 Gate 或普通 Note 成为依赖。"""
        self.state.pop("governanceFiles")
        rules = {
            "nw-workflow": "development-workflow.md",
            "nw-engineering": "engineering-standards.md",
            "nw-verification": "verification-manifest.md",
            "nw-docs": "documentation-standards.md",
        }
        rule_paths = []
        for skill_name, rule_name in rules.items():
            skill = self.skill_root.parent / skill_name
            rule = skill / "references" / rule_name
            rule.parent.mkdir(parents=True, exist_ok=True)
            (skill / "SKILL.md").write_text("# Skill\n", encoding="utf-8")
            rule.write_text("# Governance\n", encoding="utf-8")
            rule_paths.append(rule)
        before = self.snapshot()
        for rule in rule_paths:
            with self.subTest(rule=rule.name):
                rule.write_text("# Changed rule\n", encoding="utf-8")
                changed = self.snapshot()
                self.assertNotEqual(before["governanceHash"], changed["governanceHash"])
                self.assertEqual(before["gates"]["java"]["key"], changed["gates"]["java"]["key"])
                before = changed
        note = self.repo / ".agents/notes/proposed/process/example.md"
        note.parent.mkdir(parents=True, exist_ok=True)
        note.write_text("# Task progress\n", encoding="utf-8")
        after_note = self.snapshot()
        self.assertEqual(changed["governanceHash"], after_note["governanceHash"])


    def test_add_delete_and_rename_change_source_hash(self) -> None:
        """新增、删除和重命名都必须改变 sourceHash。"""
        original = self.snapshot()["domains"]["source"]["hash"]
        (self.repo / "src" / "Added.java").write_text("class Added {}\n", encoding="utf-8")
        added = self.snapshot()["domains"]["source"]["hash"]
        (self.repo / "src" / "Added.java").unlink()
        (self.repo / "src" / "Main.java").unlink()
        deleted_snapshot = self.snapshot()
        (self.repo / "src" / "Renamed.java").write_text("class Main {}\n", encoding="utf-8")
        renamed = self.snapshot()["domains"]["source"]["hash"]
        self.assertNotEqual(original, added)
        self.assertNotEqual(original, deleted_snapshot["domains"]["source"]["hash"])
        self.assertTrue(any(item["path"] == "src/Main.java" and item["status"] == "deleted" for item in deleted_snapshot["domains"]["source"]["files"]))
        self.assertNotEqual(deleted_snapshot["domains"]["source"]["hash"], renamed)

    def test_index_tracking_and_staged_deletion_change_source_hash(self) -> None:
        """tracked→untracked 与 staged deletion 都必须改变候选身份。"""
        original = self.snapshot()["domains"]["source"]["hash"]
        subprocess.run(["git", "rm", "--cached", "src/Main.java"], cwd=self.repo, check=True, capture_output=True)
        untracked = self.snapshot()["domains"]["source"]
        self.assertNotEqual(original, untracked["hash"])
        self.assertTrue(any(item["path"] == "src/Main.java" and not item["tracked"] and item["baseTracked"] for item in untracked["files"]))
        subprocess.run(["git", "add", "src/Main.java"], cwd=self.repo, check=True)
        subprocess.run(["git", "rm", "src/Main.java"], cwd=self.repo, check=True, capture_output=True)
        deleted = self.snapshot()["domains"]["source"]
        self.assertNotEqual(original, deleted["hash"])
        self.assertTrue(any(item["path"] == "src/Main.java" and item["status"] == "deleted" for item in deleted["files"]))

    def test_gate_definition_changes_only_gate_key(self) -> None:
        """Gate 脚本变化必须使 Gate key 失效。"""
        before = self.snapshot()
        (self.repo / "gate" / "java.txt").write_text("gate-v2\n", encoding="utf-8")
        after = self.snapshot()
        self.assertEqual(before["domains"]["source"]["hash"], after["domains"]["source"]["hash"])
        self.assertNotEqual(before["gates"]["java"]["key"], after["gates"]["java"]["key"])

    def test_gate_input_must_be_in_declared_candidate_domain(self) -> None:
        """Gate 可消费但被候选排除的输入必须阻断冻结。"""
        self.state["gates"]["java"]["inputPaths"].append("gate/java.txt")
        with self.assertRaises(MODULE.ManifestError):
            self.snapshot()

    def test_gate_name_must_not_traverse_paths(self) -> None:
        """Gate 名称不能通过相对路径写出本地状态目录。"""
        self.state["gates"]["../escape"] = self.state["gates"].pop("java")
        with self.assertRaises(MODULE.ManifestError):
            self.snapshot()

    def test_toolchain_fingerprint_changes_gate_key(self) -> None:
        """工具链声明变化必须使 Gate key 失效。"""
        before = self.snapshot()
        self.state["toolchains"][0]["command"] = [sys.executable, "-c", "print('different')"]
        after = self.snapshot()
        self.assertNotEqual(before["gates"]["java"]["key"], after["gates"]["java"]["key"])

    def test_declared_environment_keys_limit_invalidation(self) -> None:
        """Python Gate 忽略无关 Java 环境，声明的环境变化仍使证据失效。"""
        self.state["toolchains"][0]["environmentKeys"] = ["SKILLFORGE_TEST_ENV"]
        with patch.dict(os.environ, {"JAVA_HOME": "java-a", "SKILLFORGE_TEST_ENV": "a"}):
            before = self.snapshot()
        with patch.dict(os.environ, {"JAVA_HOME": "java-b", "SKILLFORGE_TEST_ENV": "a"}):
            unrelated = self.snapshot()
        with patch.dict(os.environ, {"JAVA_HOME": "java-b", "SKILLFORGE_TEST_ENV": "b"}):
            related = self.snapshot()
        self.assertEqual(before["gates"]["java"]["key"], unrelated["gates"]["java"]["key"])
        self.assertNotEqual(before["gates"]["java"]["key"], related["gates"]["java"]["key"])

    def test_default_toolchain_tracks_java_environment(self) -> None:
        """省略 environmentKeys 时仍绑定默认 Java 环境。"""
        with patch.dict(os.environ, {"JAVA_HOME": "java-a"}):
            before = self.snapshot()
        with patch.dict(os.environ, {"JAVA_HOME": "java-b"}):
            after = self.snapshot()
        self.assertNotEqual(before["gates"]["java"]["key"], after["gates"]["java"]["key"])

    def test_explicit_environment_override_always_changes_fingerprint(self) -> None:
        """显式环境覆盖即使未列入 environmentKeys，也必须参与指纹。"""
        toolchain = self.state["toolchains"][0]
        toolchain["environmentKeys"] = []
        toolchain["environment"] = {"SKILLFORGE_TEST_ENV": "a"}
        before = self.snapshot()
        toolchain["environment"]["SKILLFORGE_TEST_ENV"] = "b"
        after = self.snapshot()
        self.assertNotEqual(before["gates"]["java"]["key"], after["gates"]["java"]["key"])

    def test_invalid_environment_keys_are_rejected(self) -> None:
        """错误的环境依赖声明不能静默产生可复用指纹。"""
        for keys in (None, "JAVA_HOME", [1], [""], ["BAD=KEY"], ["BAD\0KEY"]):
            self.state["toolchains"][0]["environmentKeys"] = keys
            with self.subTest(keys=keys), self.assertRaises(MODULE.ManifestError):
                MODULE.toolchain_snapshot(self.repo, self.state)

    def test_init_selects_no_gates_and_only_python_environment(self) -> None:
        """初始化不要求评审或业务测试，Python 环境不依赖 Java 配置。"""
        state_path = self.repo / ".agent-work-state/state.json"
        args = argparse.Namespace(state=str(state_path), repo=str(self.repo), force=False,
                                  goal="test", stack_item="TEST-1", base_sha=None, branch=None)
        with contextlib.redirect_stdout(io.StringIO()):
            MODULE.init_state(args)
        state = MODULE.load_json(state_path)
        self.assertEqual({}, state["gates"])
        self.assertEqual(["PYTHONHOME", "PYTHONPATH"], state["toolchains"][0]["environmentKeys"])
        self.assertNotIn("governanceFiles", state)
        # 精简仓库无需创建整套治理文件即可冻结空的初始候选。
        snapshot = MODULE.candidate_snapshot(self.repo, state)
        self.assertEqual(["AGENTS.md"], [entry["path"] for entry in snapshot["governanceFiles"]])

    def test_duplicate_domain_is_rejected(self) -> None:
        """同一文件不得同时归入两个候选域。"""
        self.state["domains"]["integration"]["paths"].append("src/Main.java")
        with self.assertRaises(MODULE.ManifestError):
            self.snapshot()

    def test_forbidden_scope_is_rejected(self) -> None:
        """即使显式填写也不能扫描 .claude。"""
        self.state["domains"]["source"]["paths"] = [".claude/**"]
        with self.assertRaises(MODULE.ManifestError):
            self.snapshot()

    def test_junit_is_derived_from_xml(self) -> None:
        """测试数量必须来自 JUnit XML。"""
        report_dir = self.repo / "reports"
        report_dir.mkdir()
        (report_dir / "TEST-example.xml").write_text('<testsuite tests="3" failures="0" errors="0" skipped="1"><testcase/><testcase/><testcase><skipped/></testcase></testsuite>\n', encoding="utf-8")
        self.assertEqual({"suites": 1, "tests": 3, "failures": 0, "errors": 0, "skipped": 1}, MODULE.junit_summary(report_dir))

    def test_failed_partial_and_missing_report_are_rejected(self) -> None:
        """失败、partial 或无报告命令不能形成绿色证据。"""
        snapshot = self.snapshot()
        gate_key = snapshot["gates"]["java"]["key"]
        invalid_entries = (
            {"evidence": [{"name": "java", "gate": "java", "gateKey": gate_key, "kind": "command", "status": "fail", "exitCode": 0, "command": ["fake-gradle", "test"], "result": {"tests": 1}}]},
            {"evidence": [{"name": "java", "gate": "java", "gateKey": gate_key, "kind": "command", "status": "partial", "exitCode": 0, "command": ["fake-gradle", "test"], "result": {"tests": 1}}]},
            {"evidence": [{"name": "java", "gate": "java", "gateKey": gate_key, "kind": "command", "status": "pass", "exitCode": 0, "command": ["wrong"], "result": {"tests": 1}}]},
            {"evidence": [{"name": "java", "gate": "java", "gateKey": gate_key, "kind": "command", "status": "pass", "exitCode": 0, "command": ["fake-gradle", "test"], "result": {}}]},
            {"evidence": [{"name": "java", "gate": "java", "kind": "command", "status": "pass", "exitCode": 0, "command": ["fake-gradle", "test"], "result": {"tests": 1}}]},
        )
        for evidence in invalid_entries:
            with self.subTest(evidence=evidence), self.assertRaises(MODULE.ManifestError):
                MODULE.bind_evidence(self.repo, evidence, snapshot, self.repo / ".agent-work-state" / "artifacts")

    def test_empty_glob_and_out_of_scope_file_are_rejected(self) -> None:
        """拼错 glob 或漏填 allowedPaths 都必须 fail closed。"""
        self.state["domains"]["source"]["paths"] = ["missing/**"]
        with self.assertRaises(MODULE.ManifestError):
            self.snapshot()
        self.state["domains"]["source"]["paths"] = ["src/**"]
        self.state["allowedPaths"] = ["docs/**", "schema/**"]
        with self.assertRaises(MODULE.ManifestError):
            self.snapshot()

    def test_branch_and_worktree_mismatch_are_rejected(self) -> None:
        """恢复到不同 branch 或 worktree 时必须拒绝旧状态。"""
        self.state["branch"] = "other"
        with self.assertRaises(MODULE.ManifestError):
            self.snapshot()
        self.state["branch"] = MODULE.git_value(self.repo, "branch", "--show-current")
        self.state["worktree"] = (self.repo / "other").as_posix()
        with self.assertRaises(MODULE.ManifestError):
            self.snapshot()

    def test_segment_glob_does_not_cross_directory(self) -> None:
        """单星号不能吞掉多级目录，双星号可以。"""
        self.assertFalse(MODULE.matches("src/generated/deep/File.java", "src/generated/*"))
        self.assertTrue(MODULE.matches("src/generated/deep/File.java", "src/generated/**"))

    def test_safe_path_rejects_sensitive_and_non_state_outputs(self) -> None:
        """状态和证据不得读取 .claude/.git 或覆盖普通仓库文件。"""
        with self.assertRaises(MODULE.ManifestError):
            MODULE.ensure_safe_local_path(self.repo, self.repo / ".claude" / "state.json", "state", True)
        with self.assertRaises(MODULE.ManifestError):
            MODULE.ensure_safe_local_path(self.repo, self.repo / "AGENTS.md", "manifest", True)

    def test_required_gate_must_be_covered_and_prebound(self) -> None:
        """manifest 必须精确覆盖 required Gate 且使用冻结 key。"""
        self.state["gates"]["review"] = {
            "kind": "review",
            "version": 1,
            "domains": ["docs"],
            "inputPaths": ["docs/**"],
            "definitionFiles": ["gate/java.txt"],
            "toolchains": ["python"],
        }
        snapshot = self.snapshot()
        evidence = {"evidence": [{
            "name": "java",
            "gate": "java",
            "gateKey": snapshot["gates"]["java"]["key"],
            "kind": "command",
            "status": "pass",
            "exitCode": 0,
            "command": ["fake-gradle", "test"],
            "result": {"tests": 1},
        }]}
        with self.assertRaises(MODULE.ManifestError):
            MODULE.bind_evidence(self.repo, evidence, snapshot, self.repo / ".agent-work-state" / "artifacts")

    def test_record_and_check_reject_tampered_immutable_report(self) -> None:
        """端到端记录后可复用，证据快照被改写后必须失效。"""
        state_path = self.repo / ".agent-work-state" / "state.json"
        manifest_path = self.repo / ".agent-work-state" / "manifest.json"
        command = [sys.executable, "-c", "from pathlib import Path; Path('report.txt').write_text('green\\n', encoding='utf-8')"]
        self.state["gates"]["java"]["command"] = command
        self.state["gates"]["java"]["reports"] = ["report.txt"]
        snapshot = self.snapshot()
        state = dict(self.state)
        state.update({"status": "frozen", "openBlockers": [], "snapshot": snapshot})
        MODULE.write_json(state_path, state)
        run_args = argparse.Namespace(state=str(state_path), repo=str(self.repo), manifest=str(manifest_path), review_evidence=None)
        MODULE.run_gates(run_args)
        check_args = argparse.Namespace(state=str(state_path), repo=str(self.repo), manifest=str(manifest_path))
        MODULE.check_manifest(check_args)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        immutable_report = self.repo / manifest["evidence"][0]["reports"][0]["path"]
        immutable_report.write_text("tampered\n", encoding="utf-8")
        with self.assertRaises(MODULE.ManifestError):
            MODULE.check_manifest(check_args)


    def prepare_run(self, with_docs: bool = False) -> argparse.Namespace:
        """建立会写入计数器的受控命令，观察缓存命中是否实际避免再次执行。"""
        for name, domain in (("java", "source"), ("docs", "docs")):
            if name == "docs" and not with_docs:
                continue
            self.state["gates"][name] = {
                "kind": "command", "version": 1, "domains": [domain],
                "inputPaths": ["src/**" if domain == "source" else "docs/**"],
                "definitionFiles": ["gate/java.txt"], "toolchains": ["python"],
                "command": [sys.executable, "-c",
                    "from pathlib import Path; p=Path('.agent-work-state/" + name + ".count'); "
                    "p.write_text(str(int(p.read_text())+1) if p.exists() else '1'); print('green attempt=' + p.read_text())"],
            }
        self.state.update({"status": "frozen", "openBlockers": [], "snapshot": self.snapshot()})
        directory = self.repo / ".agent-work-state"
        args = argparse.Namespace(state=str(directory / "state.json"), repo=str(self.repo),
                                  manifest=str(directory / "manifest.json"), review_evidence=None)
        MODULE.write_json(Path(args.state), self.state)
        return args

    def run_quietly(self, args: argparse.Namespace) -> None:
        """只抑制测试 fixture 的正常 JSON 输出，异常仍交给断言处理。"""
        with contextlib.redirect_stdout(io.StringIO()):
            MODULE.run_gates(args)

    def test_partial_failure_resumes_without_rerunning_green_gate(self) -> None:
        """后一个 Gate 失败后，前一个绿色 Gate 的计数必须保持一次。"""
        args = self.prepare_run(with_docs=True)
        original = MODULE.run_process
        failing_command = self.state["gates"]["java"]["command"]

        def fail_second(repo, command, environment=None, **kwargs):
            """仅模拟第二个受控 Gate 的技术失败，不拦截 Git 或工具链检查。"""
            if command == failing_command:
                return subprocess.CompletedProcess(command, 1, "", "expected failure")
            return original(repo, command, environment, **kwargs)

        with patch.object(MODULE, "run_process", side_effect=fail_second), self.assertRaises(MODULE.ManifestError):
            self.run_quietly(args)
        state = MODULE.load_json(Path(args.state))
        self.assertEqual("failed", state["status"])
        self.assertNotIn("lastGreen", state)
        self.assertEqual(["docs"], list(state["evidenceCheckpoint"]["gateKeys"]))
        with self.assertRaises(MODULE.ManifestError):
            MODULE.check_manifest(args)
        self.run_quietly(args)
        self.run_quietly(args)
        for name in ("docs", "java"):
            self.assertEqual("1", (self.repo / ".agent-work-state" / (name + ".count")).read_text())
        self.assertEqual("verified", MODULE.load_json(Path(args.state))["status"])

    def test_docs_refreeze_preserves_source_gate(self) -> None:
        """重新冻结文档候选后只运行 docs，source 的绿色证据继续复用。"""
        args = self.prepare_run(with_docs=True)
        self.run_quietly(args)
        (self.repo / "docs/design.md").write_text("# Updated\n", encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            MODULE.snapshot_state(args)
        self.run_quietly(args)
        self.assertEqual("1", (self.repo / ".agent-work-state/java.count").read_text())
        self.assertEqual("2", (self.repo / ".agent-work-state/docs.count").read_text())

    def test_governance_refreeze_only_reruns_declared_gate(self) -> None:
        """全局规则变化保留无关 Gate 证据，只重跑显式依赖该规则的 Gate。"""
        args = self.prepare_run(with_docs=True)
        self.state["gates"]["docs"]["governanceFiles"] = ["AGENTS.md"]
        self.state["snapshot"] = self.snapshot()
        MODULE.write_json(Path(args.state), self.state)
        self.run_quietly(args)
        before = MODULE.load_json(Path(args.state))["snapshot"]
        (self.repo / "AGENTS.md").write_text("updated rules\n", encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            MODULE.snapshot_state(args)
        after = MODULE.load_json(Path(args.state))["snapshot"]
        self.assertNotEqual(before["governanceHash"], after["governanceHash"])
        self.run_quietly(args)
        self.assertEqual("1", (self.repo / ".agent-work-state/java.count").read_text())
        self.assertEqual("2", (self.repo / ".agent-work-state/docs.count").read_text())

    def test_manifest_publication_crash_recovers_previous_checkpoint(self) -> None:
        """在 manifest 写入后、state 指针更新前中断，仍能读取旧不可变 checkpoint。"""
        args = self.prepare_run(with_docs=True)
        original = MODULE.write_json
        calls = 0

        def interrupted_write(path, value):
            """在第二次 state 发布时注入崩溃，保留首次 Gate 的状态指针。"""
            nonlocal calls
            if path == Path(args.state):
                calls += 1
                if calls == 2:
                    raise OSError("simulated interruption")
            original(path, value)

        with patch.object(MODULE, "write_json", side_effect=interrupted_write), self.assertRaises(OSError):
            self.run_quietly(args)
        recovered = MODULE.load_checkpoint(self.repo, MODULE.load_json(Path(args.state)))
        self.assertEqual(["docs"], [entry["gate"] for entry in recovered["evidence"]])
        self.run_quietly(args)
        self.assertEqual("1", (self.repo / ".agent-work-state/docs.count").read_text())

    def test_missing_command_evidence_fields_are_rejected(self) -> None:
        """逐字段删除必要证据，即便单独调用统一校验器也必须拒绝。"""
        args = self.prepare_run()
        self.run_quietly(args)
        manifest = MODULE.load_json(Path(args.manifest))
        for field in ("command", "exitCode", "reports", "result", "gateKey"):
            entries = copy.deepcopy(manifest["evidence"])
            entries[0].pop(field)
            with self.subTest(field=field), self.assertRaises(MODULE.ManifestError):
                MODULE.validate_bound_entries(self.repo, entries, manifest["snapshot"])

    def test_unbound_manifest_and_tampered_checkpoint_are_rejected(self) -> None:
        """手写最小 manifest 或破坏状态绑定，都不能作为缓存继续执行。"""
        args = self.prepare_run()
        MODULE.write_json(Path(args.manifest), {"evidence": [], "status": "verified"})
        with self.assertRaises(MODULE.ManifestError):
            MODULE.check_manifest(args)
        self.run_quietly(args)
        state = MODULE.load_json(Path(args.state))
        checkpoint = self.repo / state["evidenceCheckpoint"]["checkpoint"]
        changed = MODULE.load_json(checkpoint)
        changed["evidence"][0].pop("command")
        MODULE.write_json(checkpoint, changed)
        with self.assertRaises(MODULE.ManifestError):
            self.run_quietly(args)

    def test_other_gate_input_change_during_run_blocks_publication(self) -> None:
        """命令修改不属于自身域的另一个 Gate 输入，也必须阻断整个候选发布。"""
        args = self.prepare_run()
        self.state["gates"]["java"]["command"] = [sys.executable, "-c",
            "from pathlib import Path; Path('docs/design.md').write_text('changed')"]
        self.state["snapshot"] = self.snapshot()
        MODULE.write_json(Path(args.state), self.state)
        with self.assertRaises(MODULE.ManifestError):
            self.run_quietly(args)
        self.assertNotIn("lastGreen", MODULE.load_json(Path(args.state)))

    def test_allowed_paths_must_be_fully_classified(self) -> None:
        """允许范围中的文件不能因遗漏候选域而静默跳过。"""
        self.state["allowedPaths"].append("gate/**")
        with self.assertRaises(MODULE.ManifestError):
            self.snapshot()

    def test_each_declared_report_must_be_fresh(self) -> None:
        """只刷新文本或部分 XML 时，未刷新的测试报告必须使 Gate 失败。"""
        directory = self.repo / "reports"
        directory.mkdir()
        first = directory / "TEST-first.xml"
        second = directory / "TEST-second.xml"
        report = directory / "summary.txt"
        for path in (first, second, report):
            path.write_text("old", encoding="utf-8")
        definition = {"reports": ["reports/summary.txt"], "junitPolicy": {"directory": "reports"}}
        before = MODULE.output_fingerprint(self.repo, definition)
        started = MODULE.time.time_ns()
        report.write_text("new summary", encoding="utf-8")
        with self.assertRaises(MODULE.ManifestError):
            MODULE.require_fresh_outputs(self.repo, definition, before, started)
        first.write_text("new XML", encoding="utf-8")
        with self.assertRaises(MODULE.ManifestError):
            MODULE.require_fresh_outputs(self.repo, definition, before, started)
        second.write_text("new XML", encoding="utf-8")
        MODULE.require_fresh_outputs(self.repo, definition, before, started)

    def test_junit_measures_outcomes_and_rejects_false_counts(self) -> None:
        """缺省计数仍检测失败节点，负数、虚增和错误声明都必须拒绝。"""
        directory = self.repo / "reports"
        directory.mkdir()
        report = directory / "TEST-results.xml"
        for tag, field in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
            report.write_text('<testsuite tests="1"><testcase><' + tag + '/></testcase></testsuite>', encoding="utf-8")
            self.assertEqual(1, MODULE.junit_summary(directory)[field])
            report.write_text('<testsuite tests="1" ' + field + '="0"><testcase><' + tag + '/></testcase></testsuite>', encoding="utf-8")
            with self.assertRaises(MODULE.ManifestError):
                MODULE.junit_summary(directory)

        for value in ("-1", "2", "oops"):
            report.write_text('<testsuite tests="' + value + '"><testcase/></testsuite>', encoding="utf-8")
            with self.assertRaises(MODULE.ManifestError):
                MODULE.junit_summary(directory)

    @MODULE.trace_operation
    def prepare_review_run(self) -> tuple[argparse.Namespace, dict]:
        """创建命令与评审组合候选，返回 CLI 参数和可修改的零阻断评审输入。"""
        args = self.prepare_run()
        self.state["gates"]["review"] = {
            "kind": "review", "version": 1, "domains": ["source"],
            "inputPaths": ["src/**"], "definitionFiles": ["gate/java.txt"], "toolchains": ["python"],
        }
        self.state["snapshot"] = self.snapshot()
        MODULE.write_json(Path(args.state), self.state)
        review_path = self.repo / ".agent-work-state/review.json"
        entry = {"name": "review", "gate": "review", "gateKey": self.state["snapshot"]["gates"]["review"]["key"],
                 "kind": "review", "status": "pass", "blockingFindings": 0,
                 "findings": {"high": 2, "团队建议": 3}}
        MODULE.write_json(review_path, {"evidence": [entry]})
        args.review_evidence = str(review_path)
        return args, entry

    @MODULE.trace_operation
    def publish_checkpoint_fixture(self, args: argparse.Namespace, manifest: dict) -> dict:
        """在隔离仓库重建内容寻址夹具，返回状态；当前候选快照保持不变。

        用于模拟历史版本或穿过外层 hash 后的语义损坏，绝不修改实际项目缓存。
        """
        state = MODULE.load_json(Path(args.state))
        digest = MODULE.json_hash(manifest)
        checkpoint = Path(args.state).parent / "checkpoints" / (digest + ".json")
        MODULE.write_json(checkpoint, manifest)
        MODULE.write_json(Path(args.manifest), manifest)
        pointer = {"manifest": MODULE.normalize_relative(self.repo, Path(args.manifest)),
                   "checkpoint": MODULE.normalize_relative(self.repo, checkpoint),
                   "manifestHash": digest, "candidateHash": manifest["snapshot"]["candidateHash"],
                   "verifiedAt": manifest["verifiedAt"],
                   "gateKeys": {item["gate"]: item["gateKey"] for item in manifest["evidence"]}}
        state["evidenceCheckpoint"] = pointer
        state["lastGreen"] = dict(pointer)
        MODULE.write_json(Path(args.state), state)
        return state

    @MODULE.trace_operation
    def legacy_review_fixture(self, args: argparse.Namespace) -> dict:
        """由已执行夹具构造旧评审格式，仅替换旧策略身份和计数，保留真实命令证据。"""
        manifest = MODULE.load_json(Path(args.manifest))
        review_gate = manifest["snapshot"]["gates"]["review"]
        review_gate["identity"].pop("reviewPolicyVersion")
        review_gate["key"] = MODULE.sha256_bytes(json.dumps(review_gate["identity"], sort_keys=True).encode("utf-8"))
        entry = next(item for item in manifest["evidence"] if item["kind"] == "review")
        entry["gateKey"] = review_gate["key"]
        entry.pop("blockingFindings")
        entry["findings"] = {"blocker": 0, "high": 0, "medium": 2, "low": 3}
        return manifest

    @MODULE.trace_operation
    def test_review_evidence_is_reusable_with_identical_retry_arguments(self) -> None:
        """显式零阻断可保留任意分级建议，命令失败后的同参重试不重跑绿色证据。"""
        args, entry = self.prepare_review_run()
        original = MODULE.run_process
        command_to_fail = self.state["gates"]["java"]["command"]

        @MODULE.trace_operation
        def fail_command(repo, command, environment=None, **kwargs):
            """只令 command Gate 失败，让 review 成为可复用的部分 checkpoint。"""
            if command == command_to_fail:
                return subprocess.CompletedProcess(command, 1, "", "failed")
            return original(repo, command, environment, **kwargs)

        with patch.object(MODULE, "run_process", side_effect=fail_command), self.assertRaises(MODULE.ManifestError):
            self.run_quietly(args)
        self.run_quietly(args)
        self.run_quietly(args)
        self.assertEqual("1", (self.repo / ".agent-work-state/java.count").read_text())
        manifest = MODULE.load_json(Path(args.manifest))
        self.assertEqual({"high": 2, "团队建议": 3},
                         next(item for item in manifest["evidence"] if item["kind"] == "review")["findings"])
        entries = copy.deepcopy(manifest["evidence"])
        next(item for item in entries if item["kind"] == "review").pop("findings")
        MODULE.validate_bound_entries(self.repo, entries, manifest["snapshot"], require_complete=True)
        entry["findings"]["团队建议"] = -1
        MODULE.write_json(Path(args.review_evidence), {"evidence": [entry]})
        with self.assertRaises(MODULE.ManifestError):
            self.run_quietly(args)
        entry["findings"] = {"low": 1}
        entry["blockingFindings"] = 1
        MODULE.write_json(Path(args.review_evidence), {"evidence": [entry]})
        with self.assertRaises(MODULE.ManifestError):
            self.run_quietly(args)

    @MODULE.trace_operation
    def test_review_counts_use_same_rules_for_binding_and_cache(self) -> None:
        """初次绑定与缓存检查都接受可选分级，但拒绝缺失、布尔、负数及非整数阻断数。"""
        _, base = self.prepare_review_run()
        snapshot = self.state["snapshot"]
        artifact_root = self.repo / ".agent-work-state/artifacts"
        valid = [{"blockingFindings": 0}, {"blockingFindings": 0, "findings": {}},
                 {"blockingFindings": 0, "findings": {"blocker": 7, "自定义等级": 2}}]
        invalid = [{}, *({"blockingFindings": value} for value in (None, True, False, -1, 0.0, "0", 1)),
                   {"blockingFindings": 1, "findings": {"low": 1}},
                   *({"blockingFindings": 0, "findings": value} for value in
                     (None, [], "", {"": 0}, {" ": 0}, {1: 0}, {"low": True}, {"low": -1},
                      {"low": 0.0}, {"low": "0"}))]
        for accepted, cases in ((True, valid), (False, invalid)):
            for fields in cases:
                entry = {key: value for key, value in base.items() if key not in {"blockingFindings", "findings"}}
                entry.update(fields)
                with self.subTest(accepted=accepted, fields=fields):
                    if accepted:
                        bound = MODULE.bind_evidence(self.repo, {"evidence": [entry]}, snapshot,
                                                     artifact_root, require_complete=False)
                        MODULE.validate_bound_entries(self.repo, bound, snapshot)
                    else:
                        with self.assertRaises(MODULE.ManifestError):
                            MODULE.bind_evidence(self.repo, {"evidence": [entry]}, snapshot,
                                                 artifact_root, require_complete=False)
                        with self.assertRaises(MODULE.ManifestError):
                            MODULE.validate_bound_entries(self.repo, [entry], snapshot, allow_stale_reviews=True)

    @MODULE.trace_operation
    def test_review_policy_invalidates_only_review_in_mixed_checkpoint(self) -> None:
        """升级评审策略后必须提供新评审；真实命令计数及证据保持不变，旧缓存不被改写。"""
        args, _ = self.prepare_review_run()
        self.run_quietly(args)
        current = MODULE.load_json(Path(args.manifest))
        legacy = self.legacy_review_fixture(args)
        self.assertEqual(1, current["schemaVersion"])
        self.assertEqual(2, current["snapshot"]["gates"]["review"]["identity"]["reviewPolicyVersion"])
        self.assertNotIn("reviewPolicyVersion", current["snapshot"]["gates"]["java"]["identity"])
        self.assertNotEqual(legacy["snapshot"]["gates"]["review"]["key"], current["snapshot"]["gates"]["review"]["key"])
        state = self.publish_checkpoint_fixture(args, legacy)
        legacy_path = self.repo / state["evidenceCheckpoint"]["checkpoint"]
        old_bytes = legacy_path.read_bytes()
        self.assertEqual(legacy, MODULE.load_checkpoint(self.repo, state))
        with self.assertRaises(MODULE.ManifestError):
            MODULE.check_manifest(args)
        missing = copy.copy(args)
        missing.review_evidence = None
        with self.assertRaisesRegex(MODULE.ManifestError, "review-evidence"):
            self.run_quietly(missing)
        self.assertEqual("1", (self.repo / ".agent-work-state/java.count").read_text())
        self.run_quietly(args)
        after = MODULE.load_json(Path(args.manifest))
        before_command = next(item for item in current["evidence"] if item["kind"] == "command")
        self.assertEqual(before_command, next(item for item in after["evidence"] if item["kind"] == "command"))
        self.assertEqual(current["snapshot"]["gates"]["java"], after["snapshot"]["gates"]["java"])
        self.assertEqual("1", (self.repo / ".agent-work-state/java.count").read_text())
        self.assertEqual(old_bytes, legacy_path.read_bytes())

    @MODULE.trace_operation
    def test_current_review_corruption_is_rejected_before_reuse(self) -> None:
        """当前格式即使外层 hash 重建，缺字段或非法计数仍在恢复、重试和最终检查时拒绝。"""
        args, _ = self.prepare_review_run()
        self.run_quietly(args)
        pristine = MODULE.load_json(Path(args.manifest))
        for field, value in (("missing", None), ("blockingFindings", True), ("blockingFindings", 1),
                             ("findings", {"low": -1})):
            manifest = copy.deepcopy(pristine)
            entry = next(item for item in manifest["evidence"] if item["kind"] == "review")
            if field == "missing":
                entry.pop("blockingFindings")
            else:
                entry[field] = value
            state = self.publish_checkpoint_fixture(args, manifest)
            with self.subTest(field=field, value=value):
                with self.assertRaises(MODULE.ManifestError):
                    MODULE.load_checkpoint(self.repo, state)
                with self.assertRaises(MODULE.ManifestError):
                    self.run_quietly(args)
                with self.assertRaises(MODULE.ManifestError):
                    MODULE.check_manifest(args)
        self.assertEqual("1", (self.repo / ".agent-work-state/java.count").read_text())

    @MODULE.trace_operation
    def test_stale_review_does_not_hide_checkpoint_or_binding_corruption(self) -> None:
        """旧策略不能豁免容器完整性、状态身份、Gate key 或原命令报告校验。"""
        args, _ = self.prepare_review_run()
        self.run_quietly(args)
        legacy = self.legacy_review_fixture(args)
        for corruption in ("hash", "identity", "gate", "pointer", "report"):
            manifest = copy.deepcopy(legacy)
            if corruption == "identity":
                manifest["goal"] = "another-goal"
            if corruption == "gate":
                next(item for item in manifest["evidence"] if item["kind"] == "review")["gateKey"] = "mismatch"
            if corruption == "report":
                next(item for item in manifest["evidence"] if item["kind"] == "command")["reports"][0]["hash"] = "mismatch"
            state = self.publish_checkpoint_fixture(args, manifest)
            if corruption == "hash":
                manifest["verifiedAt"] = "tampered"
                MODULE.write_json(self.repo / state["evidenceCheckpoint"]["checkpoint"], manifest)
            if corruption == "pointer":
                state["evidenceCheckpoint"]["gateKeys"]["review"] = "mismatch"
            with self.subTest(corruption=corruption), self.assertRaises(MODULE.ManifestError):
                MODULE.load_checkpoint(self.repo, state)

    @MODULE.trace_operation
    def test_review_policy_field_cannot_disguise_current_corruption(self) -> None:
        """删除策略但保留新 key、显式 null 或未知版本，均不能归类为可信旧评审。"""
        args, _ = self.prepare_review_run()
        self.run_quietly(args)
        pristine = MODULE.load_json(Path(args.manifest))
        for policy in ("missing", None, False, True, 0, 3, "2"):
            manifest = copy.deepcopy(pristine)
            gate = manifest["snapshot"]["gates"]["review"]
            if policy == "missing":
                gate["identity"].pop("reviewPolicyVersion")
            else:
                gate["identity"]["reviewPolicyVersion"] = policy
                gate["key"] = MODULE.sha256_bytes(json.dumps(gate["identity"], sort_keys=True).encode("utf-8"))
                next(item for item in manifest["evidence"] if item["kind"] == "review")["gateKey"] = gate["key"]
            state = self.publish_checkpoint_fixture(args, manifest)
            with self.subTest(policy=policy), self.assertRaises(MODULE.ManifestError):
                MODULE.load_checkpoint(self.repo, state)

    @MODULE.trace_operation
    def test_debug_logging_keeps_json_output_and_clears_context(self) -> None:
        """DEBUG 下首次运行及缓存重试均输出 JSON；嵌套追踪一致，失败不泄露载荷或残留上下文。"""
        args, _ = self.prepare_review_run()
        output, diagnostic = io.StringIO(), io.StringIO()
        handler = logging.StreamHandler(diagnostic)
        with patch.object(MODULE.LOGGER, "handlers", [handler]):
            previous_level = MODULE.LOGGER.level
            MODULE.LOGGER.setLevel(logging.DEBUG)
            try:
                with contextlib.redirect_stdout(output):
                    MODULE.run_gates(args)
                    MODULE.run_gates(args)
                self.assertTrue(output.getvalue().splitlines())
                for line in output.getvalue().splitlines():
                    self.assertIsInstance(json.loads(line), dict)
                token = MODULE.TRACE_ID.set("parent-trace")
                sensitive = "private-token-do-not-log"

                @MODULE.trace_operation
                def fail_with_payload(payload: str) -> None:
                    """模拟含敏感载荷的异常，验证包装器原样传播且日志不读取载荷。"""
                    MODULE.validate_review_findings({"blockingFindings": 0, "findings": {payload: 1}}, "review")
                    raise RuntimeError(payload)

                try:
                    with self.assertRaisesRegex(RuntimeError, sensitive):
                        fail_with_payload(sensitive)
                    self.assertEqual("parent-trace", MODULE.TRACE_ID.get())
                finally:
                    MODULE.TRACE_ID.reset(token)
                with self.assertRaises(RuntimeError):
                    fail_with_payload(sensitive)
                self.assertIsNone(MODULE.TRACE_ID.get())
            finally:
                MODULE.LOGGER.setLevel(previous_level)
        logs = diagnostic.getvalue()
        self.assertIn("操作开始", logs)
        self.assertIn("result=returned", logs)
        self.assertIn("result=failed", logs)
        self.assertIn("result=cache_hit", logs)
        self.assertIn("trace_id=parent-trace", logs)
        self.assertNotIn(sensitive, logs)
        self.assertNotIn("blockingFindings", logs)
        self.assertNotIn("Traceback", logs)

    @MODULE.trace_operation
    def test_cli_failure_logs_one_safe_stack(self) -> None:
        """最终 CLI 边界只记录一次不含异常载荷的安全栈，保持既有失败退出码。"""
        parser = MODULE.build_parser()
        sensitive = "secret-in-error-payload"
        diagnostic = io.StringIO()

        @MODULE.trace_operation
        def reject_request(args) -> None:
            """注入链式错误以检查最终日志；参数不参与日志。"""
            try:
                raise ValueError(sensitive)
            except ValueError as cause:
                raise MODULE.ManifestError(sensitive) from cause

        args = argparse.Namespace(handler=reject_request)
        with patch.object(MODULE, "build_parser", return_value=parser), \
             patch.object(parser, "parse_args", return_value=args), \
             patch.object(MODULE.LOGGER, "handlers", [logging.StreamHandler(diagnostic)]), \
             contextlib.redirect_stderr(io.StringIO()):
            previous_level = MODULE.LOGGER.level
            MODULE.LOGGER.setLevel(logging.DEBUG)
            try:
                self.assertEqual(2, MODULE.main())
            finally:
                MODULE.LOGGER.setLevel(previous_level)
        self.assertEqual(1, diagnostic.getvalue().count("frames="))
        self.assertIn("reject_request:", diagnostic.getvalue())
        self.assertNotIn(sensitive, diagnostic.getvalue())
        self.assertIn("result=failed", diagnostic.getvalue())
        self.assertIn("操作结束 operation=main", diagnostic.getvalue())
        self.assertIn("result=returned", diagnostic.getvalue())
        self.assertNotIn("result=passed", diagnostic.getvalue())
        self.assertIsNone(MODULE.TRACE_ID.get())

    def test_junit_manifest_requires_exact_xml_set_and_summary(self) -> None:
        """JUnit 证据缺失、复制目录额外 XML 和统计篡改必须被共同校验器发现。"""
        args = self.prepare_run()
        xml = '<testsuite tests="1"><testcase name="passing"/></testsuite>'
        self.state["gates"]["java"]["command"] = [sys.executable, "-c",
            "from pathlib import Path; p=Path('reports'); p.mkdir(exist_ok=True); "
            "p.joinpath('TEST-pass.xml').write_text(" + repr(xml) + ")"]
        self.state["gates"]["java"]["junitPolicy"] = {"directory": "reports", "minTests": 1, "maxSkipped": 0}
        self.state["snapshot"] = self.snapshot()
        MODULE.write_json(Path(args.state), self.state)
        self.run_quietly(args)
        manifest = MODULE.load_json(Path(args.manifest))
        for field in ("junit", "summary"):
            entries = copy.deepcopy(manifest["evidence"])
            if field == "junit":
                entries[0].pop("junit")
            else:
                entries[0]["junit"].pop("summary")
            with self.subTest(field=field), self.assertRaises(MODULE.ManifestError):
                MODULE.validate_bound_entries(self.repo, entries, manifest["snapshot"])
        junit = manifest["evidence"][0]["junit"]
        (self.repo / junit["root"] / "TEST-extra.xml").write_text(xml, encoding="utf-8")
        with self.assertRaises(MODULE.ManifestError):
            MODULE.check_manifest(args)

    def test_gate_definition_change_uses_new_artifacts(self) -> None:
        """同一文件候选下 Gate 版本改变，应重新执行并与旧工件共存。"""
        args = self.prepare_run()
        self.run_quietly(args)
        state = MODULE.load_json(Path(args.state))
        old_manifest = MODULE.load_json(Path(args.manifest))
        state["gates"]["java"]["version"] = 2
        MODULE.write_json(Path(args.state), state)
        with contextlib.redirect_stdout(io.StringIO()):
            MODULE.snapshot_state(args)
        self.run_quietly(args)
        new_manifest = MODULE.load_json(Path(args.manifest))
        self.assertEqual("2", (self.repo / ".agent-work-state/java.count").read_text())
        self.assertNotEqual(old_manifest["evidence"][0]["reports"][0]["path"], new_manifest["evidence"][0]["reports"][0]["path"])

    def test_default_governance_discovers_renamed_skill_and_new_rules(self) -> None:
        """技能目录改名及新增规则被自动发现，已跟踪删除仍改变治理指纹。"""
        self.state.pop("governanceFiles")
        renamed = self.repo / "project-skills/renamed"
        rule = renamed / "references/new-rule.md"
        rule.parent.mkdir(parents=True)
        rule.write_text("rule\n", encoding="utf-8")
        with patch.object(MODULE, "SKILL_ROOT", renamed):
            before = self.snapshot()
            self.assertIn("project-skills/renamed/references/new-rule.md", [entry["path"] for entry in before["governanceFiles"]])
            subprocess.run(["git", "add", "project-skills"], cwd=self.repo, check=True)
            tracked = self.snapshot()
            rule.unlink()
            deleted = self.snapshot()
            self.assertNotEqual(tracked["governanceHash"], deleted["governanceHash"])
            self.assertTrue(any(entry["status"] == "deleted" for entry in deleted["governanceFiles"]))

    def test_explicit_missing_governance_is_rejected(self) -> None:
        """自动发现不放宽用户或 Gate 显式依赖的路径校验。"""
        for target in (self.state, self.state["gates"]["java"]):
            with self.subTest(target="state" if target is self.state else "gate"):
                target["governanceFiles"] = ["missing-rule.md"]
                with self.assertRaisesRegex(MODULE.ManifestError, "显式路径不存在"):
                    self.snapshot()
                target["governanceFiles"] = []

    def test_default_path_is_ignored_but_explicit_path_invalidates(self) -> None:
        """同一可执行文件下无关 PATH 变化不失效，显式声明后必须失效。"""
        first_path = os.environ.get("PATH", "")
        second_path = first_path + os.pathsep + str(self.repo / "unused-bin")
        for declared in (False, True):
            if declared:
                self.state["toolchains"][0]["environmentKeys"] = ["PATH"]
            with patch.dict(os.environ, {"PATH": first_path}):
                before = self.snapshot()
            with patch.dict(os.environ, {"PATH": second_path}):
                after = self.snapshot()
            with self.subTest(declared=declared):
                self.assertEqual(declared, "PATH" in before["toolchains"]["python"]["environmentKeys"])
                self.assertEqual(not declared, before["gates"]["java"]["key"] == after["gates"]["java"]["key"])

    def test_dangling_symlink_is_not_a_deleted_candidate(self) -> None:
        """指向不存在目标的链接必须拒绝，不能被记为合法删除。"""
        alias = self.repo / "src/dangling"
        try:
            alias.symlink_to(self.repo / "missing-target")
        except OSError as exc:
            self.skipTest(f"当前环境无法创建测试符号链接: {exc}")
        try:
            self.assertFalse(alias.exists())
            with self.assertRaisesRegex(MODULE.ManifestError, "符号链接"):
                self.snapshot()
        finally:
            alias.unlink()

    def test_cached_run_preserves_checkpoint_and_verified_time(self) -> None:
        """时间流逝不为相同证据制造 checkpoint，也不伪造一次新验证。"""
        args = self.prepare_run()
        with patch.object(MODULE, "utc_now", return_value="2026-09-15T00:00:00+00:00"):
            self.run_quietly(args)
        before = MODULE.load_json(Path(args.manifest))
        checkpoints = set(Path(args.state).parent.joinpath("checkpoints").glob("*.json"))
        with patch.object(MODULE, "utc_now", return_value="2026-09-15T00:01:00+00:00"):
            self.run_quietly(args)
        self.assertEqual(before, MODULE.load_json(Path(args.manifest)))
        self.assertEqual(checkpoints, set(Path(args.state).parent.joinpath("checkpoints").glob("*.json")))
        self.assertEqual("1", (self.repo / ".agent-work-state/java.count").read_text())
        # 时间戳仍受完整 hash 保护，不以排除字段换取稳定文件名。
        state = MODULE.load_json(Path(args.state))
        checkpoint = self.repo / state["evidenceCheckpoint"]["checkpoint"]
        changed = MODULE.load_json(checkpoint)
        changed["verifiedAt"] = "2099-01-01T00:00:00+00:00"
        MODULE.write_json(checkpoint, changed)
        with self.assertRaisesRegex(MODULE.ManifestError, "checkpoint hash"):
            MODULE.load_checkpoint(self.repo, state)

    def test_process_decodes_declared_encoding_and_tolerates_invalid_bytes(self) -> None:
        """GBK 输出按显式编码还原，默认容错行为也不会因非法字节崩溃。"""
        payload = "验证通过".encode("gbk")
        command = [sys.executable, "-c", "import sys; sys.stdout.buffer.write(" + repr(payload) + ")"]
        completed = MODULE.run_process(self.repo, command, encoding="gbk")
        self.assertEqual(0, completed.returncode)
        self.assertEqual("验证通过", completed.stdout)
        malformed = MODULE.run_process(self.repo, [sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'\\xff')"])
        self.assertEqual("\ufffd", malformed.stdout)

    def test_bom_encoding_without_bom_fails_in_main_thread(self) -> None:
        """缺少 BOM 的 stdout/stderr 明确报错，不能返回 None 或在线程中丢失异常。"""
        for encoding, payload in (("utf-16", b"AB"), ("utf-32", b"ABCD")):
            for stream in ("stdout", "stderr"):
                command = [sys.executable, "-c", "import sys; sys." + stream + ".buffer.write(" + repr(payload) + ")"]
                with self.subTest(encoding=encoding, stream=stream):
                    with self.assertRaisesRegex(MODULE.ManifestError, "命令输出无法按"):
                        MODULE.run_process(self.repo, command, encoding=encoding)

    def test_bom_encoding_preserves_output_and_normalizes_newlines(self) -> None:
        """有效 BOM 编码还原双输出流，空流和文本换行保持原契约。"""
        for encoding in ("utf-16", "utf-32"):
            payload = "验证通过\r\n下一行\r结束".encode(encoding)
            stderr = "诊断\n".encode(encoding)
            command = [sys.executable, "-c",
                       "import sys; sys.stdout.buffer.write(" + repr(payload) + "); sys.stderr.buffer.write(" + repr(stderr) + ")"]
            with self.subTest(encoding=encoding):
                completed = MODULE.run_process(self.repo, command, encoding=encoding)
                self.assertEqual(0, completed.returncode)
                self.assertEqual("验证通过\n下一行\n结束", completed.stdout)
                self.assertEqual("诊断\n", completed.stderr)
                empty = MODULE.run_process(self.repo, [sys.executable, "-c", "pass"], encoding=encoding)
                self.assertEqual(("", ""), (empty.stdout, empty.stderr))

    def test_output_encoding_is_bound_to_toolchain_and_gate(self) -> None:
        """解码声明改变工具链指纹及 Gate key，未知编码在冻结时拒绝。"""
        before = self.snapshot()
        self.state["toolchains"][0]["outputEncoding"] = "gbk"
        after_tool = self.snapshot()
        self.assertNotEqual(before["gates"]["java"]["key"], after_tool["gates"]["java"]["key"])
        self.state["gates"]["java"]["outputEncoding"] = "gbk"
        after_gate = self.snapshot()
        self.assertNotEqual(after_tool["gates"]["java"]["key"], after_gate["gates"]["java"]["key"])
        for invalid in (None, "", "unknown-codec", "base64_codec"):
            self.state["gates"]["java"]["outputEncoding"] = invalid
            with self.subTest(invalid=invalid), self.assertRaises(MODULE.ManifestError):
                self.snapshot()

    def test_gate_captures_non_utf8_output_with_declared_encoding(self) -> None:
        """受控命令的日志按 Gate 声明还原并参与真实证据校验。"""
        args = self.prepare_run()
        gate = self.state["gates"]["java"]
        payload = "验证通过".encode("gbk")
        gate["command"] = [sys.executable, "-c", "import sys; sys.stdout.buffer.write(" + repr(payload) + ")"]
        gate["outputEncoding"] = "gbk"
        self.state["snapshot"] = self.snapshot()
        MODULE.write_json(Path(args.state), self.state)
        self.run_quietly(args)
        manifest = MODULE.load_json(Path(args.manifest))
        report = self.repo / manifest["evidence"][0]["reports"][0]["path"]
        self.assertIn("验证通过", report.read_text(encoding="utf-8"))

    def test_junit_multiple_suites_and_skipped_policy(self) -> None:
        """多个 suite 共同计数；跳过数超过声明阈值时拒绝绑定。"""
        reports = self.repo / "reports"
        reports.mkdir()
        xml = '<testsuites><testsuite tests="1"><testcase/></testsuite><testsuite tests="1" skipped="1"><testcase><skipped/></testcase></testsuite></testsuites>'
        (reports / "TEST-multiple.xml").write_text(xml, encoding="utf-8")
        self.assertEqual({"suites": 2, "tests": 2, "failures": 0, "errors": 0, "skipped": 1}, MODULE.junit_summary(reports))
        artifact = self.repo / ".agent-work-state/artifacts/junit-policy"
        with self.assertRaisesRegex(MODULE.ManifestError, "不满足 Gate policy"):
            MODULE.junit_snapshot(self.repo, "reports", artifact, {"minTests": 2, "maxSkipped": 0})
        evidence = MODULE.junit_snapshot(self.repo, "reports", artifact, {"minTests": 2, "maxSkipped": 1})
        self.assertEqual(1, evidence["summary"]["skipped"])

    def test_junction_to_sensitive_directory_is_rejected(self) -> None:
        """Windows junction 或平台符号链接不能把禁止目录映射成候选输入。"""
        alias = self.repo / "src/linked"
        if os.name == "nt":
            # 只在独立 fixture 建立 junction，目标不包含真实用户数据。
            result = subprocess.run(["cmd", "/c", "mklink", "/J", str(alias), str(self.repo / ".claude")], capture_output=True)
            if result.returncode != 0:
                self.skipTest("当前 Windows 环境无法创建测试 junction")
        else:
            try:
                alias.symlink_to(self.repo / ".claude", target_is_directory=True)
            except OSError as exc:
                self.skipTest(f"当前环境无法创建测试符号链接: {exc}")
        try:
            with self.assertRaises(MODULE.ManifestError):
                MODULE.ensure_safe_local_path(self.repo, alias / "secret.txt", "candidate")
        finally:
            # 仅移除测试链接本身，绝不递归删除链接目标。
            alias.rmdir() if os.name == "nt" else alias.unlink()


if __name__ == "__main__":
    unittest.main()
