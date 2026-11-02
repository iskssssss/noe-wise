"""GI-03 群来源、显式触发和原 Run 交互；不读取历史、不执行跨服务控制或外发。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from client import ApiFailure, Client, Credential


ROOT = Path(__file__).resolve().parents[2]


def decimal_id(value) -> bool:
    """身份保持宿主 Long 范围内的正整数十进制字符串。"""
    return (isinstance(value, str) and value.isascii() and value.isdecimal()
            and not value.startswith("0") and len(value) <= 19 and int(value) <= 9223372036854775807)


def validate_plan(plan: dict) -> None:
    """完整计划先检查作者分支与触发本人，来源证明和当前权限由正式 API 核验。"""
    if (not isinstance(plan, dict) or set(plan) - {"group_binding_id", "messages", "responses", "commands"}
            or "group_binding_id" not in plan
            or not isinstance(plan["group_binding_id"], str) or not plan["group_binding_id"].strip()
            or not isinstance(plan.get("messages", []), list) or not isinstance(plan.get("responses", []), list)
            or not isinstance(plan.get("commands", []), list)
            or not (plan.get("messages") or plan.get("responses") or plan.get("commands"))):
        raise ValueError("GROUP_MESSAGE_PLAN_REQUIRED")
    for row in plan.get("messages", []):
        fields = {"source_event_id", "source_revision", "author", "content", "idempotency_key"}
        if not isinstance(row, dict) or set(row) - {"trigger"} != fields:
            raise ValueError("GROUP_MESSAGE_FIELDS_REQUIRED")
        if (not isinstance(row["source_event_id"], str) or not row["source_event_id"].strip()
                or type(row["source_revision"]) is not int or not 1 <= row["source_revision"] <= 9223372036854775807
                or not isinstance(row["content"], list) or not row["content"]):
            raise ValueError("GROUP_MESSAGE_SOURCE_AND_CONTENT_REQUIRED")
        author = row["author"]
        branches = {"end_user": {"kind", "end_user_id"}, "agent": {"kind", "agent_id", "run_id", "source_ref"},
                    "system": {"kind", "source_ref"}, "tool": {"kind", "agent_id", "run_id", "source_ref"}}
        if not isinstance(author, dict) or author.get("kind") not in branches or set(author) != branches[author["kind"]]:
            raise ValueError("GROUP_MESSAGE_AUTHOR_INVALID")
        if any(not isinstance(value, str) or not value.strip() for value in author.values()):
            raise ValueError("GROUP_MESSAGE_AUTHOR_REFERENCE_REQUIRED")
        for identifier in ("end_user_id", "agent_id"):
            if identifier in author and not decimal_id(author[identifier]):
                raise ValueError("GROUP_MESSAGE_AUTHOR_ID_INVALID")
        keys = [row["idempotency_key"]]
        if "trigger" in row:
            trigger = row["trigger"]
            if (not isinstance(trigger, dict) or set(trigger) != {"requester_end_user_id", "agent_id", "idempotency_key"}
                    or author["kind"] != "end_user" or trigger["requester_end_user_id"] != author["end_user_id"]
                    or not decimal_id(trigger["agent_id"])):
                raise ValueError("GROUP_TRIGGER_REQUIRES_ORIGINAL_END_USER_AUTHOR")
            keys.append(trigger["idempotency_key"])
        if any(not isinstance(key, str) or not key.strip() or len(key) > 256 for key in keys):
            raise ValueError("GROUP_STABLE_COMMAND_KEY_REQUIRED")
    for row in plan.get("responses", []):
        if (not isinstance(row, dict) or set(row) != {"run_id", "input_id", "responder_end_user_id", "hitl_response"}
                or not isinstance(row["run_id"], str) or not row["run_id"].strip()
                or not isinstance(row["input_id"], str) or not row["input_id"].strip()
                or not decimal_id(row["responder_end_user_id"])):
            raise ValueError("GROUP_WAIT_RESPONSE_IDENTITY_REQUIRED")
        hitl = row["hitl_response"]
        if (not isinstance(hitl, dict) or hitl.get("execution_id") != row["run_id"]
                or not decimal_id(hitl.get("run_revision"))
                or len(set(hitl) & {"human_input", "tool_input", "workflow_input"}) != 1):
            raise ValueError("GROUP_ORIGINAL_CHAT_WAIT_RESPONSE_REQUIRED")
    for row in plan.get("commands", []):
        if (not isinstance(row, dict) or set(row) - {"text"} != {"run_id", "input_id", "operator_end_user_id", "action"}
                or not isinstance(row["run_id"], str) or not row["run_id"].strip()
                or not isinstance(row["input_id"], str) or not row["input_id"].strip()
                or not decimal_id(row["operator_end_user_id"]) or row["action"] not in {"cancel_execution", "steer"}):
            raise ValueError("GROUP_RUN_COMMAND_IDENTITY_REQUIRED")
        if (row["action"] == "steer" and (not isinstance(row.get("text"), str) or not row["text"].strip())
                or row["action"] == "cancel_execution" and row.get("text") is not None):
            raise ValueError("GROUP_RUN_COMMAND_TEXT_INVALID")


def run_messages(client: Client, plan: dict, *, evidence_kind: str = "http_api") -> dict:
    """每项来源只记录一次，触发显式引用原来源，当前状态只走独立 GET。"""
    validate_plan(plan)
    evidence = {"evidence_kind": evidence_kind, "scope": "GI-03 record, trigger and original Run interactions", "status": "NOT_RUN", "steps": [],
                "run_completion": "NOT_RUN", "history_controls_delivery": "NOT_RUN"}
    group_id = plan["group_binding_id"]

    def call(name: str, method: str, path: str, body=None, *, query=None, key=None):
        """首错停止并保存原命令；5xx/网络未知不证明业务未受理，不自动重试。"""
        step = {"name": name, "method": method, "path": path, "body": body, "query": query,
                "idempotency_key": key, "status": "UNKNOWN"}
        evidence["steps"].append(step)
        try:
            response = client.request(method, path, body, query=query, idempotency_key=key)
        except ApiFailure as error:
            step.update(status="UNKNOWN" if error.status >= 500 else "FAILED", http_status=error.status, code=error.code)
            raise
        except Exception as error:
            step["error_type"] = type(error).__name__
            raise
        step.update(status="PASS", response=response)
        return response

    try:
        for index, row in enumerate(plan.get("messages", [])):
            body = {"group_binding_id": group_id, **{key: row[key] for key in ("source_event_id", "source_revision", "author", "content")}}
            receipt = call(f"record:{index}", "POST", "/open-platform/groups/messages", body, key=row["idempotency_key"])
            expected_fields = {"group_binding_id", "session_id", "source_message_id", "source_sequence", "source_event_id", "source_revision"}
            if (not isinstance(receipt, dict) or set(receipt) != expected_fields or receipt["group_binding_id"] != group_id
                    or receipt["source_event_id"] != row["source_event_id"] or receipt["source_revision"] != row["source_revision"]
                    or not isinstance(receipt["session_id"], str) or not isinstance(receipt["source_message_id"], str)
                    or type(receipt["source_sequence"]) is not int or receipt["source_sequence"] < 1):
                raise ValueError("GROUP_MESSAGE_SOURCE_RECEIPT_INVALID")
            if "trigger" not in row:
                continue
            command = row["trigger"]
            trigger_body = {"group_binding_id": group_id, "source_message_id": receipt["source_message_id"],
                            "requester_end_user_id": command["requester_end_user_id"], "agent_id": command["agent_id"]}
            queued = call(f"trigger:{index}", "POST", "/open-platform/groups/triggers", trigger_body, key=command["idempotency_key"])
            trigger_fields = {"group_binding_id", "session_id", "requester_end_user_id", "agent_id", "trigger_id",
                              "trigger_sequence", "source_message_id", "status", "input_id", "run_id", "rejection_code"}
            if (not isinstance(queued, dict) or not isinstance(queued.get("trigger_id"), str)
                    or set(queued) != trigger_fields or any(queued.get(key) != value for key, value in trigger_body.items())
                    or queued.get("session_id") != receipt["session_id"]
                    or queued.get("status") not in {"queued", "accepted", "rejected"}):
                raise ValueError("GROUP_TRIGGER_RECEIPT_INVALID")
            current = call(f"trigger-detail:{index}", "GET", "/open-platform/groups/triggers/detail",
                           query={"group_binding_id": group_id, "trigger_id": queued["trigger_id"]})
            if (not isinstance(current, dict) or set(current) != trigger_fields
                    or any(current.get(key) != queued[key] for key in ("group_binding_id", "session_id", "requester_end_user_id", "agent_id", "trigger_id", "trigger_sequence", "source_message_id"))
                    or current.get("status") not in {"queued", "accepted", "rejected"}
                    or current.get("status") == "queued" and (current.get("input_id") is not None or current.get("run_id") is not None)
                    or current.get("status") == "accepted" and (not isinstance(current.get("input_id"), str) or not isinstance(current.get("run_id"), str))):
                raise ValueError("GROUP_TRIGGER_CURRENT_PROJECTION_INVALID")
            if current["status"] == "rejected":
                raise ValueError("GROUP_TRIGGER_REJECTED")
        for index, row in enumerate(plan.get("responses", [])):
            query = {"group_binding_id": group_id, "run_id": row["run_id"]}
            before = call(f"run-detail-before:{index}", "GET", "/open-platform/groups/runs/detail", query=query)
            if before.get("group_binding_id") != group_id or before.get("run_id") != row["run_id"]:
                raise ValueError("GROUP_RUN_DETAIL_IDENTITY_INVALID")
            body = {"group_binding_id": group_id, **row}
            receipt = call(f"run-respond:{index}", "POST", "/open-platform/groups/runs/respond", body, key=row["input_id"])
            if (receipt.get("group_binding_id") != group_id or receipt.get("run_id") != row["run_id"]
                    or receipt.get("input_id") != row["input_id"] or type(receipt.get("replayed")) is not bool):
                raise ValueError("GROUP_RUN_RESPONSE_RECEIPT_INVALID")
            after = call(f"run-detail-after:{index}", "GET", "/open-platform/groups/runs/detail", query=query)
            if after.get("group_binding_id") != group_id or after.get("run_id") != row["run_id"]:
                raise ValueError("GROUP_RUN_DETAIL_IDENTITY_INVALID")
        for index, row in enumerate(plan.get("commands", [])):
            query = {"group_binding_id": group_id, "run_id": row["run_id"]}
            before = call(f"run-command-detail-before:{index}", "GET", "/open-platform/groups/runs/detail", query=query)
            if before.get("group_binding_id") != group_id or before.get("run_id") != row["run_id"]:
                raise ValueError("GROUP_RUN_DETAIL_IDENTITY_INVALID")
            body = {"group_binding_id": group_id, **row}
            receipt = call(f"run-command:{index}", "POST", "/open-platform/groups/runs/commands", body, key=row["input_id"])
            if (receipt.get("group_binding_id") != group_id or receipt.get("run_id") != row["run_id"]
                    or receipt.get("input_id") != row["input_id"] or type(receipt.get("replayed")) is not bool):
                raise ValueError("GROUP_RUN_COMMAND_RECEIPT_INVALID")
            after = call(f"run-command-detail-after:{index}", "GET", "/open-platform/groups/runs/detail", query=query)
            if after.get("group_binding_id") != group_id or after.get("run_id") != row["run_id"]:
                raise ValueError("GROUP_RUN_DETAIL_IDENTITY_INVALID")
        evidence["status"] = "PASS"
    except Exception as error:
        evidence["status"] = "UNKNOWN" if evidence["steps"] and evidence["steps"][-1]["status"] == "UNKNOWN" else "FAILED"
        evidence["error_type"] = type(error).__name__
        if isinstance(error, ValueError):
            evidence["code"] = str(error)
    return evidence


def main() -> int:
    """显式 loopback 执行身份来自环境，计划与回执不保存认证秘密。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--plan", required=True, type=Path)
    parser.add_argument("--evidence", type=Path, default=ROOT / ".runtime-logs/group-messages.json")
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    validate_plan(plan)
    api_key = os.environ.get("SKILLFORGE_GROUP_API_KEY")
    secret = os.environ.get("SKILLFORGE_GROUP_SIGNING_SECRET")
    if not api_key or not secret:
        raise ValueError("EXPLICIT_GROUP_EXECUTION_CREDENTIAL_REQUIRED")
    evidence = run_messages(Client(args.origin, credential=Credential(api_key, secret)), plan)
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": evidence["status"], "evidence": str(args.evidence.resolve())}, ensure_ascii=False))
    return 0 if evidence["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
