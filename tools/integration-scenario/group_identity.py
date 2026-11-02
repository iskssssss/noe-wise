"""GI-02 群身份 HTTP 示例；不发送消息、创建 Run 或启动服务。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from client import ApiFailure, Client, Credential


ROOT = Path(__file__).resolve().parents[2]


def validate_plan(plan: dict) -> None:
    """先校验完整操作计划，所有写操作使用调用方明确给出的稳定命令键和对象版本。"""
    if not isinstance(plan, dict):
        raise ValueError("GROUP_PLAN_OBJECT_REQUIRED")
    if set(plan) - {"ensure", "ensure_key", "members", "agents", "close"}:
        raise ValueError("GROUP_PLAN_UNKNOWN_FIELD")
    ensure = plan.get("ensure")
    if not isinstance(ensure, dict) or set(ensure) != {
            "namespace", "external_group_ref", "lifecycle_ref", "business_scope_id"}:
        raise ValueError("GROUP_ENSURE_FIELDS_REQUIRED")
    if not all(isinstance(value, str) and value.strip() for value in ensure.values()):
        raise ValueError("GROUP_ENSURE_STRINGS_REQUIRED")
    operations = [(plan.get("ensure_key"), None)]
    for kind, identifier, states in (("members", "end_user_id", {"active", "left"}),
                                     ("agents", "agent_id", {"active", "removed"})):
        rows = plan.get(kind, [])
        if not isinstance(rows, list):
            raise ValueError("GROUP_UPDATE_ARRAY_REQUIRED")
        for row in rows:
            required = {identifier, "status", "expected_revision", "source_event_id", "source_revision", "idempotency_key"}
            if kind == "members":
                required.add("role")
            if not isinstance(row, dict) or set(row) != required:
                raise ValueError("GROUP_UPDATE_FIELDS_REQUIRED")
            if (not isinstance(row[identifier], str) or not row[identifier].isascii()
                    or not row[identifier].isdecimal() or row[identifier].startswith("0")
                    or not 0 < int(row[identifier]) <= 9223372036854775807):
                raise ValueError("GROUP_EXACT_DECIMAL_ID_REQUIRED")
            if row["status"] not in states or kind == "members" and row["role"] not in {"member", "manager"}:
                raise ValueError("GROUP_UPDATE_ENUM_INVALID")
            if (type(row["expected_revision"]) is not int or not 0 <= row["expected_revision"] <= 9223372036854775807
                    or type(row["source_revision"]) is not int or not 1 <= row["source_revision"] <= 9223372036854775807
                    or not isinstance(row["source_event_id"], str) or not row["source_event_id"].strip()):
                raise ValueError("GROUP_EXACT_REVISIONS_AND_SOURCE_REQUIRED")
            operations.append((row["idempotency_key"], row))
    if "close" in plan:
        close = plan["close"]
        if (not isinstance(close, dict) or set(close) != {"expected_revision", "idempotency_key"}
                or type(close["expected_revision"]) is not int or not 1 <= close["expected_revision"] <= 9223372036854775807):
            raise ValueError("GROUP_CLOSE_CAS_REQUIRED")
        operations.append((close["idempotency_key"], close))
    keys = [key for key, _ in operations]
    if any(not isinstance(key, str) or not key.strip() or len(key) > 256 for key in keys):
        raise ValueError("GROUP_STABLE_COMMAND_KEY_REQUIRED")


def run_identity(client: Client, plan: dict, *, evidence_kind: str = "http_api") -> dict:
    """逐步保留实际 HTTP 回执；首错停止，网络未知不重发、不换命令键。"""
    validate_plan(plan)
    evidence = {"evidence_kind": evidence_kind, "scope": "GI-02 group identity", "status": "NOT_RUN", "steps": []}
    group = None

    def call(name: str, method: str, path: str, *, body=None, query=None, key=None):
        """证据仅保存公开业务请求/回执，凭据只由现有客户端从进程内签名。"""
        step = {"name": name, "method": method, "path": path, "body": body,
                "query": query, "idempotency_key": key, "status": "UNKNOWN"}
        evidence["steps"].append(step)
        try:
            result = client.request(method, path, body, query=query, idempotency_key=key)
        except ApiFailure as error:
            step.update(status="UNKNOWN" if error.status >= 500 else "FAILED", http_status=error.status, code=error.code)
            raise
        except Exception as error:
            step["error_type"] = type(error).__name__
            raise
        step.update(status="PASS", response=result)
        return result

    try:
        group = call("ensure", "POST", "/open-platform/groups/ensure", body=plan["ensure"], key=plan["ensure_key"])
        group_id = group["group_binding_id"]
        session_id = group["session_id"]
        if (not isinstance(group_id, str) or not isinstance(session_id, str)
                or group.get("subject") != {"kind": "group", "group_binding_id": group_id}
                or "end_user_id" in group or "agent_id" in group):
            raise ValueError("GROUP_SUBJECT_RESPONSE_INVALID")
        detail = call("detail", "GET", "/open-platform/groups/detail", query={"group_binding_id": group_id})
        if (detail.get("session_id") != session_id or detail.get("subject") != group["subject"]
                or "members" in detail or "agents" in detail):
            raise ValueError("GROUP_STABLE_METADATA_INVALID")
        for kind in ("members", "agents"):
            for index, row in enumerate(plan.get(kind, [])):
                body = {"group_binding_id": group_id, **{key: value for key, value in row.items() if key != "idempotency_key"}}
                receipt = call(f"{kind}:{index}", "POST", f"/open-platform/groups/{kind}/update", body=body, key=row["idempotency_key"])
                expected = {key: value for key, value in body.items() if key != "expected_revision"}
                if (any(receipt.get(key) != value for key, value in expected.items())
                        or type(receipt.get("revision")) is not int or receipt["revision"] < 1):
                    raise ValueError("GROUP_OBJECT_RECEIPT_INVALID")
        if "close" in plan:
            close = plan["close"]
            receipt = call("close", "POST", "/open-platform/groups/close",
                           body={"group_binding_id": group_id, "expected_revision": close["expected_revision"]}, key=close["idempotency_key"])
            if receipt.get("status") != "closed" or receipt.get("subject") != group["subject"] or receipt.get("session_id") != session_id:
                raise ValueError("GROUP_CLOSE_RECEIPT_INVALID")
        evidence["status"] = "PASS"
    except Exception as error:
        evidence["status"] = "UNKNOWN" if evidence["steps"] and evidence["steps"][-1]["status"] == "UNKNOWN" else "FAILED"
        evidence["error_type"] = type(error).__name__
        if isinstance(error, ValueError):
            evidence["code"] = str(error)
    return evidence


def main() -> int:
    """只连接显式 loopback 宿主；真实执行域凭据通过环境提供，不写入证据。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True, help="已启动的机构宿主或网关 loopback origin")
    parser.add_argument("--plan", required=True, type=Path, help="不含凭据的明确群身份操作 JSON")
    parser.add_argument("--evidence", type=Path, default=ROOT / ".runtime-logs/group-identity.json")
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    validate_plan(plan)
    api_key = os.environ.get("SKILLFORGE_GROUP_API_KEY")
    secret = os.environ.get("SKILLFORGE_GROUP_SIGNING_SECRET")
    if not api_key or not secret:
        raise ValueError("EXPLICIT_GROUP_EXECUTION_CREDENTIAL_REQUIRED")
    result = run_identity(Client(args.origin, credential=Credential(api_key, secret)), plan)
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "evidence": str(args.evidence.resolve())}, ensure_ascii=False))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
