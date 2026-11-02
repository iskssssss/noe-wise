"""独立模拟钱包：真实 HTTP、独立 SQLite、累计目标结算与有界透支后付费。

钱包不读取适配器账本、不重新定价；已知业务拒绝与网络未知结果分别处理。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import secrets
import sqlite3
import sys
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import localcontext
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "integration-scenario"))
from settlement_contract import (MAX_BODY_BYTES, POLICY, SettlementError, currency, decode_json, fingerprint, identity,
                                 money, money_text, normalize_instruction)

LOG = logging.getLogger(__name__)
INSTRUCTION_COLUMNS = ("instruction_id", "object_id", "execution_tenant_id", "wallet_account_id", "currency",
                       "metering_object_ref", "sequence", "previous_instruction_id", "target_amount", "pricing_revision",
                       "reconciles_rejection_id")
ADMISSION_COLUMNS = ("admission_id", "execution_tenant_id", "wallet_account_id", "currency", "business_root_ref", "policy")


@dataclass(frozen=True)
class WalletPrincipal:
    caller_service_id: str
    allowed_account_ids: frozenset[str]

    def __post_init__(self):
        identity(self.caller_service_id)
        if not isinstance(self.allowed_account_ids, frozenset) or not self.allowed_account_ids:
            raise SettlementError("WALLET_ACCOUNT_SCOPE_REQUIRED")
        for account in self.allowed_account_ids:
            identity(account)


@dataclass(frozen=True)
class WalletCredential:
    principal: WalletPrincipal
    token: str = field(repr=False)


class WalletStore:
    """业务序号、回执、净扣款及余额在钱包自身事务提交；不存在跨库扣款。"""

    def __init__(self, path: Path, accounts: list[dict], *, clock=None, fault=None):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self.fault = fault
        normalized = [self._account_config(account) for account in accounts]
        if not normalized or len({(item["wallet_account_id"], item["currency"]) for item in normalized}) != len(normalized):
            raise SettlementError("UNIQUE_WALLET_ACCOUNTS_REQUIRED")
        with self._connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS wallet_accounts (
                    wallet_account_id TEXT NOT NULL, currency TEXT NOT NULL, execution_tenant_id TEXT NOT NULL,
                    name TEXT NOT NULL, initial_balance_cents TEXT NOT NULL, overdraft_limit_cents TEXT NOT NULL,
                    balance_cents TEXT NOT NULL, policy TEXT NOT NULL CHECK(policy='bounded_overdraft'),
                    revision INTEGER NOT NULL CHECK(revision>0), created_at TEXT NOT NULL,
                    PRIMARY KEY(wallet_account_id,currency)
                );
                CREATE TABLE IF NOT EXISTS wallet_settlement_objects (
                    caller_service_id TEXT NOT NULL, object_id TEXT NOT NULL, execution_tenant_id TEXT NOT NULL,
                    wallet_account_id TEXT NOT NULL, currency TEXT NOT NULL, metering_object_ref TEXT NOT NULL,
                    last_terminal_sequence INTEGER NOT NULL CHECK(last_terminal_sequence>=0), last_instruction_id TEXT,
                    confirmed_net_amount TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision>0),
                    PRIMARY KEY(object_id),
                    UNIQUE(execution_tenant_id,wallet_account_id,currency,metering_object_ref),
                    FOREIGN KEY(wallet_account_id,currency) REFERENCES wallet_accounts(wallet_account_id,currency),
                    CHECK((last_terminal_sequence=0 AND last_instruction_id IS NULL)
                       OR (last_terminal_sequence>0 AND last_instruction_id IS NOT NULL))
                );
                CREATE TABLE IF NOT EXISTS wallet_pending_instructions (
                    caller_service_id TEXT NOT NULL, instruction_id TEXT NOT NULL, object_id TEXT NOT NULL,
                    execution_tenant_id TEXT NOT NULL, wallet_account_id TEXT NOT NULL, currency TEXT NOT NULL,
                    metering_object_ref TEXT NOT NULL, sequence INTEGER NOT NULL CHECK(sequence>0), previous_instruction_id TEXT,
                    target_amount TEXT NOT NULL, pricing_revision TEXT NOT NULL, reconciles_rejection_id TEXT,
                    fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64),
                    PRIMARY KEY(instruction_id), UNIQUE(object_id,sequence),
                    FOREIGN KEY(object_id) REFERENCES wallet_settlement_objects(object_id)
                );
                CREATE TABLE IF NOT EXISTS wallet_instruction_receipts (
                    caller_service_id TEXT NOT NULL, instruction_id TEXT NOT NULL, object_id TEXT NOT NULL,
                    execution_tenant_id TEXT NOT NULL, wallet_account_id TEXT NOT NULL, currency TEXT NOT NULL,
                    metering_object_ref TEXT NOT NULL, sequence INTEGER NOT NULL CHECK(sequence>0), previous_instruction_id TEXT,
                    target_amount TEXT NOT NULL, pricing_revision TEXT NOT NULL, reconciles_rejection_id TEXT,
                    fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64), receipt_id TEXT NOT NULL UNIQUE,
                    outcome TEXT NOT NULL CHECK(outcome IN('applied','rejected')), reason TEXT,
                    delta TEXT NOT NULL, confirmed_net_amount TEXT NOT NULL, balance_cents TEXT NOT NULL,
                    last_terminal_sequence INTEGER NOT NULL CHECK(last_terminal_sequence=sequence),
                    ledger_id TEXT UNIQUE, recorded_at TEXT NOT NULL,
                    PRIMARY KEY(instruction_id), UNIQUE(object_id,sequence),
                    FOREIGN KEY(object_id) REFERENCES wallet_settlement_objects(object_id),
                    CHECK((outcome='applied' AND reason IS NULL AND ledger_id IS NOT NULL)
                       OR (outcome='rejected' AND reason IS NOT NULL AND ledger_id IS NULL AND delta='0'))
                );
                CREATE TABLE IF NOT EXISTS wallet_ledger (
                    ledger_id TEXT PRIMARY KEY, caller_service_id TEXT NOT NULL, instruction_id TEXT NOT NULL,
                    wallet_account_id TEXT NOT NULL, currency TEXT NOT NULL, delta TEXT NOT NULL,
                    balance_before TEXT NOT NULL, balance_after TEXT NOT NULL, recorded_at TEXT NOT NULL,
                    UNIQUE(instruction_id),
                    FOREIGN KEY(instruction_id) REFERENCES wallet_instruction_receipts(instruction_id)
                );
                CREATE TABLE IF NOT EXISTS wallet_admissions (
                    caller_service_id TEXT NOT NULL, admission_id TEXT NOT NULL, execution_tenant_id TEXT NOT NULL,
                    wallet_account_id TEXT NOT NULL, currency TEXT NOT NULL, business_root_ref TEXT NOT NULL,
                    policy TEXT NOT NULL CHECK(policy='bounded_overdraft'), fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64),
                    admitted INTEGER NOT NULL CHECK(admitted IN(0,1)), balance_cents TEXT NOT NULL, overdraft_limit_cents TEXT NOT NULL,
                    recorded_at TEXT NOT NULL, released_at TEXT,
                    PRIMARY KEY(caller_service_id,admission_id),
                    FOREIGN KEY(wallet_account_id,currency) REFERENCES wallet_accounts(wallet_account_id,currency)
                );
            """)
            db.execute("BEGIN IMMEDIATE")
            for account in normalized:
                saved = db.execute("SELECT * FROM wallet_accounts WHERE wallet_account_id=? AND currency=?",
                                   (account["wallet_account_id"], account["currency"])).fetchone()
                if saved is not None:
                    if any(saved[key] != value for key, value in account.items()):
                        raise SettlementError("WALLET_ACCOUNT_CONFIGURATION_CONFLICT", 409)
                    continue
                db.execute("INSERT INTO wallet_accounts VALUES(?,?,?,?,?,?,?,?,?,?)",
                           (account["wallet_account_id"], account["currency"], account["execution_tenant_id"], account["name"],
                            account["initial_balance_cents"], account["overdraft_limit_cents"], account["initial_balance_cents"],
                            POLICY, 1, self._now()))

    @contextmanager
    def _connection(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("PRAGMA journal_mode=WAL")
            with db:
                yield db
        finally:
            db.close()

    def _now(self):
        now = self.clock()
        if not isinstance(now, datetime) or now.tzinfo is None or now.utcoffset() is None:
            raise SettlementError("WALLET_CLOCK_INVALID", 503)
        return now.astimezone(timezone.utc).isoformat(timespec="microseconds")

    @staticmethod
    def _account_config(account):
        expected = {"wallet_account_id", "execution_tenant_id", "currency", "initial_balance_cents", "overdraft_limit_cents", "name"}
        if not isinstance(account, dict) or set(account) != expected:
            raise SettlementError("WALLET_ACCOUNT_CONFIGURATION_INVALID")
        result = {"wallet_account_id": identity(account["wallet_account_id"]),
                  "execution_tenant_id": identity(account["execution_tenant_id"]),
                  "currency": currency(account["currency"]), "name": identity(account["name"]),
                  "initial_balance_cents": money_text(money(account["initial_balance_cents"], allow_negative=True)),
                  "overdraft_limit_cents": money_text(money(account["overdraft_limit_cents"]))}
        if money(result["initial_balance_cents"], allow_negative=True) < money(result["overdraft_limit_cents"]).copy_negate():
            raise SettlementError("INITIAL_BALANCE_EXCEEDS_OVERDRAFT")
        return result

    @staticmethod
    def _scope(principal, account_id):
        if not isinstance(principal, WalletPrincipal):
            raise SettlementError("WALLET_AUTHENTICATION_REQUIRED", 401)
        if account_id not in principal.allowed_account_ids:
            raise SettlementError("WALLET_ACCOUNT_FORBIDDEN", 403)

    def _account(self, db, principal, account_id, currency_code, tenant=None):
        self._scope(principal, account_id)
        row = db.execute("SELECT * FROM wallet_accounts WHERE wallet_account_id=? AND currency=?", (account_id, currency_code)).fetchone()
        if row is None or (tenant is not None and tenant != row["execution_tenant_id"]):
            raise SettlementError("WALLET_ACCOUNT_SCOPE_MISMATCH", 403)
        return row

    def account(self, principal, account_id, currency_code):
        with self._connection() as db:
            return dict(self._account(db, principal, identity(account_id), currency(currency_code)))

    def accounts(self, principal):
        if not isinstance(principal, WalletPrincipal):
            raise SettlementError("WALLET_AUTHENTICATION_REQUIRED", 401)
        with self._connection() as db:
            allowed = sorted(principal.allowed_account_ids)
            return [dict(row) for row in db.execute("SELECT * FROM wallet_accounts WHERE wallet_account_id IN ("
                    + ",".join("?" for _ in allowed) + ") ORDER BY wallet_account_id,currency", allowed)]

    @staticmethod
    def _instruction_fingerprint(instruction):
        # 调用身份属于审计和当前账户授权；不能把同一经济指令按不同调用服务拆成两次扣款。
        return fingerprint(instruction)

    @staticmethod
    def _object_scope(instruction):
        return tuple(instruction[key] for key in ("execution_tenant_id", "wallet_account_id", "currency", "metering_object_ref"))

    @staticmethod
    def _receipt(row):
        return {key: row[key] for key in (*INSTRUCTION_COLUMNS, "receipt_id", "outcome", "reason", "delta", "confirmed_net_amount",
                                        "balance_cents", "last_terminal_sequence", "ledger_id", "recorded_at")}

    def receipt(self, principal, instruction_id):
        instruction_id = identity(instruction_id)
        with self._connection() as db:
            row = db.execute("SELECT * FROM wallet_instruction_receipts WHERE instruction_id=?", (instruction_id,)).fetchone()
            if row is not None:
                self._account(db, principal, row["wallet_account_id"], row["currency"], row["execution_tenant_id"])
                return self._receipt(row)
            pending = db.execute("SELECT * FROM wallet_pending_instructions WHERE instruction_id=?", (instruction_id,)).fetchone()
            if pending is not None:
                account = self._account(db, principal, pending["wallet_account_id"], pending["currency"], pending["execution_tenant_id"])
                obj = db.execute("SELECT * FROM wallet_settlement_objects WHERE object_id=?", (pending["object_id"],)).fetchone()
                return self._waiting(dict(pending), obj, account)
            raise SettlementError("NOT_FOUND", 404)

    @staticmethod
    def _waiting(instruction, obj, account):
        return {key: instruction.get(key) for key in INSTRUCTION_COLUMNS} | {
            "outcome": "waiting_predecessor", "receipt_id": None, "reason": "WAITING_PREDECESSOR", "delta": None,
            "confirmed_net_amount": obj["confirmed_net_amount"], "last_terminal_sequence": obj["last_terminal_sequence"],
            "balance_cents": account["balance_cents"], "ledger_id": None, "recorded_at": None}

    def settle(self, principal, request, idempotency_key):
        instruction = normalize_instruction(request)
        if idempotency_key != instruction["instruction_id"]:
            raise SettlementError("INSTRUCTION_IDEMPOTENCY_KEY_MISMATCH")
        self._scope(principal, instruction["wallet_account_id"])
        digest = self._instruction_fingerprint(instruction)
        caller, instruction_id, object_id = principal.caller_service_id, instruction["instruction_id"], instruction["object_id"]
        committed = False
        with self._connection() as db, localcontext() as decimal_context:
            decimal_context.prec = 80
            db.execute("BEGIN IMMEDIATE")
            account = self._account(db, principal, instruction["wallet_account_id"], instruction["currency"], instruction["execution_tenant_id"])
            original = db.execute("SELECT * FROM wallet_instruction_receipts WHERE instruction_id=?", (instruction_id,)).fetchone()
            if original is not None:
                self._account(db, principal, original["wallet_account_id"], original["currency"], original["execution_tenant_id"])
                if original["fingerprint"] != digest:
                    raise SettlementError("INSTRUCTION_IDEMPOTENCY_CONFLICT", 409)
                return 200, self._receipt(original)
            pending = db.execute("SELECT * FROM wallet_pending_instructions WHERE instruction_id=?", (instruction_id,)).fetchone()
            if pending is not None:
                self._account(db, principal, pending["wallet_account_id"], pending["currency"], pending["execution_tenant_id"])
            if pending is not None and pending["fingerprint"] != digest:
                raise SettlementError("INSTRUCTION_IDEMPOTENCY_CONFLICT", 409)
            obj = db.execute("SELECT * FROM wallet_settlement_objects WHERE object_id=?", (object_id,)).fetchone()
            if obj is None:
                try:
                    db.execute("INSERT INTO wallet_settlement_objects VALUES(?,?,?,?,?,?,?,?,?,?)",
                               (caller, object_id, *self._object_scope(instruction), 0, None, "0", 1))
                except sqlite3.IntegrityError:
                    raise SettlementError("METERING_OBJECT_IDENTITY_CONFLICT", 409) from None
                obj = db.execute("SELECT * FROM wallet_settlement_objects WHERE object_id=?", (object_id,)).fetchone()
            if tuple(obj[key] for key in ("execution_tenant_id", "wallet_account_id", "currency", "metering_object_ref")) != self._object_scope(instruction):
                raise SettlementError("SETTLEMENT_OBJECT_SCOPE_CONFLICT", 409)
            for table in ("wallet_instruction_receipts", "wallet_pending_instructions"):
                occupied = db.execute("SELECT instruction_id FROM " + table + " WHERE object_id=? AND sequence=?",
                                      (object_id, instruction["sequence"])).fetchone()
                if occupied is not None and occupied["instruction_id"] != instruction_id:
                    raise SettlementError("SETTLEMENT_SEQUENCE_CONFLICT", 409)
            if instruction["sequence"] <= obj["last_terminal_sequence"]:
                raise SettlementError("SETTLEMENT_SEQUENCE_CONFLICT", 409)
            previous_id = instruction["previous_instruction_id"]
            if previous_id is not None:
                previous = db.execute("SELECT object_id,sequence FROM wallet_instruction_receipts WHERE instruction_id=?", (previous_id,)).fetchone()
                if previous is not None and (previous["object_id"] != object_id or previous["sequence"] != instruction["sequence"] - 1):
                    raise SettlementError("SETTLEMENT_PREDECESSOR_SCOPE_INVALID")
            rejection_id = instruction.get("reconciles_rejection_id")
            if rejection_id is not None:
                rejection = db.execute("SELECT object_id,outcome FROM wallet_instruction_receipts WHERE instruction_id=?", (rejection_id,)).fetchone()
                if rejection is None or rejection["object_id"] != object_id or rejection["outcome"] != "rejected":
                    raise SettlementError("SETTLEMENT_REJECTION_REFERENCE_INVALID")
            if pending is None:
                db.execute("INSERT INTO wallet_pending_instructions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                           (caller, *(instruction.get(key) for key in INSTRUCTION_COLUMNS), digest))
            if instruction["sequence"] != obj["last_terminal_sequence"] + 1 or previous_id != obj["last_instruction_id"]:
                result = self._waiting(instruction, obj, account)
                return 202, result
            now = self._now()
            target = money(instruction["target_amount"])
            confirmed = money(obj["confirmed_net_amount"])
            delta = target - confirmed
            balance = money(account["balance_cents"], allow_negative=True)
            limit = money(account["overdraft_limit_cents"])
            rejected = balance - delta < -limit
            next_balance = balance if rejected else balance - delta
            next_net = confirmed if rejected else target
            actual_delta = money_text(0 if rejected else delta)
            receipt_id, ledger_id = str(uuid.uuid4()), None if rejected else str(uuid.uuid4())
            db.execute("UPDATE wallet_settlement_objects SET last_terminal_sequence=?,last_instruction_id=?,confirmed_net_amount=?,revision=revision+1 "
                       "WHERE object_id=?", (instruction["sequence"], instruction_id, money_text(next_net), object_id))
            if not rejected:
                db.execute("UPDATE wallet_accounts SET balance_cents=?,revision=revision+1 WHERE wallet_account_id=? AND currency=?",
                           (money_text(next_balance), instruction["wallet_account_id"], instruction["currency"]))
            first_caller = pending["caller_service_id"] if pending is not None else caller
            db.execute("INSERT INTO wallet_instruction_receipts VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                       (first_caller, *(instruction.get(key) for key in INSTRUCTION_COLUMNS), digest, receipt_id,
                        "rejected" if rejected else "applied", "INSUFFICIENT_BALANCE" if rejected else None, actual_delta,
                        money_text(next_net), money_text(next_balance), instruction["sequence"], ledger_id, now))
            if not rejected:
                db.execute("INSERT INTO wallet_ledger VALUES(?,?,?,?,?,?,?,?,?)",
                           (ledger_id, first_caller, instruction_id, instruction["wallet_account_id"], instruction["currency"], actual_delta,
                            money_text(balance), money_text(next_balance), now))
            db.execute("DELETE FROM wallet_pending_instructions WHERE instruction_id=?", (instruction_id,))
            result = self._receipt(db.execute("SELECT * FROM wallet_instruction_receipts WHERE instruction_id=?", (instruction_id,)).fetchone())
            if self.fault is not None:
                self.fault("before_commit", result)
            committed = True
        # 注入发生在真实commit之后；异常不得改写已提交结果或伪造确定拒绝。
        if committed and self.fault is not None:
            self.fault("after_commit", result)
        LOG.debug("钱包结算完成 outcome=%s", result["outcome"])
        return 200, result

    @staticmethod
    def _normalize_admission(request):
        if not isinstance(request, dict) or set(request) != set(ADMISSION_COLUMNS):
            raise SettlementError("ADMISSION_ARGUMENTS_INVALID")
        normalized = {key: identity(request[key]) for key in ADMISSION_COLUMNS}
        normalized["currency"] = currency(request["currency"])
        if normalized["policy"] != POLICY:
            raise SettlementError("ADMISSION_POLICY_NOT_SUPPORTED")
        return normalized

    @staticmethod
    def _admission(row):
        return {key: row[key] for key in (*ADMISSION_COLUMNS, "balance_cents", "overdraft_limit_cents", "recorded_at", "released_at")} | {
            "admitted": bool(row["admitted"]), "reserved_amount": "0", "releases_fees": False}

    def admit(self, principal, request, idempotency_key):
        admission = self._normalize_admission(request)
        if idempotency_key != admission["admission_id"]:
            raise SettlementError("ADMISSION_IDEMPOTENCY_KEY_MISMATCH")
        digest = fingerprint({"caller_service_id": principal.caller_service_id, "admission": admission})
        with self._connection() as db:
            db.execute("BEGIN IMMEDIATE")
            account = self._account(db, principal, admission["wallet_account_id"], admission["currency"], admission["execution_tenant_id"])
            previous = db.execute("SELECT * FROM wallet_admissions WHERE caller_service_id=? AND admission_id=?",
                                  (principal.caller_service_id, admission["admission_id"])).fetchone()
            if previous is not None:
                if previous["fingerprint"] != digest:
                    raise SettlementError("ADMISSION_IDEMPOTENCY_CONFLICT", 409)
                return self._admission(previous)
            admitted = money(account["balance_cents"], allow_negative=True) > money(account["overdraft_limit_cents"]).copy_negate()
            db.execute("INSERT INTO wallet_admissions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                       (principal.caller_service_id, *(admission[key] for key in ADMISSION_COLUMNS), digest, int(admitted),
                        account["balance_cents"], account["overdraft_limit_cents"], self._now(), None))
            return self._admission(db.execute("SELECT * FROM wallet_admissions WHERE caller_service_id=? AND admission_id=?",
                                             (principal.caller_service_id, admission["admission_id"])).fetchone())

    def release(self, principal, request, idempotency_key):
        admission = self._normalize_admission(request)
        if idempotency_key != admission["admission_id"]:
            raise SettlementError("ADMISSION_IDEMPOTENCY_KEY_MISMATCH")
        digest = fingerprint({"caller_service_id": principal.caller_service_id, "admission": admission})
        with self._connection() as db:
            db.execute("BEGIN IMMEDIATE")
            self._account(db, principal, admission["wallet_account_id"], admission["currency"], admission["execution_tenant_id"])
            previous = db.execute("SELECT * FROM wallet_admissions WHERE caller_service_id=? AND admission_id=?",
                                  (principal.caller_service_id, admission["admission_id"])).fetchone()
            if previous is None:
                raise SettlementError("ADMISSION_NOT_FOUND", 404)
            if previous["fingerprint"] != digest:
                raise SettlementError("ADMISSION_IDEMPOTENCY_CONFLICT", 409)
            if previous["released_at"] is None:
                db.execute("UPDATE wallet_admissions SET released_at=? WHERE caller_service_id=? AND admission_id=?",
                           (self._now(), principal.caller_service_id, admission["admission_id"]))
            return self._admission(db.execute("SELECT * FROM wallet_admissions WHERE caller_service_id=? AND admission_id=?",
                                             (principal.caller_service_id, admission["admission_id"])).fetchone())


class WalletApplication:
    """HTTP之外的实际服务入口；认证映射来自受控配置，不由正文指定服务身份。"""

    def __init__(self, state_path, config, *, clock=None, fault=None):
        if not isinstance(config, dict) or set(config) != {"accounts", "callers"}:
            raise SettlementError("WALLET_CONFIG_INVALID")
        self.credentials = []
        if not isinstance(config["accounts"], list) or not config["accounts"] or not all(isinstance(account, dict) for account in config["accounts"]):
            raise SettlementError("WALLET_ACCOUNTS_REQUIRED")
        configured_ids = {account.get("wallet_account_id") for account in config["accounts"]}
        seen_services, seen_tokens = set(), set()
        if not isinstance(config["callers"], list) or not config["callers"]:
            raise SettlementError("WALLET_CALLERS_REQUIRED")
        for caller in config["callers"]:
            if not isinstance(caller, dict) or set(caller) != {"caller_service_id", "token_env", "allowed_account_ids"}:
                raise SettlementError("WALLET_CALLER_CONFIG_INVALID")
            accounts = caller["allowed_account_ids"]
            if not isinstance(accounts, list) or len(accounts) != len(set(accounts)):
                raise SettlementError("WALLET_CALLER_ACCOUNT_SCOPE_INVALID")
            principal = WalletPrincipal(caller["caller_service_id"], frozenset(accounts))
            if not principal.allowed_account_ids <= configured_ids or principal.caller_service_id in seen_services:
                raise SettlementError("WALLET_CALLER_ACCOUNT_SCOPE_INVALID")
            token = os.environ.get(identity(caller["token_env"]))
            if not token or len(token) > 256 or any(ord(value) <= 32 or ord(value) >= 127 for value in token) or token in seen_tokens:
                raise SettlementError("WALLET_CREDENTIAL_UNAVAILABLE")
            seen_services.add(principal.caller_service_id); seen_tokens.add(token)
            self.credentials.append(WalletCredential(principal, token))
        self.store = WalletStore(state_path, config["accounts"], clock=clock, fault=fault)

    def authenticate(self, authorization):
        if not isinstance(authorization, str) or not authorization.startswith("Bearer "):
            raise SettlementError("WALLET_AUTHENTICATION_REQUIRED", 401)
        token = authorization[7:]
        for credential in self.credentials:
            if secrets.compare_digest(hashlib.sha256(token.encode()).digest(), hashlib.sha256(credential.token.encode()).digest()):
                return credential.principal
        raise SettlementError("WALLET_AUTHENTICATION_REQUIRED", 401)

    def request(self, method, target, authorization, body=None, idempotency_key=None):
        principal = self.authenticate(authorization)
        parsed = urlparse(target)
        query = parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True)
        if any(len(values) != 1 for values in query.values()):
            raise SettlementError("DUPLICATE_WALLET_QUERY_FIELD")
        query = {key: values[0] for key, values in query.items()}
        if method == "GET" and parsed.path == "/api/v1/accounts" and not query:
            return 200, {"items": self.store.accounts(principal)}
        if method == "GET" and parsed.path == "/api/v1/accounts/detail" and set(query) == {"wallet_account_id", "currency"}:
            return 200, self.store.account(principal, query["wallet_account_id"], query["currency"])
        if method == "GET" and parsed.path == "/api/v1/settlements/detail" and set(query) == {"instruction_id"}:
            return 200, self.store.receipt(principal, query["instruction_id"])
        if method == "POST" and not query:
            if parsed.path == "/api/v1/settlements":
                return self.store.settle(principal, body, idempotency_key)
            if parsed.path == "/api/v1/admissions":
                return 200, self.store.admit(principal, body, idempotency_key)
            if parsed.path == "/api/v1/admissions/release":
                return 200, self.store.release(principal, body, idempotency_key)
        raise SettlementError("WALLET_ROUTE_OR_QUERY_INVALID")


def parse_json(raw):
    try:
        result = decode_json(raw)
        if not isinstance(result, dict):
            raise SettlementError("JSON_OBJECT_REQUIRED")
        return result
    except RecursionError:
        raise SettlementError("INVALID_JSON_BODY") from None


def create_server(port, state_path, config, *, clock=None, fault=None):
    application = WalletApplication(state_path, config, clock=clock, fault=fault)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            LOG.debug("钱包HTTP请求结束")

        def respond(self, status, code, data, trace_id):
            raw = json.dumps({"code": code, "message": "成功" if code == "OK" else code,
                              "data": data, "trace_id": trace_id}, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(raw)

        def handle_request(self, method):
            trace_id = str(uuid.uuid4())
            try:
                application.authenticate(self.headers.get("Authorization"))
                body = None
                if method == "POST":
                    size = int(self.headers.get("Content-Length", "0"))
                    if (not 0 < size <= MAX_BODY_BYTES or self.headers.get_content_type() != "application/json"
                            or self.headers.get("Transfer-Encoding") is not None):
                        raise SettlementError("BOUNDED_JSON_BODY_REQUIRED")
                    body = parse_json(self.rfile.read(size))
                status, data = application.request(method, self.path, self.headers.get("Authorization"), body,
                                                   self.headers.get("Idempotency-Key"))
                self.respond(status, "OK", data, trace_id)
            except SettlementError as failure:
                self.close_connection = True
                self.respond(failure.status, failure.code, None, trace_id)
            except (ValueError, TypeError, KeyError, OverflowError):
                self.close_connection = True
                self.respond(400, "INVALID_WALLET_REQUEST", None, trace_id)
            except (RuntimeError, OSError, sqlite3.Error):
                self.close_connection = True
                # 可能已commit；503不生成拒绝回执，不自动重试。
                LOG.warning("钱包请求结果无法交付 trace_id=%s", trace_id)
                self.respond(503, "WALLET_OUTCOME_UNAVAILABLE", None, trace_id)

        def do_GET(self):
            self.handle_request("GET")

        def do_POST(self):
            self.handle_request("POST")

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--state", type=Path, default=HERE / ".state" / "wallet.sqlite3")
    parser.add_argument("--port", type=int, default=8099)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    config = parse_json(args.config.read_bytes())
    server = create_server(args.port, args.state, config)
    print(json.dumps({"event": "wallet_ready", "port": server.server_port, "policy": POLICY, "simulated": True}), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
