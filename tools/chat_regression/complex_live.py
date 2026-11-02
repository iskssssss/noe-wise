"""本轮复杂真实验收的单 paid lane；复用现有客户端、证据采集和预算原语。"""
from __future__ import annotations

import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import uuid

from behavior_capture import flatten
from behavior_runner import ExecutionBudget
from client import query
from runner import chat, collect_trace, save

# 仅复用锁和 Provider 累计用量读取，不借用历史验收的固定账本或配额。
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "full_project_testing"))
from budget_ledger import ledger_lock
from live_behavior_usage import natural

ACCEPTANCE_PARENT = Path(__file__).resolve().parents[2] / ".runtime-logs"
CAPS = {"max_attempts": 96, "max_tokens": 1_500_000, "max_seconds": 3600}
DEFAULTS = {**CAPS, "reserve_attempts": 12, "reserve_tokens": 180_000, "turn_timeout": 300, "settle_seconds": 20}
TERMINALS = {"completed", "failed", "cancelled", "terminated", "loop_terminated", "waiting_hitl"}


class ComplexLiveStopped(RuntimeError):
    """仅暴露固定安全代码；原请求或用量不确定时禁止新输入。"""


def _identity(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,95}", value):
        raise ValueError("complex_case_identity_invalid")
    return value


class ComplexLiveRun:
    """本批唯一根绑定持久 intent；既有输入只回读原 Run，永不重复 POST。"""

    def __init__(self, api, reader, provider, root, budget):
        self.api, self.reader, self.provider = api, reader, provider
        self.root = Path(root).resolve()
        if self.root.parent != ACCEPTANCE_PARENT.resolve():
            raise ValueError("complex_isolated_acceptance_root_required")
        if not isinstance(budget, dict) or set(budget) - set(DEFAULTS):
            raise ValueError("complex_budget_configuration_invalid")
        self.config = {**DEFAULTS, **budget}
        for key, value in self.config.items():
            if type(value) is not int or value <= 0 or (key in CAPS and value > CAPS[key]):
                raise ValueError("complex_budget_configuration_invalid")
        if self.config["reserve_attempts"] > self.config["max_attempts"] or self.config["reserve_tokens"] > self.config["max_tokens"]:
            raise ValueError("complex_reservation_exceeds_batch")
        self.journal = self.root / "complex-live.json"
        self.state = None
        self.input_budget = None

    def _load(self):
        if not self.journal.exists():
            raise ComplexLiveStopped("complex_begin_required")
        state = json.loads(self.journal.read_text(encoding="utf-8"))
        if state.get("root") != str(self.root) or state.get("budget") != self.config or state.get("schema_version") != 1:
            raise ComplexLiveStopped("complex_frozen_budget_drift")
        self.state = state
        self.provider.boundary = state["boundary"]
        self.input_budget = ExecutionBudget(self.config["max_tokens"], self.config["max_attempts"], self.config["max_seconds"])
        self.input_budget.started -= max(0, time.time() - state["started_epoch"])
        self.input_budget.turns = len(state["intents"])
        return state

    def _persist(self):
        save(self.journal, self.state)

    def begin(self):
        """资源准备完成后显式冻结边界和派发窗口；重启复用原边界，不重新计时。"""
        with ledger_lock(self.journal):
            if self.journal.exists():
                self._load()
            else:
                self.reader.preflight()
                boundary = self.provider.preflight()
                self.state = {"schema_version": 1, "root": str(self.root), "budget": self.config,
                              "started_epoch": time.time(), "boundary": boundary, "intents": [], "stop_reason": None}
                self._persist()
                self._load()
            receipt = {"operation": "begin", "root": str(self.root), "budget": self.config,
                       "started_epoch": self.state["started_epoch"], "boundary": self.state["boundary"],
                       "stop_reason": self.state["stop_reason"]}
            save(self.root / "begin.json", receipt)
            return receipt

    def _snapshot(self):
        """ProviderReader覆盖边界后全机构尝试，包含子委派和摘要；不以公开usage替代。"""
        try:
            usage = self.provider.snapshot(sorted({item["session_id"] for item in self.state["intents"]}))
            attempts = usage["attempts"]
            if not isinstance(attempts, list) or type(usage.get("usage_known")) is not bool:
                raise ValueError("invalid_provider_snapshot")
            count, tokens = len(attempts), natural(usage["tokens"])
        except Exception:
            self.state["stop_reason"] = self.state["stop_reason"] or "provider_usage_unavailable"
            self._persist()
            save(self.root / "snapshot-error.json", {"operation": "snapshot", "status": "UNKNOWN", "reason": "provider_usage_unavailable"})
            raise ComplexLiveStopped("provider_usage_unavailable") from None
        receipt = {"operation": "snapshot", "observed_epoch": time.time(), "attempts": count, "tokens": tokens,
                   "usage_known": usage["usage_known"], "provider": usage}
        save(self.root / "provider-usage.json", receipt)
        self.state["latest_usage"] = {key: receipt[key] for key in ("attempts", "tokens", "usage_known", "observed_epoch")}
        if count > self.config["max_attempts"] or tokens > self.config["max_tokens"]:
            self.state["stop_reason"] = self.state["stop_reason"] or "observed_budget_exceeded"
        self._persist()
        return receipt

    def snapshot(self):
        with ledger_lock(self.journal):
            self._load()
            return self._snapshot()

    def _check(self, reserve_attempts, reserve_tokens):
        usage = self._snapshot()
        reason = self.state["stop_reason"]
        if not usage["usage_known"]:
            reason = reason or "provider_usage_unknown_or_in_flight"
        if any(item["status"] not in TERMINALS for item in self.state["intents"]):
            reason = reason or "previous_input_not_settled"
        if self.input_budget.remaining() <= 0:
            reason = reason or "dispatch_window_elapsed"
        if usage["attempts"] + reserve_attempts > self.config["max_attempts"] or usage["tokens"] + reserve_tokens > self.config["max_tokens"]:
            reason = reason or "insufficient_conservative_reservation"
        receipt = {"operation": "check", "allowed": reason is None, "reason": reason,
                   "reserve_attempts": reserve_attempts, "reserve_tokens": reserve_tokens,
                   "remaining_seconds": max(0, self.input_budget.remaining()), "usage": self.state["latest_usage"]}
        save(self.root / "check.json", receipt)
        if reason:
            self.state["stop_reason"] = reason
            self._persist()
            raise ComplexLiveStopped(reason)
        return receipt

    def check(self, *, reserve_attempts=None, reserve_tokens=None):
        with ledger_lock(self.journal):
            self._load()
            return self._check(*self._reservation(reserve_attempts, reserve_tokens))

    def _reservation(self, attempts, tokens):
        values = (self.config["reserve_attempts"] if attempts is None else attempts,
                  self.config["reserve_tokens"] if tokens is None else tokens)
        if any(type(value) is not int or value <= 0 for value in values):
            raise ValueError("complex_positive_reservation_required")
        return values

    def send_turn(self, session_id, agent_id, end_user_id, text, attachment_ids=None, *, turn,
                  hitl_response=None, case_id, reserve_attempts=None, reserve_tokens=None):
        """先落 intent 再发送；同 case/turn 重跑只观察，HITL 新输入绑定原 Run。"""
        case_id = _identity(case_id)
        if type(turn) is not int or turn < 0 or not all(isinstance(v, str) and v for v in (session_id, agent_id, end_user_id, text)):
            raise ValueError("complex_turn_input_invalid")
        body = {"session_id": session_id, "agent_id": agent_id, "content": [{"type": "text", "text": text}],
                "context": {"end_user_id": end_user_id}}
        if attachment_ids is not None:
            if not isinstance(attachment_ids, list) or any(not isinstance(v, str) or not v for v in attachment_ids):
                raise ValueError("complex_attachment_ids_invalid")
            body["context"]["attachment_ids"] = attachment_ids
        if hitl_response is not None:
            if not isinstance(hitl_response, dict):
                raise ValueError("complex_hitl_identity_required")
            keys = (("execution_id", "run_revision", "checkpoint_revision", "workflow_execution_id", "suspend_ref",
                     "expected_revision", "resume_action_key", "action", "response")
                    if hitl_response.get("workflow_execution_id") else
                    ("execution_id", "pending_ref", "checkpoint_revision", "response"))
            if any(hitl_response.get(key) is None for key in keys):
                raise ValueError("complex_hitl_identity_required")
            body["hitl_response"] = {key: hitl_response[key] for key in keys}
        fingerprint = hashlib.sha256(json.dumps(body, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        folder = self.root / "cases" / case_id / ("turn-%02d" % turn)
        with ledger_lock(self.journal):
            self._load()
            item = next((v for v in self.state["intents"] if v["case_id"] == case_id and v["turn"] == turn), None)
            resume = item is not None
            if resume:
                if item["request_sha256"] != fingerprint:
                    raise ComplexLiveStopped("existing_intent_content_conflict")
                body["client_message_id"] = item["message_id"]
            else:
                reserves = self._reservation(reserve_attempts, reserve_tokens)
                self._check(*reserves)
                self.input_budget.before_turn()
                body["client_message_id"] = str(uuid.uuid4())
                item = {"case_id": case_id, "turn": turn, "session_id": session_id, "agent_id": agent_id,
                        "message_id": body["client_message_id"], "request_sha256": fingerprint,
                        "run_id": (hitl_response or {}).get("execution_id") or "run:" + body["client_message_id"],
                        "status": "DISPATCHING", "reserve_attempts": reserves[0], "reserve_tokens": reserves[1]}
                self.state["intents"].append(item)
                save(folder / "request.json", body)
                self._persist()
                save(folder / "intent.json", item)
            failure = None
            try:
                timing = self._observe_chat(body, folder, resume, item)
                save(folder / "timing.json", timing)
            except Exception as error:
                failure = type(error).__name__
                timing = {"terminal": {}, "observation_error": failure}
                save(folder / "timing.json", timing)
            return self._after(item, timing, folder, failure)

    def _observe_chat(self, body, folder, resume, item):
        """在途仅观察真实累计用量；触硬上限或已结束未知计量时落取消意图，不重发输入。"""
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(chat, self.api, body, folder / "events.sse.jsonl", self.config["turn_timeout"], resume=resume)
            cancelled = False
            while True:
                try:
                    return future.result(timeout=3)
                except concurrent.futures.TimeoutError:
                    if future.done():
                        return future.result()
                    usage = self._snapshot()
                    bad = (usage["attempts"] >= self.config["max_attempts"]
                           or usage["tokens"] >= self.config["max_tokens"]
                           or usage["provider"].get("unknown_attempts", 0) > 0)
                    if bad and not cancelled:
                        self.state["stop_reason"] = self.state["stop_reason"] or "in_flight_budget_or_usage_stop"
                        self._persist()
                        save(folder / "budget-cancel-intent.json", {"session_id": item["session_id"], "run_id": item["run_id"]})
                        self.api.request("POST", "/chat/runs/cancel", {"session_id": item["session_id"], "run_id": item["run_id"]})
                        cancelled = True

    def _after(self, item, timing, folder, failure):
        """输入收尾保留原终态与完整证据；任何未知结果/费用均阻断后继，观察不受派发窗限制。"""
        terminal = timing.get("terminal") or {}
        if terminal.get("run_id", terminal.get("execution_id", item["run_id"])) != item["run_id"]:
            failure = "terminal_identity_mismatch"
        item["status"] = terminal.get("status") if terminal.get("status") in TERMINALS and failure is None else "UNKNOWN"
        if item["status"] == "UNKNOWN":
            self.state["stop_reason"] = self.state["stop_reason"] or "input_outcome_unknown"
        self._persist()
        try:
            # Public delivery is preserved in SSE/history; fetch the authorized trace identities once,
            # then bind their original contents through the scoped, read-only database reader.
            trace = self.reader.augment(collect_trace(self.api, item["session_id"], include_content=False), item["session_id"])
            rich = flatten(trace, internal=True)[1]
            events = [{"event_type": row["event_type"], "run_id": row["run_id"],
                       "sequence": row.get("detail", {}).get("sessionSequence"), "payload": row.get("payload")}
                      for row in rich]
            history = self.api.request("GET", query("/chat/history", session_id=item["session_id"]))
            save(folder / "trace.json", trace)
            save(folder / "internal-events.json", events)
            save(folder / "history.json", history)
        except Exception:
            self.state["stop_reason"] = self.state["stop_reason"] or "turn_evidence_unavailable"
            self._persist()
            raise ComplexLiveStopped("turn_evidence_unavailable") from None
        usage = self._snapshot()
        settle_until = time.monotonic() + self.config["settle_seconds"]
        while not usage["usage_known"] and usage["provider"].get("pending_attempts", 0) and time.monotonic() < settle_until:
            time.sleep(min(1, max(0, settle_until - time.monotonic())))
            usage = self._snapshot()
        if not usage["usage_known"]:
            self.state["stop_reason"] = self.state["stop_reason"] or "provider_usage_unknown_or_in_flight"
        self._persist()
        finals = [row["payload"] for row in events if row["run_id"] == item["run_id"]
                  and row["event_type"] == "run.completed" and isinstance(row.get("payload"), dict)]
        reply = finals[-1].get("outcome", {}).get("report", {}).get("output", "") if finals else ""
        if item["status"] == "waiting_hitl":
            reply = (timing.get("pending_hitl") or {}).get("question", "")
        result = {"run_id": item["run_id"], "status": item["status"], "reply": reply, "history": history,
                  "events": events, "rich_events": rich, "folder": str(folder), "terminal": terminal,
                  "message_id": item["message_id"], "pending_hitl": timing.get("pending_hitl"),
                  "usage_known": usage["usage_known"], "stop_reason": self.state["stop_reason"]}
        save(folder / "after.json", result)
        return result
