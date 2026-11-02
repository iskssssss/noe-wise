"""真实 HTTP 契约探针。默认不创建业务资源；--agent-id/--end-user-id 显式启用两次聊天。

复用本目录登录环境变量；报告仅保存状态、trace 和检查名称，不保存凭据、正文或票据。
不重试结果未知的 POST。旧版本不具备 ApiResponse 时立即停止，不能以旧服务验证新源码。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid

from client import Api, query, sse_events


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def exchange(api, method, path, body=None, headers=None, raw=None):
    request = urllib.request.Request(api.base + path,
        data=raw if raw is not None else None if body is None else json.dumps(body).encode(),
        method=method, headers={**api.headers, **(headers or {})})
    try:
        response = urllib.request.urlopen(request, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = response.read(4 * 1024 * 1024 + 1)
        require(len(data) <= 4 * 1024 * 1024, "response size limit")
        return response.status, response.headers, data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", default="http://127.0.0.1:8000/api/v1")
    parser.add_argument("--platform", default="http://127.0.0.1:8100/api/v1")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--agent-id")
    parser.add_argument("--end-user-id")
    parser.add_argument("--artifact-session-id", help="读取已有 ready 产物并验证票据下载；不修改产物")
    parser.add_argument("--gateway-fault-api", help="已配置不可达上游的隔离网关 /api/v1 地址")
    args = parser.parse_args()
    if bool(args.agent_id) != bool(args.end_user_id):
        parser.error("agent-id/end-user-id 必须同时提供")
    report = {"checks": [], "resources": [], "unverified": [
        "unknown tool effects require controlled provider fixture",
        "artifact partial publication failure requires storage fault fixture",
        "cross-tenant/channel isolation requires a second authorized identity",
        "process crash recovery requires an approved restart during projection",
    ]}

    def check(name, api, method, path, expected, body=None, headers=None, raw=None):
        trace = "contract-" + uuid.uuid4().hex
        status, received, data = exchange(api, method, path, body,
            {**(headers or {}), "X-Trace-Id": trace}, raw)
        require(status in expected, f"{name}: HTTP {status}, expected {expected}")
        require(received.get("X-Trace-Id") == trace, f"{name}: trace header")
        if status in (204, 304):
            require(not data, f"{name}: bodyless response")
            value = None
        else:
            require("application/json" in received.get("Content-Type", ""), f"{name}: JSON media type")
            value = json.loads(data)
            require(isinstance(value, dict) and {"code", "message", "data", "trace_id"} <= value.keys(),
                    f"{name}: missing ApiResponse; stop before testing stale binaries")
            require(value["trace_id"] == trace, f"{name}: trace body")
            if status < 300:
                require(value["code"] == "OK", f"{name}: success code")
            else:
                require(value["code"] != "OK" and value["data"] is None, f"{name}: error envelope")
        report["checks"].append({"name": name, "status": status, "trace_id": trace})
        return value["data"] if value is not None else None

    def login(base, platform=False):
        prefix = "SF_PLATFORM" if platform else "SF_ADMIN"
        body = {"username": os.environ[prefix + "_USER"], "password": os.environ[prefix + "_PASSWORD"]}
        if not platform:
            body["tenant_slug"] = os.environ.get("SF_TENANT", "demo")
        path = "/platform/auth/login" if platform else "/auth/login"
        tokens = check(prefix + " login", Api(base), "POST", path, {200}, body)
        return Api(base, tokens["access_token"], platform)

    try:
        if args.gateway_fault_api:
            require(urllib.parse.urlsplit(args.gateway_fault_api).hostname in {"localhost", "127.0.0.1"},
                    "fault gateway must be local")
            check("gateway upstream unavailable", Api(args.gateway_fault_api), "GET", "/auth/me", {502})
        institution, platform = login(args.api), login(args.platform, True)
        for name, api, path in (("institution", institution, "/auth/me"),
                                ("platform", platform, "/platform/auth/me")):
            check(name + " me", api, "GET", path, {200})
            check(name + " unauthenticated", Api(api.base), "GET", path, {401})
        check("login validation", Api(args.api), "POST", "/auth/login", {400}, {})
        check("malformed JSON", Api(args.api), "POST", "/auth/login", {400}, raw=b"{")
        check("SSE handshake auth", Api(args.api), "GET", "/chat/events?session_id=absent", {401},
              headers={"Accept": "text/event-stream"})
        check("session not found", institution, "GET", "/chat/events?session_id=absent", {404})
        check("admission validation", institution, "POST", "/chat", {422}, {})
        check("artifact direct authorization", Api(args.api), "GET", "/chat/artifact-content/detail?ticket=invalid", {410})
        check("deliverable direct authorization", Api(args.api), "GET", "/chat/deliverable-content/detail?ticket=invalid", {410})
        check("missing tenant bodyless", platform, "GET",
              query("/admin/tenants/find-by-slug", slug="contract-absent-" + uuid.uuid4().hex), {204})
        if args.artifact_session_id:
            for kind in ("artifacts", "deliverables"):
                route = query("/chat/sessions/" + kind, sessionId=args.artifact_session_id)
                check(kind + " metadata unauthenticated", Api(args.api), "GET", route, {401})
                entries = check(kind + " metadata", institution, "GET", route, {200})
                entry = next(item for item in entries if item["status"] == "ready" and item["files"])
                file = entry["files"][0]
                payload = {"id": entry["id"], **({"payload": {"path": file["path"]}}
                    if kind == "artifacts" else {"file_id": file["file_id"]})}
                ticket = check(kind + " ticket", institution, "POST", "/chat/" + kind + "/download-ticket", {200}, payload)
                url = urllib.parse.urlsplit(ticket["url"])
                require(url.hostname in {"localhost", "127.0.0.1"}, "probe downloads only from local host")
                # 票据本身是受限凭证；直接入口在每次使用时复核票据主体权限。
                with urllib.request.urlopen(ticket["url"], timeout=25) as downloaded:
                    content = downloaded.read(4 * 1024 * 1024 + 1)
                    require(downloaded.status == 200, kind + " download status")
                    require(downloaded.headers.get("Content-Disposition", "").startswith("attachment"), kind + " disposition")
                    require(len(content) == file["size"], kind + " size")
                    require(hashlib.sha256(content).hexdigest() == file["sha256"], kind + " digest")
                report["checks"].append({"name": kind + " binary download", "status": 200, "sha256_verified": True})
                params = urllib.parse.parse_qs(url.query)
                credential = params["ticket"][0]
                params["ticket"] = [("A" if credential[0] != "A" else "B") + credential[1:]]
                invalid_path = url.path.removeprefix("/api/v1") + "?" + urllib.parse.urlencode(params, doseq=True)
                check(kind + " tampered ticket", Api(args.api), "GET", invalid_path, {410})
        if args.agent_id:
            session = check("create session", institution, "POST", "/chat/sessions", {200},
                {"agent_id": args.agent_id, "end_user_id": int(args.end_user_id)})["session_id"]
            report["resources"].append({"session_id": session})
            body = {"agent_id": args.agent_id, "session_id": session, "client_message_id": uuid.uuid4().hex,
                    "content": [{"type": "text", "text": "这是接口契约检查。请仅回复：契约检查完成。不要调用工具。"}],
                    "context": {"end_user_id": int(args.end_user_id)}}
            receipt = check("submit", institution, "POST", "/chat", {202}, body)
            require(receipt["input_id"] == body["client_message_id"], "receipt input identity")
            duplicate = check("repeat input with new trace", institution, "POST", "/chat", {202}, body)
            require(duplicate["execution_id"] == receipt["execution_id"] and duplicate["replayed"], "durable receipt replay")
            check("input fingerprint conflict", institution, "POST", "/chat", {409}, {**body, "content": [{"type": "text", "text": "不同正文"}]})
            check("invalid cursor", institution, "GET", query("/chat/events", session_id=session, cursor="absent"), {409})
            # 读取一个持久事件后主动断流，随后只能 GET 恢复。
            deadline = time.monotonic() + 90
            with institution.open("GET", query("/chat/events", session_id=session),
                                  headers={"Accept": "text/event-stream"}) as response:
                first = next(event for event in sse_events(response, deadline) if event.get("id"))
            cursor = first["id"]
            terminal = first["data"] if first["event"] == "execution.completed" else None
            while terminal is None and time.monotonic() < deadline:
                page = check("GET recovery", institution, "GET", query("/chat/events", session_id=session, cursor=cursor), {200})
                for event in page["events"]:
                    require(event["event_id"] != cursor, "cursor must be exclusive")
                    cursor = event["event_id"]
                    if event["event"] == "execution.completed" and event["data"].get("run_id") == receipt["execution_id"]:
                        terminal = event["data"]
                if terminal is None:
                    time.sleep(1)
            require(terminal is not None, "original run terminal not observed; do not resubmit")
            report["checks"].append({"name": "disconnect recovery", "run_status": terminal["status"]})
            # 取消已终结的 Run 覆盖终态胜出分支，另一次独立请求覆盖取消与执行竞争。
            done = check("cancel terminal race", institution, "POST", "/chat/runs/cancel", {200},
                         {"session_id": session, "run_id": receipt["execution_id"]})
            require(done["status"] == terminal["status"], "cancel must not overwrite terminal fact")
            active = check("submit cancellation probe", institution, "POST", "/chat", {202},
                {**body, "client_message_id": uuid.uuid4().hex, "content": [{"type": "text", "text": "仅用文字列出数字一到十，不调用工具。"}]})
            cancel = check("cancel intent", institution, "POST", "/chat/runs/cancel", {200},
                           {"session_id": session, "run_id": active["execution_id"]})
            require(cancel["status"] in {"cancelling", "cancelled", "completed", "failed"}, "cancellation status")
            report["checks"].append({"name": "cancellation acceptance", "run_status": cancel["status"]})
            cancel_deadline = time.monotonic() + 90
            settled = cancel
            while settled["status"] not in {"completed", "cancelled", "failed", "terminated"}:
                require(time.monotonic() < cancel_deadline, "cancel accepted but terminal not yet observed")
                time.sleep(1)
                settled = check("cancel status", institution, "GET",
                    query("/chat/runs/status", session_id=session, run_id=active["execution_id"]), {200})
            report["checks"].append({"name": "cancellation terminal fact", "run_status": settled["status"]})
        else:
            report["unverified"].append("run/idempotency/SSE/cancellation: no explicit agent and end-user selected")
        report["result"] = "passed_for_executed_checks"
    except Exception as error:
        report["result"] = "failed"
        report["failure"] = {"type": type(error).__name__, "reason": str(error) if isinstance(error, AssertionError) else "probe interrupted"}
        raise
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
