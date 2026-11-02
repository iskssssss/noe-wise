"""覆盖报告的证据边界；夹具只使用临时目录，不读取真实凭据或调用模型。"""

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path

import reporting


class ReportingTest(unittest.TestCase):
    """验证计划分母、语义复核绑定、公开脱敏、未知计量和精确工程证据。"""

    def setUp(self):
        """每个用例拥有独立文件目录，避免修改宿主共享状态。"""
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.suite_path = self.root / "suite.json"
        self.batch_path = self.root / "batch.json"
        self.suite = {"schemaVersion": 1, "suiteId": "noewise-memory-v1", "repeats": 3,
                      "scenarios": [{"id": f"M{index:02}", "name": f"场景{index}"} for index in range(1, 25)]}
        self.write(self.suite_path, self.suite)
        self.batch = {"schemaVersion": 1, "suiteId": self.suite["suiteId"], "batchId": "test-batch",
                      "startedAt": "2026-10-11T01:00:00+00:00", "suiteHash": reporting.sha256(self.suite_path),
                      "plannedSamples": 72, "samples": [], "model": {"name": "test-model"}, "prompt": {"sha256": "p"}}
        self.write(self.batch_path, self.batch)

    def tearDown(self):
        """只清理测试自己创建的临时资源。"""
        self.temp.cleanup()

    def write(self, path, value):
        """写入当前用例的 UTF-8 JSON 夹具。"""
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def sample(self, scenario="M01", assertions=None, **extra):
        """构造合法最小样本，显式保存批次目录与文件之间的身份关系。"""
        value = {"schemaVersion": 1, "sampleId": f"{scenario}-1", "scenario": scenario, "repeat": 1,
                 "outcome": "PASS", "stages": [], "runtimeCalls": [], "wireCalls": [],
                 "assertions": assertions if assertions is not None else [self.assertion("EXTRACTION")], **extra}
        name = f"sample-{scenario}-1.json"
        self.write(self.root / name, value)
        self.batch["samples"].append({"sampleId": value["sampleId"], "path": name, "outcome": value["outcome"]})
        self.write(self.batch_path, self.batch)
        return value

    def assertion(self, dimension, method="DETERMINISTIC", status="PASS", code="check"):
        """创建明确方法的断言，避免把启发式混同工程结果。"""
        return {"dimension": dimension, "code": code, "method": method, "outcome": status,
                "expected": "预期", "observed": "证据"}

    def loaded(self):
        """读取当前用例绑定输入。"""
        return reporting.load_batch(self.batch_path, self.suite_path)

    def test_all_planned_samples_remain_in_denominator(self):
        """只执行一个样本时保留全部 72 个计划位置和 71 个未完成。"""
        self.sample()
        report = reporting.build_report(self.loaded(), {})
        self.assertEqual(report["overall"]["planned"], 72)
        self.assertEqual(report["overall"]["PASS"], 1)
        self.assertEqual(report["overall"]["NOT_RUN"], 71)
        self.assertAlmostEqual(report["overall"]["passRate"], 1 / 72)
        self.assertEqual(report["overall"]["attemptedPassRate"], 1)

    def test_heuristic_cannot_become_formal_pass(self):
        """回答词面满足预期但没有复核时，语义维度和总体保持未知。"""
        self.sample("M13", [self.assertion("RETRIEVAL"), self.assertion("ANSWER", "TEXT_HEURISTIC")])
        report = reporting.build_report(self.loaded(), {})
        self.assertEqual(report["samples"][36]["outcome"], "UNKNOWN")
        self.assertEqual(report["review"]["pending"], 1)

    def test_review_receipt_is_bound_and_can_rescore(self):
        """合法复核可覆盖词面假失败，但源断言保持历史结果。"""
        self.sample("M13", [self.assertion("RETRIEVAL"), self.assertion("ANSWER", "TEXT_HEURISTIC", "FAIL")],
                    outcome="FAIL", stages=[{"kind": "NATURAL_PROBE", "answer": "合规的一段文字", "retrievals": []}])
        loaded = self.loaded()
        template = reporting.review_template(loaded)
        template["reviews"][0].update(outcome="PASS", reviewer="评测审阅者", reviewedAt="2026-10-11T02:00:00+00:00",
                                      reason="阅读完整回答，确认单段且无清单", evidenceRefs=["#/stages/0/answer"])
        path = self.root / "review.json"
        self.write(path, template)
        report = reporting.build_report(loaded, reporting.load_review(path, loaded))
        row = next(row for row in report["samples"] if row["sampleId"] == "M13-1")
        self.assertEqual(row["outcome"], "PASS")
        self.assertEqual(row["sourceOutcome"], "FAIL")
        self.assertEqual(row["assertions"][1]["sourceOutcome"], "FAIL")
        self.assertEqual(report["review"]["completed"], 1)

    def test_review_rejects_changed_sample_and_unresolvable_pointer(self):
        """收据不能用于修改后的样本，也不能引用不存在的回答。"""
        self.sample("M07", [self.assertion("ANSWER", "SEMANTIC_REVIEW_REQUIRED", "UNKNOWN")])
        loaded = self.loaded()
        template = reporting.review_template(loaded)
        entry = template["reviews"][0]
        entry.update(outcome="PASS", reviewer="审阅者", reviewedAt="2026-10-11T02:00:00+00:00",
                     reason="说明", evidenceRefs=["#/stages/99/answer"])
        path = self.root / "review.json"
        self.write(path, template)
        with self.assertRaises(reporting.ReportError):
            reporting.load_review(path, loaded)
        entry["sampleSha256"] = "wrong"
        self.write(path, template)
        with self.assertRaises(reporting.ReportError):
            reporting.load_review(path, loaded)

    def test_review_cannot_override_deterministic_assertion(self):
        """人工语义收据不拥有改写代码确定性事实的权限。"""
        self.sample()
        loaded = self.loaded()
        template = reporting.review_template(loaded)
        item = loaded["samples"][("M01", 1)]
        template["reviews"] = [{"sampleId": "M01-1", "sampleSha256": item["hash"], "dimension": "EXTRACTION",
                                "assertionCode": "check", "assertionId": reporting.assertion_key("M01-1", item["data"]["assertions"][0])[2],
                                "outcome": "UNKNOWN"}]
        path = self.root / "review.json"
        self.write(path, template)
        with self.assertRaises(reporting.ReportError):
            reporting.load_review(path, loaded)

    def test_missing_critical_dimension_cannot_pass(self):
        """场景涉及回答与检索，仅有检索断言不会升级为全场景成功。"""
        self.sample("M13", [self.assertion("RETRIEVAL")])
        report = reporting.build_report(self.loaded(), {})
        self.assertEqual(report["overall"]["UNKNOWN"], 1)

    def test_answer_text_cannot_infer_retrieval(self):
        """答案出现事实文本仍不能凭空生成 search_memory 或检索轨迹计数。"""
        self.sample("M07", [self.assertion("ANSWER")],
                    stages=[{"kind": "NATURAL_PROBE", "input": "预算？", "answer": "最终预算3500", "retrievals": []}])
        report = reporting.build_report(self.loaded(), {})
        row = next(row for row in report["samples"] if row["sampleId"] == "M07-1")
        self.assertEqual(row["stages"][0]["retrievalPerformedCount"], 0)
        self.assertFalse(any(ref["kind"] == "RETRIEVAL_PERFORMED" for ref in report["evidenceIndex"][0]["refs"]))

    def test_actual_candidate_fact_and_retrieval_refs(self):
        """候选、持久事实和轨迹索引指向实际样本字段，不复制内部载荷。"""
        self.sample(stages=[{"kind": "SETUP", "facts": [{"text": "事实"}], "jobs": [{"status": "SUCCEEDED"}],
                             "events": [{"payload": "PRIVATE-EVENT-SENTINEL"}], "retrievals": [{"fact_id": "f1"}]}],
                    wireCalls=[{"attempt": {"category": "MEMORY_EXTRACTION"}, "responseContent": "内部候选"}])
        report = reporting.build_report(self.loaded(), {})
        kinds = {ref["kind"] for ref in report["evidenceIndex"][0]["refs"]}
        self.assertTrue({"EXTRACTION_CANDIDATE_RESPONSE", "PERSISTED_FACT", "RETRIEVAL_PERFORMED"} <= kinds)
        self.assertNotIn("内部候选", json.dumps(report, ensure_ascii=False))
        self.assertNotIn("PRIVATE-EVENT-SENTINEL", json.dumps(report, ensure_ascii=False))

    def test_public_artifacts_redact_synthetic_and_real_credentials(self):
        """递归过滤认证字段及输入、回答、复核说明中的秘密。"""
        values = {"apiKey": "very-secret", "headers": {"Authorization": "Bearer secret123"},
                  "payload": {"internal": "raw"},
                  "answer": "密码是SYNTHETIC-SECRET-M21，Bearer abc123 password=unsafe",
                  "url": "https://user:pass@example.test/path?token=x"}
        encoded = json.dumps(reporting.public(values), ensure_ascii=False)
        for secret in ("very-secret", "secret123", "raw", "SYNTHETIC-SECRET-M21", "abc123", "unsafe", "user:pass", "token=x"):
            self.assertNotIn(secret, encoded)
        self.assertIn(reporting.REDACTED, encoded)

    def test_missing_usage_and_price_remain_unknown(self):
        """缺少输出 Tokens 不变成零，wire 观测不会重复加到 Runtime 用量。"""
        self.sample(runtimeCalls=[{"usage": {"inputTokens": 12}}], wireCalls=[{"rawUsage": {"usage.prompt_tokens": 12}}])
        usage = reporting.build_report(self.loaded(), {})["usage"]
        self.assertEqual(usage["inputTokens"], 12)
        self.assertIsNone(usage["outputTokens"])
        self.assertIsNone(usage["totalTokens"])
        self.assertIsNone(usage["cost"])
        self.assertEqual(usage["runtimeCallCount"], 1)
        self.assertEqual(usage["wireCallCount"], 1)

    def test_known_usage_can_price_once(self):
        """单价显式提供时只计算一份真实调用成本。"""
        self.sample(runtimeCalls=[{"usage": {"inputTokens": 100, "outputTokens": 50}}], wireCalls=[{}])
        usage = reporting.build_report(self.loaded(), {}, {"currency": "CNY", "inputPerMillion": 2, "outputPerMillion": 4})["usage"]
        self.assertEqual(usage["totalTokens"], 150)
        self.assertAlmostEqual(usage["cost"], 0.0004)

    def test_batch_ledger_includes_unassigned_and_deduplicates_attempts(self):
        """停止时未关联样本的调用仍计入批次；重复捕获同一 attempt 不重复累计。"""
        self.sample(runtimeCalls=[{"attemptId": "a", "usage": {"inputTokens": 1, "outputTokens": 2}}])
        complete = {"attemptId": "a", "usage": {"inputTokens": 1, "outputTokens": 2}}
        self.batch["runtimeCalls"] = [complete, complete, {"attemptId": "inflight", "usage": {"inputTokens": 3}}]
        self.batch["wireCalls"] = [{}, {}]
        self.write(self.batch_path, self.batch)
        report = reporting.build_report(self.loaded(), {})
        self.assertEqual(report["usage"]["callScope"], "BATCH")
        self.assertEqual(report["usage"]["runtimeCallCount"], 2)
        self.assertEqual(report["usage"]["inputTokens"], 4)
        self.assertIsNone(report["usage"]["outputTokens"])
        self.assertEqual(report["usage"]["wireCallCount"], 2)

    def test_secret_in_assertion_code_preserves_review_binding(self):
        """断言编码中的合成秘密被隐藏后仍以摘要准确关联原始检查。"""
        self.sample("M07", [self.assertion("ANSWER", "TEXT_HEURISTIC", "PASS", "FORBIDDEN:SYNTHETIC-SECRET-M21")],
                    stages=[{"answer": "没有披露秘密"}])
        loaded = self.loaded()
        template = reporting.public(reporting.review_template(loaded))
        self.assertNotIn("SYNTHETIC-SECRET-M21", json.dumps(template))
        template["reviews"][0].update(outcome="PASS", reviewer="审阅者", reviewedAt="2026-10-11T02:00:00+00:00",
                                      reason="确认无秘密披露", evidenceRefs=["#/stages/0/answer"])
        path = self.root / "review.json"
        self.write(path, template)
        self.assertEqual(len(reporting.load_review(path, loaded)), 1)

    def test_complex_assertion_observation_is_indexed_without_raw_data(self):
        """嵌套观测不通过断言字段进入公开报告，保留结构和真实指针。"""
        assertion = self.assertion("EXTRACTION")
        assertion["observed"] = {"arbitraryInternalField": "PRIVATE-OBSERVATION-SENTINEL"}
        self.sample(assertions=[assertion])
        report = reporting.build_report(self.loaded(), {})
        self.assertNotIn("PRIVATE-OBSERVATION-SENTINEL", json.dumps(report))
        self.assertEqual(report["samples"][0]["assertions"][0]["observed"]["evidenceRef"], "#/assertions/0/observed")

    def test_duplicate_assertion_codes_have_distinct_review_ids(self):
        """多个阶段同一编码的语义检查独立定位，不能被一个收据同时覆盖。"""
        self.sample("M07", [self.assertion("ANSWER", "TEXT_HEURISTIC"), self.assertion("ANSWER", "TEXT_HEURISTIC")])
        template = reporting.review_template(self.loaded())
        self.assertEqual(len(template["reviews"]), 2)
        self.assertNotEqual(template["reviews"][0]["assertionId"], template["reviews"][1]["assertionId"])

    def test_single_preset_fact_uses_actual_object_pointer(self):
        """Runtime预设写入返回单事实对象，索引不可虚构数组下标或按属性计数。"""
        self.sample(stages=[{"kind": "PRESET", "facts": {"ref": "f1", "text": "安全事实"}}])
        loaded = self.loaded()
        report = reporting.build_report(loaded, {})
        self.assertEqual(report["samples"][0]["stages"][0]["factCount"], 1)
        ref = next(ref for ref in report["evidenceIndex"][0]["refs"] if ref["kind"] == "PERSISTED_FACT")
        self.assertEqual(ref["pointer"], "#/stages/0/facts")
        self.assertEqual(reporting.resolve_pointer(loaded["samples"][("M01", 1)]["data"], ref["pointer"])["ref"], "f1")

    def test_suite_binding_path_escape_and_duplicate_samples_rejected(self):
        """拒绝错套件摘要、目录逃逸与重复样本，避免误用历史证据。"""
        self.batch["suiteHash"] = "wrong"
        self.write(self.batch_path, self.batch)
        with self.assertRaises(reporting.ReportError):
            self.loaded()
        self.batch["suiteHash"] = reporting.sha256(self.suite_path)
        self.sample()
        self.batch["samples"][0]["path"] = "../escape.json"
        self.write(self.batch_path, self.batch)
        with self.assertRaises(reporting.ReportError):
            self.loaded()
        self.batch["samples"][0]["path"] = "sample-M01-1.json"
        self.batch["samples"].append(self.batch["samples"][0].copy())
        self.write(self.batch_path, self.batch)
        with self.assertRaises(reporting.ReportError):
            self.loaded()

    def junit(self, name, body, tests=1):
        """创建目标 suite 的 XML 夹具。"""
        directory = self.root / "junit"
        directory.mkdir(exist_ok=True)
        path = directory / f"TEST-{name}.xml"
        path.write_text(f'<testsuite name="{name}" tests="{tests}">{body}</testsuite>', encoding="utf-8")
        return directory, path

    def test_junit_exact_suite_freshness_and_nonzero_tests(self):
        """仅精确目标 suite 被统计；旧文件和零测试都不能报告成功。"""
        directory, path = self.junit("ExactSuite", '<testcase name="works"/>')
        self.junit("Unrelated", '<testcase name="fails"><failure/></testcase>')
        boundary = "2026-10-11T01:00:00+00:00"
        os.utime(path, (2_000_000_000, 2_000_000_000))
        summary = reporting.junit_summary([directory], ["ExactSuite"], boundary)
        self.assertEqual(summary["outcome"], "PASS")
        self.assertEqual(len(summary["suites"]), 1)
        os.utime(path, (1, 1))
        with self.assertRaises(reporting.ReportError):
            reporting.junit_summary([directory], ["ExactSuite"], boundary)
        _, path = self.junit("Zero", "", tests=0)
        os.utime(path, (2_000_000_000, 2_000_000_000))
        self.assertEqual(reporting.junit_summary([directory], ["Zero"], boundary)["outcome"], "UNKNOWN")

    def test_junit_missing_exact_suite_is_unknown(self):
        """目录内存在其他测试不会填补请求的精确 suite。"""
        directory, _path = self.junit("Unrelated", '<testcase name="ok"/>')
        result = reporting.junit_summary([directory], ["Missing"], "2026-10-11T01:00:00+00:00")
        self.assertEqual(result["outcome"], "UNKNOWN")
        self.assertEqual(result["missingSuites"], ["Missing"])

    def test_explicit_frontend_junit_file_is_supported(self):
        """前端报告无 TEST 前缀时以精确文件与精确 suite 收集。"""
        path = self.root / "frontend.xml"
        path.write_text('<testsuites><testsuite name="src/api/memories.test.ts" tests="1"><testcase name="api works"/></testsuite></testsuites>')
        os.utime(path, (2_000_000_000, 2_000_000_000))
        result = reporting.junit_summary([], ["src/api/memories.test.ts"], "2026-10-11T01:00:00+00:00", [path])
        self.assertEqual(result["outcome"], "PASS")
        self.assertEqual(result["suites"][0]["counts"]["PASS"], 1)

    def test_engineering_summary_must_match_real_xml(self):
        """手填通过摘要与实际失败 testcase 不符时明确拒绝。"""
        directory = self.root / "engineering" / "module"
        directory.mkdir(parents=True)
        xml = directory / "TEST-ExactSuite.xml"
        xml.write_text('<testsuite name="ExactSuite" tests="1"><testcase name="x"><failure/></testcase></testsuite>')
        os.utime(xml, (2_000_000_000, 2_000_000_000))
        summary = directory.parent / "summary.json"
        self.write(summary, [{"module": "module", "class": "ExactSuite", "tests": 1, "failures": 0, "errors": 0, "skipped": 0}])
        with self.assertRaises(reporting.ReportError):
            reporting.engineering_summary([summary], [], [], "2026-10-11T01:00:00+00:00")

    def test_report_and_reassessment_never_overwrite_history(self):
        """新目录生成完整报告；重复使用目录拒绝并保持历史字节。"""
        self.sample()
        output = self.root / "report-01"
        argv = ["report", "--batch", str(self.batch_path), "--suite", str(self.suite_path), "--output", str(output)]
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(reporting.main(argv), 0)
            before = (output / "report.json").read_bytes()
            self.assertEqual(reporting.main(argv), 2)
        self.assertEqual((output / "report.json").read_bytes(), before)
        self.assertTrue((output / "evidence-index.json").is_file())
        self.assertTrue((output / "review-template.json").is_file())
        self.assertIn("计划 72 个样本", (output / "report.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
