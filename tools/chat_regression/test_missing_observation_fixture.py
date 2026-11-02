"""缺失观测夹具的离线准备与拒绝路径；不构造真实 Provider 绑定。"""

import copy
import json
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from missing_observation_fixture import build_fixture, document, finalize_instruction, prepare_budget, validate_fixture


class MissingObservationFixtureTest(unittest.TestCase):
    """确保准备失败先于任何外部调用，并保留冻结调用契约。"""

    def setUp(self):
        """使用最小合成冻结请求，避免读取环境或历史私有证据。"""
        self.source = {"target": {"binding": "synthetic-test-only"},
                       "generation": {"maxOutputTokens": "4096"},
                       "response": {"format": {"kind": "NATIVE_CONVERSATION"}},
                       "reasoning": "DISABLED", "toolChoice": "NONE",
                       "tools": [],
                       "messages": [{"role": "SYSTEM", "messageId": "instructions", "contents": []},
                                    {"role": "USER", "contents": [{"text": "private-business-sentinel"}]},
                                    {"role": "TOOL", "contents": [{"text": json.dumps({"output": {"structuredContent": {
                                        "state": "finalizing", "instruction": "actual-production-finalize-guidance\n保持原文"}}}, ensure_ascii=False)}]},
                                    {"role": "SYSTEM", "messageId": "answer-phase", "contents": []}]}
        self.fixture = build_fixture(self.source)

    def test_preserves_contract_replaces_business_history(self):
        """保留当前冻结契约，合成历史不能带入旧业务值或修改输入。"""
        for key in ("target", "generation", "response", "reasoning", "tools", "toolChoice"):
            self.assertEqual(self.source[key], self.fixture[key])
        self.assertNotIn("private-business-sentinel", document(self.fixture))
        self.assertEqual(4, len(self.source["messages"]))
        receipt = json.loads(self.fixture["messages"][4]["contents"][0]["text"].split("\n", 1)[1])
        content = receipt["output"]["structuredContent"]
        self.assertEqual([], content["public_presentation"]["results"])
        self.assertEqual(0, content["exit_code"])
        self.assertNotIn("raw_total", content)
        finalize = json.loads(self.fixture["messages"][6]["contents"][0]["text"].split("\n", 1)[1])
        self.assertEqual("finalizing", finalize["output"]["structuredContent"]["state"])
        self.assertEqual(finalize_instruction(self.source), finalize["output"]["structuredContent"]["instruction"])
        args = json.loads(self.fixture["messages"][3]["toolCalls"][0]["arguments"]["canonicalJson"])["arguments"]
        self.assertEqual("inline", args["mode"])
        self.assertEqual("python", args["language"])
        self.assertEqual(["00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002"], args["attachment_ids"])
        self.assertIn("/input/data/orders.csv", args["code"])
        self.assertIn("mkdir(parents=True, exist_ok=True)", args["code"])
        compile(args["code"], "synthetic_history.py", "exec")

    def test_rejects_missing_or_ambiguous_finalize_instruction(self):
        """缺少真实收尾指导或同一最后回执有多个候选时不自行添加规则。"""
        self.source["messages"][2]["contents"] *= 2
        with self.assertRaisesRegex(ValueError, "finalize_instruction_ambiguous"):
            build_fixture(self.source)
        self.source["messages"].pop(2)
        with self.assertRaisesRegex(ValueError, "finalize_instruction_missing"):
            build_fixture(self.source)

    def test_preserves_last_finalize_instruction(self):
        """存在多次历史冻结时采用最后一次真实 TOOL 指令，不串用前一修订。"""
        older = copy.deepcopy(self.source["messages"][2])
        older["contents"][0]["text"] = older["contents"][0]["text"].replace("actual-production", "old")
        self.source["messages"].insert(2, older)
        self.assertEqual("actual-production-finalize-guidance\n保持原文", finalize_instruction(self.source))

    def test_offline_preparation_and_budget(self):
        """正常配对通过，单次预算和输出上限被明确绑定。"""
        budget = prepare_budget(self.fixture)
        self.assertEqual(1, budget["max_attempts"])
        self.assertFalse(budget["automatic_retry"])
        validate_fixture(self.fixture, budget, document(self.fixture).encode("utf-8"))

    def test_cli_binds_actual_written_bytes(self):
        """真实文件摘要必须匹配预算及 manifest，Windows 换行转换也不能改变绑定。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.json"
            source.write_bytes(document(self.source).encode("utf-8"))
            output = root / "output"
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("missing_observation_fixture.py")),
                                     "--source", str(source), "--output", str(output)], capture_output=True, check=True)
            self.assertIn(b"provider_calls=0", result.stdout)
            manifest = json.loads((output / "fixture-manifest.json").read_bytes())
            budget = json.loads((output / "budget.json").read_bytes())
            raw = (output / "fixture.json").read_bytes()
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), manifest["source_sha256"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), manifest["fixture_sha256"])
            self.assertEqual(0, manifest["provider_calls"])
            self.assertEqual("NOT_RUN", manifest["semantic_review"])
            validate_fixture(json.loads(raw), budget, raw)

    def test_rejects_bad_pairing(self):
        """缺失、重复、错误身份的回执均不能通过。"""
        for change in ("wrong_id", "missing", "duplicate"):
            fixture = copy.deepcopy(self.fixture)
            if change == "wrong_id":
                fixture["messages"][4]["toolCallId"] = "unseen"
            elif change == "missing":
                fixture["messages"].pop(4)
            else:
                fixture["messages"].insert(5, copy.deepcopy(fixture["messages"][4]))
            with self.subTest(change=change), self.assertRaises(ValueError):
                prepare_budget(fixture)

    def test_rejects_nonfinal_source(self):
        """不能把行动阶段请求悄悄改造成最终阶段。"""
        self.source["tools"].append({"wireName": "code_execute"})
        with self.assertRaisesRegex(ValueError, "native_answer_without_tools"):
            build_fixture(self.source)

    def test_rejects_missing_answer_phase(self):
        """必须有当前生产最终答复指令，不由夹具自行冒充。"""
        self.source["messages"].pop()
        with self.assertRaisesRegex(ValueError, "answer_phase"):
            build_fixture(self.source)

    def test_rejects_digest_drift_and_budget_expansion(self):
        """冻结后变动、自动重试和不合法预算都使离线准备失败。"""
        raw = document(self.fixture).encode("utf-8")
        budget = prepare_budget(self.fixture)
        for key, value in (("fixture_sha256", "wrong"), ("max_attempts", 2),
                           ("automatic_retry", True), ("max_tokens", 1),
                           ("max_seconds", 181), ("max_output_tokens", 1)):
            altered = {**budget, key: value}
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_fixture(self.fixture, altered, raw)
        with self.assertRaisesRegex(ValueError, "digest"):
            validate_fixture(self.fixture, budget, raw + b" ")
        self.fixture["generation"]["maxOutputTokens"] = "4097"
        with self.assertRaisesRegex(ValueError, "budget"):
            prepare_budget(self.fixture)


if __name__ == "__main__":
    unittest.main()
