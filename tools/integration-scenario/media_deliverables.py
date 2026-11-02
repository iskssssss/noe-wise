"""消费正式会话附件、图片和已完成 Run 的成果；不把模拟媒体引用当作文件。"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import re
import tempfile
import uuid
from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request

from client import ApiFailure, Client, Credential

LOG = logging.getLogger(__name__)
BASE = "/open-platform/sessions"


class UploadOutcomeUnknown(RuntimeError):
    """已派发上传未取得有效回执；原 upload_id 必须保留且不能自动重做。"""


class MediaDeliverables:
    """复用正式客户端的固定执行身份、签名和禁止重定向策略。"""

    def __init__(self, client: Client):
        if not client.credential:
            raise ValueError("INSTITUTION_EXECUTION_CREDENTIAL_REQUIRED")
        self.client = client

    def _binary(self, method: str, path: str, body: bytes | None = None, *, query=None,
                content_type: str | None = None, expected_size: int | None = None):
        """只派发一次；签名覆盖原始字节和原始 query，失败不重做上传。"""
        target = "/api/v1" + path + ("?" + urlencode(query) if query else "")
        headers = {"Accept": "application/octet-stream" if expected_size is not None else "application/json"}
        headers.update(self.client.credential.headers(method, target, body or b""))
        if content_type:
            headers["Content-Type"] = content_type
        LOG.debug("正式材料HTTP进入 method=%s path=%s", method, path)
        try:
            with self.client.opener.open(Request(self.client.origin + target, data=body,
                                                headers=headers, method=method), timeout=90) as response:
                if expected_size is not None:
                    if response.status != 200:
                        raise ApiFailure(response.status, "PUBLISHED_FILE_RESPONSE_REQUIRED")
                    return response.read(expected_size + 1)
                envelope = json.loads(response.read())
                if (not isinstance(envelope, dict) or envelope.get("code") != "OK"
                        or not isinstance(envelope.get("data"), dict)):
                    raise ValueError("VALID_UPLOAD_RECEIPT_REQUIRED")
                return envelope["data"]
        except HTTPError as failure:
            if method == "POST" and failure.code >= 500:
                raise UploadOutcomeUnknown("HTTP_UPLOAD_OUTCOME_UNKNOWN: inspect the original upload_id") from None
            try:
                code = json.loads(failure.read()).get("code", "INVALID_ENVELOPE")
            except (ValueError, AttributeError, HTTPException):
                code = "INVALID_ENVELOPE"
            raise ApiFailure(failure.code, str(code)) from None
        except (URLError, TimeoutError, OSError, HTTPException, ValueError) as failure:
            if method == "POST":
                raise UploadOutcomeUnknown("HTTP_OUTCOME_UNKNOWN: preserve the upload_id and inspect the original Session") from None
            raise RuntimeError("HTTP_FILE_READ_FAILED") from failure
        finally:
            LOG.debug("正式材料HTTP退出 method=%s path=%s", method, path)

    def upload(self, session_id: str, upload_id: str, filename: str, content: bytes, *, image=False) -> dict:
        """附件或图片先走会话授权登记；音视频 Connector 不扩展原生图片 MediaRef。"""
        uuid.UUID(upload_id)
        if (not isinstance(session_id, str) or not session_id or not filename or len(filename) > 128
                or any(ord(char) < 32 or ord(char) == 127 or char in '/\\' for char in filename)):
            raise ValueError("EXPLICIT_SESSION_AND_SAFE_UPLOAD_FILENAME_REQUIRED")
        if not isinstance(content, bytes) or not content:
            raise ValueError("NONEMPTY_UPLOAD_BYTES_REQUIRED")
        query = {"sessionId": session_id, "uploadId": upload_id}
        if not image:
            query["filename"] = filename
        return self._binary("POST", BASE + ("/media" if image else "/attachments"), content,
                            query=query, content_type="application/octet-stream")

    def collect(self, session_id: str, run_id: str, directory: Path) -> dict:
        """只读取精确原 Run 的已发布版本；下载前后由服务器复验当前授权。"""
        if not session_id or not run_id:
            raise ValueError("EXPLICIT_SESSION_AND_RUN_REQUIRED")
        status = self.client.request("GET", BASE + "/status", query={"sessionId": session_id, "runId": run_id})
        if status.get("status") != "completed":
            raise ValueError("ORIGINAL_RUN_MUST_BE_COMPLETED")
        revisions = self.client.request("GET", BASE + "/deliverables", query={"sessionId": session_id, "history": "true"})
        selected = [revision for revision in revisions if revision.get("run_id") == run_id
                    and revision.get("session_id") == session_id and revision.get("status") == "ready"]
        if not selected:
            raise ValueError("ORIGINAL_RUN_PUBLISHED_DELIVERABLE_REQUIRED")
        identifiers, files = set(), []
        for revision in selected:
            if not isinstance(revision.get("id"), str) or not revision["id"] or not revision.get("files"):
                raise ValueError("PUBLISHED_REVISION_AND_FILE_LIST_REQUIRED")
            for file in revision["files"]:
                identifier = (revision["id"], file.get("file_id"))
                size, digest = file.get("size"), file.get("sha256")
                if (not isinstance(identifier[1], str) or not identifier[1] or identifier in identifiers
                        or type(size) is not int or size < 0 or not isinstance(digest, str)
                        or not re.fullmatch(r"[0-9a-f]{64}", digest)):
                    raise ValueError("EXACT_PUBLISHED_FILE_METADATA_REQUIRED")
                identifiers.add(identifier)
                files.append((revision["id"], file))
        directory.mkdir(parents=True, exist_ok=True)
        collected = []
        for revision_id, file in files:
            content = self._binary("GET", BASE + "/deliverables/file", expected_size=file["size"],
                                   query={"sessionId": session_id, "revisionId": revision_id, "fileId": file["file_id"]})
            if len(content) != file["size"] or hashlib.sha256(content).hexdigest() != file["sha256"]:
                raise ValueError("PUBLISHED_FILE_SIZE_OR_SHA256_MISMATCH")
            # 远端下载名、路径和ID只作元数据；本地路径不采用这些不受信字符串。
            local_name = hashlib.sha256((revision_id + "\0" + file["file_id"]).encode()).hexdigest() + ".bin"
            destination = directory / local_name
            temporary = None
            try:
                # 独占创建避免跟随复用目录中已有的可预测临时路径或符号链接。
                with tempfile.NamedTemporaryFile(dir=directory, prefix=".noewise-", suffix=".tmp", delete=False) as output:
                    temporary = Path(output.name)
                    output.write(content)
                temporary.replace(destination)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
            collected.append({"revision_id": revision_id, **file, "local_file": str(destination.resolve())})
        return {"session_id": session_id, "run_id": run_id, "run_status": status,
                "files": collected, "evidence_kind": "public_http_deliverable_consumer"}


def main() -> int:
    """上传和下载分开调用；成果由正式 Runtime 生成、冻结并发布，客户端不创建成果事实。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--evidence", required=True, type=Path)
    commands = parser.add_subparsers(dest="command", required=True)
    upload = commands.add_parser("upload")
    upload.add_argument("--upload-id", required=True)
    upload.add_argument("--file", required=True, type=Path)
    upload.add_argument("--image", action="store_true")
    collect = commands.add_parser("collect")
    collect.add_argument("--run-id", required=True)
    collect.add_argument("--directory", required=True, type=Path)
    args = parser.parse_args()
    key, secret = os.environ.get("SKILLFORGE_MEDIA_API_KEY"), os.environ.get("SKILLFORGE_MEDIA_SIGNING_SECRET")
    if not key or not secret:
        raise ValueError("EXPLICIT_MEDIA_EXECUTION_CREDENTIAL_REQUIRED")
    consumer = MediaDeliverables(Client(args.origin, credential=Credential(key, secret)))
    evidence = {"status": "pending", "command": args.command, "session_id": args.session_id}
    try:
        if args.command == "upload":
            evidence.update({"upload_id": args.upload_id, "kind": "image" if args.image else "attachment"})
            evidence["receipt"] = consumer.upload(args.session_id, args.upload_id, args.file.name,
                                                   args.file.read_bytes(), image=args.image)
        else:
            evidence["run_id"] = args.run_id
            evidence["result"] = consumer.collect(args.session_id, args.run_id, args.directory)
        evidence["status"] = "PASS"
    except Exception as failure:
        evidence.update({"status": "UNKNOWN" if isinstance(failure, UploadOutcomeUnknown)
                         else "FAILED", "error_type": type(failure).__name__})
        if isinstance(failure, ApiFailure):
            evidence.update({"http_status": failure.status, "code": failure.code})
        elif isinstance(failure, ValueError):
            evidence["code"] = str(failure)
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": evidence["status"], "evidence": str(args.evidence.resolve())}, ensure_ascii=False))
    return 0 if evidence["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
