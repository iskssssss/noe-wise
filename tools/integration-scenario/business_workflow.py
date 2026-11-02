"""门店业务示例：凭据限定授权，业务自身持有确认、事务与原操作回执。

SQLite 仅属于外部模拟业务系统；这里的幂等能力不授予 Runtime 重放 UNKNOWN 的权限。
"""
from __future__ import annotations

import hashlib
import json
import os
import secrets
import sqlite3
import threading
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

CONFIRMATION_TTL_SECONDS = 300
CONFIRMATION_REQUEST = "confirm_business"
BUSINESS_PROTOCOL = "2026-07-28"
MAX_ID_LENGTH = 128
MAX_CONTENT_LENGTH = 2000


class BusinessError(ValueError):
    """只允许固定原因码越过 HTTP 边界，不输出患者信息或凭据。"""

    def __init__(self, code: str, status: int = 422):
        super().__init__(code)
        self.code, self.status = code, status


def _text(value, maximum=MAX_ID_LENGTH):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise BusinessError("BOUNDED_BUSINESS_STRING_REQUIRED")
    return value


def _time(value):
    _text(value, 64)
    try:
        # 保留原时区表示供确认展示；比较时统一为绝对时刻。
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if result.tzinfo is None or result.utcoffset() is None:
            raise ValueError()
        return result
    except ValueError:
        raise BusinessError("OFFSET_BUSINESS_TIME_REQUIRED") from None


def _utc(value):
    return value.astimezone(timezone.utc).isoformat(timespec="microseconds")


def _schema(properties):
    return {"type": "object", "properties": properties, "required": list(properties),
            "additionalProperties": False}


_ID = {"type": "string", "minLength": 1, "maxLength": MAX_ID_LENGTH}
_TIME = {"type": "string", "format": "date-time", "maxLength": 64}
_APPOINTMENT = {"patient_ref": _ID, "patient_time": _TIME, "appointment_time": _TIME}
BUSINESS_TOOLS = [
    {"name": "appointment_query", "description": "查询当前门店获授权患者的已创建预约。只读，不提交预约，不改变私聊主体。",
     "inputSchema": _schema({"patient_ref": _ID})},
    {"name": "appointment_evaluate", "description": "评估患者原始时间意向与选定预约时间。只读，不预占、不创建、不写长期记忆。",
     "inputSchema": _schema(_APPOINTMENT)},
    {"name": "appointment_create", "description": "创建预约前展示对象、患者意向、选定时间和有效期，等待本次确认。原request_id只对应同一操作；结果未知时查询business_receipt，不自动重新提交。",
     "inputSchema": _schema({"request_id": _ID, **_APPOINTMENT})},
    {"name": "followup_create", "description": "创建随访前等待用户确认患者、时间、内容与有效期。任务草稿不写长期记忆。未知结果只查询原business_receipt。",
     "inputSchema": _schema({"request_id": _ID, "patient_ref": _ID, "scheduled_time": _TIME,
                            "content": {"type": "string", "minLength": 1, "maxLength": MAX_CONTENT_LENGTH}})},
    {"name": "business_receipt", "description": "只读查询当前门店及服务操作者名下原request_id的确认状态或业务回执；未找到不代表外部未知操作可以重放。",
     "inputSchema": _schema({"request_id": _ID})},
]
BUSINESS_TOOL_NAMES = frozenset(tool["name"] for tool in BUSINESS_TOOLS)


@dataclass(frozen=True)
class BusinessAuthorization:
    """独立 Connector 凭据映射一个固定服务操作者；不是当前聊天用户的身份。"""

    token: str = field(repr=False)
    tenant_ref: str
    store_ref: str
    operator_ref: str
    patient_refs: frozenset[str]

    def __post_init__(self):
        if (not isinstance(self.token, str) or not self.token
                or any(ord(character) <= 32 or ord(character) >= 127 for character in self.token)):
            raise BusinessError("BUSINESS_CREDENTIAL_REQUIRED")
        for value in (self.tenant_ref, self.store_ref, self.operator_ref):
            _text(value)
        if not isinstance(self.patient_refs, frozenset) or not self.patient_refs:
            raise BusinessError("BUSINESS_PATIENT_SCOPE_REQUIRED")
        for patient in self.patient_refs:
            _text(patient)


class BusinessAuthorizations:
    """凭据映射由业务服务配置持有；替换授权与执行事务串行，不接受模型覆盖。"""

    def __init__(self, authorizations=()):
        self._lock = threading.RLock()
        self.replace(authorizations)

    def replace(self, authorizations):
        grants = {}
        for grant in authorizations:
            if not isinstance(grant, BusinessAuthorization):
                raise BusinessError("BUSINESS_AUTHORIZATION_REQUIRED")
            key = hashlib.sha256(grant.token.encode()).digest()
            if key in grants:
                raise BusinessError("DUPLICATE_BUSINESS_CREDENTIAL")
            grants[key] = grant
        with self._lock:
            self._grants = grants

    @contextmanager
    def authorized(self, authorization):
        if not isinstance(authorization, str) or not authorization.startswith("Bearer "):
            raise BusinessError("BUSINESS_AUTHENTICATION_REQUIRED", 401)
        key = hashlib.sha256(authorization[7:].encode()).digest()
        with self._lock:
            grant = self._grants.get(key)
            if grant is None:
                raise BusinessError("BUSINESS_AUTHENTICATION_REQUIRED", 401)
            yield grant


def load_business_config(path: Path):
    """非秘密配置只保存环境变量名；缺失凭据时阻止启用，不降级到无授权业务写入。"""
    config = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(config, dict) or set(config) - {"authorizations", "confirmation_ttl_seconds"}:
        raise BusinessError("BUSINESS_CONFIG_INVALID")
    items = config.get("authorizations")
    if not isinstance(items, list) or not items:
        raise BusinessError("BUSINESS_AUTHORIZATIONS_REQUIRED")
    grants = []
    for item in items:
        if not isinstance(item, dict) or set(item) != {"token_env", "tenant_ref", "store_ref", "operator_ref", "patient_refs"}:
            raise BusinessError("BUSINESS_AUTHORIZATION_CONFIG_INVALID")
        environment = _text(item["token_env"])
        patients = item["patient_refs"]
        if not isinstance(patients, list) or len(patients) != len(set(patients)):
            raise BusinessError("BUSINESS_PATIENT_SCOPE_REQUIRED")
        grants.append(BusinessAuthorization(os.environ.get(environment), item["tenant_ref"], item["store_ref"],
                                            item["operator_ref"], frozenset(patients)))
    return BusinessAuthorizations(grants), config.get("confirmation_ttl_seconds", CONFIRMATION_TTL_SECONDS)


class BusinessWorkflow:
    """确认计划与最终写入同属外部业务系统，固定字段关系化保存，原参数摘要防止换参。"""

    def __init__(self, path: Path, authorizations: BusinessAuthorizations,
                 confirmation_ttl_seconds=CONFIRMATION_TTL_SECONDS, clock=None):
        if (type(confirmation_ttl_seconds) is not int
                or not 1 <= confirmation_ttl_seconds <= 3600):
            raise BusinessError("BUSINESS_CONFIRMATION_TTL_INVALID")
        self.path, self.authorizations = Path(path), authorizations
        self.confirmation_ttl_seconds = confirmation_ttl_seconds
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connection() as db:
            # 一行是一个稳定业务动作；pending 为待确认草稿，created 才是业务已写事实。
            # 冻结参数使用类型列，SHA-256 仅核对规范 JSON 参数整体，没有第二份 JSON 状态。
            db.executescript("""
                CREATE TABLE IF NOT EXISTS business_operations (
                    tenant_ref TEXT NOT NULL, store_ref TEXT NOT NULL, operator_ref TEXT NOT NULL,
                    request_id TEXT NOT NULL CHECK(length(request_id) BETWEEN 1 AND 128),
                    tool_name TEXT NOT NULL CHECK(tool_name IN ('appointment_create','followup_create')),
                    patient_ref TEXT NOT NULL, argument_digest TEXT NOT NULL CHECK(length(argument_digest)=64),
                    patient_time TEXT, appointment_time TEXT, scheduled_time TEXT, content TEXT,
                    request_state TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL, expires_at TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('pending_confirmation','created','declined','expired')),
                    business_id TEXT UNIQUE, confirmed_at TEXT,
                    PRIMARY KEY (tenant_ref,store_ref,operator_ref,request_id),
                    CHECK((tool_name='appointment_create' AND patient_time IS NOT NULL AND appointment_time IS NOT NULL
                           AND scheduled_time IS NULL AND content IS NULL)
                       OR (tool_name='followup_create' AND patient_time IS NULL AND appointment_time IS NULL
                           AND scheduled_time IS NOT NULL AND content IS NOT NULL)),
                    CHECK((status='created' AND business_id IS NOT NULL AND confirmed_at IS NOT NULL)
                       OR (status<>'created' AND business_id IS NULL AND confirmed_at IS NULL))
                );
                CREATE INDEX IF NOT EXISTS business_operations_patient
                    ON business_operations(tenant_ref,store_ref,patient_ref,status);
            """)

    @contextmanager
    def _connection(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            db.execute("PRAGMA journal_mode=WAL")
            with db:
                yield db
        finally:
            db.close()

    def tools(self, authorization):
        with self.authorizations.authorized(authorization):
            return BUSINESS_TOOLS

    def invoke(self, name, arguments, authorization, input_responses=None, request_state=None):
        """每次含查询、续答与回执读取都核对当前凭据和患者授权，extraParameters 无授权作用。"""
        if name not in BUSINESS_TOOL_NAMES:
            raise BusinessError("BUSINESS_TOOL_UNKNOWN")
        with self.authorizations.authorized(authorization) as grant:
            self._validate(name, arguments)
            if "patient_ref" in arguments and arguments["patient_ref"] not in grant.patient_refs:
                raise BusinessError("BUSINESS_PATIENT_FORBIDDEN", 403)
            continuation = input_responses is not None or request_state is not None
            if continuation and name not in {"appointment_create", "followup_create"}:
                raise BusinessError("BUSINESS_CONTINUATION_NOT_SUPPORTED")
            with self._connection() as db:
                if name == "appointment_evaluate":
                    return self._complete({"status": "evaluated", "patient_ref": arguments["patient_ref"],
                                           "patient_time": arguments["patient_time"],
                                           "appointment_time": arguments["appointment_time"],
                                           "time_matches": _time(arguments["patient_time"]) == _time(arguments["appointment_time"]),
                                           "submitted": False, "simulated": True})
                if name == "appointment_query":
                    rows = db.execute("SELECT * FROM business_operations WHERE tenant_ref=? AND store_ref=? "
                                      "AND patient_ref=? AND tool_name='appointment_create' AND status='created' "
                                      "ORDER BY confirmed_at,request_id", (grant.tenant_ref, grant.store_ref, arguments["patient_ref"])).fetchall()
                    return self._complete({"status": "queried", "appointments": [self._receipt(row) for row in rows],
                                           "submitted": False, "simulated": True})
                if name == "business_receipt":
                    row = self._find(db, grant, arguments["request_id"])
                    if row is None:
                        return self._complete({"status": "not_found", "request_id": arguments["request_id"],
                                               "replay_authorized": False, "simulated": True})
                    self._require_patient(grant, row)
                    return self._complete(self._receipt(row))
                return self._write(db, grant, name, arguments, input_responses, request_state, continuation)

    def _write(self, db, grant, name, arguments, responses, state, continuation):
        digest = hashlib.sha256(json.dumps(arguments, sort_keys=True, separators=(",", ":"),
                                           ensure_ascii=False).encode()).hexdigest()
        db.execute("BEGIN IMMEDIATE")
        # 写锁等待可能跨过确认有效期；取得业务写权后采样，不能使用排队前的时间。
        now = self.clock()
        if not isinstance(now, datetime) or now.tzinfo is None:
            raise BusinessError("BUSINESS_CLOCK_INVALID")
        row = self._find(db, grant, arguments["request_id"])
        if row is None:
            if continuation:
                raise BusinessError("BUSINESS_CONFIRMATION_NOT_FOUND", 409)
            if _time(arguments.get("appointment_time", arguments.get("scheduled_time"))) <= now:
                raise BusinessError("BUSINESS_TARGET_TIME_EXPIRED", 409)
            db.execute("INSERT INTO business_operations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                       (grant.tenant_ref, grant.store_ref, grant.operator_ref, arguments["request_id"], name,
                        arguments["patient_ref"], digest, arguments.get("patient_time"), arguments.get("appointment_time"),
                        arguments.get("scheduled_time"), arguments.get("content"), secrets.token_urlsafe(32),
                        _utc(now), _utc(now + timedelta(seconds=self.confirmation_ttl_seconds)),
                        "pending_confirmation", None, None))
            row = self._find(db, grant, arguments["request_id"])
        if row["argument_digest"] != digest or row["tool_name"] != name:
            raise BusinessError("BUSINESS_IDEMPOTENCY_CONFLICT", 409)
        self._require_patient(grant, row)
        if continuation:
            if not isinstance(state, str) or not secrets.compare_digest(state, row["request_state"]):
                raise BusinessError("BUSINESS_CONFIRMATION_STATE_MISMATCH", 409)
            accepted = self._accepted(responses)
        # 已完成的原身份只读原回执；时效过后也不重做业务操作。
        if row["status"] != "pending_confirmation":
            return self._complete(self._receipt(row))
        target_time = _time(row["appointment_time"] or row["scheduled_time"])
        if now >= _time(row["expires_at"]) or target_time <= now:
            db.execute("UPDATE business_operations SET status='expired' WHERE tenant_ref=? AND store_ref=? "
                       "AND operator_ref=? AND request_id=?", self._key(grant, arguments["request_id"]))
            return self._complete(self._receipt(self._find(db, grant, arguments["request_id"])))
        if not continuation:
            return self._confirmation(row)
        if not accepted:
            db.execute("UPDATE business_operations SET status='declined' WHERE tenant_ref=? AND store_ref=? "
                       "AND operator_ref=? AND request_id=?", self._key(grant, arguments["request_id"]))
        else:
            # 外部模拟系统的业务事实和回执同事务；丢失网络响应不会造成第二次写入。
            db.execute("UPDATE business_operations SET status='created',business_id=?,confirmed_at=? "
                       "WHERE tenant_ref=? AND store_ref=? AND operator_ref=? AND request_id=?",
                       (str(uuid.uuid4()), _utc(now), *self._key(grant, arguments["request_id"])))
        return self._complete(self._receipt(self._find(db, grant, arguments["request_id"])))

    @staticmethod
    def _validate(name, arguments):
        schema = next(tool["inputSchema"] for tool in BUSINESS_TOOLS if tool["name"] == name)
        if not isinstance(arguments, dict) or set(arguments) != set(schema["properties"]):
            raise BusinessError("DECLARED_BUSINESS_ARGUMENTS_REQUIRED")
        for key, value in arguments.items():
            _text(value, MAX_CONTENT_LENGTH if key == "content" else 64 if key.endswith("_time") else MAX_ID_LENGTH)
            if key.endswith("_time"):
                _time(value)

    @staticmethod
    def _accepted(responses):
        if not isinstance(responses, dict) or set(responses) != {CONFIRMATION_REQUEST}:
            raise BusinessError("EXACT_BUSINESS_CONFIRMATION_RESPONSE_REQUIRED")
        response = responses[CONFIRMATION_REQUEST]
        if not isinstance(response, dict) or response.get("action") not in {"accept", "decline", "cancel"}:
            raise BusinessError("BUSINESS_CONFIRMATION_RESPONSE_INVALID")
        if response["action"] != "accept":
            if set(response) != {"action"}:
                raise BusinessError("BUSINESS_CONFIRMATION_RESPONSE_INVALID")
            return False
        if (set(response) != {"action", "content"} or not isinstance(response["content"], dict)
                or set(response["content"]) != {"confirmed"} or type(response["content"]["confirmed"]) is not bool):
            raise BusinessError("EXPLICIT_BUSINESS_CONFIRMATION_REQUIRED")
        return response["content"]["confirmed"]

    @staticmethod
    def _key(grant, request_id):
        return grant.tenant_ref, grant.store_ref, grant.operator_ref, request_id

    def _find(self, db, grant, request_id):
        return db.execute("SELECT * FROM business_operations WHERE tenant_ref=? AND store_ref=? "
                          "AND operator_ref=? AND request_id=?", self._key(grant, request_id)).fetchone()

    @staticmethod
    def _require_patient(grant, row):
        if row["patient_ref"] not in grant.patient_refs:
            raise BusinessError("BUSINESS_PATIENT_FORBIDDEN", 403)

    @staticmethod
    def _receipt(row):
        fields = {key: row[key] for key in ("patient_ref", "patient_time", "appointment_time", "scheduled_time", "content")
                  if row[key] is not None}
        return {"type": "business_card", "card_type": "appointment" if row["tool_name"] == "appointment_create" else "followup",
                "request_id": row["request_id"], "business_id": row["business_id"], "status": row["status"],
                "tenant_ref": row["tenant_ref"], "store_ref": row["store_ref"], "operator_ref": row["operator_ref"],
                "fields": fields, "created_at": row["created_at"], "expires_at": row["expires_at"],
                "confirmed_at": row["confirmed_at"], "submitted": row["status"] == "created", "simulated": True}

    def _confirmation(self, row):
        card = self._receipt(row)
        return {"resultType": "input_required", "requestState": row["request_state"],
                "inputRequests": {CONFIRMATION_REQUEST: {"method": "elicitation/create", "params": {
                    "mode": "form", "message": "请确认本次业务提交，确认过期后无效：" + json.dumps(card, ensure_ascii=False),
                    "requestedSchema": _schema({"confirmed": {"type": "boolean", "title": "确认提交"}})}}}}

    @staticmethod
    def _complete(result):
        return {"resultType": "complete", "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
                "structuredContent": result, "isError": False}
