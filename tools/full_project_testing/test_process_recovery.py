"""恢复 harness 的协议、屏障、断言与进程所有权负控；不启动业务服务。"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.request

import process_recovery as recovery


class ProcessRecoveryHarnessTest(unittest.TestCase):
    def request(self, provider, payload):
        request = urllib.request.Request(provider.endpoint + "/chat/completions",
            data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.read().decode()

    def test_native_human_request_and_approved_stream_keep_original_fixture_marker(self):
        provider = recovery.Provider()
        provider.start()
        try:
            prompt = {"model": "synthetic", "messages": [{"role": "user", "content": "RECOVERY:human:original-42"}],
                "tools": [{"function": {"name": "sf_agent_action"}}]}
            first = json.loads(self.request(provider, prompt))
            decision = first["choices"][0]["message"]["tool_calls"][0]
            self.assertEqual("sf_agent_action", decision["function"]["name"])
            self.assertEqual("ask_user", json.loads(decision["function"]["arguments"])["type"])
            self.assertEqual("tool_calls", first["choices"][0]["finish_reason"])
            prompt["messages"].append({"role": "user", "content": "RECOVERY_APPROVED:original-42"})
            prompt["stream"] = True
            wire = self.request(provider, prompt)
            events = [json.loads(line[6:]) for line in wire.splitlines() if line.startswith("data: ") and line != "data: [DONE]"]
            self.assertEqual("恢复验收结果:original-42", events[0]["choices"][0]["delta"]["content"])
            self.assertEqual("stop", events[-1]["choices"][0]["finish_reason"])
            self.assertEqual(18, events[-1]["usage"]["total_tokens"])
            self.assertEqual(["ask_user", "human"], [call["category"] for call in provider.calls])
            self.assertEqual(2, provider.count())
        finally:
            provider.close()

    def test_actual_http_barrier_records_dispatch_before_allowing_response(self):
        provider = recovery.Provider()
        provider.start()
        completed = threading.Event()
        responses = []

        def call():
            try:
                responses.append(self.request(provider, {"model": "synthetic", "messages": [
                    {"role": "user", "content": "RECOVERY:held_consumed:external-1"}]}))
            finally:
                completed.set()

        worker = threading.Thread(target=call, daemon=False)
        try:
            worker.start()
            self.assertTrue(provider.entered.wait(5), "必须观察到真实外调到达屏障")
            self.assertEqual(1, provider.count())
            self.assertFalse(completed.is_set(), "释放前不能生成终态响应")
            provider.release.set()
            worker.join(10)
            self.assertFalse(worker.is_alive())
            self.assertEqual("恢复验收结果:external-1", json.loads(responses[0])["choices"][0]["message"]["content"])
        finally:
            provider.release.set()
            worker.join(10)
            provider.close()

    def test_changed_checkpoint_attempt_and_receipt_fail_the_exact_oracle(self):
        original = {"run": {"run_id": "run:1", "status": "COMPLETED"},
            "checkpoints": [{"content_hash": "old", "payload_sha256": "original"}],
            "attempts": [{"attempt_id": "attempt:1", "fact_ref": "fact:1"}]}
        recovery.unchanged(original, json.loads(json.dumps(original)), tuple(original), "exact")
        for key in original:
            changed = json.loads(json.dumps(original))
            changed[key] = {"tampered": True}
            with self.assertRaisesRegex(AssertionError, "exact_" + key):
                recovery.unchanged(original, changed, tuple(original), "exact")
        first = {"execution_id": "run:1", "input_id": "input:1", "replayed": False}
        self.assertEqual(recovery.receipt_identity(first), recovery.receipt_identity(dict(first, replayed=True)))
        self.assertNotEqual(recovery.receipt_identity(first), recovery.receipt_identity(dict(first, input_id="input:2")))

    def test_postgres_bytea_hashes_exact_bytes_instead_of_memoryview_object_identity(self):
        payload = "原始 checkpoint🧭".encode("utf-8")
        self.assertEqual(recovery.digest(payload), recovery.digest(memoryview(payload)))
        self.assertEqual(recovery.digest(memoryview(payload)), recovery.digest(memoryview(bytes(bytearray(payload)))))
        self.assertNotEqual(recovery.digest(payload), recovery.digest(payload + b"tampered"))

    def test_one_attempt_preserves_both_started_and_terminal_evidence_revisions(self):
        facts = {"attempts": [
            {"action_id": "action:1", "attempt_id": "attempt:1", "evidence_revision": 1, "status": "STARTED"},
            {"action_id": "action:1", "attempt_id": "attempt:1", "evidence_revision": 2, "status": "SUCCEEDED"}]}
        self.assertEqual(1, recovery.attempt_count(facts))
        facts["attempts"].append({"action_id": "action:1", "attempt_id": "attempt:2", "evidence_revision": 1, "status": "STARTED"})
        self.assertEqual(2, recovery.attempt_count(facts), "重试产生新 attempt 时必须识别新增外调许可")

    def test_public_history_freeze_requires_committed_outbox_and_latest_source_checkpoint(self):
        completed = {"source_sequence": 17, "projection_checkpoint_sequence": 17,
            "projection_error_code": None, "total": 9}
        self.assertTrue(recovery.publication_is_complete(completed))
        self.assertFalse(recovery.publication_is_complete(None))
        for changes in ({"projection_checkpoint_sequence": 16}, {"source_sequence": None},
                        {"projection_checkpoint_sequence": None}, {"projection_error_code": "rejected"},
                        {"total": 0}):
            with self.subTest(changes=changes):
                self.assertFalse(recovery.publication_is_complete(dict(completed, **changes)))

    def test_database_rejects_workspace_database_and_remote_hosts_before_connect(self):
        for url in ("jdbc:postgresql://127.0.0.1:5433/skillforge", "jdbc:postgresql://localhost:11111/test",
                    "jdbc:postgresql://192.0.2.1:11111/test", "jdbc:postgresql://127.0.0.1:11111/test?service=other"):
            with self.subTest(url=url), self.assertRaisesRegex(AssertionError, "isolated_pg_required"):
                recovery.Database({"SPRING_DATASOURCE_URL": url})

    def test_force_kill_only_reaps_the_popen_created_by_this_test(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            jar = root / "source.jar"
            jar.write_bytes(b"synthetic fixture, never executed")
            owned_root = root / "owned"
            owned_root.mkdir()
            app = recovery.OwnedApplication(owned_root, jar, {}, "42")
            process = subprocess.Popen([sys.executable, "-u", "-c",
                "import sys; print('READY', flush=True); sys.stdin.read()"], stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, text=True,
                creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)
            try:
                self.assertEqual("READY", process.stdout.readline().strip())
                app.process = process
                app.lifecycle.append({"pid": process.pid})
                app.kill("test_controlled_boundary")
                self.assertIsNotNone(process.poll())
                self.assertEqual("FORCE_KILL", app.lifecycle[0]["termination"])
                with self.assertRaisesRegex(AssertionError, "only_live_owned_process"):
                    app.kill("must_not_rekill")
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(10)
                process.stdin.close()
                process.stdout.close()


if __name__ == "__main__":
    unittest.main()
