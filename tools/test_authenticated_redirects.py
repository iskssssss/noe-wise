"""真实loopback跨origin重定向负控覆盖独立认证工具封装，不连接业务服务或模型。"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
for directory in ("full_project_testing", "chat_regression", "runtime"):
    sys.path.insert(0, str(ROOT / "tools" / directory))


def load(relative):
    spec = importlib.util.spec_from_file_location("redirect_" + Path(relative).stem.replace("-", "_"), ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AuthenticatedRedirectsTest(unittest.TestCase):
    def test_standalone_authenticated_clients_do_not_forward_to_redirect_target(self):
        browser = load("tools/browser/publish_browser_skill.py")
        lifecycle = load("tools/browser/verify-authorization-lifecycle.py")
        foundation = load("tools/full_project_testing/foundation_lifecycle_acceptance.py")
        host = load("tools/full_project_testing/host_business_acceptance.py")
        process = load("tools/full_project_testing/process_recovery.py")
        team = load("tools/full_project_testing/team_directory_acceptance.py")
        observation = load("tools/observation/persistent_acceptance.py")
        readiness = load("tools/runtime/check_agent_readiness.py")
        package = load("tools/skills/package_skills.py")
        multipart = load("tools/chat_regression/behavior_product_runner.py")
        target_requests, origin_requests, state = [], [], {"status": 302}

        class Target(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                target_requests.append(dict(self.headers))
                raw = b'{"code":"OK","data":{}}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            do_POST = do_GET

        target = ThreadingHTTPServer(("127.0.0.1", 0), Target)

        class Origin(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                origin_requests.append((self.command, self.path, dict(self.headers)))
                self.send_response(state["status"])
                self.send_header("Location", f"http://127.0.0.1:{target.server_port}/capture")
                self.send_header("Content-Length", "0")
                self.end_headers()

            do_POST = do_GET

        origin = ThreadingHTTPServer(("127.0.0.1", 0), Origin)
        threads = [threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}) for server in (target, origin)]
        for thread in threads:
            thread.start()
        try:
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                base = f"http://127.0.0.1:{origin.server_port}"
                token = "fixture-only-token"
                service_state = {"services": {name: {"base_url": base} for name in ("institution-service", "platform-service")}}
                observed = observation.Host(base + "/api/v1", SimpleNamespace())
                observed.token = token
                invocations = {
                    "browser-publication": lambda: browser.Platform(base, token).request("GET", "/read"),
                    "foundation": lambda: foundation.Api(service_state, root / "foundation.jsonl").call("GET", foundation.F + "/detail", token=token, fast=True),
                    "host-business": lambda: host.Api(base, root / "host.jsonl").call("GET", host.ACCESS + "/departments", token=token),
                    "process-recovery": lambda: process.Http(base, token).request("GET", "/read"),
                    "team-directory": lambda: team.Api(service_state, root / "team.jsonl").call("GET", team.G, token=token),
                    "persistent-observation": lambda: observed.request("GET", "/read"),
                    "readiness": lambda: readiness.Api(base + "/api/v1", token).request("GET", "/read"),
                    "skill-publication": lambda: package.Platform(base, token).request("GET", "/read"),
                    "multipart-upload": lambda: multipart._upload_session_attachment(SimpleNamespace(base=base + "/api/v1", headers={"Authorization": "Bearer " + token}, timeout=5), "/attachments", "session", "upload", "data.csv", b"x,y\n1,2"),
                }
                for status in (302, 307):
                    state["status"] = status
                    for name, invoke in invocations.items():
                        with self.subTest(status=status, client=name):
                            before = len(origin_requests)
                            with self.assertRaises((RuntimeError, AssertionError, urllib.error.HTTPError)):
                                invoke()
                            self.assertEqual(before + 1, len(origin_requests))
                            self.assertEqual([], target_requests)
                    config = root / f"browser-{status}.json"
                    config.write_text(json.dumps({"endpoint": base, "token": token}), encoding="utf-8")
                    with self.subTest(status=status, client="browser-authorization"), \
                            patch.object(sys, "argv", ["verify", "--service-config", str(config), "--evidence", str(root / f"evidence-{status}")]), \
                            contextlib.redirect_stdout(io.StringIO()):
                        self.assertEqual(1, lifecycle.main())
                        self.assertEqual([], target_requests)
                self.assertTrue(all(any(key.lower() in {"authorization", "x-platform-token", "x-browser-service-token"} for key in headers)
                                    for _, _, headers in origin_requests))
        finally:
            for server in (origin, target):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=2)
                self.assertFalse(thread.is_alive())


if __name__ == "__main__":
    unittest.main()
