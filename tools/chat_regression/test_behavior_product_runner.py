"""D09 最终回答必须指向本轮已验证的产物身份。"""
import unittest
from unittest.mock import Mock, patch

from behavior_product_runner import (_prepare_browser_resources_for_selection,
    _public_history_privacy_safe, _references_current_artifact,
    _updated_artifact_reference, _verify_execution_config_readback)


class ProductPreparationBoundaryTests(unittest.TestCase):
    def test_d08_selection_skips_browser_resources_but_d09_prepares_them(self):
        resources = {"marker": "original"}
        with patch("behavior_product_browser.prepare_browser_users",
                   return_value={"marker": "browser"}) as prepare:
            d08 = _prepare_browser_resources_for_selection(
                Mock(), Mock(), resources, "batch", {"local_selection": {"case_ids": ["D08"]}})
            self.assertIs(resources, d08)
            prepare.assert_not_called()

            d09 = _prepare_browser_resources_for_selection(
                Mock(), Mock(), resources, "batch", {"local_selection": {"case_ids": ["D09"]}})
            self.assertEqual({"marker": "browser"}, d09)
            prepare.assert_called_once()

    def test_execution_budget_readback_allows_defaults_but_rejects_missing_requested_field(self):
        expected = {"max_total_tokens": 98304}
        resources = {"run_max_total_tokens": 98304,
                     "agents": {"d08": {"execution_config": expected}}}
        snapshot = {"agents": {"d08": {"execution_config": {
            **expected, "max_iterations": 40}}}}
        _verify_execution_config_readback(resources, snapshot)

        snapshot["agents"]["d08"]["execution_config"].pop("max_total_tokens")
        with self.assertRaisesRegex(ValueError, "product_execution_config_readback_mismatch"):
            _verify_execution_config_readback(resources, snapshot)


class PublicHistoryPrivacyTests(unittest.TestCase):
    def history(self, **changes):
        process = {"type": "reasoning", "status": "failed", "summary": "",
                   "detail": {"reason_code": "model_http_rejected"}}
        process.update(changes)
        return {"processes": [process]}

    def test_public_failure_metadata_is_safe(self):
        self.assertTrue(_public_history_privacy_safe(self.history()))
        self.assertTrue(_public_history_privacy_safe(self.history(status="outcome_unknown",
            detail={"reason_code": "model_outcome_unknown"})))
        self.assertTrue(_public_history_privacy_safe(self.history(status="cancelled",
            detail={"reason_code": "model_cancelled"})))
        self.assertTrue(_public_history_privacy_safe(self.history(detail={
            "reason_code": "decision_decode_failed", "finish_reason": "length"})))
        self.assertTrue(_public_history_privacy_safe(self.history(detail=None, status="succeeded")))

    def test_content_and_uncontracted_detail_are_rejected(self):
        for changes in ({"summary": "private text"}, {"status": "running"},
                        {"detail": {"reason_code": "private free text"}},
                        {"detail": {"reason_code": "model_http_rejected", "text": "private"}},
                        {"detail": {"finish_reason": "stop"}}, {"detail": ["private"]}):
            with self.subTest(changes=changes):
                self.assertFalse(_public_history_privacy_safe(self.history(**changes)))

    def test_nested_private_content_is_rejected(self):
        for key in ("reasoning_content", "reasoningContent", "modelRequest", "private_reasoning"):
            history = self.history()
            history["messages"] = [{"nested": {key: "private text"}}]
            self.assertFalse(_public_history_privacy_safe(history))

    def test_static_decode_failure_summary_is_public_metadata(self):
        for summary, detail in (
            ("模型输出格式校验失败", {"reason_code": "INVALID_DECISION"}),
            ("模型输出达到长度上限，被截断；本次动作未执行",
             {"reason_code": "INVALID_DECISION", "finish_reason": "length"}),
        ):
            self.assertTrue(_public_history_privacy_safe(self.history(summary=summary, detail=detail)))
            self.assertFalse(_public_history_privacy_safe(self.history(summary=summary + "私有正文", detail=detail)))
            self.assertFalse(_public_history_privacy_safe(self.history(summary=summary, detail={**detail, "text": "私有正文"})))
            self.assertFalse(_public_history_privacy_safe(self.history(summary=summary, detail=detail, status="running")))


class UpdatedArtifactReferenceTests(unittest.TestCase):
    def setUp(self):
        self.initial = {"sha256": "initial-sha256"}
        self.updated = {
            "id": "artifact-current",
            "revision_id": "revision-current",
            "file_id": "file-current",
            "valid": True,
            "run_id": "run-current",
            "sha256": "updated-sha256",
            "path": "business-report.txt",
        }
        self.terminal = {"run_id": "run-current"}

    def test_plain_current_artifact_id_passes_without_markdown(self):
        answer = "本轮成果 artifact-current 已生成。"
        self.assertTrue(_references_current_artifact(answer, self.updated))
        self.assertTrue(_updated_artifact_reference(self.initial, self.updated, self.terminal, answer))

    def test_wrong_or_old_identity_fails(self):
        answer = "本轮成果 artifact-old 已生成。"
        self.assertFalse(_references_current_artifact(answer, self.updated))
        self.assertFalse(_updated_artifact_reference(self.initial, self.updated, self.terminal, answer))

    def test_identity_embedded_in_a_larger_identifier_fails(self):
        answer = "本轮成果 artifact-current-previous 已生成。"
        self.assertFalse(_references_current_artifact(answer, self.updated))

    def test_same_name_path_alone_fails(self):
        answer = "本轮成果 business-report.txt 已生成。"
        self.assertFalse(_references_current_artifact(answer, self.updated))
        self.assertFalse(_updated_artifact_reference(self.initial, self.updated, self.terminal, answer))

    def test_current_identity_without_valid_artifact_fails(self):
        updated = {key: value for key, value in self.updated.items() if key != "valid"}
        self.assertTrue(_references_current_artifact("artifact-current", updated))
        self.assertFalse(_updated_artifact_reference(self.initial, updated, self.terminal, "artifact-current"))

    def test_allowed_protocol_link_can_reference_current_revision(self):
        answer = "[下载](artifact:revision-current)"
        self.assertTrue(_references_current_artifact(answer, self.updated))


if __name__ == "__main__":
    unittest.main()
