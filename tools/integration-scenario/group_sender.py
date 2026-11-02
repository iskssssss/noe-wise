"""GI-04独立示例发送域：持久门禁、世代隔离、原delivery及本地控制意图。"""
from __future__ import annotations

import hashlib
import json
import sqlite3
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Protocol

from client import ApiFailure

SQLITE_BUSY_TIMEOUT_SECONDS = 30
IDENTITY_MAX_CHARACTERS = 256
IDENTITY_LIMITS = {"group_binding_id": 64, "endpoint_id": 128, "run_id": 128, "delivery_id": 128,
                   "local_intent_id": 128, "intent_id": 128, "resume_command_id": 128}
SEND_SCOPE = "send"
CONTROL_SCOPES = frozenset({"execution", "business_write", SEND_SCOPE, "memory_extraction"})


class DeliveryStatus(StrEnum):
    PENDING = "PENDING"
    BLOCKED = "BLOCKED"
    DISPATCHING = "DISPATCHING"
    DELIVERED = "DELIVERED"
    UNKNOWN = "UNKNOWN"


class AttemptStatus(StrEnum):
    PENDING = "PENDING"
    UNKNOWN = "UNKNOWN"
    REJECTED = "REJECTED"
    ACCEPTED = "ACCEPTED"
    TERMINAL_NOT_ACCEPTED = "TERMINAL_NOT_ACCEPTED"


class IntentStatus(StrEnum):
    BLOCKING = "BLOCKING"
    REMOTE_ACCEPTED = "REMOTE_ACCEPTED"
    RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED"
    ABANDONED = "ABANDONED"
    RELEASED = "RELEASED"


class LocalCommandOperation(StrEnum):
    CREATE = "intent.create"
    RECONCILE = "intent.reconcile"
    RELEASE = "intent.release"


@dataclass(frozen=True)
class LocalCommandReceipt:
    operation: LocalCommandOperation
    request_key: str
    fingerprint: str
    receipt: dict  # 已持久化的原公开回执，保留其当时状态，不推断后来意图状态。


class SenderError(RuntimeError):
    """只携带稳定错误码和HTTP分类，不把正文、凭据或供应商响应写入异常。"""
    def __init__(self, code: str, status: int = 409):
        self.code, self.status = code, status
        super().__init__(code)


class UnknownSend(RuntimeError):
    """网络已派发而无确定回执；只能沿原delivery查询。"""


def identity(value, field: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > IDENTITY_LIMITS.get(field, IDENTITY_MAX_CHARACTERS) or any(ord(c) < 32 for c in value):
        raise SenderError("INVALID_" + field.upper(), 400)
    return value


def revision(value, *, initial=False) -> int:
    if type(value) is not int or value < (0 if initial else 1) or value > 2**63 - 1:
        raise SenderError("INVALID_REVISION", 400)
    return value


def canonical(value) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def digest(value) -> str:
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class Endpoint:
    group_binding_id: str
    endpoint_id: str
    generation: int
    expected_generation: int

    def __post_init__(self):
        identity(self.group_binding_id, "group_binding_id")
        identity(self.endpoint_id, "endpoint_id")
        revision(self.generation)
        revision(self.expected_generation, initial=True)
        if self.generation != self.expected_generation + 1:
            raise SenderError("ENDPOINT_GENERATION_MUST_ADVANCE_ONCE", 400)


class ControlPlane(Protocol):
    def authorize(self, group: str, action: str) -> dict: ...
    def current(self, group: str) -> dict: ...
    def submit(self, command: dict) -> dict: ...
    def detail(self, group: str, command: str) -> dict: ...
    def terminalize(self, command: dict, key: str) -> dict: ...
    def release(self, command: dict, key: str) -> dict: ...
    def register(self, command: dict) -> dict: ...
    def retire(self, command: dict) -> dict: ...
    def ack(self, command: dict) -> dict: ...
    def qualify(self, command: dict) -> dict: ...


class DeliverySink(Protocol):
    def send(self, delivery: dict) -> dict: ...
    def receipt(self, delivery_id: str) -> dict | None: ...


class SenderStore:
    """同逻辑端点各代共用此SQLite账本；写资格、门禁和fence竞争同一写锁。"""
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, timeout=SQLITE_BUSY_TIMEOUT_SECONDS)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS group_sender_ledger(id INTEGER PRIMARY KEY CHECK(id=1),ledger_ref TEXT NOT NULL UNIQUE);
            CREATE TABLE IF NOT EXISTS group_sender_endpoints(
                group_id TEXT NOT NULL,endpoint_id TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>0),
                fence_revision INTEGER NOT NULL CHECK(fence_revision>0),ready INTEGER NOT NULL CHECK(ready IN(0,1)),
                PRIMARY KEY(group_id,endpoint_id));
            CREATE TABLE IF NOT EXISTS group_sender_retirements(
                receipt_ref TEXT PRIMARY KEY,group_id TEXT NOT NULL,endpoint_id TEXT NOT NULL,old_generation INTEGER NOT NULL,
                new_generation INTEGER NOT NULL,fence_revision INTEGER NOT NULL,body TEXT NOT NULL,
                UNIQUE(group_id,endpoint_id,old_generation));
            CREATE TABLE IF NOT EXISTS group_sender_controls(
                group_id TEXT PRIMARY KEY,control_revision INTEGER NOT NULL CHECK(control_revision>=0),snapshot TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS group_sender_acks(
                group_id TEXT NOT NULL,endpoint_id TEXT NOT NULL,generation INTEGER NOT NULL,control_revision INTEGER NOT NULL,
                body TEXT NOT NULL,PRIMARY KEY(group_id,endpoint_id,generation,control_revision));
            CREATE TABLE IF NOT EXISTS group_sender_deliveries(
                group_id TEXT NOT NULL,endpoint_id TEXT NOT NULL,delivery_id TEXT NOT NULL,request TEXT NOT NULL,fingerprint TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN('PENDING','BLOCKED','DISPATCHING','DELIVERED','UNKNOWN')),
                generation INTEGER,control_revision INTEGER,authorization TEXT,receipt TEXT,blocked_refs TEXT NOT NULL DEFAULT '[]',
                PRIMARY KEY(group_id,endpoint_id,delivery_id));
            CREATE TABLE IF NOT EXISTS group_sender_intents(
                intent_id TEXT PRIMARY KEY,group_id TEXT NOT NULL,run_id TEXT,scopes TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),
                active INTEGER NOT NULL CHECK(active IN(0,1)),status TEXT NOT NULL,actor_id TEXT NOT NULL,command_id TEXT NOT NULL,
                create_fingerprint TEXT NOT NULL,boundary_ref TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS group_sender_control_attempts(
                command_id TEXT PRIMARY KEY,intent_id TEXT NOT NULL REFERENCES group_sender_intents(intent_id),ordinal INTEGER NOT NULL,
                body TEXT NOT NULL,fingerprint TEXT NOT NULL,status TEXT NOT NULL,receipt TEXT,actor_id TEXT NOT NULL,
                UNIQUE(intent_id,ordinal));
            CREATE TABLE IF NOT EXISTS group_sender_remote_submissions(
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,command_id TEXT NOT NULL REFERENCES group_sender_control_attempts(command_id),
                actor_id TEXT NOT NULL,status TEXT NOT NULL,receipt TEXT);
            CREATE TABLE IF NOT EXISTS group_sender_terminalizations(
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,command_id TEXT NOT NULL REFERENCES group_sender_control_attempts(command_id),
                actor_id TEXT NOT NULL,request_key TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,receipt TEXT);
            CREATE TABLE IF NOT EXISTS group_sender_local_commands(
                actor_id TEXT NOT NULL,operation TEXT NOT NULL,request_key TEXT NOT NULL,fingerprint TEXT NOT NULL,receipt TEXT NOT NULL,
                PRIMARY KEY(actor_id,operation,request_key));
            CREATE TABLE IF NOT EXISTS group_sender_audit(
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,actor_id TEXT NOT NULL,operation TEXT NOT NULL,subject_id TEXT NOT NULL,details TEXT NOT NULL);
        """)
        with self.transaction():
            self.db.execute("INSERT OR IGNORE INTO group_sender_ledger VALUES(1,?)", ("sender-ledger:" + str(uuid.uuid4()),))
        self.ledger_ref = self.db.execute("SELECT ledger_ref FROM group_sender_ledger WHERE id=1").fetchone()[0]

    def close(self):
        self.db.close()

    @contextmanager
    def transaction(self):
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            yield

    def row(self, sql: str, args=()) -> dict | None:
        row = self.db.execute(sql, args).fetchone()
        return dict(row) if row is not None else None

    def audit(self, actor: str, operation: str, subject: str, details: dict):
        self.db.execute("INSERT INTO group_sender_audit(actor_id,operation,subject_id,details) VALUES(?,?,?,?)",
                        (actor, operation, subject, canonical(details)))

    def replay(self, actor: str, operation: str, key: str, body: dict) -> dict | None:
        row = self.row("SELECT fingerprint,receipt FROM group_sender_local_commands WHERE actor_id=? AND operation=? AND request_key=?", (actor, operation, key))
        if row is None:
            return None
        if row["fingerprint"] != digest(body):
            raise SenderError("LOCAL_IDEMPOTENCY_CONFLICT")
        return json.loads(row["receipt"])

    def remember(self, actor: str, operation: str, key: str, body: dict, receipt: dict):
        self.db.execute("INSERT INTO group_sender_local_commands VALUES(?,?,?,?,?)", (actor, operation, key, digest(body), canonical(receipt)))

    def command_receipt(self, actor: str, group: str, operation: str, key: str) -> LocalCommandReceipt:
        try:
            operation = LocalCommandOperation(operation)
        except ValueError:
            raise SenderError("LOCAL_COMMAND_OPERATION_INVALID", 400) from None
        identity(key, "idempotency_key")
        row = self.row("SELECT fingerprint,receipt FROM group_sender_local_commands WHERE actor_id=? AND operation=? AND request_key=?", (actor, operation, key))
        if row is None:
            raise SenderError("LOCAL_COMMAND_RECEIPT_NOT_FOUND", 404)
        receipt = json.loads(row["receipt"])
        if receipt.get("group_id") != group:
            raise SenderError("LOCAL_COMMAND_OUTSIDE_GROUP", 403)
        return LocalCommandReceipt(operation, key, row["fingerprint"], receipt)

    def endpoint(self, endpoint: Endpoint) -> dict | None:
        return self.row("SELECT group_id,endpoint_id,generation,fence_revision,ready FROM group_sender_endpoints WHERE group_id=? AND endpoint_id=?", (endpoint.group_binding_id, endpoint.endpoint_id))

    def in_flight(self, endpoint: Endpoint) -> list[dict]:
        """只能在持有门禁写锁时取证；保留原delivery身份及当时的实际派发状态。"""
        flights = []
        for row in self.db.execute("SELECT delivery_id,status,receipt FROM group_sender_deliveries WHERE group_id=? AND endpoint_id=? AND status IN(?,?) ORDER BY delivery_id",
                                   (endpoint.group_binding_id, endpoint.endpoint_id, DeliveryStatus.DISPATCHING, DeliveryStatus.UNKNOWN)):
            receipt = json.loads(row["receipt"]) if row["receipt"] else {}
            flights.append({"delivery_id": row["delivery_id"], "status": row["status"], "result_ref": receipt.get("result_ref")})
        return flights

    def stage_endpoint(self, endpoint: Endpoint) -> dict | None:
        """同代重复启动复用隔离版本；新进程须换代，绝不清除原delivery与本地意图。"""
        with self.transaction():
            old = self.endpoint(endpoint)
            if old is not None and old["generation"] not in {endpoint.expected_generation, endpoint.generation}:
                raise SenderError("ENDPOINT_GENERATION_CONFLICT")
            if old is None and endpoint.expected_generation != 0:
                raise SenderError("PRIOR_LEDGER_REQUIRED")
            # 没有完成回执的已取得资格项在进程重建后保留UNKNOWN，禁止转回PENDING。
            self.db.execute("UPDATE group_sender_deliveries SET status=? WHERE group_id=? AND endpoint_id=? AND status=?",
                            (DeliveryStatus.UNKNOWN, endpoint.group_binding_id, endpoint.endpoint_id, DeliveryStatus.DISPATCHING))
            fence = old["fence_revision"] if old and old["generation"] == endpoint.generation else (old["fence_revision"] + 1 if old else 1)
            self.db.execute("INSERT INTO group_sender_endpoints VALUES(?,?,?,?,0) ON CONFLICT(group_id,endpoint_id) DO UPDATE SET generation=excluded.generation,fence_revision=excluded.fence_revision,ready=0",
                            (endpoint.group_binding_id, endpoint.endpoint_id, endpoint.generation, fence))
            if old and old["generation"] != endpoint.generation:
                receipt_ref = "sender-retirement:" + str(uuid.uuid4())
                body = {"group_binding_id": endpoint.group_binding_id, "endpoint_id": endpoint.endpoint_id,
                        "old_generation": old["generation"], "new_generation": endpoint.generation, "ledger_ref": self.ledger_ref,
                        "fence_revision": fence, "retirement_receipt_ref": receipt_ref,
                        "applied_boundary_ref": receipt_ref, "in_flight": self.in_flight(endpoint)}
                self.db.execute("INSERT INTO group_sender_retirements VALUES(?,?,?,?,?,?,?)", (receipt_ref, endpoint.group_binding_id, endpoint.endpoint_id, old["generation"], endpoint.generation, fence, canonical(body)))
                return body
            prior = self.row("SELECT body FROM group_sender_retirements WHERE group_id=? AND endpoint_id=? AND new_generation=?", (endpoint.group_binding_id, endpoint.endpoint_id, endpoint.generation))
            return json.loads(prior["body"]) if prior else None

    def apply_control(self, endpoint: Endpoint, snapshot: dict) -> dict:
        if snapshot.get("group_binding_id") != endpoint.group_binding_id:
            raise SenderError("REMOTE_GROUP_IDENTITY_MISMATCH", 503)
        rev = revision(snapshot.get("control_revision"), initial=True)
        with self.transaction():
            row = self.endpoint(endpoint)
            if row is None or row["generation"] != endpoint.generation:
                raise SenderError("ENDPOINT_FENCED")
            old = self.row("SELECT control_revision,snapshot FROM group_sender_controls WHERE group_id=?", (endpoint.group_binding_id,))
            if old and rev < old["control_revision"]:
                raise SenderError("STALE_CONTROL_REVISION")
            if old and rev == old["control_revision"] and json.loads(old["snapshot"]).get("states", []) != snapshot.get("states", []):
                raise SenderError("CONTROL_REVISION_CONTENT_CONFLICT")
            self.db.execute("INSERT INTO group_sender_controls VALUES(?,?,?) ON CONFLICT(group_id) DO UPDATE SET control_revision=excluded.control_revision,snapshot=excluded.snapshot", (endpoint.group_binding_id, rev, canonical(snapshot)))
            self.db.execute("UPDATE group_sender_endpoints SET ready=1 WHERE group_id=? AND endpoint_id=? AND generation=?", (endpoint.group_binding_id, endpoint.endpoint_id, endpoint.generation))
            prior_ack = self.row("SELECT body FROM group_sender_acks WHERE group_id=? AND endpoint_id=? AND generation=? AND control_revision=?",
                                 (endpoint.group_binding_id, endpoint.endpoint_id, endpoint.generation, rev))
            if prior_ack:
                return json.loads(prior_ack["body"])
            latest = snapshot.get("latest_command") or {}
            ack = {"group_binding_id": endpoint.group_binding_id, "endpoint_id": endpoint.endpoint_id, "generation": endpoint.generation,
                   "command_id": latest.get("command_id", "initial"), "control_revision": rev,
                   "ledger_ref": self.ledger_ref, "fence_revision": row["fence_revision"],
                   "applied_boundary_ref": "sender-control:" + digest([self.ledger_ref, endpoint.group_binding_id, endpoint.generation, rev]),
                   "in_flight": self.in_flight(endpoint)}
            self.db.execute("INSERT INTO group_sender_acks VALUES(?,?,?,?,?)", (endpoint.group_binding_id, endpoint.endpoint_id, endpoint.generation, rev, canonical(ack)))
            return ack

    def delivery(self, endpoint: Endpoint, delivery_id: str) -> dict | None:
        row = self.row("SELECT group_id,endpoint_id,delivery_id,request,status,generation,control_revision,authorization,receipt,blocked_refs FROM group_sender_deliveries WHERE group_id=? AND endpoint_id=? AND delivery_id=?", (endpoint.group_binding_id, endpoint.endpoint_id, delivery_id))
        if row:
            for field in ("request", "authorization", "receipt", "blocked_refs"):
                row[field] = json.loads(row[field]) if row[field] is not None else None
        return row

    def enqueue(self, endpoint: Endpoint, request: dict) -> dict:
        required = {"group_binding_id", "run_id", "message_id", "message_version", "delivery_id"}
        if set(request) != required or request["group_binding_id"] != endpoint.group_binding_id:
            raise SenderError("EXACT_GROUP_DELIVERY_IDENTITY_REQUIRED", 400)
        for field in required - {"message_version"}:
            identity(request[field], field)
        revision(request["message_version"])
        with self.transaction():
            old = self.delivery(endpoint, request["delivery_id"])
            if old and old["request"] != request:
                raise SenderError("DELIVERY_IDENTITY_CONFLICT")
            if not old:
                self.db.execute("INSERT INTO group_sender_deliveries(group_id,endpoint_id,delivery_id,request,fingerprint,status) VALUES(?,?,?,?,?,?)", (endpoint.group_binding_id, endpoint.endpoint_id, request["delivery_id"], canonical(request), digest(request), DeliveryStatus.PENDING))
        return self.delivery(endpoint, request["delivery_id"])

    def qualify_dispatch(self, endpoint: Endpoint, delivery_id: str, authorization: dict) -> bool:
        """唯一发送资格事务；远端已核final/根边界，本地最后核世代、版本和全部阻止。"""
        with self.transaction():
            row = self.delivery(endpoint, delivery_id)
            if row is None:
                raise SenderError("DELIVERY_NOT_FOUND", 404)
            if row["status"] in {DeliveryStatus.DELIVERED, DeliveryStatus.UNKNOWN, DeliveryStatus.DISPATCHING}:
                return False
            current = self.endpoint(endpoint)
            if current is None or current["generation"] != endpoint.generation or not current["ready"]:
                raise SenderError("ENDPOINT_FENCED")
            for field, value in row["request"].items():
                if authorization.get(field) != value:
                    raise SenderError("REMOTE_DELIVERY_IDENTITY_MISMATCH", 503)
            control = self.row("SELECT control_revision,snapshot FROM group_sender_controls WHERE group_id=?", (endpoint.group_binding_id,))
            if control is None or authorization.get("control_revision") != control["control_revision"]:
                raise SenderError("CONTROL_SNAPSHOT_ALIGNMENT_REQUIRED", 503)
            snapshot = json.loads(control["snapshot"])
            run = row["request"]["run_id"]
            blockers = list(authorization.get("blocked_boundary_refs", []))
            for state in snapshot.get("states", []):
                if state.get("run_id") in {None, run} and state.get("desired_state") == "paused" and SEND_SCOPE in state.get("scopes", []):
                    blockers.append("control:" + str(control["control_revision"]))
            for intent in self.db.execute("SELECT boundary_ref,run_id,scopes FROM group_sender_intents WHERE group_id=? AND active=1", (endpoint.group_binding_id,)):
                if intent["run_id"] in {None, run} and SEND_SCOPE in json.loads(intent["scopes"]):
                    blockers.append(intent["boundary_ref"])
            # 旧根边界由中央持久保留并只可精确release；最新正式qualify已覆盖的边界不在本地复活。
            # 本地意图只有经terminalize/远端恢复证明后才可inactive，因此仍有效的阻止独立叠加。
            blockers = sorted(set(blockers))
            allowed = authorization.get("eligible") is True and not blockers
            status = DeliveryStatus.DISPATCHING if allowed else DeliveryStatus.BLOCKED
            self.db.execute("UPDATE group_sender_deliveries SET status=?,generation=?,control_revision=?,authorization=?,blocked_refs=? WHERE group_id=? AND endpoint_id=? AND delivery_id=?",
                            (status, endpoint.generation, control["control_revision"], canonical(authorization), canonical(blockers), endpoint.group_binding_id, endpoint.endpoint_id, delivery_id))
            return allowed

    def finish(self, endpoint: Endpoint, delivery_id: str, receipt: dict | None):
        with self.transaction():
            row = self.delivery(endpoint, delivery_id)
            if row is None or row["status"] not in {DeliveryStatus.DISPATCHING, DeliveryStatus.UNKNOWN}:
                raise SenderError("DELIVERY_RESULT_STATE_CONFLICT")
            if receipt is not None and (not isinstance(receipt, dict) or receipt.get("delivery_id") != delivery_id or receipt.get("status") != "delivered"):
                raise SenderError("SINK_RECEIPT_IDENTITY_MISMATCH", 503)
            self.db.execute("UPDATE group_sender_deliveries SET status=?,receipt=? WHERE group_id=? AND endpoint_id=? AND delivery_id=?",
                            (DeliveryStatus.DELIVERED if receipt else DeliveryStatus.UNKNOWN, canonical(receipt) if receipt else None, endpoint.group_binding_id, endpoint.endpoint_id, delivery_id))

    def intent(self, intent_id: str) -> dict:
        row = self.row("SELECT intent_id,group_id,run_id,scopes,revision,active,status,actor_id,command_id,boundary_ref FROM group_sender_intents WHERE intent_id=?", (intent_id,))
        if row is None:
            raise SenderError("LOCAL_INTENT_NOT_FOUND", 404)
        row["scopes"] = json.loads(row["scopes"])
        row["attempts"] = [dict(item) for item in self.db.execute("SELECT command_id,ordinal,fingerprint,status,receipt,actor_id FROM group_sender_control_attempts WHERE intent_id=? ORDER BY ordinal", (intent_id,))]
        for attempt in row["attempts"]:
            attempt["receipt"] = json.loads(attempt["receipt"]) if attempt["receipt"] else None
        row["submissions"] = [dict(item) for item in self.db.execute("SELECT s.sequence,s.command_id,s.actor_id,s.status,s.receipt FROM group_sender_remote_submissions s JOIN group_sender_control_attempts a ON a.command_id=s.command_id WHERE a.intent_id=? ORDER BY s.sequence", (intent_id,))]
        for submission in row["submissions"]:
            submission["receipt"] = json.loads(submission["receipt"]) if submission["receipt"] else None
        row["terminalizations"] = [dict(item) for item in self.db.execute("SELECT t.sequence,t.command_id,t.actor_id,t.request_key,t.body,t.status,t.receipt FROM group_sender_terminalizations t JOIN group_sender_control_attempts a ON a.command_id=t.command_id WHERE a.intent_id=? ORDER BY t.sequence", (intent_id,))]
        for terminalization in row["terminalizations"]:
            terminalization["body"] = json.loads(terminalization["body"])
            terminalization["receipt"] = json.loads(terminalization["receipt"]) if terminalization["receipt"] else None
        return row

    def snapshot(self, group: str) -> dict:
        return {"ledger_ref": self.ledger_ref,
                "endpoints": [dict(row) for row in self.db.execute("SELECT group_id,endpoint_id,generation,fence_revision,ready FROM group_sender_endpoints WHERE group_id=? ORDER BY endpoint_id", (group,))],
                "deliveries": [dict(row) for row in self.db.execute("SELECT group_id,endpoint_id,delivery_id,status,generation,control_revision,blocked_refs FROM group_sender_deliveries WHERE group_id=? ORDER BY delivery_id", (group,))],
                "intents": [self.intent(row[0]) for row in self.db.execute("SELECT intent_id FROM group_sender_intents WHERE group_id=? ORDER BY intent_id", (group,))],
                "audit": [dict(row) for row in self.db.execute("SELECT a.sequence,a.actor_id,a.operation,a.subject_id,a.details FROM group_sender_audit a JOIN group_sender_intents i ON a.subject_id=i.intent_id WHERE i.group_id=? ORDER BY a.sequence", (group,))]}


class GroupSender:
    """单请求生命周期的发送服务；远端控制权由映射client验证，本地只保存安全阻止。"""
    def __init__(self, store: SenderStore, endpoint: Endpoint, remote: ControlPlane, sink: DeliverySink):
        self.store, self.endpoint, self.remote, self.sink = store, endpoint, remote, sink

    def start(self) -> dict:
        endpoint = self.endpoint
        self.remote.authorize(endpoint.group_binding_id, "manage")
        retired = self.store.stage_endpoint(endpoint)
        self.remote.register({"group_binding_id": endpoint.group_binding_id, "endpoint_id": endpoint.endpoint_id,
                              "generation": endpoint.generation, "expected_generation": endpoint.expected_generation, "ledger_ref": self.store.ledger_ref})
        if retired:
            self.remote.retire(retired)
        return self.apply()

    def apply(self) -> dict:
        self.remote.authorize(self.endpoint.group_binding_id, "ack")
        current = self.remote.current(self.endpoint.group_binding_id)
        ack = self.store.apply_control(self.endpoint, current)
        return {"local": ack, "remote": self.remote.ack(ack)}

    def deliver(self, request: dict) -> dict:
        row = self.store.enqueue(self.endpoint, request)
        if row["status"] in {DeliveryStatus.DELIVERED, DeliveryStatus.UNKNOWN, DeliveryStatus.DISPATCHING}:
            return row
        authorization = self.remote.qualify(request | {"endpoint_id": self.endpoint.endpoint_id, "generation": self.endpoint.generation})
        if not self.store.qualify_dispatch(self.endpoint, request["delivery_id"], authorization):
            return self.store.delivery(self.endpoint, request["delivery_id"])
        # SQLite资格提交在前，网络在后；崩溃/断链不回滚为未发送，也不重取一个delivery_id。
        try:
            receipt = self.sink.send(request | {"content": authorization["content"], "content_digest": authorization["content_digest"]})
        except (UnknownSend, OSError, TimeoutError):
            self.store.finish(self.endpoint, request["delivery_id"], None)
        else:
            try:
                self.store.finish(self.endpoint, request["delivery_id"], receipt)
            except SenderError:
                self.store.finish(self.endpoint, request["delivery_id"], None)
        return self.store.delivery(self.endpoint, request["delivery_id"])

    def reconcile_delivery(self, delivery_id: str) -> dict:
        row = self.store.delivery(self.endpoint, identity(delivery_id, "delivery_id"))
        if row is None:
            raise SenderError("DELIVERY_NOT_FOUND", 404)
        if row["status"] in {DeliveryStatus.UNKNOWN, DeliveryStatus.DISPATCHING}:
            self.remote.authorize(self.endpoint.group_binding_id, "reconcile")
            receipt = self.sink.receipt(delivery_id)
            if receipt is not None:
                self.store.finish(self.endpoint, delivery_id, receipt)
        return self.store.delivery(self.endpoint, delivery_id)

    def create_intent(self, actor: str, request: dict, key: str) -> dict:
        group = self.endpoint.group_binding_id
        authorization = self.remote.authorize(group, "write")
        allowed = {"local_intent_id", "group_binding_id", "run_id", "scopes", "expected_revision"}
        if set(request) - allowed or request.get("group_binding_id") != group:
            raise SenderError("INVALID_LOCAL_INTENT", 400)
        intent_id = identity(request.get("local_intent_id"), "local_intent_id")
        if request.get("expected_revision") != 0 or type(request.get("expected_revision")) is not int:
            raise SenderError("LOCAL_INTENT_CREATE_REQUIRES_REVISION_ZERO", 400)
        scopes = request.get("scopes")
        if not isinstance(scopes, list) or not scopes or len(scopes) != len(set(scopes)) or not set(scopes) <= CONTROL_SCOPES:
            raise SenderError("INVALID_CONTROL_SCOPES", 400)
        run = request.get("run_id")
        if run is not None:
            identity(run, "run_id")
        with self.store.transaction():
            previous = self.store.replay(actor, "intent.create", key, request)
            if previous is not None:
                return previous
        with self.store.transaction():
            previous = self.store.replay(actor, "intent.create", key, request)
            if previous is not None:
                return previous
            exists = self.store.row("SELECT create_fingerprint,actor_id FROM group_sender_intents WHERE intent_id=?", (intent_id,))
            if exists:
                if exists["create_fingerprint"] != digest(request):
                    raise SenderError("LOCAL_INTENT_IDENTITY_CONFLICT")
                if exists["actor_id"] != actor:
                    raise SenderError("LOCAL_INTENT_REQUIRES_EXPLICIT_RECONCILIATION")
            else:
                command_id = "local-control:" + digest([self.store.ledger_ref, intent_id, 1])
                command = {"command_id": command_id, "group_binding_id": group, "run_id": run,
                           "expected_revision": revision(authorization["control_revision"], initial=True), "desired_state": "paused", "scopes": sorted(scopes)}
                boundary = "local-intent:" + digest([self.store.ledger_ref, intent_id])
                self.store.db.execute("INSERT INTO group_sender_intents VALUES(?,?,?,?,1,1,?,?,?,?,?)", (intent_id, group, run, canonical(sorted(scopes)), IntentStatus.BLOCKING, actor, command_id, digest(request), boundary))
                self.store.db.execute("INSERT INTO group_sender_control_attempts VALUES(?,?,1,?,?,?,?,?)", (command_id, intent_id, canonical(command), digest(command), AttemptStatus.PENDING, None, actor))
                self.store.audit(actor, "intent.create", intent_id, {"command_id": command_id, "boundary_ref": boundary})
            receipt = self.store.intent(intent_id)
            self.store.remember(actor, "intent.create", key, request, receipt)
        # 本地阻止已经提交；此后的远端错误始终保留原尝试身份和阻止。
        self.submit_intent(intent_id, actor)
        return receipt

    def submit_intent(self, intent_id: str, actor: str) -> dict:
        intent = self.store.intent(intent_id)
        self.remote.authorize(intent["group_id"], "write")
        attempt = self.store.row("SELECT body,status FROM group_sender_control_attempts WHERE command_id=?", (intent["command_id"],))
        if attempt["status"] not in {AttemptStatus.PENDING, AttemptStatus.UNKNOWN}:
            return intent
        with self.store.transaction():
            submission = self.store.db.execute("INSERT INTO group_sender_remote_submissions(command_id,actor_id,status) VALUES(?,?,?)", (intent["command_id"], actor, AttemptStatus.PENDING)).lastrowid
        try:
            receipt = self.remote.submit(json.loads(attempt["body"]))
        except ApiFailure as failure:
            state = AttemptStatus.UNKNOWN if failure.status >= 500 else AttemptStatus.REJECTED
            receipt = {"http_status": failure.status, "code": failure.code}
        except RuntimeError:
            state, receipt = AttemptStatus.UNKNOWN, {"code": "REMOTE_OUTCOME_UNKNOWN"}
        else:
            state = AttemptStatus.ACCEPTED
        with self.store.transaction():
            self.store.db.execute("UPDATE group_sender_remote_submissions SET status=?,receipt=? WHERE sequence=?", (state, canonical(receipt), submission))
            known = self.store.row("SELECT status FROM group_sender_control_attempts WHERE command_id=?", (intent["command_id"],))["status"]
            if known not in {AttemptStatus.ACCEPTED, AttemptStatus.TERMINAL_NOT_ACCEPTED}:
                self.store.db.execute("UPDATE group_sender_control_attempts SET status=?,receipt=? WHERE command_id=?", (state, canonical(receipt), intent["command_id"]))
                status = IntentStatus.REMOTE_ACCEPTED if state == AttemptStatus.ACCEPTED else IntentStatus.RECONCILIATION_REQUIRED
                self.store.db.execute("UPDATE group_sender_intents SET status=?,revision=revision+1 WHERE intent_id=? AND active=1", (status, intent_id))
            self.store.audit(actor, "intent.submit", intent_id, {"command_id": intent["command_id"], "result": state})
        return self.store.intent(intent_id)

    def _terminalize(self, actor: str, intent: dict, command: dict, key: str) -> dict:
        """终结也有未知结果：提交前记原body/key，后继沿原请求取证，不由新revision改写。"""
        current = self.remote.current(intent["group_id"])
        with self.store.transaction():
            proof = self.store.row("SELECT receipt FROM group_sender_terminalizations WHERE command_id=? AND status IN(?,?) ORDER BY sequence LIMIT 1",
                                   (intent["command_id"], AttemptStatus.ACCEPTED, AttemptStatus.TERMINAL_NOT_ACCEPTED))
            if proof:
                return json.loads(proof["receipt"])
            previous = self.store.row("SELECT request_key,body,status FROM group_sender_terminalizations WHERE command_id=? ORDER BY sequence DESC LIMIT 1", (intent["command_id"],))
            if previous and previous["status"] in {AttemptStatus.PENDING, AttemptStatus.UNKNOWN}:
                request_key, body = previous["request_key"], json.loads(previous["body"])
            else:
                if previous and previous["request_key"] == key:
                    raise SenderError("REJECTED_TERMINALIZATION_REQUIRES_NEW_KEY")
                request_key = key
                body = {"group_binding_id": intent["group_id"], "run_id": intent["run_id"], "command_id": intent["command_id"],
                        "fingerprint": command["fingerprint"], "local_intent_id": intent["intent_id"], "expected_revision": current["control_revision"]}
            sequence = self.store.db.execute("INSERT INTO group_sender_terminalizations(command_id,actor_id,request_key,body,status) VALUES(?,?,?,?,?)",
                                             (intent["command_id"], actor, request_key, canonical(body), AttemptStatus.PENDING)).lastrowid
        try:
            receipt = self.remote.terminalize(body, request_key)
            kind = receipt.get("result_kind")
            state = {"accepted": AttemptStatus.ACCEPTED, "terminal_not_accepted": AttemptStatus.TERMINAL_NOT_ACCEPTED}.get(kind, AttemptStatus.UNKNOWN)
        except ApiFailure as failure:
            state = AttemptStatus.UNKNOWN if failure.status >= 500 else AttemptStatus.REJECTED
            receipt = {"http_status": failure.status, "code": failure.code}
            error = failure
        except RuntimeError as failure:
            state, receipt, error = AttemptStatus.UNKNOWN, {"code": "REMOTE_TERMINALIZATION_UNKNOWN"}, failure
        else:
            error = None
        with self.store.transaction():
            self.store.db.execute("UPDATE group_sender_terminalizations SET status=?,receipt=? WHERE sequence=?", (state, canonical(receipt), sequence))
            self.store.audit(actor, "intent.terminalize", intent["intent_id"], {"command_id": intent["command_id"], "request_key": request_key, "result": state})
        if error:
            raise error
        return receipt

    def reconcile_intent(self, actor: str, request: dict, key: str) -> dict:
        intent_id = identity(request.get("intent_id"), "intent_id")
        self.remote.authorize(self.endpoint.group_binding_id, "reconcile")
        action = request.get("action")
        if set(request) - {"intent_id", "expected_revision", "action"} or action not in {"query", "resubmit", "abandon"}:
            raise SenderError("INVALID_RECONCILIATION_ACTION", 400)
        with self.store.transaction():
            prior = self.store.replay(actor, "intent.reconcile", key, request)
            if prior is not None:
                return prior
            intent = self.store.intent(intent_id)
            if intent["group_id"] != self.endpoint.group_binding_id or intent["revision"] != revision(request.get("expected_revision")) or not intent["active"]:
                raise SenderError("LOCAL_INTENT_REVISION_CONFLICT")
            command = self.store.row("SELECT body,fingerprint FROM group_sender_control_attempts WHERE command_id=?", (intent["command_id"],))
        # 终结与中央受理竞争同一权威锁；普通404/403/网络结果不能替代未受理证明。
        proof = self._terminalize(actor, intent, command, key)
        kind = proof.get("result_kind")
        current = self.remote.current(intent["group_id"])
        with self.store.transaction():
            latest = self.store.intent(intent_id)
            if latest["revision"] != intent["revision"]:
                raise SenderError("LOCAL_INTENT_REVISION_CONFLICT")
            if kind == "accepted":
                state, status = AttemptStatus.ACCEPTED, IntentStatus.REMOTE_ACCEPTED
                if action != "query":
                    raise SenderError("ACCEPTED_INTENT_REQUIRES_REMOTE_RESUME")
            elif kind == "terminal_not_accepted":
                if not proof.get("result_ref") or not proof.get("boundary_ref") or type(proof.get("through_trigger_sequence")) is not int:
                    raise SenderError("DURABLE_UNACCEPTED_BOUNDARY_REQUIRED", 503)
                state, status = AttemptStatus.TERMINAL_NOT_ACCEPTED, IntentStatus.RECONCILIATION_REQUIRED
                if action == "abandon":
                    if self._remote_paused(current, intent["run_id"], intent["scopes"]):
                        raise SenderError("CURRENT_REMOTE_CONTROL_BLOCKS_ABANDON")
                    status = IntentStatus.ABANDONED
                    self.store.db.execute("UPDATE group_sender_intents SET active=0 WHERE intent_id=?", (intent_id,))
                elif action == "resubmit":
                    old_body = json.loads(command["body"])
                    ordinal = len(latest["attempts"]) + 1
                    new_id = "local-control:" + digest([self.store.ledger_ref, intent_id, ordinal])
                    new_body = old_body | {"command_id": new_id, "expected_revision": current["control_revision"]}
                    self.store.db.execute("INSERT INTO group_sender_control_attempts VALUES(?,?,?,?,?,?,?,?)", (new_id, intent_id, ordinal, canonical(new_body), digest(new_body), AttemptStatus.PENDING, None, actor))
                    self.store.db.execute("UPDATE group_sender_intents SET command_id=? WHERE intent_id=?", (new_id, intent_id))
            else:
                raise SenderError("REMOTE_ACCEPTANCE_UNRESOLVED", 503)
            self.store.db.execute("UPDATE group_sender_control_attempts SET status=?,receipt=? WHERE command_id=?", (state, canonical(proof), intent["command_id"]))
            self.store.db.execute("UPDATE group_sender_intents SET revision=revision+1,status=? WHERE intent_id=?", (status, intent_id))
            self.store.audit(actor, "intent." + action, intent_id, {"proof_ref": proof.get("result_ref"), "previous_actor": intent["actor_id"]})
            result = self.store.intent(intent_id)
            self.store.remember(actor, "intent.reconcile", key, request, result)
        if action == "resubmit":
            self.submit_intent(intent_id, actor)
        return result

    @staticmethod
    def _remote_paused(snapshot: dict, run: str | None, scopes: list[str]) -> bool:
        return any(state.get("desired_state") == "paused" and state.get("run_id") in {None, run} and set(scopes).intersection(state.get("scopes", [])) for state in snapshot.get("states", []))

    def release_intent(self, actor: str, request: dict, key: str) -> dict:
        self.remote.authorize(self.endpoint.group_binding_id, "release")
        if set(request) != {"intent_id", "expected_revision", "resume_command_id"}:
            raise SenderError("EXACT_LOCAL_RELEASE_PROOF_REQUIRED", 400)
        intent_id = identity(request.get("intent_id"), "intent_id")
        with self.store.transaction():
            prior = self.store.replay(actor, "intent.release", key, request)
            if prior is not None:
                return prior
        intent = self.store.intent(intent_id)
        if intent["group_id"] != self.endpoint.group_binding_id:
            raise SenderError("LOCAL_INTENT_OUTSIDE_GROUP", 403)
        receipt = self.remote.detail(intent["group_id"], identity(request["resume_command_id"], "resume_command_id"))
        current = self.remote.current(intent["group_id"])
        if (receipt.get("desired_state") != "running" or receipt.get("status") != "APPLIED"
                or receipt.get("control_revision") != current["control_revision"] or receipt.get("run_id") != intent["run_id"]
                or not set(intent["scopes"]) <= set(receipt.get("scopes", [])) or self._remote_paused(current, intent["run_id"], intent["scopes"])):
            raise SenderError("APPLIED_CURRENT_REMOTE_RESUME_REQUIRED")
        with self.store.transaction():
            prior = self.store.replay(actor, "intent.release", key, request)
            if prior is not None:
                return prior
            latest = self.store.intent(intent_id)
            if latest["revision"] != revision(request["expected_revision"]) or not latest["active"]:
                raise SenderError("LOCAL_INTENT_REVISION_CONFLICT")
            if any(attempt["status"] in {AttemptStatus.PENDING, AttemptStatus.UNKNOWN, AttemptStatus.REJECTED} for attempt in latest["attempts"]):
                raise SenderError("ALL_REMOTE_ATTEMPTS_MUST_BE_RECONCILED")
            self.store.db.execute("UPDATE group_sender_intents SET active=0,status=?,revision=revision+1 WHERE intent_id=?", (IntentStatus.RELEASED, intent_id))
            self.store.audit(actor, "intent.release", intent_id, {"resume_command_id": request["resume_command_id"], "previous_actor": intent["actor_id"]})
            result = self.store.intent(intent_id)
            self.store.remember(actor, "intent.release", key, request, result)
            return result
