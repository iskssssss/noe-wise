"""规格扩展不能把旧验收改为通过或静默丢失证据。"""
import unittest

from sync_ui_acceptance_index import synchronize


class AcceptanceIndexTests(unittest.TestCase):
    def test_preserves_evidence_and_marks_new_controls_unexecuted(self):
        index = {"counts": {}, "records": [{"id": "P01.UI01", "origin": "page_refinement", "verification_status": "not_executed", "evidence": [{"kind": "automated_partial"}]}]}
        result = synchronize(index, {"P01.UI01": {"title": "刷新", "source": "home.md"}, "P03-C01": {"title": "名称", "source": "tenant.md"}})
        self.assertEqual(result["records"][0]["evidence"], [{"kind": "automated_partial"}])
        self.assertEqual(result["records"][1]["verification_status"], "not_executed")
        self.assertEqual(result["counts"]["indexed_rule_records"], 2)

    def test_removing_registered_controls_requires_explicit_resolution(self):
        with self.assertRaisesRegex(ValueError, "已登记控件"):
            synchronize({"counts": {}, "records": [{"id": "P03-C01", "origin": "actual_control_contract"}]}, {})

    def test_retired_sales_controls_preserve_evidence_without_reusing_their_ids(self):
        evidence = [{"kind": "automated_partial", "path": "original-evidence"}]
        index = {"counts": {}, "records": [{"id": "P05-C16a", "origin": "actual_control_contract",
                 "implementation_status": "implemented", "verification_status": "not_executed", "evidence": evidence}]}
        result = synchronize(index, {"P05-C33": {"title": "模型费用只读边界", "source": "model-billing.md"}})
        retired, current = result["records"]
        self.assertEqual(retired["applicability_status"], "retired")
        self.assertEqual(retired["evidence"], evidence)
        self.assertEqual(retired["verification_status"], "not_executed")
        self.assertEqual(current["id"], "P05-C33")
        self.assertEqual(current["verification_status"], "not_executed")
        self.assertEqual(result["counts"]["retired_rule_records"], 1)


if __name__ == "__main__":
    unittest.main()
