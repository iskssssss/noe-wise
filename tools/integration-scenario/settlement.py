"""消费适配器拥有客户计价与可靠待发链；钱包仅通过明确 HTTP 客户端访问。"""
from __future__ import annotations

import json
import sqlite3
import time
import uuid
import math
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP, localcontext

from settlement_contract import (SettlementError, canonical, currency, fingerprint,
                                 instruction_identity, money, money_text,
                                 normalize_instruction, object_identity)


MEDIA_EVENT_TYPES = frozenset({"media.image", "media.video", "media.transcription", "media.speech"})
MEDIA_MEASUREMENT_FIELDS = ("image_count", "video_count", "characters", "duration_seconds", "tokens",
                            "prompt_tokens", "completion_tokens", "cached_input_tokens",
                            "cache_write_input_tokens", "reasoning_output_tokens")


@dataclass(frozen=True)
class PriceSnapshot:
    revision: str
    currency: str
    input_cents_per_thousand_tokens: str
    output_cents_per_thousand_tokens: str
    tool_cents_per_call: str

    @classmethod
    def parse(cls, value):
        revision = str(value.get("revision", "")).strip()
        if not revision:
            raise SettlementError("PRICING_REVISION_REQUIRED")
        return cls(revision, currency(value.get("currency")), *(money_text(money(value[name])) for name in (
            "input_cents_per_thousand_tokens", "output_cents_per_thousand_tokens", "tool_cents_per_call")))

    def as_dict(self):
        return dict(self.__dict__)


def _fault(callback, stage):
    if callback is not None:
        callback(stage)


def _fact(event):
    return {key: value for key, value in event.items() if key != "cursor"}


def _count(value):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise SettlementError("INVALID_USAGE_MEASUREMENT")
    return value


class SettlementAdapter:
    """事件、对象计价、连续指令和水位共享 BEGIN IMMEDIATE，外发严格在提交后。"""
    LEASE_SECONDS = 30

    def __init__(self, db: sqlite3.Connection, pricing: dict, accounts: dict, *, clock=time.time, lease_seconds=30):
        self.db = db
        if not callable(clock) or isinstance(lease_seconds, bool) or not isinstance(lease_seconds, (int, float)) or not math.isfinite(lease_seconds) or lease_seconds <= 0:
            raise SettlementError("INVALID_DISPATCH_LEASE")
        self.clock, self.lease_seconds = clock, lease_seconds
        self.price = PriceSnapshot.parse(pricing)
        self.accounts = {}
        for tenant, account in accounts.items():
            account_id = str(account.get("wallet_account_id", "")).strip()
            if not str(tenant).strip() or not account_id:
                raise SettlementError("EXPLICIT_WALLET_ACCOUNT_REQUIRED")
            self.accounts[str(tenant)] = {"wallet_account_id": account_id, "currency": currency(account.get("currency"))}
            if self.accounts[str(tenant)]["currency"] != self.price.currency:
                raise SettlementError("PRICING_ACCOUNT_CURRENCY_CONFLICT")
        if db.in_transaction:
            raise SettlementError("ADAPTER_REQUIRES_TRANSACTION_OWNERSHIP")
        db.executescript("""
          CREATE TABLE IF NOT EXISTS usage_consumer_cursors(stream_key TEXT PRIMARY KEY,cursor TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS consumed_usage_events(event_id TEXT PRIMARY KEY,stream_key TEXT NOT NULL,
            cursor TEXT,object_id TEXT,fingerprint TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS settlement_consumed_pages(stream_key TEXT NOT NULL,page_hash TEXT NOT NULL,
            next_cursor TEXT NOT NULL,PRIMARY KEY(stream_key,page_hash));
          CREATE TABLE IF NOT EXISTS settlement_objects(object_id TEXT PRIMARY KEY,execution_tenant_id TEXT NOT NULL,
            wallet_account_id TEXT NOT NULL,currency TEXT NOT NULL,metering_object_ref TEXT NOT NULL,
            event_type TEXT NOT NULL,pricing_revision TEXT NOT NULL,pricing_snapshot TEXT NOT NULL,
            pricing_status TEXT NOT NULL DEFAULT 'priced',measurement_unit TEXT,
            input_quantity INTEGER,input_revision INTEGER,input_priority INTEGER,
            output_quantity INTEGER,output_revision INTEGER,output_priority INTEGER,
            tool_quantity INTEGER,tool_revision INTEGER,tool_priority INTEGER,target_amount TEXT,measurement_status TEXT NOT NULL,
            next_sequence INTEGER NOT NULL DEFAULT 1,revision INTEGER NOT NULL DEFAULT 0,
            confirmed_net_amount TEXT NOT NULL DEFAULT '0',last_instruction_id TEXT,
            lease_token TEXT,lease_owner TEXT,lease_until REAL,
            UNIQUE(execution_tenant_id,wallet_account_id,currency,metering_object_ref));
          CREATE TABLE IF NOT EXISTS settlement_instructions(instruction_id TEXT PRIMARY KEY,
            object_id TEXT NOT NULL REFERENCES settlement_objects(object_id),sequence INTEGER NOT NULL,
            previous_instruction_id TEXT,target_amount TEXT NOT NULL,pricing_revision TEXT NOT NULL,
            fingerprint TEXT NOT NULL,payload TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',receipt TEXT,
            reconciles_rejection_id TEXT,UNIQUE(object_id,sequence));
          CREATE TABLE IF NOT EXISTS settlement_reconciliations(authorization_ref TEXT PRIMARY KEY,
            object_id TEXT NOT NULL,rejection_id TEXT NOT NULL,expected_revision INTEGER,reason TEXT,
            actor TEXT,idempotency_key TEXT,request_fingerprint TEXT NOT NULL,http_request_fingerprint TEXT,
            instruction_id TEXT NOT NULL,UNIQUE(actor,idempotency_key));
        """)

    def _begin(self):
        if self.db.in_transaction:
            raise SettlementError("ADAPTER_REQUIRES_TRANSACTION_OWNERSHIP")
        self.db.execute("BEGIN IMMEDIATE")

    def _one(self, sql, params=()):
        cursor = self.db.execute(sql, params)
        row = cursor.fetchone()
        return None if row is None else dict(zip((column[0] for column in cursor.description), row))

    def _all(self, sql, params=()):
        cursor = self.db.execute(sql, params)
        names = [column[0] for column in cursor.description]
        return [dict(zip(names, row)) for row in cursor]

    def consume_usage(self, stream_key, page, expected_cursor=None, fault=None):
        """跨视图去重只认事实正文；不在事务中访问钱包，不用供应商成本定客户价。"""
        if not isinstance(stream_key, str) or not stream_key or not isinstance(page.get("next_cursor"), str):
            raise SettlementError("USAGE_PAGE_CURSOR_REQUIRED")
        items = page.get("items")
        if not isinstance(items, list) or len({item.get("event_id") for item in items}) != len(items):
            raise SettlementError("DUPLICATE_OR_INVALID_USAGE_PAGE")
        page_hash = fingerprint({"items": [_fact(item) for item in items], "next_cursor": page["next_cursor"]})
        inserted = duplicates = instructions = 0
        self._begin()
        try:
            previous = self._one("SELECT cursor FROM usage_consumer_cursors WHERE stream_key=?", (stream_key,))
            current = None if previous is None else previous["cursor"]
            replay = self._one("SELECT next_cursor FROM settlement_consumed_pages WHERE stream_key=? AND page_hash=?",
                               (stream_key, page_hash))
            if replay:
                self._verify_replay(items)
                self.db.commit()
                return {"inserted": 0, "duplicates": len(items), "instructions": 0, "next_cursor": current}
            if current != expected_cursor:
                raise SettlementError("CHECKPOINT_CONFLICT", 409)
            for item in items:
                event_id, tenant = item.get("event_id"), item.get("tenant_id")
                if not isinstance(event_id, str) or not event_id or not isinstance(tenant, str) or not tenant:
                    raise SettlementError("PERSISTENT_USAGE_ID_REQUIRED")
                old = self._one("SELECT tenant,payload FROM usage WHERE event_id=?", (event_id,))
                digest = fingerprint(_fact(item))
                if old is not None and (old["tenant"] != tenant or fingerprint(_fact(json.loads(old["payload"]))) != digest):
                    raise SettlementError("IMMUTABLE_USAGE_EVENT_CONFLICT", 409)
                consumed = self._one("SELECT fingerprint FROM consumed_usage_events WHERE event_id=?", (event_id,))
                if consumed:
                    if consumed["fingerprint"] != digest:
                        raise SettlementError("IMMUTABLE_USAGE_EVENT_CONFLICT", 409)
                    duplicates += 1
                    continue
                if old is None:
                    self.db.execute("INSERT INTO usage(event_id,tenant,payload) VALUES(?,?,?)", (event_id, tenant, canonical(item)))
                object_id, created = self._consume_event(item)
                instructions += created
                self.db.execute("INSERT INTO consumed_usage_events VALUES(?,?,?,?,?)",
                                (event_id, stream_key, item.get("cursor"), object_id, digest))
                inserted += 1
            self.db.execute("INSERT INTO usage_consumer_cursors VALUES(?,?) ON CONFLICT(stream_key) DO UPDATE SET cursor=excluded.cursor",
                            (stream_key, page["next_cursor"]))
            self.db.execute("INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                            ("usage_cursor:" + stream_key, canonical(page["next_cursor"])))
            self.db.execute("INSERT INTO settlement_consumed_pages VALUES(?,?,?)", (stream_key, page_hash, page["next_cursor"]))
            _fault(fault, "before_commit")
            self.db.commit()
        except BaseException:
            self.db.rollback()
            raise
        _fault(fault, "after_commit_before_send")
        return {"inserted": inserted, "duplicates": duplicates, "instructions": instructions, "next_cursor": page["next_cursor"]}

    def _verify_replay(self, items):
        for item in items:
            row = self._one("SELECT fingerprint FROM consumed_usage_events WHERE event_id=?", (item.get("event_id"),))
            if row is None or row["fingerprint"] != fingerprint(_fact(item)):
                raise SettlementError("IMMUTABLE_USAGE_EVENT_CONFLICT", 409)

    def _consume_event(self, event):
        kind, event_type = event.get("kind"), event.get("event_type")
        if event_type == "request" or kind in {"confirmation", "adjustment", "reconciliation"}:
            return None, 0
        if event_type not in {"llm.chat", "llm.embedding", "llm.rerank", "tool.call", "knowledge.source", "workflow.external"} | MEDIA_EVENT_TYPES:
            raise SettlementError("CUSTOMER_PRICE_TYPE_REQUIRED")
        attempt = event.get("attempt_id")
        if not isinstance(attempt, str) or not attempt:
            raise SettlementError("ORIGINAL_ATTEMPT_REQUIRED")
        account = self.accounts.get(event["tenant_id"])
        if account is None:
            raise SettlementError("EXPLICIT_WALLET_ACCOUNT_REQUIRED")
        meter = "attempt:" + attempt
        object_id = object_identity(event["tenant_id"], account["wallet_account_id"], account["currency"], meter)
        obj = self._one("SELECT * FROM settlement_objects WHERE object_id=?", (object_id,))
        if obj is None:
            if account["currency"] != self.price.currency:
                raise SettlementError("PRICING_ACCOUNT_CURRENCY_CONFLICT")
            self.db.execute("""INSERT INTO settlement_objects(object_id,execution_tenant_id,wallet_account_id,currency,
              metering_object_ref,event_type,pricing_revision,pricing_snapshot,measurement_status,pricing_status,measurement_unit)
              VALUES(?,?,?,?,?,?,?,?, 'unknown',?,?)""", (object_id, event["tenant_id"], account["wallet_account_id"],
                            account["currency"], meter, event_type, self.price.revision, canonical(self.price.as_dict()),
                            "priced" if event_type in {"llm.chat", "llm.embedding", "tool.call"} else "unpriced", event.get("measurement_unit")))
            obj = self._one("SELECT * FROM settlement_objects WHERE object_id=?", (object_id,))
        if obj["event_type"] != event_type:
            raise SettlementError("ATTEMPT_METERING_TYPE_CONFLICT", 409)
        revision = _count(event.get("revision", 0))
        if revision is None:
            raise SettlementError("USAGE_REVISION_REQUIRED")
        media_not_sent = event_type in MEDIA_EVENT_TYPES and kind == "measured" and event.get("amount_status") == "not_applicable"
        if media_not_sent and (_count(event.get("quantity")) != 0 or event.get("measurement_unit") != "call"
                               or event.get("usage_source") != "UNKNOWN"
                               or any(event.get(field) is not None for field in MEDIA_MEASUREMENT_FIELDS)):
            raise SettlementError("INVALID_MEDIA_NOT_SENT_FACT")
        token_metered = event_type in {"llm.chat", "llm.embedding"}
        dimensions = (("input", event.get("prompt_tokens")), ("output", event.get("completion_tokens"))) if token_metered else (("tool", event.get("quantity")),)
        changed = False
        priority = 2 if kind == "measured" else 1
        for name, value in dimensions:
            value = _count(value)
            if value is None:
                continue
            if token_metered and event.get("usage_source") != "PROVIDER_REPORTED":
                continue
            prior_revision, prior_value = obj[name + "_revision"], obj[name + "_quantity"]
            prior_priority = obj[name + "_priority"]
            if prior_revision is not None and revision < prior_revision:
                continue
            if prior_revision == revision and prior_value is not None:
                if priority < prior_priority:
                    continue
                if priority == prior_priority and prior_value != value:
                    raise SettlementError("USAGE_REVISION_MEASUREMENT_CONFLICT", 409)
            if prior_revision != revision or prior_value != value or prior_priority != priority:
                obj[name + "_revision"], obj[name + "_quantity"] = revision, value
                obj[name + "_priority"] = priority
                changed = True
        if obj["pricing_status"] == "not_applicable":
            # 仍核验同修订冲突；旧报告与新消费视图不能重新打开明确未发送的对象。
            return object_id, 0
        if not changed:
            return object_id, 0
        if media_not_sent:
            obj["pricing_status"] = "not_applicable"
        # 媒体只保存原始用量与调用次数；没有独立媒体售价时，不借用工具单价计算应收。
        price = PriceSnapshot.parse(json.loads(obj["pricing_snapshot"])) if obj["pricing_status"] == "priced" else None
        needed = ["input"] if event_type == "llm.embedding" else ["input", "output"] if event_type == "llm.chat" else ["tool"]
        known = [name for name in needed if obj[name + "_quantity"] is not None]
        with localcontext() as context:
            context.prec = 90
            total = Decimal(0)
            if price is not None:
                for name in known:
                    rate = getattr(price, {"input": "input_cents_per_thousand_tokens", "output": "output_cents_per_thousand_tokens", "tool": "tool_cents_per_call"}[name])
                    total += Decimal(obj[name + "_quantity"]) * Decimal(rate) / (Decimal(1000) if name != "tool" else Decimal(1))
            target = money_text(total.quantize(Decimal("0.000000000001"), rounding=ROUND_HALF_UP)) if known and price is not None else None
        status = "not_sent" if media_not_sent else "known" if len(known) == len(needed) else "partial" if known else "unknown"
        previous_target = obj["target_amount"]
        self.db.execute("""UPDATE settlement_objects SET input_quantity=?,input_revision=?,input_priority=?,
          output_quantity=?,output_revision=?,output_priority=?,tool_quantity=?,tool_revision=?,tool_priority=?,
          target_amount=?,measurement_status=?,pricing_status=?,revision=revision+1 WHERE object_id=?""",
                        (obj["input_quantity"], obj["input_revision"], obj["input_priority"],
                         obj["output_quantity"], obj["output_revision"], obj["output_priority"],
                         obj["tool_quantity"], obj["tool_revision"], obj["tool_priority"], target, status, obj["pricing_status"], object_id))
        if target is not None and (previous_target is None or Decimal(target) != Decimal(previous_target)):
            self._instruction(object_id, target)
            return object_id, 1
        return object_id, 0

    def _instruction(self, object_id, target, rejection=None):
        obj = self._one("SELECT * FROM settlement_objects WHERE object_id=?", (object_id,))
        sequence = obj["next_sequence"]
        instruction = normalize_instruction({"instruction_id": instruction_identity(object_id, sequence),
            "object_id": object_id, "execution_tenant_id": obj["execution_tenant_id"],
            "wallet_account_id": obj["wallet_account_id"], "currency": obj["currency"],
            "metering_object_ref": obj["metering_object_ref"], "sequence": sequence,
            "previous_instruction_id": obj["last_instruction_id"], "target_amount": target,
            "pricing_revision": obj["pricing_revision"], **({"reconciles_rejection_id": rejection} if rejection else {})})
        self.db.execute("""INSERT INTO settlement_instructions(instruction_id,object_id,sequence,previous_instruction_id,
          target_amount,pricing_revision,fingerprint,payload,reconciles_rejection_id) VALUES(?,?,?,?,?,?,?,?,?)""",
                        (instruction["instruction_id"], object_id, sequence, obj["last_instruction_id"], target,
                         obj["pricing_revision"], fingerprint(instruction), canonical(instruction), rejection))
        self.db.execute("UPDATE settlement_objects SET next_sequence=next_sequence+1,last_instruction_id=? WHERE object_id=?",
                        (instruction["instruction_id"], object_id))
        return instruction

    def dispatch(self, wallet_client, worker_id="adapter", max_instructions=100, fault=None):
        """一次有界派发；对象租约只恢复原指令，未知前序不能被后序越过。"""
        if not isinstance(max_instructions, int) or isinstance(max_instructions, bool) or max_instructions < 1 or not worker_id:
            raise SettlementError("INVALID_DISPATCH_WINDOW")
        result = dict(attempted=0, applied=0, rejected=0, unknown=0, waiting=0)
        blocked = set()
        for _ in range(max_instructions):
            claim = self._claim(worker_id, blocked)
            if claim is None:
                break
            result["attempted"] += 1
            instruction, token, previous_status = claim
            _fault(fault, "after_commit_before_send")
            try:
                receipt = wallet_client.lookup(instruction["instruction_id"]) if previous_status in {"unknown", "sending"} else None
                if receipt is None or receipt.get("outcome") not in {"applied", "rejected"}:
                    receipt = wallet_client.settle(instruction)
            except Exception:
                self._finish(instruction, token, None)
                blocked.add(instruction["object_id"])
                result["unknown"] += 1
                continue
            _fault(fault, "after_http_before_ack")
            outcome = self._finish(instruction, token, receipt)
            result[{"applied": "applied", "rejected": "rejected", "waiting_predecessor": "waiting"}.get(outcome, "unknown")] += 1
            if outcome not in {"applied", "rejected"}:
                blocked.add(instruction["object_id"])
        return result

    def _claim(self, worker_id, blocked):
        self._begin()
        try:
            exclusions = " AND i.object_id NOT IN (" + ",".join("?" for _ in blocked) + ")" if blocked else ""
            row = self._one("""SELECT i.* FROM settlement_instructions i JOIN settlement_objects o ON o.object_id=i.object_id
              WHERE i.status NOT IN ('applied','rejected') AND (o.lease_until IS NULL OR o.lease_until<=?)
              AND NOT EXISTS(SELECT 1 FROM settlement_instructions p WHERE p.object_id=i.object_id
                AND p.sequence<i.sequence AND p.status NOT IN ('applied','rejected'))""" + exclusions + " ORDER BY i.object_id,i.sequence LIMIT 1",
                            (self.clock(), *sorted(blocked)))
            if row is None:
                self.db.commit()
                return None
            token = uuid.uuid4().hex
            self.db.execute("UPDATE settlement_objects SET lease_owner=?,lease_token=?,lease_until=? WHERE object_id=?",
                            (worker_id, token, self.clock() + self.lease_seconds, row["object_id"]))
            self.db.execute("UPDATE settlement_instructions SET status='sending' WHERE instruction_id=?", (row["instruction_id"],))
            self.db.commit()
            return json.loads(row["payload"]), token, row["status"]
        except BaseException:
            self.db.rollback()
            raise

    def _finish(self, instruction, token, receipt):
        self._begin()
        try:
            obj = self._one("SELECT * FROM settlement_objects WHERE object_id=?", (instruction["object_id"],))
            if obj["lease_token"] != token:
                raise SettlementError("DISPATCH_LEASE_LOST", 409)
            outcome = None if receipt is None else receipt.get("outcome")
            if outcome in {"applied", "rejected"}:
                if (receipt.get("instruction_id") != instruction["instruction_id"] or receipt.get("object_id") != instruction["object_id"]
                    or not receipt.get("receipt_id") or receipt.get("sequence") != instruction["sequence"]):
                    raise SettlementError("WALLET_RECEIPT_IDENTITY_CONFLICT", 409)
                net = money_text(money(receipt.get("confirmed_net_amount")))
                self.db.execute("UPDATE settlement_instructions SET status=?,receipt=? WHERE instruction_id=?",
                                (outcome, canonical(receipt), instruction["instruction_id"]))
                self.db.execute("UPDATE settlement_objects SET confirmed_net_amount=? WHERE object_id=?", (net, obj["object_id"]))
            else:
                self.db.execute("UPDATE settlement_instructions SET status='unknown',receipt=? WHERE instruction_id=?",
                                (None if receipt is None else canonical(receipt), instruction["instruction_id"]))
            self.db.execute("UPDATE settlement_objects SET lease_token=NULL,lease_owner=NULL,lease_until=NULL WHERE object_id=?", (obj["object_id"],))
            self.db.commit()
            return outcome
        except BaseException:
            self.db.rollback()
            raise

    def reconcile_rejection(self, object_id, rejection_id, authorization_ref, expected_revision=None, reason=None,
                            reconciliation_request=None):
        """显式授权的重结算继承最新目标；原拒绝回执不变，网络重试不能换身份。"""
        if not isinstance(authorization_ref, str) or not authorization_ref.strip():
            raise SettlementError("EXPLICIT_RECONCILIATION_AUTHORIZATION_REQUIRED", 403)
        actor = idempotency_key = http_fingerprint = None
        if reconciliation_request is not None:
            if not isinstance(reconciliation_request, dict):
                raise SettlementError("RECONCILIATION_REQUEST_REQUIRED")
            actor, idempotency_key = reconciliation_request.get("actor"), reconciliation_request.get("idempotency_key")
            http_fingerprint = reconciliation_request.get("request_fingerprint")
            if any(not isinstance(value, str) or not value.strip() for value in (actor, idempotency_key, http_fingerprint)):
                raise SettlementError("RECONCILIATION_REQUEST_REQUIRED")
            supplied_reason = reconciliation_request.get("reason")
            if reason is not None and reason != supplied_reason:
                raise SettlementError("RECONCILIATION_REASON_CONFLICT", 409)
            reason = supplied_reason
            if expected_revision is None or not isinstance(reason, str) or not reason.strip():
                raise SettlementError("RECONCILIATION_REVISION_AND_REASON_REQUIRED")
        if expected_revision is not None and (isinstance(expected_revision, bool)
                or not isinstance(expected_revision, int) or expected_revision < 0):
            raise SettlementError("EXPECTED_OBJECT_REVISION_REQUIRED")
        if reason is not None and (not isinstance(reason, str) or not reason.strip()):
            raise SettlementError("RECONCILIATION_REASON_REQUIRED")
        request_digest = fingerprint({"object_id": object_id, "rejected_instruction_id": rejection_id,
            "expected_revision": expected_revision, "reason": reason, "actor": actor,
            "idempotency_key": idempotency_key, "http_request_fingerprint": http_fingerprint})
        self._begin()
        try:
            old = self._one("""SELECT * FROM settlement_reconciliations WHERE authorization_ref=?
              OR (actor=? AND idempotency_key=?)""", (authorization_ref, actor, idempotency_key))
            if old:
                if old["request_fingerprint"] != request_digest or old["authorization_ref"] != authorization_ref:
                    raise SettlementError("RECONCILIATION_IDEMPOTENCY_CONFLICT", 409)
                payload = self._one("SELECT payload FROM settlement_instructions WHERE instruction_id=?", (old["instruction_id"],))
                self.db.commit()
                return json.loads(payload["payload"])
            obj = self._one("SELECT * FROM settlement_objects WHERE object_id=?", (object_id,))
            rejected = self._one("SELECT * FROM settlement_instructions WHERE object_id=? AND instruction_id=?", (object_id, rejection_id))
            if obj is not None and expected_revision is not None and obj["revision"] != expected_revision:
                raise SettlementError("SETTLEMENT_OBJECT_REVISION_CONFLICT", 409)
            if obj is None or rejected is None or rejected["status"] != "rejected" or obj["last_instruction_id"] != rejection_id:
                raise SettlementError("CURRENT_REJECTED_INSTRUCTION_REQUIRED", 409)
            if obj["target_amount"] is None or Decimal(obj["target_amount"]) == Decimal(obj["confirmed_net_amount"]):
                raise SettlementError("NO_UNSETTLED_TARGET", 409)
            instruction = self._instruction(object_id, obj["target_amount"], rejection_id)
            self.db.execute("UPDATE settlement_objects SET revision=revision+1 WHERE object_id=?", (object_id,))
            self.db.execute("""INSERT INTO settlement_reconciliations(authorization_ref,object_id,rejection_id,
              expected_revision,reason,actor,idempotency_key,request_fingerprint,http_request_fingerprint,instruction_id)
              VALUES(?,?,?,?,?,?,?,?,?,?)""", (authorization_ref, object_id, rejection_id,
                obj["revision"] if expected_revision is None else expected_revision, reason, actor, idempotency_key,
                request_digest, http_fingerprint, instruction["instruction_id"]))
            self.db.commit()
            return instruction
        except BaseException:
            self.db.rollback()
            raise

    def totals(self):
        result = {}
        currencies = {}
        with localcontext() as context:
            context.prec = 90
            for row in self._all("SELECT execution_tenant_id,currency,target_amount FROM settlement_objects WHERE target_amount IS NOT NULL"):
                tenant = row["execution_tenant_id"]
                if tenant in currencies and currencies[tenant] != row["currency"]:
                    raise SettlementError("MIXED_CURRENCY_TOTAL_REQUIRES_DIMENSION")
                currencies[tenant] = row["currency"]
                result[tenant] = money_text(Decimal(result.get(tenant, "0")) + Decimal(row["target_amount"]))
        return result

    def snapshot(self):
        objects = self._all("SELECT * FROM settlement_objects ORDER BY object_id")
        for obj in objects:
            obj["pricing_snapshot"] = json.loads(obj["pricing_snapshot"])
            with localcontext() as context:
                context.prec = 90
                obj["settlement_gap"] = None if obj["target_amount"] is None else money_text(Decimal(obj["target_amount"]) - Decimal(obj["confirmed_net_amount"]))
        instructions = self._all("SELECT * FROM settlement_instructions ORDER BY object_id,sequence")
        for instruction in instructions:
            instruction["payload"] = json.loads(instruction["payload"])
            instruction["receipt"] = None if instruction["receipt"] is None else json.loads(instruction["receipt"])
        return {"objects": objects, "instructions": instructions,
                "consumed_events": self.db.execute("SELECT count(*) FROM consumed_usage_events").fetchone()[0],
                "cursors": {row["stream_key"]: row["cursor"] for row in self._all("SELECT * FROM usage_consumer_cursors")}}
