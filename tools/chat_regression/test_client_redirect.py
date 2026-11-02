"""真实本机重定向不得把认证令牌或写入转发到另一origin。"""
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from client import Api, ApiError


class RedirectIsolationTest(unittest.TestCase):
    def test_302_and_307_never_forward_credentials_or_replay_requests(self):
        target_requests, origin_requests = [], []

        class Target(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                target_requests.append(dict(self.headers))
                self.send_response(200)
                self.end_headers()

            do_POST = do_GET

        target = ThreadingHTTPServer(("127.0.0.1", 0), Target)

        class Origin(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                origin_requests.append((self.command, self.path))
                self.send_response(int(self.path.rsplit("/", 1)[1]))
                self.send_header("Location", f"http://127.0.0.1:{target.server_port}/capture")
                self.send_header("Content-Length", "0")
                self.end_headers()

            do_POST = do_GET

        origin = ThreadingHTTPServer(("127.0.0.1", 0), Origin)
        threads = [threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05})
                   for server in (target, origin)]
        for thread in threads:
            thread.start()
        try:
            for platform in (False, True):
                api = Api(f"http://127.0.0.1:{origin.server_port}", "fixture-only-token", platform=platform)
                for status in (302, 307):
                    for method in ("GET", "POST"):
                        with self.subTest(platform=platform, status=status, method=method):
                            with self.assertRaises(ApiError) as failure:
                                api.request(method, f"/redirect/{status}", {} if method == "POST" else None)
                            self.assertEqual(status, failure.exception.status_code)
                            self.assertNotIn("fixture-only-token", str(failure.exception))
            self.assertEqual(8, len(origin_requests))
            self.assertEqual([], target_requests)
        finally:
            for server in (origin, target):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=2)
                self.assertFalse(thread.is_alive())


if __name__ == "__main__":
    unittest.main()
