"""人工抽评只取绑定的公开最终答案，缺失与待评分不得补造。"""
import unittest

from behavior_manual_review import export_manual_review
from runner import read, save
import test_behavior_failure_export as fixtures


class ManualReviewTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.FailureFlowTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.source, self.target = self.fixture.source, self.fixture.target
        save(self.source / "cases/D04/1/evidence.json", {
            "case_id": "D04", "repetition": 1, "privacy_verified": True,
            "answer": "公开结论 Bearer secret-token", "answer_event_id": "answer-1",
            "events": [{"private_reasoning": "DO_NOT_COPY_REASONING"}]})

    def test_public_only_redacted_missing_and_pending(self):
        before = (self.source / "evaluation.json").read_bytes()
        pack = export_manual_review([{"root": str(self.source), "case_ids": ["D04", "D06"]}], self.target)
        text = (self.target / "review.md").read_text(encoding="utf-8")
        self.assertNotIn("DO_NOT_COPY_REASONING", text)
        self.assertNotIn("secret-token", text)
        self.assertIsNone(pack["entries"][1]["final_answer"])
        self.assertTrue(all(s is None for e in pack["entries"] for s in e["scores"].values()))
        self.assertFalse(pack["release_eligible"])
        self.assertEqual(before, (self.source / "evaluation.json").read_bytes())
        self.assertEqual(pack["fingerprint"], read(self.target / "scores-template.json")["pack_fingerprint"])

    def test_unverified_answer_is_not_exported(self):
        path = self.source / "cases/D04/1/evidence.json"
        value = read(path)
        value["privacy_verified"] = False
        save(path, value)
        pack = export_manual_review([{"root": str(self.source), "case_ids": ["D04"]}], self.target)
        self.assertIsNone(pack["entries"][0]["final_answer"])

    def test_duplicate_or_unbound_evidence_rejected_before_write(self):
        with self.assertRaisesRegex(ValueError, "duplicate_trial"):
            export_manual_review([{"root": str(self.source), "case_ids": ["D04", "D04"]}], self.target)
        self.assertFalse(self.target.exists())
        path = self.source / "cases/D04/1/evidence.json"
        value = read(path)
        value["case_id"] = "D07"
        save(path, value)
        with self.assertRaisesRegex(ValueError, "evidence_mismatch"):
            export_manual_review([{"root": str(self.source), "case_ids": ["D04"]}], self.target)
