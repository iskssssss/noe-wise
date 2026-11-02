"""专用行为测试环境收据与 Windows 网络隔离验证。"""
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
from behavior_environment import verify_environment


class BehaviorEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.jar_one = self.root / "api.jar"
        self.jar_two = self.root / "platform.jar"
        self.java_one = self.root / "jdk-one" / "bin" / "java.exe"
        self.java_two = self.root / "jdk-two" / "bin" / "java.exe"
        for path, content in ((self.jar_one, b"api-artifact"),
                              (self.jar_two, b"platform-artifact"),
                              (self.java_one, b"java-one"),
                              (self.java_two, b"java-two")):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        self.api = "http://127.0.0.1:8000/api/v1"
        self.platform = "http://127.0.0.1:8100/api/v1"
        self.revision = "deployment-7"
        self.receipt = {
            "schema_version": 1,
            "deployment_revision": self.revision,
            "api": self.api,
            "platform": self.platform,
            "processes": [
                {"pid": 101, "artifact_path": str(self.jar_one.resolve()),
                 "artifact_sha256": hashlib.sha256(self.jar_one.read_bytes()).hexdigest()},
                {"pid": 202, "artifact_path": str(self.jar_two.resolve()),
                 "artifact_sha256": hashlib.sha256(self.jar_two.read_bytes()).hexdigest()},
            ],
            "denied_sentinel_url": "http://10.77.0.9:18080/blocked",
            "sentinel_ready_url": "http://127.0.0.1:19090/status",
            "sentinel_nonce": "nonce-for-this-run",
            "allowed_remote_ips": ["198.51.100.20"],
            "verified_at": datetime.now(timezone.utc).isoformat(),
        }

    def tearDown(self):
        self.temporary.cleanup()

    def _write_receipt(self):
        path = self.root / "receipt.json"
        path.write_text(json.dumps(self.receipt), encoding="utf-8")
        return path

    def _process_rows(self):
        return [
            {"ProcessId": 101, "CommandLine": f'java -jar "{self.jar_one.resolve()}"',
             "ExecutablePath": str(self.java_one.resolve())},
            {"ProcessId": 202, "CommandLine": f'java -jar "{self.jar_two.resolve()}"',
             "ExecutablePath": str(self.java_two.resolve())},
        ]

    @staticmethod
    def _listener_rows():
        return [
            {"LocalAddress": "127.0.0.1", "LocalPort": 8000, "OwningProcess": 101},
            {"LocalAddress": "127.0.0.1", "LocalPort": 8100, "OwningProcess": 202},
        ]

    @staticmethod
    def _valid_rules():
        return [
            {"Name": "model-endpoint", "Programs": ["Any"],
             "RemoteAddresses": ["198.51.100.20"], "Protocols": [6],
             "LocalPorts": ["Any"], "RemotePorts": [443]},
            {"Name": "local-services", "Programs": ["Any"],
             "RemoteAddresses": ["127.0.0.1"], "Protocols": ["TCP"],
             "LocalPorts": ["Any"], "RemotePorts": [8000, 8100, 19090]},
        ]

    def _environment(self, *, processes=None, listeners=None, rules=None, profiles=None,
                     sentinel=None, probe_codes=None):
        stack = ExitStack()
        stack.enter_context(patch("behavior_environment.IS_WINDOWS", True))
        stack.enter_context(patch("behavior_environment._read_process_rows",
                                  return_value=processes if processes is not None else self._process_rows()))
        stack.enter_context(patch("behavior_environment._read_listener_rows",
                                  return_value=listeners if listeners is not None else self._listener_rows()))
        stack.enter_context(patch("behavior_environment._read_firewall_profiles", return_value=(
            profiles if profiles is not None else [
                {"Name": "Domain", "Enabled": True, "DefaultOutboundAction": "Block"},
                {"Name": "Private", "Enabled": True, "DefaultOutboundAction": 4},
                {"Name": "Public", "Enabled": True, "DefaultOutboundAction": "Block"},
            ])))
        stack.enter_context(patch("behavior_environment._read_firewall_rules",
                                  return_value=rules if rules is not None else self._valid_rules()))
        stack.enter_context(patch("behavior_environment._read_sentinel_status", return_value=(
            sentinel if sentinel is not None else {
                "nonce": "nonce-for-this-run", "target_host": "10.77.0.9",
                "target_port": 18080, "listening": True,
            })))
        probe = stack.enter_context(patch("behavior_environment._run_egress_probe",
                                          side_effect=probe_codes)) if probe_codes is not None else (
            stack.enter_context(patch("behavior_environment._run_egress_probe", return_value=42)))
        return stack, probe

    def _verify(self, **environment):
        stack, probe = self._environment(**environment)
        with stack:
            result = verify_environment(self._write_receipt(), self.revision, self.api, self.platform)
        return result, probe

    def test_valid_receipt_checks_each_actual_java_executable(self):
        result, probe = self._verify(probe_codes=[42, 42])

        self.assertEqual(self.revision, result["deployment_revision"])
        self.assertEqual(2, probe.call_count)
        self.assertEqual(
            {str(self.java_one.resolve()), str(self.java_two.resolve())},
            {str(item.args[0]) for item in probe.call_args_list})
        self.assertTrue(all(item.args[1:] == ("10.77.0.9", 18080)
                            for item in probe.call_args_list))

    def test_arbitrary_running_pid_cannot_replace_api_listener_owner(self):
        listeners = self._listener_rows()
        listeners[0]["OwningProcess"] = 999

        with self.assertRaisesRegex(ValueError, "api_listener_process_mismatch"):
            self._verify(listeners=listeners)

    def test_process_command_line_requires_exact_absolute_jar_argument(self):
        processes = self._process_rows()
        processes[0]["CommandLine"] = f'java -jar "{self.jar_one.resolve()}.backup"'

        with self.assertRaisesRegex(ValueError, "deployment_process_does_not_run_artifact"):
            self._verify(processes=processes)

    def test_default_deny_does_not_excuse_allow_all_rule(self):
        rules = self._valid_rules() + [{
            "Name": "unsafe-any", "Programs": ["Any"], "RemoteAddresses": ["Any"],
            "Protocols": [6], "LocalPorts": ["Any"], "RemotePorts": [443],
        }]

        with self.assertRaisesRegex(ValueError, "firewall_remote_address_not_exact"):
            self._verify(rules=rules)

    def test_relevant_allow_rule_requires_exact_approved_remote_port(self):
        for unsafe_port in ("Any", "80-443", 8443):
            with self.subTest(remote_port=unsafe_port):
                rules = self._valid_rules()
                rules[0] = {**rules[0], "RemotePorts": [unsafe_port]}
                with self.assertRaisesRegex(
                        ValueError, "firewall_remote_port_not_(?:exact|approved)"):
                    self._verify(rules=rules)

    def test_only_socket_timeout_is_accepted_as_denial(self):
        for code in (41, 43, 44):
            with self.subTest(exit_code=code):
                with self.assertRaisesRegex(ValueError, "egress_probe_not_blocked_by_timeout"):
                    self._verify(probe_codes=[code, 42])

    def test_receipt_older_than_thirty_minutes_is_rejected_before_system_queries(self):
        self.receipt["verified_at"] = (
            datetime.now(timezone.utc) - timedelta(minutes=31)).isoformat()
        with patch("behavior_environment.IS_WINDOWS", True), \
                patch("behavior_environment._read_process_rows") as process_query:
            with self.assertRaisesRegex(ValueError, "environment_receipt_stale"):
                verify_environment(self._write_receipt(), self.revision, self.api, self.platform)
        process_query.assert_not_called()

    def test_ipv4_and_ipv6_ranges_are_not_exact_firewall_addresses(self):
        for unsafe in ("198.51.100.20-198.51.100.21", "2001:db8::1-2001:db8::2",
                       "198.51.100.20/32", "2001:db8::1/128"):
            with self.subTest(address=unsafe):
                rules = self._valid_rules()
                rules[0] = {**rules[0], "RemoteAddresses": [unsafe]}
                with self.assertRaisesRegex(ValueError, "firewall_remote_address_not_exact"):
                    self._verify(rules=rules)

    def test_application_filter_ignores_unrelated_program_but_checks_each_java_path(self):
        unrelated = self._valid_rules() + [{
            "Name": "unrelated", "Programs": [str(self.root / "other.exe")],
            "RemoteAddresses": ["Any"], "Protocols": ["Any"],
            "LocalPorts": ["Any"], "RemotePorts": ["Any"],
        }]
        self._verify(rules=unrelated)

        matching = unrelated + [{
            "Name": "matching-java", "Programs": [str(self.java_two.resolve())],
            "RemoteAddresses": ["Internet"], "Protocols": [6],
            "LocalPorts": ["Any"], "RemotePorts": [443],
        }]
        with self.assertRaisesRegex(ValueError, "firewall_remote_address_not_exact"):
            self._verify(rules=matching)

    def test_sentinel_control_must_match_nonce_target_and_listening_state(self):
        invalid_statuses = [
            {"nonce": "wrong", "target_host": "10.77.0.9", "target_port": 18080,
             "listening": True},
            {"nonce": "nonce-for-this-run", "target_host": "10.77.0.8", "target_port": 18080,
             "listening": True},
            {"nonce": "nonce-for-this-run", "target_host": "10.77.0.9", "target_port": 18080,
             "listening": False},
        ]
        for status in invalid_statuses:
            with self.subTest(status=status):
                with self.assertRaises(ValueError):
                    self._verify(sentinel=status)


if __name__ == "__main__":
    unittest.main()
