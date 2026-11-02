"""接入端持久状态与唯一结算适配器；钱包余额只归独立钱包服务。"""
from __future__ import annotations

import json
import hashlib
import logging
import sqlite3
from pathlib import Path

LOG = logging.getLogger(__name__)
# Open Platform status 原样返回 Runtime RunStatus 的小写名；挂起与恢复不是终态。
TERMINAL = frozenset({"completed", "terminated", "failed", "cancelled"})


class State:
    def __init__(self, path: Path):
        """创建被 gitignore 的本地状态，游标、账本和消息投影共享事务。"""
        LOG.debug("打开接入持久状态: phase=entry")
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.db = sqlite3.connect(path, timeout=30)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS kv(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS usage(event_id TEXT PRIMARY KEY, tenant TEXT NOT NULL, payload TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS message(session TEXT NOT NULL, id TEXT NOT NULL, version INTEGER NOT NULL,
                payload TEXT NOT NULL, PRIMARY KEY(session,id));
            CREATE TABLE IF NOT EXISTS event(session TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL,
                PRIMARY KEY(session,id));
            CREATE TABLE IF NOT EXISTS business(id TEXT PRIMARY KEY, payload TEXT NOT NULL);
        """)
        self.billing = None
        LOG.debug("打开接入持久状态: phase=exit")

    def get(self, key: str, default=None):
        """读取消费 checkpoint 或一次签发结果，不回显敏感载荷。"""
        LOG.debug("读取接入状态: phase=entry")
        row = self.db.execute("SELECT value FROM kv WHERE key=?", (key,)).fetchone()
        LOG.debug("读取接入状态: phase=exit found=%s", bool(row))
        return json.loads(row[0]) if row else default

    def put(self, key: str, value):
        """提交一个接入配置结果；多事实原子写入由调用方法拥有事务。"""
        LOG.debug("写入接入状态: phase=entry")
        with self.db:
            self._put(key, value)
        LOG.debug("写入接入状态: phase=exit")

    def _put(self, key: str, value):
        """在调用方事务中保存 checkpoint；不另行 commit。"""
        LOG.debug("写入接入事务状态: phase=entry")
        self.db.execute("INSERT INTO kv VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                        (key, json.dumps(value, ensure_ascii=False)))
        LOG.debug("写入接入事务状态: phase=exit")

    def configure_billing(self, pricing: dict, accounts: dict):
        """显式安装唯一计价和结算outbox；未配置的普通业务State不获得计费能力。"""
        from settlement import SettlementAdapter
        self.billing = SettlementAdapter(self.db, pricing, accounts)
        return self.billing

    def consume_usage(self, stream_key: str, page: dict, expected_cursor=None):
        """原事件、checkpoint、应收和结算指令只由唯一Adapter拥有原子写权。"""
        if self.billing is None:
            raise ValueError("BILLING_CONFIGURATION_REQUIRED")
        return self.billing.consume_usage(stream_key, page, expected_cursor=expected_cursor)

    def observe_usage(self, stream_key: str, page: dict, expected_cursor=None):
        """外部Demo已拥有计价时，只保存原事件及只读进度，不建立第二份应收或扣款。"""
        LOG.debug("消费平台用量: phase=entry count=%s", len(page["items"]))
        next_cursor = page["next_cursor"]
        if not isinstance(next_cursor, str) or not next_cursor:
            raise ValueError("OPAQUE_USAGE_CURSOR_REQUIRED")
        receipt_key = "usage_page:" + stream_key + ":" + hashlib.sha256(
            json.dumps(page, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        with self.db:
            last = self.get("usage_cursor:" + stream_key)
            # 原页重放只重读消费结果；新页必须承接请求时的本地游标，不能用字符串比较游标。
            if self.get(receipt_key):
                return
            if expected_cursor != last:
                raise ValueError("USAGE_CONSUMER_CHECKPOINT_CONFLICT")
            seen = set()
            for item in page["items"]:
                if not item.get("event_id") or not item.get("tenant_id") or item["event_id"] in seen:
                    raise ValueError("UNIQUE_EVENT_AND_TENANT_REQUIRED")
                seen.add(item["event_id"])
                stored = self.db.execute("SELECT payload FROM usage WHERE event_id=?", (item["event_id"],)).fetchone()
                # cursor 属于本次授权/筛选流，不是稳定事件正文；跨流重读只能按 event_id 去重。
                serialized = json.dumps({key: value for key, value in item.items() if key != "cursor"},
                                        sort_keys=True, ensure_ascii=False)
                if stored:
                    if stored[0] != serialized:
                        raise ValueError("IMMUTABLE_USAGE_EVENT_CHANGED")
                    continue
                self.db.execute("INSERT INTO usage VALUES(?,?,?)",
                                (item["event_id"], str(item["tenant_id"]), serialized))
            self._put("usage_cursor:" + stream_key, next_cursor)
            self._put(receipt_key, True)
        LOG.debug("消费平台用量: phase=exit")

    def consume_event(self, session: str, event: dict):
        """累计消息按 version 替换；Run 完成看 status，waiting_hitl 保持挂起。"""
        LOG.debug("消费会话事件: phase=entry event=%s", event["event"])
        data, name = event["data"], event["event"]
        event_id = event.get("event_id") or data.get("event_id")
        if not event_id or data.get("session_id") != session:
            raise ValueError("PERSISTENT_SESSION_EVENT_REQUIRED")
        with self.db:
            inserted = self.db.execute("INSERT OR IGNORE INTO event VALUES(?,?,?)",
                                       (session, event_id, json.dumps(event, ensure_ascii=False))).rowcount
            if not inserted:
                return
            if name.startswith("message.") and data.get("message_id"):
                row = self.db.execute("SELECT version FROM message WHERE session=? AND id=?",
                                      (session, data["message_id"])).fetchone()
                version = int(data["message_version"])
                if row is None or version > row[0]:
                    self.db.execute("INSERT INTO message VALUES(?,?,?,?) ON CONFLICT(session,id) DO UPDATE "
                                    "SET version=excluded.version,payload=excluded.payload",
                                    (session, data["message_id"], version, json.dumps(data, ensure_ascii=False)))
            run_id = data.get("run_id") or data.get("execution_id")
            if run_id and name.startswith("execution."):
                self._put("run:" + session + ":" + run_id, data)
            sequence = data.get("session_sequence")
            current = self.get("event_sequence:" + session, -1)
            if sequence is not None and int(sequence) > int(current):
                self._put("event_sequence:" + session, int(sequence))
                self._put("event_cursor:" + session, event_id)
        LOG.debug("消费会话事件: phase=exit")

    def messages(self, session: str) -> list[dict]:
        """读取合并后的消息快照，业务发送只选终态 Run 的 final。"""
        LOG.debug("读取合并消息: phase=entry")
        result = [json.loads(row[0]) for row in self.db.execute("SELECT payload FROM message WHERE session=?", (session,))]
        LOG.debug("读取合并消息: phase=exit count=%s", len(result))
        return result

    def events(self, session: str) -> list[dict]:
        """读取已消费公开事件，供模拟业务WebSocket展示实际Runtime消息快照。"""
        LOG.debug("读取业务事件投影: phase=entry")
        result = [json.loads(row[0]) for row in self.db.execute("SELECT payload FROM event WHERE session=?", (session,))]
        result.sort(key=lambda event: int(event["data"].get("session_sequence", 0)))
        LOG.debug("读取业务事件投影: phase=exit count=%s", len(result))
        return result

    def totals(self) -> dict:
        """客户应收来自唯一Adapter；普通业务State没有配置时返回空展示。"""
        return self.billing.totals() if self.billing is not None else {}

    def business_result(self, key: str, request: dict, produce):
        """串行拥有幂等业务写权；同键并发读旧结果，同键异参冲突，暂停结果不占发送身份。"""
        LOG.debug("执行模拟业务事务: phase=entry")
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            previous = self.get(key)
            if previous:
                if previous["request"] != request:
                    raise ValueError("BUSINESS_IDEMPOTENCY_CONFLICT")
                result = previous["result"]
            else:
                result, persist = produce()
                if persist:
                    self._put(key, {"request": request, "result": result})
        LOG.debug("执行模拟业务事务: phase=exit")
        return result

    def websocket_snapshot(self, request: dict) -> dict:
        """模拟业务WS按机构、房间、Agent和Session隔离累计消息，旧版本只回当前快照。"""
        LOG.debug("合并业务WebSocket快照: phase=entry")
        event = request["event"]
        data = event["data"]
        if (not event["event"].startswith("message.") or not data.get("message_id")
                or data.get("session_id") != request["session_id"]):
            raise ValueError("PUBLIC_SESSION_MESSAGE_REQUIRED")
        key = "ws:" + ":".join(request[field] for field in ("tenant_ref", "room_ref", "agent_ref", "session_id")) + ":" + data["message_id"]
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            previous = self.get(key)
            if not previous or int(data["message_version"]) > int(previous["message_version"]):
                previous = data
                self._put(key, data)
        LOG.debug("合并业务WebSocket快照: phase=exit")
        return {"type": "assistant_snapshot", "message": previous}

    def close(self):
        """关闭业务端数据库连接，checkpoint 已在各事实事务中落盘。"""
        LOG.debug("关闭接入状态: phase=entry")
        self.db.close()
        LOG.debug("关闭接入状态: phase=exit")
