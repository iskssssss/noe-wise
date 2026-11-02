"""C02 离线正负控；不启动服务或调用模型。"""
from contextlib import contextmanager
import copy
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

from complex_artifact_case import CSV, PROFILE, REPORT_NAME, _download, code_sources, oracle, provision_case, run_case, validate_case
from provision import _validate_profiles


def artifact(index):
    file = {"file_id": "file-" + str(index), "artifact_id": "code-artifact", "path": REPORT_NAME}
    return {"report": oracle(CSV, None if index == 0 else {"A", "B"}), "sha256": "hash-" + str(index),
            "revision": {"id": "revision-" + str(index), "deliverable_id": "report", "revision": index + 1,
                         "session_id": "session", "run_id": "run-" + str(index),
                         "artifact_inputs": [], "files": [file.copy()]},
            "file": file}


def evidence():
    return {"session_id": "session", "retained_first_sha256": "hash-0", "turns": [
        {"run_id": "run-" + str(index), "status": "COMPLETED", "artifact": artifact(index),
         "code_source": {"verified": True}} for index in range(2)]}


class ComplexArtifactCaseTests(unittest.TestCase):
    def test_independent_decimal_oracles_and_profile_are_valid(self):
        first, second = oracle(CSV), oracle(CSV, {"A", "B"})
        self.assertEqual((490, 370, 360, 10, ["B"], {"C": 10}),
                         tuple(first[key] for key in ("raw_total", "unique_total", "ledger_total", "difference", "duplicate_ids", "differences")))
        self.assertEqual((220, 220, 0, ["A", "B"], {}),
                         tuple(second[key] for key in ("unique_total", "ledger_total", "difference", "selected_ids", "differences")))
        self.assertEqual(PROFILE["key"], _validate_profiles([PROFILE])[0]["key"])

    def test_formal_fixture_hashes_are_frozen_and_drift_is_rejected(self):
        with TemporaryDirectory() as folder:
            resource = provision_case(folder)
            for binding in resource["material_bindings"]:
                content = (Path(folder) / binding["path"]).read_bytes()
                self.assertEqual(binding["sha256"], hashlib.sha256(content).hexdigest())
            (Path(folder) / resource["material_bindings"][0]["path"]).write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "fixture_drift"): provision_case(folder)

    def test_two_revision_download_oracles_pass_without_claiming_browser_acceptance(self):
        value = evidence()
        for turn in value["turns"]: turn["status"] = "completed"
        result = validate_case(value)
        self.assertEqual("PASS", result["status"])
        self.assertEqual("NOT_RUN", result["browser"])

    def test_numbers_sources_scope_and_old_revision_cannot_be_faked(self):
        for mutation in ("total", "scope", "hash", "duplicate", "boolean", "lineage", "owner", "retention", "source"):
            with self.subTest(mutation=mutation):
                value = evidence()
                second = value["turns"][1]["artifact"]
                if mutation == "total": second["report"]["unique_total"] = 370
                if mutation == "scope": second["report"]["selected_ids"].append("C")
                if mutation == "hash": second["report"]["source_sha256"]["orders"] = "forged"
                if mutation == "duplicate": value["turns"][0]["artifact"]["report"]["duplicate_ids"] = []
                if mutation == "boolean": second["report"]["difference"] = False
                if mutation == "lineage": second["revision"]["deliverable_id"] = "another-report"
                if mutation == "owner": second["revision"]["session_id"] = "other"
                if mutation == "retention": value["retained_first_sha256"] = "overwritten"
                if mutation == "source": value["turns"][1]["code_source"] = {"verified": False}
                self.assertNotEqual("PASS", validate_case(value)["status"])

    def source_fixture(self):
        uploads = {ref: {"metadata": {"id": ref, "name": ref + ".csv", "input_path": "attachments/" + ref + "/input.csv",
                                      "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}}
                   for ref, content in CSV.items()}
        output = {"success": True, "attachment_inputs": [value["metadata"] for value in uploads.values()],
                  "artifacts": [{"id": "code-artifact", "paths": [REPORT_NAME]}]}
        event = {"event_id": "code-result", "run_id": "run-0", "event_type": "agent.tool-result",
                 "payload": {"toolHandle": "opaque-code", "status": "SUCCEEDED", "output": {
                     "canonicalJson": json.dumps({"structuredContent": output})}},
                 "detail": {"arguments": {"attachment_ids": ["orders", "ledger"]}}}
        return uploads, {"run_id": "run-0", "rich_events": [event]}, artifact(0)

    def test_source_requires_exact_successful_code_and_original_attachments(self):
        uploads, turn, report = self.source_fixture()
        self.assertTrue(code_sources(turn, uploads, "opaque-code", report)["verified"])
        for mutation in ("handle", "run", "inline", "hash", "artifact", "path", "selected", "provenance", "missing"):
            with self.subTest(mutation=mutation):
                wrong = copy.deepcopy(turn)
                wrong_report = copy.deepcopy(report)
                event = wrong["rich_events"][0]
                if mutation == "handle": event["payload"]["toolHandle"] = "guessed-code"
                if mutation == "run": event["run_id"] = "other-run"
                if mutation == "inline": event["detail"]["arguments"]["input_files"] = {"orders.csv": "copied"}
                if mutation == "hash":
                    output = json.loads(event["payload"]["output"]["canonicalJson"])
                    output["structuredContent"]["attachment_inputs"][0]["sha256"] = "forged"
                    event["payload"]["output"]["canonicalJson"] = json.dumps(output)
                if mutation == "artifact": wrong_report["revision"]["files"] = []
                if mutation == "path": wrong_report["revision"]["files"][0]["path"] = "other/" + REPORT_NAME
                if mutation == "selected": wrong_report["file"]["artifact_id"] = "unrelated"
                if mutation == "provenance":
                    wrong_report["revision"]["files"] = []
                    wrong_report["revision"]["artifact_inputs"] = [{"artifact_id": "code-artifact"}]
                if mutation == "missing": wrong["rich_events"] = []
                self.assertFalse(code_sources(wrong, uploads, "opaque-code", wrong_report)["verified"])

    def test_stable_mount_resolves_only_through_this_runs_frozen_request_and_controls(self):
        uploads, turn, report = self.source_fixture()
        snapshot = {"event_id": "model-context-snapshot:op", "run_id": "run-0", "event_type": "agent.context-snapshot",
                    "payload": {"modelRequest": {"tools": [{"kind": "TOOL_CALL", "toolHandle": "opaque-code",
                                                            "wireName": "sf_tool_frozen"}]}}}
        controls = {"event_id": "preparation-controls:op", "run_id": "run-0", "event_type": "agent.preparation-controls",
                    "payload": {"identities": {"opaque-code": "t:mount-code"}}}
        turn["rich_events"].extend([snapshot, controls])
        self.assertTrue(code_sources(turn, uploads, "t:mount-code", report)["verified"])
        controls["run_id"] = "other-run"
        self.assertFalse(code_sources(turn, uploads, "t:mount-code", report)["verified"])
        self.assertFalse(code_sources(turn, uploads, None, report)["verified"])
        snapshot["payload"]["modelRequest"]["tools"][0]["qualifiedName"] = "platform:platform_tools/code_execute"
        self.assertTrue(code_sources(turn, uploads, None, report)["verified"])

    def test_download_verifies_bytes_and_rejects_external_ticket_before_fetch(self):
        content = json.dumps(oracle(CSV)).encode()
        file = {"file_id": "f", "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
        api = Mock(base="http://127.0.0.1:8000/api/v1")
        api.request.return_value = {"url": "http://127.0.0.1:8000/download"}
        @contextmanager
        def response():
            yield Mock(read=Mock(return_value=content))
        with patch("urllib.request.build_opener") as opener:
            opener.return_value.open.return_value = response()
            self.assertEqual(370, _download(api, {"id": "r"}, file)["report"]["unique_total"])
            api.request.return_value = {"url": "https://external.invalid/download"}
            with self.assertRaisesRegex(ValueError, "download_url_rejected"): _download(api, {"id": "r"}, file)
            self.assertEqual(1, opener.return_value.open.call_count)

    def test_unknown_turn_is_journaled_and_never_automatically_resent(self):
        with TemporaryDirectory() as folder:
            api, sender = Mock(), Mock(side_effect=TimeoutError("unknown"))
            with patch("complex_artifact_case.ensure_d08_attachments", return_value=["orders", "ledger"]):
                with self.assertRaises(TimeoutError):
                    run_case(api, agent_id="a", end_user_id="u", root=folder, send_turn=sender,
                             code_handle="opaque-code", session_id="session")
                with self.assertRaisesRegex(ValueError, "turn_outcome_unknown"):
                    run_case(api, agent_id="a", end_user_id="u", root=folder, send_turn=sender,
                             code_handle="opaque-code", session_id="session")
            self.assertEqual(1, sender.call_count)
            self.assertEqual(0, json.loads((Path(folder) / "c02/state.json").read_text(encoding="utf-8"))["pending_turn"]["turn"])

    def test_orchestration_reuses_one_session_and_completed_turns_are_not_resent(self):
        with TemporaryDirectory() as folder:
            api = Mock()
            sender = Mock(side_effect=[{"run_id": "run-0", "status": "completed"},
                                      {"run_id": "run-1", "status": "completed"}])
            with patch("complex_artifact_case.ensure_d08_attachments", return_value=["orders", "ledger"]), \
                    patch("complex_artifact_case.collect_revision", side_effect=[artifact(0), artifact(1)]), \
                    patch("complex_artifact_case.code_sources", return_value={"verified": True}), \
                    patch("complex_artifact_case._download", return_value={"sha256": "hash-0"}):
                # 生产上传器会落这份状态；此处只替代正式附件I/O，不替代调度/判定逻辑。
                provision_case(folder)
                state = {"turns": [], "attachment_uploads": {}}
                (Path(folder) / "c02/state.json").write_text(json.dumps(state), encoding="utf-8")
                result = run_case(api, agent_id="a", end_user_id="u", root=folder, send_turn=sender,
                                  code_handle="opaque-code", session_id="session")
                self.assertEqual("PASS", result["evaluation"]["status"])
                for turn in result["turns"]: turn["code_source"] = {"verified": False}
                (Path(folder) / "c02/state.json").write_text(json.dumps(result), encoding="utf-8")
                replay = run_case(api, agent_id="a", end_user_id="u", root=folder, send_turn=sender,
                                  code_handle="opaque-code", session_id="session")
                self.assertTrue(all(turn["code_source"]["verified"] for turn in replay["turns"]))
            self.assertEqual(2, sender.call_count)
            self.assertEqual(["session", "session"], [call.kwargs["session_id"] for call in sender.call_args_list])
            self.assertEqual([["orders", "ledger"], ["orders", "ledger"]],
                             [call.kwargs["attachment_ids"] for call in sender.call_args_list])


if __name__ == "__main__": unittest.main()
