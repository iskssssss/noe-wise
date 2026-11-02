"""浏览器桥的终态归属、计量尾账及只读恢复契约。"""
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import hashlib
import json
import unittest
from unittest.mock import Mock, patch

from behavior_product_browser import execute_browser_case, recover_browser_turns
from behavior_runner import ExecutionBudget
from behavior_dataset import independent_expected
from runner import save
from test_behavior_dataset import candidate


class ProductBrowserExecutionTests(unittest.TestCase):
    def d08_attachment_fixture(self, root):
        bindings = []
        for ref, content in (("orders", b"id,total\nA,100\n"),
                             ("ledger", b"id,total\nA,90\n")):
            path = root / "product-fixtures" / "d08" / f"{ref}.csv"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            bindings.append({"ref": ref, "kind": "csv", "path": path.relative_to(root).as_posix(),
                             "size": len(content), "sha256": hashlib.sha256(content).hexdigest()})
        return {"agent_id": "agent", "user_id": "user", "material_bindings": bindings,
                "attachment_transport": {"source": "session_attachment",
                    "upload_endpoint": "/chat/sessions/attachments/upload",
                    "list_endpoint": "/chat/sessions/attachments", "refs": ["orders", "ledger"]}}

    @staticmethod
    def attachment_metadata(upload_id, filename, binding):
        return {"id": upload_id, "name": filename,
                "input_path": f"attachments/{upload_id}/input.csv",
                "size": binding["size"], "sha256": binding["sha256"],
                "expires_at": "2099-01-01T00:00:00Z"}

    def test_d08_uploads_real_files_and_freezes_attachment_ids_in_message_context(self):
        from behavior_product_runner import execute_product_case
        with TemporaryDirectory() as directory:
            root = Path(directory)
            resource = self.d08_attachment_fixture(root)
            api = Mock()
            api.request.side_effect = lambda method, path, body=None: (
                {"session_id": "session"} if (method, path) == ("POST", "/chat/sessions")
                else (_ for _ in ()).throw(AssertionError((method, path))))
            uploaded = []
            def upload(method, path, *, fields, filename, content, content_type):
                uploaded.append((method, path, fields, filename, content, content_type))
                binding = next(row for row in resource["material_bindings"] if Path(row["path"]).name == filename)
                return self.attachment_metadata(fields["upload_id"], filename, binding)
            api.request_multipart = upload
            budget = ExecutionBudget(1000, 10, 600)
            monitor = Mock()
            monitor.check.return_value = True
            selected = candidate("D08")
            case = {"id": "D08", "turns": selected["user_turns"], "candidate": selected,
                    "expected": independent_expected("D08", selected["variant"])}
            plan = {"local_selection": {"turn_timeout_seconds": 60}}
            with patch("behavior_product_runner.monitored_chat", side_effect=RuntimeError("stop")):
                with self.assertRaisesRegex(RuntimeError, "stop"):
                    execute_product_case(api, case, 1, plan, {"cases": {"D08": resource}},
                                         root, budget, monitor)
            state = json.loads((root / "cases" / "D08" / "1" / "state.json").read_text())
            ids = [state["attachment_uploads"][ref]["upload_id"] for ref in ("orders", "ledger")]
            self.assertEqual(ids, state["turns"]["0"]["request"]["context"]["attachment_ids"])
            self.assertEqual(2, len(uploaded))
            self.assertEqual({"session_id", "upload_id"}, set(uploaded[0][2]))
            self.assertEqual(resource["material_bindings"][0]["sha256"],
                             hashlib.sha256(uploaded[0][4]).hexdigest())

    def test_d08_unknown_upload_recovers_by_exact_id_without_second_post(self):
        from behavior_product_runner import ensure_d08_attachments
        with TemporaryDirectory() as directory:
            root = Path(directory)
            resource = self.d08_attachment_fixture(root)
            state_path = root / "state.json"
            upload_id = "11111111-1111-1111-1111-111111111111"
            first = resource["material_bindings"][0]
            state = {"session_id": "session", "turns": {}, "attachment_uploads": {
                "orders": {"upload_id": upload_id, "status": "uploading",
                           "expected_sha256": first["sha256"], "expected_size": first["size"],
                           "expected_name": "orders.csv"}}}
            save(state_path, state)
            metadata = self.attachment_metadata(upload_id, "orders.csv", first)
            api = Mock()
            api.request.return_value = [metadata]
            def upload(_method, _path, *, fields, filename, content, content_type):
                self.assertEqual("ledger.csv", filename)
                binding = resource["material_bindings"][1]
                return self.attachment_metadata(fields["upload_id"], filename, binding)
            api.request_multipart = Mock(side_effect=upload)

            ids = ensure_d08_attachments(api, resource, state, state_path, root)

            self.assertEqual(upload_id, ids[0])
            self.assertEqual("ready", state["attachment_uploads"]["orders"]["status"])
            # ledger 是全新的上传；orders 的未知结果只执行 GET 精确回读。
            self.assertEqual(1, api.request_multipart.call_count)
            self.assertIn("session_id=session", api.request.call_args.args[1])

    def test_d08_confirmed_absent_retries_same_uuid(self):
        from behavior_product_runner import ensure_d08_attachments
        with TemporaryDirectory() as directory:
            root = Path(directory)
            resource = self.d08_attachment_fixture(root)
            state_path = root / "state.json"
            upload_id = "11111111-1111-1111-1111-111111111111"
            first = resource["material_bindings"][0]
            state = {"session_id": "session", "turns": {}, "attachment_uploads": {
                "orders": {"upload_id": upload_id, "status": "uploading",
                           "expected_sha256": first["sha256"], "expected_size": first["size"],
                           "expected_name": "orders.csv"}}}
            save(state_path, state)
            api = Mock()
            api.request.return_value = []
            seen = []
            def upload(_method, _path, *, fields, filename, content, content_type):
                seen.append(fields["upload_id"])
                binding = next(row for row in resource["material_bindings"] if Path(row["path"]).name == filename)
                return self.attachment_metadata(fields["upload_id"], filename, binding)
            api.request_multipart = upload

            ensure_d08_attachments(api, resource, state, state_path, root)

            self.assertEqual(upload_id, seen[0])
            self.assertEqual(upload_id, state["attachment_uploads"]["orders"]["upload_id"])

    def test_d08_failed_unknown_query_stops_without_post_or_new_uuid(self):
        from behavior_product_runner import ensure_d08_attachments
        with TemporaryDirectory() as directory:
            root = Path(directory)
            resource = self.d08_attachment_fixture(root)
            state_path = root / "state.json"
            upload_id = "11111111-1111-1111-1111-111111111111"
            first = resource["material_bindings"][0]
            state = {"session_id": "session", "turns": {}, "attachment_uploads": {
                "orders": {"upload_id": upload_id, "status": "uploading",
                           "expected_sha256": first["sha256"], "expected_size": first["size"],
                           "expected_name": "orders.csv"}}}
            save(state_path, state)
            api = Mock()
            api.request.side_effect = TimeoutError("unknown query")
            api.request_multipart = Mock()

            with self.assertRaisesRegex(TimeoutError, "unknown query"):
                ensure_d08_attachments(api, resource, state, state_path, root)

            api.request_multipart.assert_not_called()
            persisted = json.loads(state_path.read_text())
            self.assertEqual(upload_id, persisted["attachment_uploads"]["orders"]["upload_id"])
            self.assertEqual("uploading", persisted["attachment_uploads"]["orders"]["status"])

    def test_d08_rejects_server_input_path_outside_exact_upload_contract(self):
        from behavior_product_runner import ensure_d08_attachments
        with TemporaryDirectory() as directory:
            root = Path(directory)
            resource = self.d08_attachment_fixture(root)
            state_path = root / "state.json"
            state = {"session_id": "session", "turns": {}}
            api = Mock()
            def upload(_method, _path, *, fields, filename, content, content_type):
                binding = next(row for row in resource["material_bindings"] if Path(row["path"]).name == filename)
                return dict(self.attachment_metadata(fields["upload_id"], filename, binding),
                            input_path=f"attachments/{fields['upload_id']}/renamed.csv")
            api.request_multipart = upload

            with self.assertRaisesRegex(ValueError, "product_attachment_input_path_invalid"):
                ensure_d08_attachments(api, resource, state, state_path, root)

    def test_privacy_readback_must_match_current_markers(self):
        import hashlib
        from behavior_product_browser import _privacy_complete
        from test_behavior_product_process import live_case
        with TemporaryDirectory() as directory:
            output = Path(directory) / "privacy-readback.json"
            bundle = live_case("D12")
            bundle["privacy"]["marker_digests"] = [hashlib.sha256(b"old-marker").hexdigest()]
            bundle["privacy"]["marker_count"] = 1
            save(output, {"bundles": [bundle]})
            self.assertTrue(_privacy_complete(output, True, ["old-marker"]))
            self.assertFalse(_privacy_complete(output, True, ["new-marker"]))
            self.assertFalse(_privacy_complete(output, True, []))

    def test_hitl_recovery_discards_old_waiting_event_in_journal_and_persistence(self):
        import json
        with TemporaryDirectory() as directory:
            output = Path(directory) / "browser.json"
            rows = [
                {"session_id": "session", "turn_index": 0, "phase": "receipt", "run_id": "original"},
                {"session_id": "session", "turn_index": 0, "phase": "terminal", "run_id": "original", "status": "waiting_hitl"},
            ]
            output.with_suffix(".json.journal.jsonl").write_text("\n".join(map(json.dumps, rows)))
            api = Mock()
            api.request.return_value = {"events": [{"event": "execution.completed", "event_id": "old-wait",
                "data": {"run_id": "original", "status": "waiting_hitl"}}]}
            with patch("behavior_runner.read_run_status", return_value={"status": "completed", "run_id": "original"}) as status:
                recovered = recover_browser_turns(api, output, "session", [{"hitl_action": "确认"}],
                                                  ExecutionBudget(100, 2, 20), Mock())
            self.assertEqual("completed", recovered[0]["terminal"]["status"])
            status.assert_called_once()
            self.assertTrue(all(call.args[0] == "GET" for call in api.request.call_args_list))

    def test_delegated_waiting_status_is_not_a_recovered_terminal(self):
        import json
        with TemporaryDirectory() as directory:
            output = Path(directory) / "browser.json"
            rows = [
                {"session_id": "session", "turn_index": 0, "phase": "receipt", "run_id": "original"},
                {"session_id": "session", "turn_index": 0, "phase": "terminal", "run_id": "original",
                 "status": "waiting_hitl", "event": "execution.completed"},
            ]
            output.with_suffix(".json.journal.jsonl").write_text("\n".join(map(json.dumps, rows)))
            api = Mock()
            api.request.return_value = {"events": [{"event": "execution.completed", "event_id": "delegated-wait",
                "data": {"run_id": "original", "status": "waiting_hitl"}}]}
            with patch("behavior_runner.read_run_status", return_value={"status": "completed", "run_id": "original"}) as status:
                recovered = recover_browser_turns(api, output, "session", [{"text": "delegate"}],
                                                  ExecutionBudget(100, 2, 20), Mock())
            self.assertEqual("completed", recovered[0]["terminal"]["status"])
            status.assert_called_once()

    def test_channel_authorizes_declared_children_without_changing_frozen_bindings(self):
        from behavior_product_browser import prepare_browser_users
        with TemporaryDirectory() as directory:
            root = Path(directory)
            resources = {"cases": {cid: {"agent_id": str(index)}
                                   for index, cid in enumerate(("D09", "D10", "D11", "D12"), 1)}}
            resources["cases"]["D11"]["team"] = {"child_agent_ids": ["5", "6", "5"]}
            profiles = {cid: {"client_ref": cid, "end_user_id": cid} for cid in resources["cases"]}
            save(root / "browser-resources-private.json", {"channel": {"id": "channel"}, "profiles": profiles,
                                                         "channel_agent_ids": []})
            api = Mock()
            prepare_browser_users(api, root, resources, "batch")
            bindings = api.request.call_args.args[2]["payload"]
            self.assertEqual([1, 2, 3, 4, 5, 6], [row["agent_id"] for row in bindings])
            api.reset_mock()
            resources["cases"]["D11"]["team"]["child_agent_ids"].append("7")
            with self.assertRaisesRegex(ValueError, "browser_agent_binding_changed"):
                prepare_browser_users(api, root, resources, "batch")
            api.request.assert_not_called()

    def test_browser_session_is_created_with_channel_identity(self):
        from behavior_product_runner import execute_product_case
        with TemporaryDirectory() as directory:
            root = Path(directory)
            save(root / "browser-resources-private.json", {"channel": {"channel_token": "test-only"}})
            api = Mock(base="http://localhost:8000/api/v1")
            with patch("behavior_product_runner.Api") as factory, patch(
                    "behavior_product_browser.execute_browser_case", side_effect=RuntimeError("after-create")):
                channel = factory.return_value
                channel.headers = {}
                channel.request.return_value = {"session_id": "channel-session"}
                with self.assertRaisesRegex(RuntimeError, "after-create"):
                    execute_product_case(api, {"id": "D10"}, 1, {}, {"cases": {
                        "D10": {"agent_id": "agent", "user_id": "user"}}}, root, Mock(), Mock())
                self.assertEqual("test-only", channel.headers["X-Channel-Token"])
                api.request.assert_not_called()

    def test_amount_table_uses_result_column_not_formula_terms(self):
        from behavior_product_eval import _labeled_number
        table = "|口径|计算|金额|\n|---|---|---|\n|去重总额|A 100 + B 120 + C 150|370 元|"
        self.assertTrue(_labeled_number(table, "去重总额", 370))
        self.assertFalse(_labeled_number(table, "去重总额", 100))
        self.assertFalse(_labeled_number(table.replace("370 元", "490 元"), "去重总额", 370))

    def run_bridge(self, cid, *, resume=False, first_terminal=None):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        folder = root / "cases" / cid / "1"
        folder.mkdir(parents=True)
        c = candidate(cid)
        case = {"id": cid, "candidate": c, "turns": c["user_turns"], "expected": independent_expected(cid, 1)}
        resource = {"agent_id": "agent", "user_id": "user"}
        state = {"session_id": "session", "turns": {}}
        if resume:
            state["browser_dispatch_intent"] = True
            save(folder / "browser-scenario.json", {"sessions": [{"session_id": "session"}]})
        save(root / "browser-resources-private.json", {"channel": {}, "profiles": {cid: {"client_ref": "client", "identity_token": "fixture-only-identity"}}})
        receipts = [{"turn_index": i, "kind": "hitl" if cid == "D10" and i == 1 else "message",
                     "run_id": "run-0" if cid == "D10" else f"run-{i}", "input_id": f"input-{i}"}
                    for i in range(len(case["turns"]))]
        terminal = [{"run_id": r["run_id"], "status": "waiting_hitl" if cid == "D10" and i == 0 else
                     "cancelled" if cid == "D12" else "completed", "event": "execution.completed"}
                    for i, r in enumerate(receipts)]
        if first_terminal is not None:
            receipts = receipts[:1]
            terminal = [{**terminal[0], "status": first_terminal}]
        bundle = {"session": {"session_id": "session"}, "browser": {}, "network_requests": receipts, "terminal_facts": terminal}
        def invoke(env, **kwargs):
            if resume:
                self.assertNotIn("PRODUCT_PROCESS_READ_ONLY", env)
            save(folder / "browser-bundle.json", {"bundles": [bundle]})
        budget = ExecutionBudget(1000, 60, 1000)
        monitor = SimpleNamespace(reasons=[])
        with (patch("behavior_product_browser._environment", return_value={}), patch("behavior_product_browser._invoke", side_effect=invoke) as process,
              patch("behavior_product_browser.collect_product_usage", return_value={"tokens": 17, "unknown_usage_calls": 0})):
            execute_browser_case(Mock(), case, resource, state, folder, root, budget, monitor)
            self.assertEqual(1, process.call_count)
        self.assertEqual(17, budget.sessions["session"])
        return state, budget

    def test_short_browser_run_accounts_final_usage(self):
        state, budget = self.run_bridge("D09")
        self.assertEqual(2, budget.turns)
        self.assertEqual(2, len(state["turns"]))

    def test_known_first_failure_is_evaluated_without_dependent_input(self):
        for status in ("failed", "cancelled"):
            with self.subTest(status=status):
                state, budget = self.run_bridge("D09", first_terminal=status)
                self.assertEqual(1, len(state["turns"]))
                self.assertEqual(status, state["turns"]["0"]["result"]["terminal"]["status"])
                self.assertEqual(17, budget.sessions["session"])

    def test_missing_next_input_after_success_remains_incomplete(self):
        with self.assertRaisesRegex(ValueError, "browser_inputs_incomplete"):
            self.run_bridge("D09", first_terminal="completed")

    def test_hitl_first_terminal_cannot_borrow_resumed_completion(self):
        state, _ = self.run_bridge("D10")
        self.assertEqual("waiting_hitl", state["turns"]["0"]["result"]["terminal"]["status"])
        self.assertEqual("completed", state["turns"]["1"]["result"]["terminal"]["status"])

    def test_cancelled_terminal_belongs_to_original_input(self):
        state, budget = self.run_bridge("D12")
        self.assertEqual(2, budget.turns)
        self.assertEqual("cancelled", state["turns"]["0"]["result"]["terminal"]["status"])

    def test_unknown_browser_outcome_only_runs_readback(self):
        _, budget = self.run_bridge("D11", resume=True)
        self.assertEqual(0, budget.turns)

    def test_browser_intent_without_receipt_never_allows_resend(self):
        import json
        with TemporaryDirectory() as directory:
            output = Path(directory) / "browser.json"
            output.with_suffix(".json.journal.jsonl").write_text(json.dumps({
                "session_id": "session", "turn_index": 0, "phase": "intent"}) + "\n")
            with self.assertRaisesRegex(ValueError, "unknown_do_not_resend"):
                recover_browser_turns(Mock(), output, "session", [{"text": "one"}], ExecutionBudget(100, 2, 20), Mock())

    def test_browser_recovery_reads_existing_run_and_leaves_unsent_turn_unsubmitted(self):
        import json
        with TemporaryDirectory() as directory:
            output = Path(directory) / "browser.json"
            output.with_suffix(".json.journal.jsonl").write_text(json.dumps({
                "session_id": "session", "turn_index": 0, "phase": "receipt", "run_id": "original"}) + "\n")
            with patch("behavior_runner.read_run_status", return_value={"status": "completed"}) as status:
                api = Mock()
                api.request.return_value = {"events": []}
                recovered = recover_browser_turns(api, output, "session", [{"text": "one"}, {"text": "two"}],
                                                  ExecutionBudget(100, 2, 20), Mock())
            self.assertEqual(1, len(recovered))
            self.assertEqual("original", status.call_args.args[2])

    def test_browser_recovery_preserves_persisted_cancel_event_identity(self):
        import json
        with TemporaryDirectory() as directory:
            output = Path(directory) / "browser.json"
            output.with_suffix(".json.journal.jsonl").write_text(json.dumps({
                "session_id": "session", "turn_index": 0, "phase": "receipt", "run_id": "original"}) + "\n")
            api = Mock()
            api.request.return_value = {"events": [{"event": "execution.completed", "event_id": "persisted-1",
                "data": {"run_id": "original", "status": "cancelled"}}]}
            recovered = recover_browser_turns(api, output, "session", [{"cancel": True}], ExecutionBudget(100, 2, 20), Mock())
            self.assertEqual("persisted-1", recovered[0]["terminal"]["event_id"])
            self.assertEqual("persistent_output_readback", recovered[0]["terminal"]["source"])
            self.assertTrue(all(call.args[0] == "GET" for call in api.request.call_args_list))


if __name__ == "__main__":
    unittest.main()
