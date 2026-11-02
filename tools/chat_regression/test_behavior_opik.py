"""导出保真、脱敏、导入身份和故障恢复的离线契约测试。"""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
import uuid

from behavior_contract import digest
from behavior_opik_export import atomic_json, export_bundle, load_bundle, project_trial, sanitise, sha_file
from behavior_opik_import import Importer, MAX_BYTES, OpikApi, OpikError, chunks
from behavior_suite import make_plan
from test_behavior_reporting import report


class FakeApi:
    url = "http://127.0.0.1:15173/api"

    def __init__(self):
        self.objects, self.items, self.scores = {}, {}, {}
        self.fail_spans_once = False
        self.partial_spans_once = False
        self.partial_dataset_once = False
        self.span_writes = 0

    def request(self, method, path, body=None):
        if method == "GET":
            row = deepcopy(self.objects.get(path))
            if row and path.startswith("/traces/"):
                row["span_count"] = sum(v.get("trace_id") == row["id"] for k, v in self.objects.items() if k.startswith("/spans/"))
                row["feedback_scores"] = [s for (trace_id, _), s in self.scores.items() if trace_id == row["id"]]
            if row and path.startswith("/experiments/") and not path.startswith("/experiments/items/"):
                row["trace_count"] = sum(v.get("experiment_id") == row["id"] for k, v in self.objects.items() if k.startswith("/experiments/items/"))
            return row
        if path == "/datasets/items":
            selected = body["items"][:1] if self.partial_dataset_once else body["items"]
            self.partial_dataset_once = False
            for item in selected:
                self.items[item["id"]] = deepcopy(item)
            dataset = self.objects["/datasets/" + body["dataset_id"]]
            dataset["latest_version"] = {"id": body["batch_group_id"], "version_hash": "frozen-version"}
        elif path == "/spans/batch":
            selected = body["spans"][:1] if self.partial_spans_once else body["spans"]
            self.partial_spans_once = False
            for span in selected:
                self.objects["/spans/" + span["id"]] = deepcopy(span)
                self.span_writes += 1
            if self.fail_spans_once:
                self.fail_spans_once = False
                raise OpikError("response_lost_after_commit")
        elif path == "/traces/feedback-scores":
            for score in body["scores"]:
                self.scores[(score["id"], score["name"])] = deepcopy(score)
        elif path == "/experiments/items":
            for item in body["experiment_items"]:
                self.objects["/experiments/items/" + item["id"]] = deepcopy(item)
        else:
            self.objects[path + "/" + body["id"]] = deepcopy(body)

    def pages(self, path):
        return deepcopy(list(self.items.values()))


def fixture(root):
    plan = make_plan("opik-offline")
    original = report(behavior_status="failed", release_eligible=False)
    original["cases"][0].update(case_id="B04", repetition=2, behavior_status="failed")
    original["cases"][0]["assertions"][0].update(id="query_count", evaluator="code", status="fail")
    original["binding"]["suite_version"] = plan["suite_version"]
    batch = {"lifecycle": "finalized", "binding": original["binding"], "started_at": "2026-09-28T01:00:00Z"}
    event = {"event_id": "e1", "run_id": "r1", "event_type": "run.completed", "occurred_at": "2026-09-28T01:00:01Z",
             "payload": {"text": "CANARY-PRIVATE-VALUE", "apiKey": "credential-without-pattern"}}
    evidence = {"case_id": "B04", "repetition": 2, "events": [event], "calls": [], "metrics": {},
                "frozen_at": "2026-09-28T01:00:02Z", "answer": {"value": 100}, "completeness": {"trace": True}}
    for name, value in {"plan.json": plan, "batch.json": batch, "evaluation.json": original,
                        "private.json": {"sensitive_values": ["CANARY-PRIVATE-VALUE"]},
                        "cases/B04/2/evidence.json": evidence, "cases/B04/2/state.json": {"turns": {}}}.items():
        atomic_json(root / name, value)
    return original


class OpikContractTest(unittest.TestCase):
    def test_export_preserves_failure_and_source_and_scrubs_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root, target = Path(directory) / "source", Path(directory) / "export"
            original = fixture(root)
            sse = root / "cases/B04/2/turn-0.sse.jsonl"
            sse.write_text("", encoding="utf-8")
            hashes = {p: sha_file(p) for p in root.rglob("*.json")}
            export_bundle(root, target)
            manifest, trials = load_bundle(target)
            self.assertEqual(sha_file(sse), manifest["source"]["files"]["cases/B04/2/turn-0.sse.jsonl"])
            self.assertEqual("failed", manifest["report"]["behavior_status"])
            self.assertFalse(manifest["report"]["release_eligible"])
            self.assertEqual(original["cases"][0]["assertions"], trials[0]["trace"]["output"]["skillforge_result"]["assertions"])
            self.assertEqual(0, trials[0]["scores"][0]["value"])
            text = "".join(p.read_text(encoding="utf-8") for p in target.rglob("*.json"))
            self.assertNotIn("CANARY-PRIVATE-VALUE", text)
            self.assertNotIn("credential-without-pattern", text)
            self.assertTrue(all(sha_file(p) == value for p, value in hashes.items()))

    def test_export_refuses_unfrozen_batch_and_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root, target = Path(directory) / "source", Path(directory) / "export"
            fixture(root)
            batch = json.loads((root / "batch.json").read_text())
            batch["lifecycle"] = "running"
            atomic_json(root / "batch.json", batch)
            with self.assertRaisesRegex(ValueError, "frozen"):
                export_bundle(root, target)
            self.assertFalse(target.exists())

    def test_aliases_are_scoped_and_business_numbers_preserved(self):
        data = {"userId": "tenant-user-123", "text": "tenant-user-123", "value": 100, "inputTokens": 12}
        first = sanitise(data, ("private",), "one")
        self.assertEqual(first["userId"], first["text"])
        self.assertNotEqual(first["userId"], sanitise(data, ("private",), "two")["userId"])
        self.assertEqual(100, first["value"])
        self.assertEqual(12, first["inputTokens"])

    def test_unknown_and_cancel_are_not_numeric_passes(self):
        result = {"case_id": "B15", "repetition": 1, "behavior_status": "unknown", "blocking_reasons": [],
                  "assertions": [{"id": "a", "status": "unknown", "evaluator": "code", "reason": "missing"}]}
        trial = project_trial({"turns": []}, result, {"cancellation": {"cancel_persisted": True}}, {}, "2026-09-28T00:00:00Z")
        self.assertEqual([], trial["scores"])
        self.assertEqual("unknown", trial["trace"]["output"]["skillforge_result"]["behavior_status"])
        self.assertTrue(trial["trace"]["metadata"]["cancellation"]["cancel_persisted"])

    def test_chunks_keep_all_spans_and_limit_utf8_bytes(self):
        items = [{"content": "中" * 9000, "i": i} for i in range(201)]
        batches = list(chunks(items, "spans"))
        self.assertEqual(items, [row for batch in batches for row in batch])
        self.assertTrue(all(len(batch) <= 80 for batch in batches))
        with self.assertRaisesRegex(ValueError, "too_large"):
            list(chunks([{"content": "中" * MAX_BYTES}], "spans"))

    def test_remote_targets_and_redirects_are_rejected(self):
        for url in ("https://example.com/api", "http://127.0.0.1:15173/api?token=secret", "http://user:pass@127.0.0.1/api"):
            with self.assertRaises(ValueError):
                OpikApi(url)

    def test_response_loss_resumes_without_duplicate_entities(self):
        with tempfile.TemporaryDirectory() as directory:
            root, target = Path(directory) / "source", Path(directory) / "export"
            fixture(root)
            export_bundle(root, target)
            manifest, trials = load_bundle(target)
            api, state = FakeApi(), Path(directory) / "registry.json"
            api.fail_spans_once = True
            with self.assertRaisesRegex(OpikError, "response_lost"):
                Importer(api, state).run(manifest, trials)
            count = api.span_writes
            receipt = Importer(api, state).run(manifest, trials)
            self.assertEqual("complete", receipt["status"])
            self.assertEqual(count, api.span_writes)
            ids = set(api.objects)
            Importer(api, state).run(manifest, trials)
            self.assertEqual(ids, set(api.objects))
            self.assertEqual(1, len([k for k in ids if k.startswith("/experiments/items/")]))
            self.assertTrue(all(uuid.UUID(row["id"]).version == 7 for row in api.objects.values()))

    def test_remote_content_conflict_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            api = FakeApi()
            state = Path(directory) / "registry.json"
            importer = Importer(api, state)
            row = importer.entity("trace", "/traces", {"name": "failed", "output": {"status": "failed"}})
            api.objects["/traces/" + row["id"]]["output"] = {"status": "passed"}
            with self.assertRaisesRegex(ValueError, "content_conflict"):
                Importer(api, state).entity("trace", "/traces", {"name": "failed", "output": {"status": "failed"}})

    def test_successful_http_with_partial_storage_stays_pending_and_recovers(self):
        with tempfile.TemporaryDirectory() as directory:
            root, target = Path(directory) / "source", Path(directory) / "export"
            fixture(root)
            export_bundle(root, target)
            manifest, trials = load_bundle(target)
            api, state = FakeApi(), Path(directory) / "registry.json"
            api.partial_spans_once = True
            with self.assertRaisesRegex(OpikError, "not_visible"):
                Importer(api, state).run(manifest, trials)
            saved = json.loads(state.read_text())
            self.assertEqual("pending", saved["imports"][manifest["bundle_id"]]["status"])
            receipt = Importer(api, state).run(manifest, trials)
            self.assertEqual("complete", receipt["status"])
            self.assertEqual(len(trials[0]["spans"]), api.span_writes)

    def test_dataset_version_is_frozen_only_after_complete_readback(self):
        with tempfile.TemporaryDirectory() as directory:
            root, target = Path(directory) / "source", Path(directory) / "export"
            fixture(root)
            export_bundle(root, target)
            manifest, _ = load_bundle(target)
            another = deepcopy(manifest["dataset"]["cases"][0])
            another["id"] = "B05"
            manifest["dataset"]["cases"].append(another)
            manifest["dataset_digest"] = digest(manifest["dataset"])
            api, state = FakeApi(), Path(directory) / "registry.json"
            api.partial_dataset_once = True
            with self.assertRaisesRegex(ValueError, "count_mismatch"):
                Importer(api, state).dataset(manifest)
            key = "dataset/" + manifest["dataset_digest"]
            self.assertNotIn("version_id", json.loads(state.read_text())["entities"][key])
            _, entry, items = Importer(api, state).dataset(manifest)
            self.assertIn("version_id", entry)
            self.assertEqual(2, len(items))
            self.assertEqual(2, len(api.items))


if __name__ == "__main__":
    unittest.main()
