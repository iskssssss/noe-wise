"""正式材料消费者的真实本地 HTTP 测试；服务端替身不作为产品授权验收。"""
from __future__ import annotations

import hashlib
import hmac
import io
import json
import os
import sys
import tempfile
import threading
import unittest
import uuid
from contextlib import contextmanager, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import URLError
from urllib.parse import parse_qs, urlparse
from unittest.mock import patch

from client import ApiFailure, Client, Credential
from media_deliverables import MediaDeliverables, UploadOutcomeUnknown, main


@contextmanager
def public_server(handler):
    """每例使用独立端口和线程；记录实际签名请求，关闭时回收服务线程。"""
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.dispatch()

        def do_POST(self):
            self.dispatch()

        def dispatch(self):
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            canonical = "\n".join((self.headers["X-Timestamp"], self.command, self.path, hashlib.sha256(raw).hexdigest()))
            signature = hmac.new(b"test-secret", canonical.encode(), hashlib.sha256).hexdigest()
            if self.headers.get("X-Api-Key") != "test-key" or not hmac.compare_digest(signature, self.headers.get("X-Signature", "")):
                self.send_response(401)
                self.end_headers()
                return
            requests.append({"method": self.command, "path": self.path, "headers": dict(self.headers), "body": raw})
            status, content_type, content, headers = handler(requests[-1])
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            for key, value in headers.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(content)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield MediaDeliverables(Client("http://127.0.0.1:" + str(server.server_port),
                                      credential=Credential("test-key", "test-secret"))), requests
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)


def envelope(data, status=200):
    return status, "application/json", json.dumps({"code": "OK", "data": data}).encode(), {}


class MediaDeliverablesTest(unittest.TestCase):
    content = "真实成果的模拟供应商结果\n".encode()

    def revision(self):
        return {"id": "revision:1", "session_id": "session:1", "run_id": "run:1", "status": "ready",
                "files": [{"file_id": "file:1/中文", "artifact_id": "artifact:1", "path": "../../untrusted.txt",
                           "download_name": "../../outside.txt", "size": len(self.content),
                           "sha256": hashlib.sha256(self.content).hexdigest(), "format": "txt"}]}

    def routes(self, *, revision=None, status="completed", content=None, download_status=200):
        """仅替代公开服务端，消费者仍发真实 HTTP 和原目标签名。"""
        revision = self.revision() if revision is None else revision

        def respond(request):
            path = urlparse(request["path"]).path
            if path.endswith("/status"):
                return envelope({"status": status})
            if path.endswith("/deliverables"):
                return envelope([revision])
            if path.endswith("/deliverables/file"):
                return (download_status, "application/octet-stream", self.content if content is None else content, {})
            raise AssertionError("UNEXPECTED_REQUEST")

        return respond

    def test_signed_binary_preserves_original_session_uuid_filename_and_file_bytes(self):
        upload_id = str(uuid.uuid4())
        content = b"\x00\xfffixture\r\nbytes"
        with public_server(lambda request: envelope({"id": upload_id})) as (consumer, requests):
            receipt = consumer.upload("session:1", upload_id, "输入.json", content)
        self.assertEqual(upload_id, receipt["id"])
        self.assertEqual(1, len(requests))
        request = requests[0]
        self.assertEqual("/api/v1/open-platform/sessions/attachments", urlparse(request["path"]).path)
        self.assertEqual({"sessionId": ["session:1"], "uploadId": [upload_id], "filename": ["输入.json"]},
                         parse_qs(urlparse(request["path"]).query))
        self.assertEqual(content, request["body"])
        self.assertEqual("application/octet-stream", request["headers"]["Content-Type"])
        self.assertNotIn("Authorization", request["headers"])

    def test_image_uses_formal_media_route(self):
        with public_server(lambda request: envelope({"media_id": "registered-media"})) as (consumer, requests):
            result = consumer.upload("session:1", str(uuid.uuid4()), "image.png", b"fixture-image", image=True)
        self.assertEqual("registered-media", result["media_id"])
        self.assertEqual("/api/v1/open-platform/sessions/media", urlparse(requests[0]["path"]).path)
        self.assertNotIn("filename", parse_qs(urlparse(requests[0]["path"]).query))

    def test_completed_original_run_downloads_exact_current_authorized_revision_and_bytes(self):
        with tempfile.TemporaryDirectory() as directory, public_server(self.routes()) as (consumer, requests):
            result = consumer.collect("session:1", "run:1", Path(directory))
            destination = Path(result["files"][0]["local_file"])
            self.assertEqual(Path(directory), destination.parent)
            self.assertEqual(self.content, destination.read_bytes())
            self.assertEqual("public_http_deliverable_consumer", result["evidence_kind"])
        self.assertEqual(3, len(requests))
        self.assertEqual({"sessionId": ["session:1"], "revisionId": ["revision:1"], "fileId": ["file:1/中文"]},
                         parse_qs(urlparse(requests[-1]["path"]).query))

    def test_noncompleted_run_never_reads_or_downloads_deliverables(self):
        for status in ("running", "failed", "terminated", "suspended", "cancelled"):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory, public_server(self.routes(status=status)) as (consumer, requests):
                with self.assertRaisesRegex(ValueError, "ORIGINAL_RUN_MUST_BE_COMPLETED"):
                    consumer.collect("session:1", "run:1", Path(directory))
                self.assertEqual(1, len(requests))
                self.assertEqual([], list(Path(directory).iterdir()))

    def test_foreign_run_session_and_unpublished_reference_are_not_downloaded(self):
        for changed in ({"run_id": "another-run"}, {"session_id": "another-session"}, {"status": "pending"}):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as directory, public_server(self.routes(revision=self.revision() | changed)) as (consumer, requests):
                with self.assertRaisesRegex(ValueError, "ORIGINAL_RUN_PUBLISHED_DELIVERABLE_REQUIRED"):
                    consumer.collect("session:1", "run:1", Path(directory))
                self.assertEqual(2, len(requests))

    def test_invalid_exact_file_metadata_stops_before_download(self):
        for changed in ({"size": -1}, {"size": True}, {"sha256": "fixture:tts:1"}, {"file_id": ""}):
            revision = self.revision()
            revision["files"][0].update(changed)
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as directory, public_server(self.routes(revision=revision)) as (consumer, requests):
                with self.assertRaisesRegex(ValueError, "EXACT_PUBLISHED_FILE_METADATA_REQUIRED"):
                    consumer.collect("session:1", "run:1", Path(directory))
                self.assertEqual(2, len(requests))

    def test_duplicate_file_selector_cannot_download_twice(self):
        revision = self.revision()
        revision["files"] *= 2
        with tempfile.TemporaryDirectory() as directory, public_server(self.routes(revision=revision)) as (consumer, requests):
            with self.assertRaisesRegex(ValueError, "EXACT_PUBLISHED_FILE_METADATA_REQUIRED"):
                consumer.collect("session:1", "run:1", Path(directory))
            self.assertEqual(2, len(requests))

    def test_hash_and_length_mismatch_leave_no_local_success_file(self):
        for content in (self.content + b"extra", self.content[:-1], b"x" * len(self.content)):
            with self.subTest(content=content), tempfile.TemporaryDirectory() as directory, public_server(self.routes(content=content)) as (consumer, requests):
                with self.assertRaisesRegex(ValueError, "PUBLISHED_FILE_SIZE_OR_SHA256_MISMATCH"):
                    consumer.collect("session:1", "run:1", Path(directory))
                self.assertEqual(3, len(requests))
                self.assertEqual([], list(Path(directory).iterdir()))

    def test_revoked_download_does_not_use_previous_list_as_permission(self):
        with tempfile.TemporaryDirectory() as directory, public_server(self.routes(download_status=403)) as (consumer, requests):
            with self.assertRaises(ApiFailure) as failure:
                consumer.collect("session:1", "run:1", Path(directory))
            self.assertEqual(403, failure.exception.status)
            self.assertEqual([], list(Path(directory).iterdir()))
            self.assertEqual(3, len(requests))

    def test_redirect_is_not_followed_with_execution_credentials(self):
        with public_server(lambda request: (302, "text/plain", b"", {"Location": "/credential-leak"})) as (consumer, requests):
            with self.assertRaises(ApiFailure) as failure:
                consumer.upload("session:1", str(uuid.uuid4()), "input.txt", b"one")
            self.assertEqual(302, failure.exception.status)
            self.assertEqual(1, len(requests))

    def test_unknown_upload_is_not_retried(self):
        class Unknown:
            count = 0

            def open(self, request, timeout):
                self.count += 1
                raise URLError("fixture disconnect")

        client = Client("http://127.0.0.1:1", credential=Credential("test-key", "test-secret"))
        client.opener = Unknown()
        with self.assertRaisesRegex(RuntimeError, "HTTP_OUTCOME_UNKNOWN"):
            MediaDeliverables(client).upload("session:1", str(uuid.uuid4()), "input.txt", b"one")
        self.assertEqual(1, client.opener.count)

    def test_unsafe_filename_is_rejected_before_dispatch(self):
        consumer = MediaDeliverables(Client("http://127.0.0.1:1", credential=Credential("key", "secret")))
        for filename in ("../input.txt", 'input"\r\nheader.txt', "a\\input.txt"):
            with self.subTest(filename=filename), self.assertRaises(ValueError):
                consumer.upload("session:1", str(uuid.uuid4()), filename, b"one")

    def test_malformed_successful_upload_receipt_preserves_unknown_without_retry(self):
        for raw in (b"{", b"[]", b'{"code":"OK"}', b'{"code":"OK","data":null}'):
            with self.subTest(raw=raw), public_server(lambda request: (200, "application/json", raw, {})) as (consumer, requests):
                with self.assertRaises(UploadOutcomeUnknown):
                    consumer.upload("session:1", str(uuid.uuid4()), "input.txt", b"one")
                self.assertEqual(1, len(requests))

    def test_truncated_upload_receipt_and_server_error_preserve_unknown(self):
        for response in ((200, "application/json", b'{"code":', {"Content-Length": "100"}),
                         (500, "application/json", b'{"error":"server_failure"}', {})):
            with self.subTest(status=response[0]), public_server(lambda request: response) as (consumer, requests):
                with self.assertRaises(UploadOutcomeUnknown):
                    consumer.upload("session:1", str(uuid.uuid4()), "input.txt", b"one")
                self.assertEqual(1, len(requests))

    def test_cli_records_original_upload_id_as_unknown_after_invalid_receipt(self):
        with tempfile.TemporaryDirectory() as root, public_server(lambda request: (200, "application/json", b"{", {})) as (consumer, requests):
            source, evidence = Path(root) / "input.txt", Path(root) / "evidence.json"
            source.write_bytes(b"one")
            upload_id = str(uuid.uuid4())
            arguments = ["media_deliverables.py", "--origin", consumer.client.origin, "--session-id", "session:1",
                         "--evidence", str(evidence), "upload", "--upload-id", upload_id, "--file", str(source)]
            with patch.dict(os.environ, {"SKILLFORGE_MEDIA_API_KEY": "test-key", "SKILLFORGE_MEDIA_SIGNING_SECRET": "test-secret"}), patch.object(sys, "argv", arguments), redirect_stdout(io.StringIO()):
                self.assertEqual(1, main())
            result = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual("UNKNOWN", result["status"])
            self.assertEqual(upload_id, result["upload_id"])
            self.assertEqual(1, len(requests))

    def test_download_never_opens_preexisting_predictable_temporary_link(self):
        with tempfile.TemporaryDirectory() as root, public_server(self.routes()) as (consumer, requests):
            directory = Path(root) / "files"
            directory.mkdir()
            outside = Path(root) / "outside.txt"
            outside.write_bytes(b"must remain unchanged")
            name = hashlib.sha256("revision:1\0file:1/中文".encode()).hexdigest() + ".bin.tmp"
            link = directory / name
            try:
                link.symlink_to(outside)
            except OSError:
                # Windows 未开放符号链接时，硬链接同样证明旧可预测路径会覆盖目录外文件。
                link.hardlink_to(outside)
            result = consumer.collect("session:1", "run:1", directory)
            self.assertEqual(b"must remain unchanged", outside.read_bytes())
            self.assertEqual(self.content, Path(result["files"][0]["local_file"]).read_bytes())
            self.assertEqual([], list(directory.glob(".noewise-*.tmp")))


if __name__ == "__main__":
    unittest.main()
