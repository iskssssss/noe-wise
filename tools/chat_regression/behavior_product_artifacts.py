"""下载本轮真实产物，校验原始字节摘要和本地业务预期。"""
import hashlib
import re
import urllib.request
from urllib.parse import urljoin, urlsplit

from behavior_contract import require
from client import query


def collect_report(api, session_id, run_id, case, turn):
    expected = case["expected"]
    quantity = expected["latest_quantity"] if turn else 20 + case["candidate"]["variant"]
    deliverables = api.request("GET", query("/chat/sessions/deliverables", sessionId=session_id, includeHistory="true"))
    matches = [(deliverable, file) for deliverable in deliverables
               if (deliverable.get("session_id") == session_id and deliverable.get("run_id") == run_id
                   and run_id in deliverable.get("result_run_ids", []) and deliverable.get("status") == "ready"
                   and isinstance(deliverable.get("id"), str) and deliverable["id"]
                   and isinstance(deliverable.get("deliverable_id"), str) and deliverable["deliverable_id"]
                   and type(deliverable.get("revision")) is int and deliverable["revision"] > 0)
               for file in deliverable.get("files", [])
               if (file.get("path", "").split("/")[-1] == expected["report_name"]
                   and isinstance(file.get("file_id"), str) and file["file_id"]
                   and isinstance(file.get("artifact_id"), str) and file["artifact_id"])]
    if len(matches) != 1:
        return {"valid": False, "reason": "report_missing_or_ambiguous", "run_id": run_id, "turn": turn}
    deliverable, entry = matches[0]
    ticket = api.request("POST", "/chat/deliverables/download-ticket",
                         {"id": deliverable["id"], "file_id": entry["file_id"]})
    url = urlsplit(urljoin(api.base, ticket["url"]))
    require(url.scheme in {"http", "https"} and url.hostname in {"127.0.0.1", "localhost", "::1"}
            and url.username is None and url.password is None, "product_artifact_requires_local_url")
    # 下载票据不携带机构 Authorization，重定向也不能逃逸本机。
    class LocalRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            target = urlsplit(newurl)
            require(target.scheme in {"http", "https"} and target.hostname in {"127.0.0.1", "localhost", "::1"}
                    and target.username is None and target.password is None, "product_artifact_redirect_rejected")
            return super().redirect_request(req, fp, code, msg, headers, newurl)
    with urllib.request.build_opener(LocalRedirect).open(url.geturl(), timeout=30) as response:
        content = response.read(1024 * 1024 + 1)
    require(len(content) <= 1024 * 1024, "product_artifact_too_large")
    fingerprint = hashlib.sha256(content).hexdigest()
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError:
        text = ""
    def contains_number(value):
        return re.search(r"(?<![\d.])" + re.escape(str(value)) + r"(?![\d.])", text) is not None
    valid = (fingerprint == entry.get("sha256") and len(content) == entry.get("size")
             and expected["report_marker"] in text and expected["deadline"] in text
             and contains_number(expected["budget"]) and contains_number(quantity))
    return {"id": entry["artifact_id"], "revision_id": deliverable["id"], "deliverable_id": deliverable["deliverable_id"],
            "revision": deliverable["revision"], "file_id": entry["file_id"], "path": entry["path"], "run_id": run_id,
            "artifact_inputs": deliverable.get("artifact_inputs", []),
            "turn": turn, "sha256": fingerprint, "size": len(content), "valid": valid,
            "reason": "download_and_content_verified" if valid else "artifact_content_or_digest_mismatch"}
