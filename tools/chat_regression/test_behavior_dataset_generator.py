"""数据集生成器的预算、未知结果恢复和单次修复离线契约。"""
from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch

import behavior_dataset_generator as generator
from client import ApiError
from runner import read


def args(**changes):
    values = {
        "api": "http://127.0.0.1:8080/api/v1",
        "platform": "http://127.0.0.1:8081/api/v1",
        "generator_model_id": "catalog-1",
        "max_generator_tokens": 1_000,
        "max_generator_requests": 24,
        "max_generator_seconds": 1_800,
    }
    values.update(changes)
    return SimpleNamespace(**values)


def model(max_output=10):
    return {"id": 7, "name": "generator", "model_id": "provider-model", "kind": "LLM",
            "enabled": True, "credential_configured": True, "max_output_tokens": max_output}


def resources():
    return {"agents": {"generator": {"id": "agent", "mounts": {}}},
            "users": [{"id": "user"}]}


def execution_config():
    return {"max_iterations": 1, "max_total_tokens": 100, "max_wall_time_seconds": 540}


def raw_candidates(category="D01"):
    return [{"category": category, "variant": index, "goal": f"goal {index}",
             "user_turns": [f"turn {index}"], "material_refs": ["rules"],
             "required_capabilities": [],
             "tags": {"business": "采购", "input": "自然文本", "interaction": "单轮", "anomaly": "无"},
             "reference_answer": "reference"} for index in range(1, 6)]


def trace(answer, tokens=20, unknown=0):
    phase = {"event_id": "event", "event_type": "run.completed", "content_ref": "content",
             "detail": {"sessionSequence": 1}}
    page = {"rounds": [{"runs": [{"run_id": "run:message",
                                    "steps": [{"phases": [phase]}]}]}],
            "usage_summary": {"tokens": tokens, "unknown_usage_calls": unknown}}
    return {"pages": [page],
        "contents": {"content": {"availability": "FULL", "value": {"outcome": {"report": {"output": answer}}}}},
        "usage": {"tokens": tokens, "unknown_usage_calls": unknown}}


def accepted_report(candidates):
    return {"accepted": candidates, "rejected": [], "near_duplicates": [],
            "selected": [candidates[0]["candidate_id"]] if candidates else [],
            "gaps": [] if candidates else ["D01"], "coverage": [], "reference_answers_are_gold": False}


def normalized_report(candidates):
    accepted, normalizations = deepcopy(candidates), []
    for candidate in accepted:
        for key, value in candidate.get("tags", {}).items():
            if isinstance(value, list) and len(value) == 1:
                candidate["tags"][key] = value[0]
                normalizations.append({"candidate_id": candidate["candidate_id"],
                                       "field": "tags." + key,
                                       "rule": "singleton_enum_to_scalar"})
    return {"accepted": accepted, "rejected": [], "near_duplicates": [],
            "normalizations": normalizations,
            "selected": [accepted[0]["candidate_id"]] if accepted else [],
            "gaps": [] if accepted else ["D01"], "coverage": [], "reference_answers_are_gold": False}


class DatasetGeneratorTests(unittest.TestCase):
    def common(self):
        return patch.multiple(generator,
            CATEGORY_IDS=("D01",),
            generation_payload=Mock(return_value={"category": "D01", "count": 5}),
            login=Mock(side_effect=lambda base, platform=False: Mock(name="platform" if platform else "api")),
            resolve_model=Mock(return_value=model()),
            provision=Mock(return_value=resources()),
            _execution_config=Mock(return_value=execution_config()),
            _configure_generator_agent=Mock(),
            _verify_dispatch_configuration=Mock(),
            validate_candidates=Mock(side_effect=accepted_report))

    def test_budget_is_persisted_and_exhaustion_prevents_chat_send(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            journal_path = root / "journal.json"
            journal = generator._new_journal("catalog-1", 20, 24, 1_800, __import__("time").time)
            generator.save(journal_path, journal)
            budget = generator._GenerationBudget(journal, journal_path)
            budget.reserve("first", 15)
            budget.observe("first", "session", {"tokens": 10, "unknown_usage_calls": 0})
            with self.assertRaisesRegex(generator.DatasetGenerationStopped, "token_budget_exhausted"):
                budget.reserve("second", 15)
            restored = generator._load_journal(journal_path, "catalog-1", 20, 24, 1_800)
            self.assertEqual(1, restored["budget"]["requests"])
            self.assertEqual("generator_token_budget_exhausted", restored["budget"]["stop_reason"])
            with self.assertRaisesRegex(generator.DatasetGenerationStopped, "token_budget_exhausted"):
                generator._GenerationBudget(restored, journal_path).assert_open()

    def test_runtime_hard_budget_and_dispatch_readback_reject_mount_drift(self):
        config = generator._execution_config(
            {"max_output_tokens": 4_096}, 300_000, 12, 1_800)
        self.assertEqual(25_000, config["max_total_tokens"])
        self.assertNotIn("max_input_tokens", config)
        self.assertNotIn("run_max_output_tokens", config)
        api, platform = Mock(), Mock()
        detail = {"id": "agent", "system_prompt": generator.GENERATOR_PROMPT, "model_config_id": "7",
                  "execution_config": config, "mount_set_generation": 0, "mounts": [],
                  "status": "published"}
        api.request.side_effect = lambda method, path, body=None: (
            {"id": "agent"} if method == "PATCH" else detail)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "journal.json"
            journal = generator._new_journal("catalog-1", 300_000, 24, 1_800, __import__("time").time)
            generator.save(path, journal)
            generator._configure_generator_agent(api, "agent", config, journal, path)
            api.request.assert_any_call("PATCH", "/agents/update", {
                "agent_id": "agent", "payload": {"execution_config": config}})
            resolved = model(max_output=4_096)
            fingerprint = generator.digest(generator._safe_model(resolved))
            with patch.object(generator, "resolve_model", return_value=resolved):
                generator._verify_dispatch_configuration(
                    api, platform, "agent", "catalog-1", fingerprint, config, journal, path)
                detail["mounts"] = [{"mount_id": "unexpected-tool"}]
                with self.assertRaisesRegex(ValueError, "must_have_no_capability_mounts"):
                    generator._verify_dispatch_configuration(
                        api, platform, "agent", "catalog-1", fingerprint, config, journal, path)

    def test_frozen_dataset_and_expanded_generator_limits_fail_before_login(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(generator, "login") as authenticate:
            root = Path(directory)
            generator.save(root / "dataset.json", {"frozen": True})
            with self.assertRaisesRegex(ValueError, "frozen_dataset"):
                generator.generate_dataset(args(), root)
            authenticate.assert_not_called()
        with tempfile.TemporaryDirectory() as directory, patch.object(generator, "login") as authenticate:
            with self.assertRaisesRegex(ValueError, "exceeds_generator_ceiling"):
                generator.generate_dataset(args(max_generator_tokens=300_001), Path(directory))
            authenticate.assert_not_called()

    def test_unknown_transport_resumes_original_request_without_second_reservation(self):
        api = Mock()
        api.request.return_value = {"session_id": "session"}
        logins = [api, Mock()]
        calls = []

        def invoke(_api, body, _path, _timeout, resume=False):
            calls.append((body["client_message_id"], resume))
            if len(calls) == 1:
                raise ApiError("response lost")
            return {"terminal": {"run_id": "run:message", "status": "completed"}}

        with tempfile.TemporaryDirectory() as directory, patch.multiple(generator,
                CATEGORY_IDS=("D01",), generation_payload=Mock(return_value={"category": "D01", "count": 5}),
                login=Mock(side_effect=lambda *_: logins.pop(0) if logins else (api if len(logins) == 0 else Mock())),
                resolve_model=Mock(return_value=model()), provision=Mock(return_value=resources()),
                _execution_config=Mock(return_value=execution_config()),
                _configure_generator_agent=Mock(), _verify_dispatch_configuration=Mock(),
                validate_candidates=Mock(side_effect=accepted_report), chat=Mock(side_effect=invoke),
                collect_trace=Mock(return_value=trace({"candidates": raw_candidates()}))):
            root = Path(directory)
            with self.assertRaises(ApiError):
                generator.generate_dataset(args(), root)
            persisted = read(root / "generation/journal.json")
            persisted["budget"]["started_at_epoch"] = 0
            generator.save(root / "generation/journal.json", persisted)
            # 第二次调用需要重新登录两个客户端，结果不影响已保存的请求身份。
            generator.login.side_effect = lambda base, platform=False: Mock()
            with self.assertRaisesRegex(generator.DatasetGenerationStopped, "wallclock_budget_exhausted"):
                generator.generate_dataset(args(), root)
            self.assertEqual([(calls[0][0], False), (calls[0][0], True)], calls)
            journal = read(root / "generation/journal.json")
            self.assertEqual(1, journal["budget"]["requests"])
            self.assertEqual(20, journal["budget"]["observed_tokens"])
            self.assertEqual(5, len(read(root / "candidates.json")))
            api.request.assert_called_once_with("POST", "/chat/sessions", {"agent_id": "agent", "end_user_id": "user"})

    def test_completed_singleton_enum_attempt_is_revalidated_without_paid_request(self):
        provenance = {"model_id": "7", "model_config_digest": "config", "prompt_version": "product-generator-1",
                      "call_identity": "run:old"}
        candidates, _ = generator._augment("D01", raw_candidates(), provenance)
        for candidate in candidates:
            candidate["tags"] = {key: [value] for key, value in candidate["tags"].items()}
        with tempfile.TemporaryDirectory() as directory, patch.multiple(generator,
                CATEGORY_IDS=("D01",), login=Mock(return_value=Mock()), resolve_model=Mock(return_value=model()),
                provision=Mock(return_value=resources()), _execution_config=Mock(return_value=execution_config()),
                _configure_generator_agent=Mock(), _verify_dispatch_configuration=Mock(),
                validate_candidates=Mock(side_effect=normalized_report), chat=Mock()):
            root = Path(directory)
            path = root / "generation/journal.json"
            journal = generator._new_journal("catalog-1", 1_000, 24, 1_800, __import__("time").time)
            journal["model_config_digest"] = generator.digest(generator._safe_model(model()))
            journal["categories"] = {"D01": {"attempts": [{
                "call_key": "D01:0", "status": "completed", "candidates": candidates,
                "validation_reasons": ["invalid_coverage_label"]}], "complete": False}}
            journal["budget"]["requests"] = 1
            generator.save(path, journal)

            result = generator.generate_dataset(args(), root)

            self.assertEqual([], result["gaps"])
            generator.chat.assert_not_called()
            restored = read(path)
            attempt = restored["categories"]["D01"]["attempts"][0]
            self.assertEqual(["invalid_coverage_label"], attempt["validation_reasons"])
            self.assertEqual([], attempt["current_validation"]["reasons"])
            self.assertEqual(20, len(attempt["current_validation"]["normalizations"]))
            self.assertEqual(1, restored["budget"]["requests"])

    def test_unknown_usage_persists_stop_and_blocks_all_future_sends(self):
        api = Mock()
        api.request.return_value = {"session_id": "session"}
        with tempfile.TemporaryDirectory() as directory, patch.multiple(generator,
                CATEGORY_IDS=("D01",), generation_payload=Mock(return_value={"category": "D01", "count": 5}),
                login=Mock(return_value=api), resolve_model=Mock(return_value=model()),
                provision=Mock(return_value=resources()), _execution_config=Mock(return_value=execution_config()),
                _configure_generator_agent=Mock(),
                _verify_dispatch_configuration=Mock(), validate_candidates=Mock(side_effect=accepted_report),
                chat=Mock(return_value={"terminal": {"run_id": "run:message", "status": "completed"}}),
                collect_trace=Mock(return_value=trace({"candidates": raw_candidates()}, 20, unknown=1))):
            root = Path(directory)
            with self.assertRaisesRegex(generator.DatasetGenerationStopped, "token_usage_unknown"):
                generator.generate_dataset(args(), root)
            self.assertEqual(1, generator.chat.call_count)
            self.assertEqual(5, len(read(root / "candidates.json")))
            with self.assertRaisesRegex(generator.DatasetGenerationStopped, "token_usage_unknown"):
                generator.generate_dataset(args(), root)
            self.assertEqual(1, generator.chat.call_count)

    def test_malformed_response_gets_exactly_one_repair_with_local_reasons(self):
        api = Mock()
        sessions = iter(("session-1", "session-2"))
        api.request.side_effect = lambda *_args, **_kwargs: {"session_id": next(sessions)}
        messages, dispatched_sessions = [], []

        def invoke(_api, body, _path, _timeout, resume=False):
            self.assertNotIn("message", body)
            self.assertEqual(body["content"][0]["type"], "text")
            messages.append(json.loads(body["content"][0]["text"]))
            dispatched_sessions.append(body["session_id"])
            return {"terminal": {"run_id": "run:message", "status": "completed"}}

        supplied_materials = raw_candidates()
        for candidate in supplied_materials:
            candidate["materials"] = [{"ref": "untrusted", "kind": "text", "content": "replace source"}]
        traces = [trace({"candidates": supplied_materials}, 10),
                  trace({"candidates": raw_candidates()}, 30)]
        with tempfile.TemporaryDirectory() as directory, patch.multiple(generator,
                CATEGORY_IDS=("D01",), generation_payload=Mock(return_value={"category": "D01", "count": 5}),
                login=Mock(return_value=api), resolve_model=Mock(return_value=model()),
                provision=Mock(return_value=resources()), _execution_config=Mock(return_value=execution_config()),
                _configure_generator_agent=Mock(),
                _verify_dispatch_configuration=Mock(), validate_candidates=Mock(side_effect=accepted_report),
                chat=Mock(side_effect=invoke), collect_trace=Mock(side_effect=traces)):
            root = Path(directory)
            self.assertEqual([], generator.generate_dataset(args(), root)["gaps"])
            self.assertEqual(2, len(messages))
            self.assertEqual(["session-1", "session-2"], dispatched_sessions)
            self.assertEqual({"category": "D01", "count": 5}, messages[0])
            self.assertEqual(1, messages[1]["repair_feedback"]["attempt"])
            self.assertIn("model_must_not_supply_materials", messages[1]["repair_feedback"]["reasons"])
            candidates = read(root / "candidates.json")
            self.assertEqual([f"D01-{index:02d}" for index in range(1, 6)],
                             [candidate["candidate_id"] for candidate in candidates])
            self.assertTrue(all(candidate["provenance"]["source_digest"] for candidate in candidates))
            self.assertTrue(all(candidate["materials"] == generator.source_fixture("D01", index)["materials"]
                                for index, candidate in enumerate(candidates, 1)))
            self.assertEqual(2, read(root / "generation/journal.json")["budget"]["requests"])


if __name__ == "__main__":
    unittest.main()
