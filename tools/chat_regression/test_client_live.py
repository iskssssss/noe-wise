"""client 的认证刷新与实时 trace 读取回归测试。"""
from __future__ import annotations

import io
import json
from email.message import Message
from pathlib import Path
import sys
import threading
import time
import unittest
import urllib.error
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
from client import Api, ApiError, collect_trace, is_execution_terminal, poll_terminal  # noqa: E402


class Response:
    def __init__(self, value):
        self.value = value

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, *_):
        return json.dumps(self.value).encode("utf-8")


class ClientLiveTest(unittest.TestCase):
    base = "https://skillforge.test/api/v1"

    def test_outbox_continues_after_delegation_running_receipt_until_parent_completed(self):
        class Outbox:
            calls = 0

            def request(self, method, path):
                self.calls += 1
                if self.calls == 1:
                    return {"events": [
                        {"event": "execution.accepted", "event_id": "o:1",
                         "data": {"input_id": "m", "run_id": "run:m"}},
                        {"event": "execution.completed", "event_id": "o:2",
                         "data": {"run_id": "run:m", "status": "running"}},
                    ], "next_cursor": "o:2"}
                if self.calls == 2:
                    return {"events": [
                        {"event": "execution.completed", "event_id": "o:3",
                         "data": {"run_id": "run:m", "status": "queued"}},
                        {"event": "message.delta", "event_id": "o:4",
                         "data": {"run_id": "run:m", "content": "真实父任务结果"}},
                        {"event": "execution.completed", "event_id": "o:5",
                         "data": {"run_id": "run:m", "status": "completed"}},
                    ]}
                raise AssertionError("完成后不应继续轮询")

        api, observed = Outbox(), []
        result = poll_terminal(api, "s", "m", time.monotonic() + 2, observed.append)
        self.assertEqual("completed", result["status"])
        self.assertEqual(2, api.calls)
        self.assertEqual(["running", "queued", "completed"],
                         [event["data"]["status"] for event in observed if event["event"] == "execution.completed"])
        self.assertTrue(any(event["event"] == "message.delta" for event in observed))

    def test_execution_observation_keeps_controlled_hitl_and_rejects_nonterminal_statuses(self):
        for status in ("completed", "failed", "cancelled", "waiting_hitl"):
            self.assertTrue(is_execution_terminal("execution.completed", {"status": status}))
        for status in ("running", "queued", "", None):
            self.assertFalse(is_execution_terminal("execution.completed", {"status": status}))
        self.assertFalse(is_execution_terminal("execution.accepted", {"status": "completed"}))

    def test_due_refresh_precedes_business_request_and_uses_institution_header(self):
        requests = []

        def urlopen(request, timeout):
            requests.append(request)
            if request.full_url.endswith("/auth/refresh"):
                self.assertIsNone(request.get_header("Authorization"))
                return Response({"code": "OK", "data": {
                    "access_token": "new-access", "refresh_token": "new-refresh", "expires_in": 3600}})
            return Response({"code": "OK", "data": {"ready": True}})

        api = Api(self.base, "old-access", expires_in=10, refresh_token="refresh-value")
        with patch("client.urllib.request.OpenerDirector.open", side_effect=urlopen):
            self.assertEqual({"ready": True}, api.request("POST", "/chat", {"message": "one"}))

        self.assertEqual(["/auth/refresh", "/chat"], [request.full_url.removeprefix(self.base) for request in requests])
        self.assertEqual(json.dumps({"refresh_token": "refresh-value"}).encode(), requests[0].data)
        self.assertEqual("Bearer new-access", requests[1].get_header("Authorization"))

    def test_due_refresh_uses_platform_route_and_header(self):
        requests = []

        def urlopen(request, timeout):
            requests.append(request)
            if request.full_url.endswith("/platform/auth/refresh"):
                self.assertIsNone(request.get_header("X-platform-token"))
                return Response({"code": "OK", "data": {
                    "access_token": "new-platform", "refresh_token": "new-refresh", "expires_in": 3600}})
            return Response({"code": "OK", "data": {"ready": True}})

        api = Api(self.base, "old-platform", platform=True, expires_in=10, refresh_token="refresh-value")
        with patch("client.urllib.request.OpenerDirector.open", side_effect=urlopen):
            api.request("GET", "/platform/auth/me")

        self.assertEqual(["/platform/auth/refresh", "/platform/auth/me"],
                         [request.full_url.removeprefix(self.base) for request in requests])
        self.assertEqual("Bearer new-platform", requests[1].get_header("X-platform-token"))
        self.assertIsNone(requests[1].get_header("Authorization"))

    def test_failed_refresh_stops_without_retrying_business_write(self):
        calls = []

        def urlopen(request, timeout):
            calls.append(request.full_url)
            raise urllib.error.HTTPError(request.full_url, 500, "failed", Message(), io.BytesIO(b"refresh-secret"))

        api = Api(self.base, "old-access", expires_in=10, refresh_token="refresh-value")
        with patch("client.urllib.request.OpenerDirector.open", side_effect=urlopen):
            with self.assertRaises(ApiError) as first:
                api.request("POST", "/chat", {"message": "must-not-send"})
            with self.assertRaises(ApiError) as second:
                api.request("POST", "/chat", {"message": "must-not-send"})

        self.assertEqual(500, first.exception.status_code)
        self.assertNotIn("refresh-secret", str(first.exception))
        self.assertEqual(str(first.exception), str(second.exception))
        self.assertEqual([self.base + "/auth/refresh"], calls)

    def test_concurrent_requests_share_one_refresh(self):
        entered, release, requests, errors = threading.Event(), threading.Event(), [], []

        def urlopen(request, timeout):
            requests.append(request)
            if request.full_url.endswith("/auth/refresh"):
                entered.set()
                self.assertTrue(release.wait(1))
                return Response({"code": "OK", "data": {
                    "access_token": "new-access", "refresh_token": "new-refresh", "expires_in": 3600}})
            return Response({"code": "OK", "data": {"ready": True}})

        api = Api(self.base, "old-access", expires_in=10, refresh_token="refresh-value")

        def send():
            try:
                api.request("GET", "/chat/sessions")
            except BaseException as error:  # 线程异常应回到断言线程。
                errors.append(error)

        first, second = threading.Thread(target=send), threading.Thread(target=send)
        with patch("client.urllib.request.OpenerDirector.open", side_effect=urlopen):
            first.start()
            try:
                self.assertTrue(entered.wait(1))
                second.start()
            finally:
                release.set()
                first.join(1)
                second.join(1)

        self.assertFalse(first.is_alive())
        self.assertFalse(second.is_alive())
        self.assertEqual([], errors)
        self.assertEqual(1, sum(request.full_url.endswith("/auth/refresh") for request in requests))
        self.assertEqual(2, sum(request.full_url.endswith("/chat/sessions") for request in requests))

    def test_content_cache_keeps_pages_fresh_and_reloads_missing_content(self):
        class TraceApi:
            def __init__(self):
                self.round = 0
                self.calls = []
                self.content_reads = {}

            def request(self, method, path):
                self.calls.append(path)
                if "/content?" in path:
                    ref = path.rsplit("ref=", 1)[1]
                    self.content_reads[ref] = self.content_reads.get(ref, 0) + 1
                    if ref == "missing" and self.content_reads[ref] == 1:
                        return {"availability": "PENDING"}
                    return {"availability": "FULL", "value": {"event": ref}}
                self.round += 1
                return {"next_cursor": None, "usage_summary": {"tokens": self.round}, "rounds": [{"runs": [{"steps": [{
                    "phases": [{"event_id": "done", "event_type": "run.completed", "content_ref": "full",
                                "detail": {"attempts": [{"status": "RUNNING" if self.round == 1 else "SUCCEEDED"}]}},
                               {"event_id": "missing", "event_type": "run.completed", "content_ref": "missing"}],
                }]}]}]}

        api, cache = TraceApi(), {"missing": {"availability": "PENDING"}}
        first = collect_trace(api, "session", content_cache=cache)
        second = collect_trace(api, "session", content_cache=cache)

        self.assertEqual(2, len([path for path in api.calls if "/trace?" in path]))
        self.assertEqual(1, len([path for path in api.calls if "ref=full" in path]))
        self.assertEqual(2, len([path for path in api.calls if "ref=missing" in path]))
        self.assertEqual("SUCCEEDED", second["pages"][0]["rounds"][0]["runs"][0]["steps"][0]["phases"][0]["detail"]["attempts"][0]["status"])
        self.assertEqual(2, second["usage"]["tokens"])
        self.assertEqual("PENDING", first["contents"]["missing"]["availability"])
        self.assertEqual("FULL", cache["missing"]["availability"])

    def test_usage_only_trace_never_fetches_event_content(self):
        class TraceApi:
            def __init__(self):
                self.calls = []

            def request(self, method, path):
                self.calls.append(path)
                if "/content?" in path:
                    raise AssertionError("usage-only 读取不得请求正文")
                return {"next_cursor": None, "usage_summary": {"tokens": 7}, "rounds": [{"runs": [{"steps": [{
                    "phases": [{"event_id": "done", "event_type": "run.completed", "content_ref": "full"}],
                }]}]}]}

        api = TraceApi()
        trace = collect_trace(api, "session", include_content=False)
        self.assertEqual({"tokens": 7}, trace["usage"])
        self.assertEqual({}, trace["contents"])
        self.assertEqual(1, len(api.calls))


if __name__ == "__main__":
    unittest.main()
