"""D09 只接受正式发布成果，不把沙箱候选文件当成交付。"""
from contextlib import contextmanager
import hashlib
import unittest
from unittest.mock import Mock, patch

from behavior_product_artifacts import collect_report


class _Download:
    def __init__(self, content):
        self.content = content

    def read(self, _limit):
        return self.content


class ProductArtifactCollectionTests(unittest.TestCase):
    def case(self):
        return {"expected": {"latest_quantity": 32, "report_name": "business-report.txt",
                              "report_marker": "SF-D09-V2", "budget": 700, "deadline": "2026-11-12"},
                "candidate": {"variant": 2}}

    def deliverable(self, content, **overrides):
        value = {"id": "revision-2", "deliverable_id": "deliverable-report", "revision": 2, "current": True,
                 "session_id": "session-1", "run_id": "run-2", "result_run_ids": ["run-2"], "status": "ready",
                 "artifact_inputs": [{"artifact_id": "artifact-1", "path": "business-report.txt"}],
                 "files": [{"file_id": "file-2", "artifact_id": "artifact-2", "path": "business-report.txt",
                            "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}]}
        value.update(overrides)
        return value

    @contextmanager
    def download(self, content):
        yield _Download(content)

    def test_collects_the_matching_published_deliverable_revision(self):
        content = "SF-D09-V2\n预算 700\n日期 2026-11-12\n数量 32\n".encode()
        api = Mock(base="http://127.0.0.1:8000/api/v1")
        api.request.side_effect = [[self.deliverable(content)], {"url": "http://127.0.0.1:8000/download"}]
        with patch("urllib.request.build_opener") as opener:
            opener.return_value.open.return_value = self.download(content)
            report = collect_report(api, "session-1", "run-2", self.case(), 1)
        self.assertTrue(report["valid"])
        self.assertEqual("artifact-2", report["id"])
        self.assertEqual("revision-2", report["revision_id"])
        self.assertEqual("deliverable-report", report["deliverable_id"])
        self.assertEqual("file-2", report["file_id"])
        self.assertEqual(("GET", "/chat/sessions/deliverables?sessionId=session-1&includeHistory=true"),
                         api.request.call_args_list[0].args)
        self.assertEqual(("POST", "/chat/deliverables/download-ticket",
                          {"id": "revision-2", "file_id": "file-2"}), api.request.call_args_list[1].args)

    def test_candidate_artifact_without_a_published_deliverable_cannot_pass(self):
        content = b"SF-D09-V2 700 2026-11-12 32"
        api = Mock(base="http://127.0.0.1:8000/api/v1")
        api.request.return_value = [self.deliverable(content, status="candidate")]
        report = collect_report(api, "session-1", "run-2", self.case(), 1)
        self.assertFalse(report["valid"])
        self.assertEqual("report_missing_or_ambiguous", report["reason"])
        self.assertEqual(1, api.request.call_count)

    def test_matching_run_id_without_a_formal_revision_identity_cannot_pass(self):
        content = b"SF-D09-V2 700 2026-11-12 32"
        api = Mock(base="http://127.0.0.1:8000/api/v1")
        api.request.return_value = [self.deliverable(content, deliverable_id="")]
        report = collect_report(api, "session-1", "run-2", self.case(), 1)
        self.assertFalse(report["valid"])
        self.assertEqual(1, api.request.call_count)


if __name__ == "__main__":
    unittest.main()
