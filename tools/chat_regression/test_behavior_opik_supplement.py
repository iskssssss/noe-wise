"""补证导出只能追加可追溯读回，不得改写原冻结结论或发布资格。"""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from behavior_opik_export import atomic_json, export_bundle, load_bundle, sha_file
from behavior_opik_import import Importer
from behavior_opik_supplement import export_supplement
from test_behavior_opik import FakeApi, fixture


CASE_PATH = "cases/B04/2"
SESSION_ID = "session-b04"


def supplement_fixture(parent_root, supplement_root, *, mutate=None):
    original = fixture(parent_root)
    parent_evidence_path = parent_root / CASE_PATH / "evidence.json"
    parent_evidence = json.loads(parent_evidence_path.read_text(encoding="utf-8"))
    parent_evidence["session_id"] = SESSION_ID
    atomic_json(parent_evidence_path, parent_evidence)

    result = deepcopy(original["cases"][0])
    result.update(behavior_status="passed", blocking_reasons=[], metrics={"human_quality_status": "pending"})
    result["assertions"] = [{**assertion, "status": "pass", "reason": "supplemental_readback"}
                            for assertion in result["assertions"]]
    evidence = deepcopy(parent_evidence)
    evidence["readback"] = {"status": "completed"}
    lineage = {"source_hashes": {str(parent_evidence_path.resolve()): sha_file(parent_evidence_path)},
               "new_model_inputs": 0, "original_report_unchanged": True, "release_eligible": False,
               "readback_deployment": None}
    if mutate is not None:
        mutate(result, evidence, lineage)
    atomic_json(supplement_root / "evaluation.json", result)
    atomic_json(supplement_root / "evidence.json", evidence)
    atomic_json(supplement_root / "lineage.json", lineage)
    return original, result, evidence, lineage


class OpikSupplementTest(unittest.TestCase):
    def test_exports_readback_supplement_without_mutating_parent_or_release_eligibility(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory) / "parent"
            supplement = Path(directory) / "supplement"
            parent_bundle = Path(directory) / "parent-bundle"
            destination = Path(directory) / "supplement-bundle"
            original, _, _, lineage = supplement_fixture(parent, supplement)
            parent_export = export_bundle(parent, parent_bundle)

            exported = export_supplement(parent, supplement, destination)
            manifest, trials = load_bundle(destination)

            self.assertNotEqual(parent_export["bundle_id"], exported["bundle_id"])
            self.assertEqual("supplemental_readback", manifest["authority"])
            self.assertEqual(parent_export["bundle_id"], manifest["supplemental"]["parent_bundle_id"])
            self.assertNotEqual(manifest["source"]["batch"], manifest["supplemental"]["parent_batch"])
            self.assertIn("supplement", manifest["source"]["batch"])
            self.assertEqual(sha_file(parent / "evaluation.json"), manifest["supplemental"]["parent_report_sha256"])
            self.assertEqual("failed", manifest["supplemental"]["parent_case_status"])
            self.assertTrue(manifest["supplemental"]["original_result_unchanged"])
            self.assertEqual(0, manifest["supplemental"]["new_model_inputs"])
            self.assertFalse(manifest["report"]["release_eligible"])
            self.assertEqual("pending", trials[0]["trace"]["metadata"]["human_quality_status"])
            self.assertEqual(manifest["supplemental"], trials[0]["trace"]["metadata"]["supplemental"])
            self.assertEqual(lineage["source_hashes"][str((parent / CASE_PATH / "evidence.json").resolve())],
                             sha_file(parent / CASE_PATH / "evidence.json"))
            self.assertEqual("failed", original["cases"][0]["behavior_status"])
            self.assertEqual("failed", json.loads((parent / "evaluation.json").read_text(encoding="utf-8"))["cases"][0]["behavior_status"])

            api, state = FakeApi(), Path(directory) / "supplement-import.json"
            receipt = Importer(api, state).run(manifest, trials)
            entities = set(api.objects)
            self.assertEqual(manifest["supplemental"], api.objects["/experiments/" + receipt["experiment_id"]]["metadata"]["supplemental"])
            Importer(api, state).run(manifest, trials)
            self.assertEqual(entities, set(api.objects))
            self.assertEqual(1, len([key for key in entities if key.startswith("/experiments/items/")]))

    def test_rejects_untrusted_or_promoting_supplement_inputs(self):
        mutations = {
            "parent_hash": lambda result, evidence, lineage: lineage["source_hashes"].update({f"{CASE_PATH}/evidence.json": "0" * 64}),
            "wrong_session": lambda result, evidence, lineage: evidence.update(session_id="other-session"),
            "wrong_case": lambda result, evidence, lineage: result.update(case_id="B05"),
            "model_input": lambda result, evidence, lineage: lineage.update(new_model_inputs=1),
            "release": lambda result, evidence, lineage: lineage.update(release_eligible=True),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                parent = Path(directory) / "parent"
                supplement = Path(directory) / "supplement"
                destination = Path(directory) / "supplement-bundle"
                supplement_fixture(parent, supplement, mutate=mutate)
                with self.assertRaises(ValueError):
                    export_supplement(parent, supplement, destination)
                self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()
