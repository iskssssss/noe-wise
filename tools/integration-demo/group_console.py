"""固定执行上下文的群服务端桥接；复用正式 HMAC/sender 协议，不建立执行或权限引擎。"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from enum import StrEnum
from pathlib import Path
from urllib.parse import urlencode

from client import ApiFailure, Client, Credential
from group_sender_client import GroupSenderClient
from group_sender import LocalCommandOperation, digest as sender_digest
from group_sender_http import DELIVERIES, LOCAL_INTENTS, SENDER
from group_contracts import (Operation, decode, wire, RunRespond, RunCommand, Control, Terminalize,
                             MemoryReview, MemoryCorrect, MemoryRevision, SenderCreate)

ROOT = "/open-platform/groups"
DEFAULT_PAGE_SIZE = 100
MEMORY_STATUSES = frozenset(("ACTIVE", "DECAYED", "PENDING_CONFIRM", "REJECTED", "ARCHIVED", "FORGOTTEN"))


class ConsoleFailure(ValueError):
    def __init__(self, code, status=422):
        self.code, self.status = code, status
        super().__init__(code)


class SectionState(StrEnum):
    READY = "ready"
    FORBIDDEN = "forbidden"
    FAILED = "failed"


class WriteState(StrEnum):
    CONFIRMED = "CONFIRMED"
    UNKNOWN = "UNKNOWN"
    REJECTED = "REJECTED"


@dataclass(frozen=True)
class Section:
    state: SectionState
    data: object | None = None  # 原公开 DTO，仅在传输边界保真转交，不作为内部业务模型。
    error_code: str | None = None


@dataclass(frozen=True)
class SafeContext:
    id: str
    label: str
    group_binding_id: str
    operator_end_user_id: str
    agent_id: str
    sender_configured: bool


@dataclass(frozen=True)
class Context:
    id: str
    label: str
    origin: str
    group_binding_id: str
    operator_end_user_id: str
    agent_id: str
    api_key_env: str = field(repr=False)
    signing_secret_env: str = field(repr=False)
    sender_origin: str | None = None
    sender_token_env: str | None = field(default=None, repr=False)

    def __post_init__(self):
        if not isinstance(self.id, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", self.id):
            raise ConsoleFailure("GROUP_CONTEXT_ID_INVALID")
        if not isinstance(self.label, str) or not self.label.strip():
            raise ConsoleFailure("GROUP_CONTEXT_LABEL_REQUIRED")
        Client(self.origin)
        for value in (self.group_binding_id, self.operator_end_user_id, self.agent_id):
            decimal(value)
        for name in (self.api_key_env, self.signing_secret_env):
            if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
                raise ConsoleFailure("GROUP_CREDENTIAL_ENV_REQUIRED")
        if bool(self.sender_origin) != bool(self.sender_token_env):
            raise ConsoleFailure("GROUP_SENDER_CONFIG_INCOMPLETE")
        if self.sender_origin:
            Client(self.sender_origin)

    def safe(self):
        return SafeContext(self.id, self.label, self.group_binding_id, self.operator_end_user_id, self.agent_id, bool(self.sender_origin))


@dataclass(frozen=True)
class WriteReceipt:
    state: WriteState
    operation: Operation
    idempotency_key: str
    data: object | None = None
    error_code: str | None = None
    replayed: bool = False


@dataclass(frozen=True)
class Spec:
    method: str
    path: str
    required_response: tuple[str, ...]
    control_action: str | None = None
    sender: bool = False


SPECS = {
    Operation.MEMBER: Spec("POST", ROOT + "/members/update", ("group_binding_id", "end_user_id", "revision")),
    Operation.AGENT: Spec("POST", ROOT + "/agents/update", ("group_binding_id", "agent_id", "revision")),
    Operation.MEMORY_REVIEW: Spec("POST", ROOT + "/memories/review", ("group_binding_id", "fact_id", "revision", "status")),
    Operation.MEMORY_CORRECT: Spec("POST", ROOT + "/memories/correct", ("group_binding_id", "fact_id", "revision", "status")),
    Operation.MEMORY_FORGET: Spec("POST", ROOT + "/memories/forget", ("group_binding_id", "fact_id", "revision", "status")),
    Operation.MEMORY_RESTORE: Spec("POST", ROOT + "/memories/restore", ("group_binding_id", "fact_id", "revision", "status")),
    Operation.EXTRACTION_POLICY: Spec("PUT", ROOT + "/memories/extraction-policy", ("group_binding_id", "enabled", "extractor_agent_id", "revision")),
    Operation.CONTROL: Spec("POST", ROOT + "/controls", ("group_binding_id", "command_id", "result_kind", "status", "control_revision"), "write"),
    Operation.CONTROL_TERMINALIZE: Spec("POST", ROOT + "/controls/terminalize", ("group_binding_id", "command_id", "result_kind", "status"), "reconcile"),
    Operation.CONTROL_RECONCILE: Spec("GET", ROOT + "/controls/detail", ("group_binding_id", "command_id", "result_kind", "status"), "reconcile"),
    Operation.CONTROL_RELEASE: Spec("POST", ROOT + "/controls/release", ("group_binding_id", "release_id", "messages", "blocked_boundary_refs"), "release"),
    Operation.RUN_COMMAND: Spec("POST", ROOT + "/runs/commands", ("group_binding_id", "run_id", "input_id", "status", "replayed")),
    Operation.RUN_RESPOND: Spec("POST", ROOT + "/runs/respond", ("group_binding_id", "run_id", "input_id", "status", "replayed")),
    Operation.SENDER_CREATE: Spec("POST", LOCAL_INTENTS, ("intent_id", "group_id", "revision", "active", "status", "attempts"), sender=True),
    Operation.SENDER_RECONCILE: Spec("POST", LOCAL_INTENTS + "/reconcile", ("intent_id", "group_id", "revision", "active", "status", "attempts"), sender=True),
    Operation.SENDER_RELEASE: Spec("POST", LOCAL_INTENTS + "/release", ("intent_id", "group_id", "revision", "active", "status", "attempts"), sender=True),
    Operation.SENDER_DELIVERY_RECONCILE: Spec("POST", DELIVERIES + "/reconcile", ("delivery_id", "group_id", "status"), sender=True),
}
SENDER_COMMANDS = {
    Operation.SENDER_CREATE: LocalCommandOperation.CREATE,
    Operation.SENDER_RECONCILE: LocalCommandOperation.RECONCILE,
    Operation.SENDER_RELEASE: LocalCommandOperation.RELEASE,
}


def decimal(value):
    if not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", value) or int(value) > 9223372036854775807:
        raise ConsoleFailure("GROUP_EXACT_DECIMAL_ID_REQUIRED")
    return value


def stable(value):
    if not isinstance(value, str) or not value.strip() or len(value) > 256:
        raise ConsoleFailure("GROUP_STABLE_REQUEST_ID_REQUIRED")
    return value


def load_contexts(path: Path | None):
    if path is None:
        return ()
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict) or set(raw) != {"contexts"} or not isinstance(raw["contexts"], list):
        raise ConsoleFailure("GROUP_CONTEXT_CONFIG_INVALID")
    try:
        contexts = tuple(Context(**item) for item in raw["contexts"])
    except (TypeError, KeyError):
        raise ConsoleFailure("GROUP_CONTEXT_CONFIG_INVALID") from None
    if len({item.id for item in contexts}) != len(contexts):
        raise ConsoleFailure("GROUP_CONTEXT_ID_DUPLICATED")
    return contexts


class RequestJournal:
    """本地只保存已派发请求的身份和原 wire 回执；UNKNOWN 永不重新领取或自动派发。"""
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS group_console_requests(
                context_id TEXT NOT NULL,operation TEXT NOT NULL,request_key TEXT NOT NULL,
                request_digest TEXT NOT NULL,command_ref TEXT,state TEXT NOT NULL,http_status INTEGER NOT NULL,
                error_code TEXT,response_wire BLOB,
                PRIMARY KEY(context_id,operation,request_key),
                CHECK(state IN('UNKNOWN','CONFIRMED','REJECTED')))""")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def begin(self, context, operation, key, digest, command_ref):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT request_digest,state,http_status,error_code,response_wire FROM group_console_requests WHERE context_id=? AND operation=? AND request_key=?",
                             (context, operation, key)).fetchone()
            if row:
                if row["request_digest"] != digest:
                    raise ConsoleFailure("GROUP_LOCAL_IDEMPOTENCY_CONFLICT", 409)
                data = json.loads(row["response_wire"]) if row["response_wire"] is not None else None
                return row["http_status"], WriteReceipt(WriteState(row["state"]), operation, key, data, row["error_code"], True)
            db.execute("INSERT INTO group_console_requests VALUES(?,?,?,?,?,'UNKNOWN',202,'GROUP_REQUEST_OUTCOME_UNKNOWN',NULL)",
                       (context, operation, key, digest, command_ref))
        return None

    def finish(self, context, operation, key, status, receipt):
        # response_wire 是原外部公开回执的协议材料；固定身份、状态及错误字段均使用类型列。
        raw = None if receipt.data is None else json.dumps(receipt.data, ensure_ascii=False, separators=(",", ":")).encode()
        with self.connect() as db:
            db.execute("UPDATE group_console_requests SET state=?,http_status=?,error_code=?,response_wire=? WHERE context_id=? AND operation=? AND request_key=?",
                       (receipt.state, status, receipt.error_code, raw, context, operation, key))

    def confirm_command(self, context, command, data):
        raw = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()
        with self.connect() as db:
            db.execute("UPDATE group_console_requests SET state='CONFIRMED',http_status=200,error_code=NULL,response_wire=? WHERE context_id=? AND command_ref=? AND state='UNKNOWN' AND operation IN(?,?)",
                       (raw, context, command, Operation.CONTROL, Operation.CONTROL_TERMINALIZE))


class GroupConsole:
    def __init__(self, contexts, journal: Path, client_factory=None, sender_factory=None):
        self.contexts = {item.id: item for item in contexts}
        self.journal = RequestJournal(journal)
        self.client_factory = client_factory or self._client
        self.sender_factory = sender_factory or self._sender

    @staticmethod
    def _client(context):
        key, secret = os.environ.get(context.api_key_env), os.environ.get(context.signing_secret_env)
        if not key or not secret:
            raise ConsoleFailure("GROUP_SERVER_CREDENTIAL_UNAVAILABLE", 503)
        return Client(context.origin, Credential(key, secret))

    @staticmethod
    def _sender(context):
        if not context.sender_origin:
            raise ConsoleFailure("SENDER_NOT_CONFIGURED", 409)
        token = os.environ.get(context.sender_token_env)
        if not token:
            raise ConsoleFailure("GROUP_SENDER_CREDENTIAL_UNAVAILABLE", 503)
        return GroupSenderClient(context.sender_origin, token)

    def context(self, name):
        if name not in self.contexts:
            raise ConsoleFailure("GROUP_CONTEXT_NOT_FOUND", 404)
        return self.contexts[name]

    def listing(self):
        return {"items": [asdict(item.safe()) for item in self.contexts.values()]}

    def _call(self, context, method, path, body=None, query=None, key=None):
        return self.client_factory(context).request(method, path, body, query=query, idempotency_key=key)

    def _authorize(self, context, action):
        result = self._call(context, "POST", ROOT + "/controls/authorize", {"group_binding_id": context.group_binding_id, "action": action})
        if not isinstance(result, dict) or result.get("group_binding_id") != context.group_binding_id or result.get("action") != action:
            raise ConsoleFailure("GROUP_AUTHORIZATION_RECEIPT_INVALID", 503)
        if result.get("authorized") is not True:
            raise ConsoleFailure("GROUP_CONTROL_NOT_AUTHORIZED", 403)
        return result

    @staticmethod
    def _section(action):
        try:
            result = action()
            if result is not None and not isinstance(result, (dict, list)):
                raise ConsoleFailure("GROUP_UPSTREAM_DTO_INVALID", 502)
            return Section(SectionState.READY, result)
        except (ApiFailure, ConsoleFailure) as failure:
            return Section(SectionState.FORBIDDEN if failure.status in (401, 403) else SectionState.FAILED, error_code=failure.code)
        except (RuntimeError, ValueError, KeyError, TypeError, AttributeError):
            return Section(SectionState.FAILED, error_code="GROUP_UPSTREAM_READ_FAILED")

    def snapshot(self, name, *, run_id=None, members_after=None, agents_after=None, history_cursor=None,
                 memory_status="ACTIVE", subject_end_user_id=None):
        context = self.context(name)
        if memory_status not in MEMORY_STATUSES:
            raise ConsoleFailure("GROUP_MEMORY_STATUS_INVALID")
        for value in (members_after, agents_after, subject_end_user_id):
            if value is not None:
                decimal(value)
        if run_id is not None:
            stable(run_id)
        group = {"group_binding_id": context.group_binding_id}
        calls = {
            "group": lambda: self._call(context, "GET", ROOT + "/detail", query=group),
            "members": lambda: self._call(context, "GET", ROOT + "/members", query=group | {"after_id": members_after, "limit": DEFAULT_PAGE_SIZE}),
            "agents": lambda: self._call(context, "GET", ROOT + "/agents", query=group | {"after_id": agents_after, "limit": DEFAULT_PAGE_SIZE}),
            "history": lambda: self._call(context, "GET", ROOT + "/history", query=group | {"reader_end_user_id": context.operator_end_user_id, "cursor": history_cursor, "page_size": DEFAULT_PAGE_SIZE}),
            "control": lambda: self._call(context, "GET", ROOT + "/controls/current", query=group),
            "memories": lambda: self._call(context, "GET", ROOT + "/memories", query=group | {"reader_end_user_id": context.operator_end_user_id,
                "agent_id": context.agent_id, "status": memory_status, "subject_end_user_id": subject_end_user_id, "limit": DEFAULT_PAGE_SIZE}),
            "extraction_policy": lambda: self._call(context, "GET", ROOT + "/memories/extraction-policy", query=group | {"operator_end_user_id": context.operator_end_user_id}),
            "usage": lambda: self._call(context, "GET", "/open-platform/usage/events", query=group | {"limit": DEFAULT_PAGE_SIZE}),
            "run": (lambda: self._call(context, "GET", ROOT + "/runs/detail", query=group | {"run_id": run_id})) if run_id else lambda: None,
            "sender": lambda: self.sender_factory(context).request("GET", SENDER + "/snapshot"),
        }
        with ThreadPoolExecutor(max_workers=6) as executor:
            tasks = {key: executor.submit(self._section, action) for key, action in calls.items()}
            permissions = {action: executor.submit(self._section, lambda action=action: self._authorize(context, action))
                           for action in ("write", "read", "reconcile", "release", "ack", "manage")}
            result = {key: asdict(task.result()) for key, task in tasks.items()}
            result["permissions"] = {key: asdict(task.result()) for key, task in permissions.items()}
        return {"context": asdict(context.safe()), **result}

    def command(self, name, command_id):
        context = self.context(name)
        stable(command_id)
        result = self._call(context, "GET", ROOT + "/controls/detail", query={"group_binding_id": context.group_binding_id, "command_id": command_id})
        self._validate(context, SPECS[Operation.CONTROL_RECONCILE], result)
        if result.get("command_id") != command_id:
            raise ConsoleFailure("GROUP_COMMAND_RECEIPT_MISMATCH", 502)
        self.journal.confirm_command(name, command_id, result)
        return result

    @staticmethod
    def _validate(context, spec, result):
        if not isinstance(result, dict) or not set(spec.required_response) <= set(result):
            raise ConsoleFailure("GROUP_UPSTREAM_RECEIPT_INVALID", 502)
        group = result.get("group_id" if spec.sender else "group_binding_id")
        if group != context.group_binding_id:
            raise ConsoleFailure("GROUP_UPSTREAM_RECEIPT_SCOPE_MISMATCH", 502)

    @staticmethod
    def _validate_sender_target(body, result):
        target = body.get("intent_id", body.get("local_intent_id"))
        if target is not None and result.get("intent_id") != target:
            raise ConsoleFailure("GROUP_SENDER_RECEIPT_IDENTITY_MISMATCH", 502)
        if "delivery_id" in body and result.get("delivery_id") != body["delivery_id"]:
            raise ConsoleFailure("GROUP_SENDER_RECEIPT_IDENTITY_MISMATCH", 502)

    def _sender_original(self, name, context, operation, body, key, prior):
        """显式重复提交 UNKNOWN 只核对原本地命令；不重新POST或推断后来状态。"""
        try:
            query = urlencode({"operation": SENDER_COMMANDS[operation], "request_key": key})
            original = self.sender_factory(context).request("GET", LOCAL_INTENTS + "/commands/detail?" + query)
            if (not isinstance(original, dict) or original.get("operation") != SENDER_COMMANDS[operation]
                    or original.get("request_key") != key or original.get("fingerprint") != sender_digest(body)):
                raise ConsoleFailure("GROUP_SENDER_COMMAND_RECEIPT_MISMATCH", 502)
            data = original.get("receipt")
            self._validate(context, SPECS[operation], data)
            self._validate_sender_target(body, data)
            receipt = WriteReceipt(WriteState.CONFIRMED, operation, key, data, replayed=True)
            self.journal.finish(name, operation, key, 200, receipt)
            return 200, receipt
        except (ApiFailure, ConsoleFailure) as failure:
            if failure.status == 404:
                return prior
            return 202, WriteReceipt(WriteState.UNKNOWN, operation, key, error_code=failure.code, replayed=True)
        except (RuntimeError, ValueError, KeyError, TypeError, AttributeError):
            return prior

    def _authorize_cached(self, context, operation, command, body, key, receipt):
        """本地幂等回执不授予读取权；复用对应正式GET核验当前身份与对象域。"""
        group = {"group_binding_id": context.group_binding_id}
        if operation in SENDER_COMMANDS:
            query = urlencode({"operation": SENDER_COMMANDS[operation], "request_key": key})
            current = self.sender_factory(context).request("GET", LOCAL_INTENTS + "/commands/detail?" + query)
            if (not isinstance(current, dict) or current.get("operation") != SENDER_COMMANDS[operation]
                    or current.get("request_key") != key or current.get("fingerprint") != sender_digest(body)):
                raise ConsoleFailure("GROUP_SENDER_COMMAND_RECEIPT_MISMATCH", 502)
            self._validate(context, SPECS[operation], current.get("receipt"))
            self._validate_sender_target(body, current["receipt"])
        elif operation is Operation.SENDER_DELIVERY_RECONCILE:
            result = self.sender_factory(context).request("GET", DELIVERIES + "/detail?" + urlencode({"delivery_id": command.delivery_id}))
            self._validate(context, SPECS[operation], result)
            self._validate_sender_target(body, result)
        elif isinstance(command, MemoryRevision):
            query = group | {"reader_end_user_id": context.operator_end_user_id, "agent_id": context.agent_id, "limit": 1}
            if isinstance(receipt.data, dict):
                query.update({name: receipt.data[name] for name in ("scope", "status", "subject_end_user_id") if receipt.data.get(name) is not None})
            self._call(context, "GET", ROOT + "/memories", query=query)
        elif operation in (Operation.MEMBER, Operation.AGENT):
            self._call(context, "GET", ROOT + ("/members" if operation is Operation.MEMBER else "/agents"), query=group | {"limit": 1})
        elif operation is Operation.EXTRACTION_POLICY:
            self._call(context, "GET", ROOT + "/memories/extraction-policy", query=group | {"operator_end_user_id": context.operator_end_user_id})
        elif isinstance(command, (RunCommand, RunRespond)):
            self._call(context, "GET", ROOT + "/runs/detail", query=group | {"run_id": command.run_id})
        elif isinstance(command, (Control, Terminalize)):
            self._call(context, "GET", ROOT + "/controls/detail", query=group | {"command_id": command.command_id})
        else:
            self._call(context, "GET", ROOT + "/controls/current", query=group)

    def submit(self, name, operation, value, key):
        context, key = self.context(name), stable(key)
        try:
            operation = Operation(operation)
            command = decode(operation, value)
        except ValueError as invalid:
            raise ConsoleFailure(str(invalid) if str(invalid).startswith("GROUP_") else "GROUP_OPERATION_INVALID") from None
        spec = SPECS[operation]
        if spec.control_action:
            self._authorize(context, spec.control_action)
        body = wire(command)
        if spec.sender:
            if not context.sender_origin:
                raise ConsoleFailure("SENDER_NOT_CONFIGURED", 409)
            if isinstance(command, SenderCreate):
                body["group_binding_id"] = context.group_binding_id
        else:
            body["group_binding_id"] = context.group_binding_id
            if operation is Operation.AGENT:
                decimal(command.agent_id)
            elif isinstance(command, (MemoryRevision, MemoryReview, MemoryCorrect)):
                body.update(operator_end_user_id=context.operator_end_user_id, agent_id=context.agent_id)
            elif operation is Operation.EXTRACTION_POLICY:
                body.update(operator_end_user_id=context.operator_end_user_id, extractor_agent_id=context.agent_id)
            elif isinstance(command, RunCommand):
                body["operator_end_user_id"] = context.operator_end_user_id
            elif isinstance(command, RunRespond):
                body["responder_end_user_id"] = context.operator_end_user_id
        if isinstance(command, (RunCommand, RunRespond)) and command.input_id != key:
            raise ConsoleFailure("GROUP_RUN_INPUT_KEY_MISMATCH")
        if operation is Operation.CONTROL and command.command_id != key:
            raise ConsoleFailure("GROUP_CONTROL_COMMAND_KEY_MISMATCH")
        if operation is Operation.CONTROL_RECONCILE:
            return 200, WriteReceipt(WriteState.CONFIRMED, operation, key, self.command(name, command.command_id))
        digest = hashlib.sha256(json.dumps({"origin": context.sender_origin if spec.sender else context.origin,
                "group": context.group_binding_id, "operator": context.operator_end_user_id, "agent": context.agent_id,
                "body": body}, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
        reference = command.command_id if isinstance(command, (Control, Terminalize)) else None
        prior = self.journal.begin(name, operation, key, digest, reference)
        if prior is not None:
            if prior[1].state is WriteState.UNKNOWN and operation in SENDER_COMMANDS:
                return self._sender_original(name, context, operation, body, key, prior)
            if prior[1].state is not WriteState.UNKNOWN:
                self._authorize_cached(context, operation, command, body, key, prior[1])
            return prior
        try:
            result = self.sender_factory(context).request(spec.method, spec.path, body, key=key) if spec.sender else self._call(context, spec.method, spec.path, body, key=key)
            self._validate(context, spec, result)
            if spec.sender:
                self._validate_sender_target(body, result)
            if reference and result.get("command_id") != reference:
                raise ConsoleFailure("GROUP_COMMAND_RECEIPT_MISMATCH", 502)
            receipt, status = WriteReceipt(WriteState.CONFIRMED, operation, key, result), 200
        except (ApiFailure, ConsoleFailure) as failure:
            unknown = failure.status >= 500 or failure.status < 400
            receipt = WriteReceipt(WriteState.UNKNOWN if unknown else WriteState.REJECTED, operation, key, error_code=failure.code)
            status = 202 if unknown else failure.status
        except (RuntimeError, ValueError, KeyError, TypeError, AttributeError):
            receipt, status = WriteReceipt(WriteState.UNKNOWN, operation, key, error_code="GROUP_REQUEST_OUTCOME_UNKNOWN"), 202
        self.journal.finish(name, operation, key, status, receipt)
        return status, receipt
