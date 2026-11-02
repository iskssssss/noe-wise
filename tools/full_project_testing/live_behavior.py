"""单 paid lane 的真实行为测试入口：固定账本、透明 HTTP 派发门、Provider 只读结算。"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
import traceback
from datetime import datetime, timezone
from urllib.parse import urlsplit

from run_suite import REPO, git, load_environment, save
import budget_ledger as ledger
from live_behavior_usage import ProviderReader, UsageUnavailable
from live_behavior_namespace import fixture_namespace
from live_paid_actors import paid_actor_environment
from live_behavior_inputs import InputManifestMonitor, capture_inputs, verify_ready, file_hash, product_dataset

sys.path.insert(0, str(REPO / "tools" / "chat_regression"))
import behavior_main
import behavior_runner
from behavior_contract import issue
from behavior_internal_evidence import configure_internal_evidence
from client import login, query
from behavior_environment import configuration

ENVIRONMENT_ROOT = REPO / ".runtime-logs" / ledger.RUN_ID / "environment"
LIVE_ROOT = REPO / ".runtime-logs" / ledger.RUN_ID / "live-behavior"
PRODUCT_FILES = ("dataset.json", "candidates.json", "validation.json", "all-attempts.json",
                 "dataset-derivation.json", "generation-cost.json", "generation-summary.json")
TERMINAL_OR_WAITING = {"completed", "cancelled", "failed", "terminated", "waiting_hitl"}
HOP_HEADERS = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
               "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length"}


class DispatchStopped(RuntimeError):
    """固定诊断码；代理错误不包含请求、认证或上游正文。"""


def experiment_counts():
    """三份独立资源与根目录，内层 repetitions=1，避免误做九次重复。"""
    return {"rounds": 3, "inner_repetitions": 1, "core": 54, "stress": 18, "product": 36}


def copy_product_dataset(destination):
    from behavior_dataset import load_dataset
    source_root, original = product_dataset(os.environ)
    hashes = {}
    for name in PRODUCT_FILES:
        source = source_root / name
        if not source.is_file():
            continue
        hashes[name] = hashlib.sha256(source.read_bytes()).hexdigest()
        shutil.copy2(source, destination / name)
    copied = load_dataset(destination)
    if copied["fingerprint"] != original["fingerprint"]:
        raise ValueError("copied_product_dataset_changed")
    save(destination / "dataset-source-receipt.json", {
        "source_root": str(source_root), "fingerprint": original["fingerprint"],
        "files_sha256": hashes, "authoring_method": "byte_identical_copy_of_audited_dataset",
        "new_generation_requests": 0, "derivation_preserved": True,
        "historic_generation_cost_is_current_execution_usage": False})


@contextmanager
def process_environment(environment):
    original = dict(os.environ)
    os.environ.clear()
    os.environ.update(environment)
    try:
        yield
    finally:
        os.environ.clear()
        os.environ.update(original)


class DispatchGuard:
    """同一预留内的 API 与浏览器共用一个持久派发序列；未知结果不可重放。"""

    def __init__(self, receipt, provider, admin_api, root, *, clock=time.monotonic):
        self.receipt, self.provider, self.admin_api, self.root = receipt, provider, admin_api, root
        self.active_limits = receipt.get("remaining_limits") or {
            key: receipt[key] for key in ("max_tokens", "max_seconds", "max_input_turns")}
        self.clock, self.started = clock, clock()
        self.started_at = datetime.now(timezone.utc).isoformat()
        self.lock = threading.RLock()
        self.intents, self.sessions, self.stopped = [], set(), None
        self.last_provider = None
        self.input_monitor = None
        self.input_suite = receipt["suite"]
        self.journal = root / "guard" / "dispatch-journal.json"
        if self.journal.exists():
            raise DispatchStopped("guard_journal_exists_query_only_no_replay")
        self.persist()

    def persist(self):
        save(self.journal, {"reservation_id": self.receipt["reservation_id"],
                           "batch_id": self.receipt["batch_id"], "intents": self.intents,
                           "stop_reason": self.stopped, "source": "transparent_loopback_guard",
                           "owner_pid": os.getpid(), "started_at": self.started_at})

    def stop(self, code):
        self.stopped = self.stopped or code
        self.persist()
        raise DispatchStopped(code)

    def snapshot(self):
        try:
            value = self.provider.snapshot(self.sessions)
        except UsageUnavailable:
            self.stop("provider_usage_readback_unavailable")
        self.last_provider = value
        save(self.root / "guard/provider-latest.json", value)
        return value

    def limits(self, snapshot, *, new_input=False):
        elapsed = self.clock() - self.started
        turns = sum(item["is_input"] for item in self.intents)
        if (snapshot["tokens"] > self.active_limits["max_tokens"] or elapsed > self.active_limits["max_seconds"]
                or turns > self.active_limits["max_input_turns"]):
            self.stop("observed_reservation_budget_exceeded")
        if new_input and (turns >= self.active_limits["max_input_turns"]
                          or snapshot["tokens"] >= self.active_limits["max_tokens"]
                          or elapsed >= self.active_limits["max_seconds"]):
            self.stop("new_input_reservation_budget_exhausted")

    def run_ready(self, item):
        state = self.admin_api.request("GET", query("/chat/runs/status",
            session_id=item["session_id"], run_id=item["run_id"]))
        if (state.get("session_id") != item["session_id"] or state.get("execution_id") != item["run_id"]):
            raise DispatchStopped("previous_run_status_identity_mismatch")
        if state.get("status") in TERMINAL_OR_WAITING:
            return True
        if state.get("status") == "suspended":
            history = self.admin_api.request("GET", query("/chat/history", session_id=item["session_id"], limit=1))
            return history.get("session_id") == item["session_id"] and any(
                pending.get("execution_id") == item["run_id"] for pending in history.get("pending_interactions", []))
        return False

    def before(self, method, path, raw):
        """保存 intent 后才可 forward；所有潜在计费写请求检查当前 Provider 事实。"""
        with self.lock:
            ledger.check_reservation(ledger.LEDGER_PATH, self.receipt["reservation_id"])
            if self.stopped:
                raise DispatchStopped(self.stopped)
            if self.input_monitor is not None:
                if method == "POST" and path == "/api/v1/chat":
                    try:
                        self.input_monitor.freeze_batch(product=self.input_suite == "product")
                    except Exception:
                        self.stop("frozen_batch_input_manifest_unavailable")
                if not self.input_monitor.check():
                    self.stop("execution_input_manifest_changed")
            snapshot = self.snapshot()
            is_input = method == "POST" and path == "/api/v1/chat"
            self.limits(snapshot, new_input=is_input)
            if snapshot["pending_attempts"] or snapshot["unknown_attempts"]:
                self.stop("pending_or_unknown_provider_usage_blocks_new_dispatch")
            if any(item["phase"] == "intent" for item in self.intents):
                self.stop("previous_dispatch_receipt_unknown")
            for item in self.intents:
                if not item["is_input"] or item["phase"] != "receipt":
                    continue
                if not self.run_ready(item):
                    self.stop("previous_run_not_terminal_blocks_new_dispatch")
            record = {"sequence": len(self.intents) + 1, "method": method, "path": path,
                      "body_sha256": hashlib.sha256(raw).hexdigest(), "is_input": is_input,
                      "phase": "intent"}
            if is_input:
                try:
                    body = json.loads(raw)
                    if "message" in body or not isinstance(body.get("content"), list):
                        raise ValueError()
                    for part in body["content"]:
                        if (not isinstance(part, dict) or part.get("type") not in {"text", "image"}
                                or part["type"] == "text" and not isinstance(part.get("text"), str)
                                or part["type"] == "image" and not isinstance(part.get("media_id"), str)):
                            raise ValueError()
                    session, message = body["session_id"], body["client_message_id"]
                    if not isinstance(session, str) or not session or not isinstance(message, str) or not message:
                        raise ValueError()
                    record.update(session_id=session, client_message_id=message,
                                  expected_run_id=(body.get("hitl_response") or {}).get("execution_id") or "run:" + message)
                except (ValueError, TypeError, KeyError):
                    self.stop("formal_input_content_and_identity_required_before_forward")
                self.sessions.add(session)
            self.intents.append(record)
            self.persist()
            return record

    def after(self, record, status, raw):
        with self.lock:
            if record["is_input"]:
                try:
                    envelope = json.loads(raw)
                    data = envelope["data"]
                    if (not 200 <= status < 300 or envelope.get("code") != "OK"
                            or data.get("session_id") != record["session_id"]
                            or data.get("execution_id") != record["expected_run_id"]
                            or not isinstance(data.get("input_id"), str) or not data["input_id"]):
                        raise ValueError()
                except (ValueError, TypeError, KeyError, AttributeError):
                    self.stop("input_dispatch_receipt_unknown")
                record.update(phase="receipt", run_id=data["execution_id"], input_id=data["input_id"],
                              http_status=status, response_sha256=hashlib.sha256(raw).hexdigest())
            else:
                # 资源写的失败也保留实际响应身份，不将它改写为成功。
                record.update(phase="receipt", http_status=status,
                              response_sha256=hashlib.sha256(raw).hexdigest())
            self.persist()

    def observe(self, budget):
        """在途仅使用明确的下界监控；STARTED 永不允许下一 paid input。"""
        with self.lock:
            snapshot = self.snapshot()
            self.limits(snapshot)
            if snapshot["unknown_attempts"]:
                self.stop("closed_provider_usage_unknown")
            budget.sessions = {"reservation_provider_total": snapshot["tokens"]}
            # 下界与 pending 单独落证；此适配不修改真实 Trace 响应。
            save(self.root / "guard/consumer-adapter.json", {
                "usage_source": snapshot["source"], "tokens_lower_bound": snapshot["tokens"],
                "pending_attempts": snapshot["pending_attempts"], "unknown_attempts": snapshot["unknown_attempts"],
                "public_decimal_representation_adapter": True,
                "pending_is_complete_usage": False})

    def settlement(self):
        """未知时仅登记已确认维度；已确认下界/超额不能被 UNKNOWN 擦掉。"""
        with self.lock:
            read_failed = False
            try:
                snapshot = self.provider.snapshot(self.sessions)
                self.last_provider = snapshot
            except Exception:
                snapshot, read_failed = self.last_provider, True
            unknown_intents = any(item["phase"] != "receipt" for item in self.intents)
            unresolved_runs = []
            for item in self.intents:
                if not item["is_input"] or item["phase"] != "receipt":
                    continue
                try:
                    if not self.run_ready(item):
                        unresolved_runs.append(item["sequence"])
                except Exception:
                    unresolved_runs.append(item["sequence"])
            known = bool(snapshot is not None and snapshot["usage_known"] and not read_failed
                         and not unknown_intents and not unresolved_runs)
            actual = {"usage_known": known, "observed_input_turns": sum(item["is_input"] for item in self.intents),
                      "observed_elapsed_seconds": self.clock() - self.started}
            if snapshot is not None:
                actual["observed_tokens"] = snapshot["tokens"]
            evidence = {**actual, "reservation_id": self.receipt["reservation_id"],
                        "batch_id": self.receipt["batch_id"], "provider_usage": snapshot,
                        "provider_read_failed": read_failed, "dispatch_receipts_unknown": unknown_intents,
                        "unresolved_run_sequences": unresolved_runs, "stop_reason": self.stopped,
                        "dispatch_journal": str(self.journal), "public_trace_root": str(self.root / "cases")}
            path = self.root / "guard/settlement-evidence.json"
            save(path, evidence)
            return ledger.settle(ledger.LEDGER_PATH, self.receipt["reservation_id"], actual, path)


class GuardProxy:
    """请求字节/认证/回执保持原样，HTTP/SSE 都来自已隔离真实 institution。"""

    def __init__(self, target, guard):
        endpoint = urlsplit(target)
        if endpoint.scheme != "http" or endpoint.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("guard_requires_loopback_target")
        owner = self
        self.target, self.guard = endpoint, guard

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def handle_request(self):
                connection, record, response_started = None, None, False
                try:
                    path = urlsplit(self.path)
                    if path.scheme or path.netloc or not path.path.startswith(("/api/", "/health")):
                        raise DispatchStopped("proxy_path_rejected")
                    length = int(self.headers.get("Content-Length", "0"))
                    if length < 0 or length > 16 * 1024 * 1024 or self.headers.get("Transfer-Encoding"):
                        raise DispatchStopped("proxy_body_length_rejected")
                    raw = self.rfile.read(length)
                    if len(raw) != length:
                        raise DispatchStopped("proxy_request_body_incomplete")
                    guarded = (self.command not in {"GET", "HEAD", "OPTIONS"}
                               and path.path not in {"/api/v1/chat/runs/cancel", "/api/v1/auth/login",
                                                     "/api/v1/auth/refresh"})
                    if guarded:
                        record = owner.guard.before(self.command, path.path, raw)
                    headers = {key: value for key, value in self.headers.items() if key.lower() not in HOP_HEADERS}
                    headers["Content-Length"] = str(len(raw))
                    connection = http.client.HTTPConnection(owner.target.hostname, owner.target.port or 80, timeout=600)
                    connection.request(self.command, self.path, body=raw if length else None, headers=headers)
                    response = connection.getresponse()
                    event_stream = response.getheader("Content-Type", "").split(";", 1)[0] == "text/event-stream"
                    if record is not None:
                        payload = response.read(16 * 1024 * 1024 + 1)
                        if len(payload) > 16 * 1024 * 1024:
                            owner.guard.stop("guarded_response_limit")
                        owner.guard.after(record, response.status, payload)
                    elif not event_stream:
                        payload = response.read(16 * 1024 * 1024 + 1)
                        if len(payload) > 16 * 1024 * 1024:
                            raise DispatchStopped("proxy_response_limit")
                    else:
                        payload = None
                    self.send_response(response.status)
                    for key, value in response.getheaders():
                        if key.lower() not in HOP_HEADERS:
                            self.send_header(key, value)
                    self.send_header("Content-Length", str(len(payload))) if payload is not None else self.send_header("Connection", "close")
                    self.end_headers()
                    response_started = True
                    if payload is not None:
                        self.wfile.write(payload)
                    else:
                        self.close_connection = True
                        while chunk := response.read1(16384):
                            self.wfile.write(chunk)
                            self.wfile.flush()
                except Exception:
                    if record is not None and record["phase"] == "intent":
                        with owner.guard.lock:
                            owner.guard.stopped = owner.guard.stopped or "forward_result_unknown_do_not_replay"
                            owner.guard.persist()
                    if not response_started:
                        payload = b'{"code":"TEST_DISPATCH_STOPPED","message":"inspect private guard evidence","data":null}'
                        self.send_response(503)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(payload)))
                        self.end_headers()
                        try:
                            self.wfile.write(payload)
                        except OSError:
                            pass
                finally:
                    if connection:
                        connection.close()

            do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_OPTIONS = do_HEAD = handle_request

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, name="paid-behavior-guard", daemon=True)
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(10)


@contextmanager
def guarded_consumers(guard):
    """只适配本次评测进程的预算消费者，不改服务/公开响应或模型答案。"""
    original = behavior_runner.ExecutionBudget.observe
    original_prepare = behavior_main.prepare

    def observe(budget, session, usage):
        try:
            guard.observe(budget)
        except DispatchStopped as exc:
            budget.stopped = issue("incomplete", str(exc))
            raise behavior_runner.StopBatch(budget.stopped) from None

    behavior_runner.ExecutionBudget.observe = observe

    def prepare(*args, **kwargs):
        try:
            return original_prepare(*args, **kwargs)
        except Exception as exc:
            # Call sites identify the failed formal contract; omit arbitrary exception/body text.
            frames = []
            for frame in traceback.extract_tb(exc.__traceback__):
                path = Path(frame.filename).resolve()
                if path.is_relative_to(REPO):
                    frames.append({"file": path.relative_to(REPO).as_posix(), "line": frame.lineno,
                                   "function": frame.name})
            save(guard.root / "guard/preparation-failure-origin.json", {
                "failure_kind": type(exc).__name__, "frames": frames})
            raise

    behavior_main.prepare = prepare
    try:
        yield
    finally:
        behavior_runner.ExecutionBudget.observe = original
        behavior_main.prepare = original_prepare


@contextmanager
def product_frontends(root, proxy):
    """只管理本次 Popen 产生的 Node/Vite；两个客户端指向同一透明派发门。"""
    import behavior_product_browser as browser
    import behavior_product_usage as usage
    import behavior_product_runner as runner
    receipt = root / "guard/frontend-receipt.json"
    environment = dict(os.environ, FULL_TEST_GUARD_PROXY=proxy.base,
                       FULL_TEST_FRONTEND_RECEIPT=str(receipt))
    with (root / "guard/frontend-command.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(["node", str(Path(__file__).with_name("live_behavior_frontend.mjs"))],
                                   cwd=REPO, env=environment, stdout=log, stderr=log, shell=False)
        original_environment = browser._environment
        originals = [(module, module.collect_product_usage) for module in (browser, usage, runner)]
        try:
            deadline = time.monotonic() + 60
            while not receipt.exists():
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise ValueError("fresh_frontends_failed_before_paid_inputs")
                time.sleep(.1)
            references = json.loads(receipt.read_text(encoding="utf-8"))
            if references.get("pid") != process.pid or references.get("guard_proxy") != proxy.base:
                raise ValueError("fresh_frontend_receipt_identity_mismatch")

            def scoped_environment(*args, **kwargs):
                result = original_environment(*args, **kwargs)
                result.update(PRODUCT_PROCESS_ADMIN_ORIGIN=references["admin_origin"],
                              PRODUCT_PROCESS_CHAT_ORIGIN=references["chat_origin"])
                return result

            browser._environment = scoped_environment
            for module, _ in originals:
                module.collect_product_usage = lambda api, sid: product_usage(proxy.guard, sid)
            yield references
        finally:
            browser._environment = original_environment
            for module, original in originals:
                module.collect_product_usage = original
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=10)


def product_usage(guard, parent_session):
    """按真实 Provider attempt 回读父子 Session，拒绝旧消费者的 ESTIMATED/重复报告合计。"""
    from live_behavior_usage import natural
    with guard.lock:
        snapshot = guard.snapshot()
    records = {row["id"]: row for row in snapshot.get("provider_records", [])}
    children, frontier, seen = [], [parent_session], {parent_session}
    while frontier:
        current = [row for row in snapshot.get("delegation_records", []) if row["parent_session_id"] in frontier]
        children += current
        frontier = ["delegation:" + row["delegation_id"] for row in current
                    if "delegation:" + row["delegation_id"] not in seen]
        seen.update(frontier)

    def for_sessions(sessions):
        selected = [row for row in snapshot["attempts"] if any(
            records[event]["session_id"] in sessions for event in row["provider_event_ids"])]
        amount = {}
        for row in selected:
            measured = [records[event] for event in row["provider_event_ids"]
                        if records[event]["usage_event_kind"] == "measured"]
            if measured:
                latest = max(measured, key=lambda value: natural(value["id"]))
                if latest.get("currency") and latest.get("total_amount_cents") is not None:
                    currency = latest["currency"]
                    amount[currency] = amount.get(currency, 0) + natural(latest["total_amount_cents"])
        return {"calls": len(selected), "tokens": sum(row["tokens_lower_bound"] for row in selected),
                "prompt_tokens": sum(row["provider_values"].get("prompt_tokens", 0) for row in selected),
                "completion_tokens": sum(row["provider_values"].get("completion_tokens", 0) for row in selected),
                "unknown_usage_calls": sum(not row["usage_known"] and not row["in_flight"] for row in selected),
                "amount_by_currency": amount,
                "pending_attempts": sum(row["in_flight"] for row in selected),
                "tokens_are_lower_bound_while_pending": True, "source": snapshot["source"]}

    child_rows = [{**row, "session_id": "delegation:" + row["delegation_id"],
                   "run_id": row["child_run_id"], "usage": for_sessions({"delegation:" + row["delegation_id"]})}
                  for row in children]
    return {**for_sessions(seen), "child_sessions": child_rows}


def cli_arguments(suite, root, receipt, references, revision, proxy, *, cases=None):
    limits = receipt.get("remaining_limits") or receipt
    # Legacy runner needs positive integer seconds; never round residual time upward.
    seconds = int(limits["max_seconds"])
    if seconds < 1 or limits["max_tokens"] < 1 or limits["max_input_turns"] < 1:
        raise ValueError("no_remaining_dispatch_budget")
    namespace = fixture_namespace(receipt)
    result = ["local-run", "--suite", suite, "--root", str(root), "--batch", namespace["fixture_batch_id"],
            "--api", proxy.base + "/api/v1", "--platform", references["platform"],
            "--model-id", str(references["models"]["llm"]["id"]), "--deployment-revision", revision,
            "--repetitions", "1", "--workers", "1", "--internal-evidence",
            "--max-tokens", str(limits["max_tokens"]), "--max-turns", str(limits["max_input_turns"]),
            "--max-seconds", str(seconds)]
    if cases:
        if suite != "core":
            raise ValueError("case_selection_supported_only_for_core_diagnostic")
        result += ["--cases", ",".join(cases)]
    return result


def run_round(suite, number, environment, references, revision, *, diagnostic=False, cases=None, ready_receipt=None):
    if suite == "product":
        product_dataset(environment)
    original_root = LIVE_ROOT / suite / f"round-{number}"
    root = original_root
    if root.exists() and not diagnostic:
        raise ValueError("fresh_round_root_required_existing_root_query_only")
    # login / read-only permission preflight do not dispatch a model or create business resources.
    api = login(references["api"])
    platform = login(references["platform"], True)
    if ready_receipt is None:
        raise ValueError("actual_preflight_receipt_required_before_paid_reservation")
    current_inputs = capture_inputs(REPO, suite, dict(os.environ))
    current_config = configuration(api, platform, {"model_id": str(references["models"]["llm"]["id"]), "agents": {}})
    verify_ready(ready_receipt, suite, current_inputs, current_config)
    configure_internal_evidence(api, True)
    provider = ProviderReader(api.internal_evidence_reader)
    boundary = provider.preflight()
    batch = f"full-{suite}-r{number}-" + hashlib.sha256(revision.encode()).hexdigest()[:8]
    if diagnostic:
        original = json.loads((original_root / "supervisor-reservation.json").read_text(encoding="utf-8"))
        row = ledger.check_reservation(ledger.LEDGER_PATH, original["reservation_id"], query_only=True)
        sequence = row.get("diagnostic_sequence", 0) + 1
        root = original_root / f"diagnostic-{sequence}"
        receipt = ledger.reopen_known_diagnostic(ledger.LEDGER_PATH, original["reservation_id"], root)
        if Path(receipt["active_evidence_root"]).resolve() != root.resolve():
            raise ValueError("diagnostic_evidence_root_identity_mismatch")
    else:
        receipt = ledger.reserve(ledger.LEDGER_PATH, suite, number, batch, root)
    root.mkdir(parents=True, exist_ok=False)
    save(root / "supervisor-reservation.json", receipt)
    save(root / "provider-boundary.json", boundary)
    save(root / "fixture-namespace.json", fixture_namespace(receipt))
    guard = DispatchGuard(receipt, provider, api, root)
    exit_code, failure, input_monitor, inputs_stable = 2, None, None, False
    try:
        if suite == "product":
            copy_product_dataset(root)
        with GuardProxy(references["api"], guard) as proxy, guarded_consumers(guard):
            arguments = cli_arguments(suite, root, receipt, references, revision, proxy, cases=cases)
            save(root / "guard/runner-invocation.json", {"arguments": arguments,
                 "three_fresh_rounds_inner_repetitions_one": experiment_counts(),
                 "real_target": references["api"], "platform": references["platform"]})
            context = {"reservation_id": receipt["reservation_id"], "batch_id": receipt["batch_id"],
                       "suite": suite, "round": number, "remaining_limits": receipt.get("remaining_limits") or {
                           key: receipt[key] for key in ("max_tokens", "max_seconds", "max_input_turns")},
                       "fixture_namespace": fixture_namespace(receipt), "cases": cases,
                       "preflight_receipt_sha256": file_hash(ready_receipt),
                       "runner_arguments_sha256": file_hash(root / "guard/runner-invocation.json")}
            input_monitor = InputManifestMonitor(lambda: capture_inputs(REPO, suite, dict(os.environ),
                extra_paths=(ready_receipt, root / "guard/runner-invocation.json")), root, context=context)
            guard.input_monitor = input_monitor
            if suite == "product":
                with product_frontends(root, proxy):
                    exit_code = behavior_main.main(arguments)
            else:
                exit_code = behavior_main.main(arguments)
    except Exception as exc:
        failure = type(exc).__name__
    try:
        settled = guard.settlement()
    finally:
        if input_monitor is not None:
            inputs_stable = input_monitor.close()
            if not inputs_stable:
                exit_code, failure = 2, "execution_input_manifest_changed"
    save(root / "supervisor-result.json", {"suite": suite, "round": number, "exit_code": exit_code,
         "failure_kind": failure, "settlement": settled, "behavior_evaluation": str(root / "evaluation.json"),
         "input_manifest_stable": inputs_stable, "preflight_receipt_sha256": file_hash(ready_receipt),
         "source_bound_execution_evidence": inputs_stable, "behavior_passed": exit_code == 0})
    return {"suite": suite, "round": number, "exit_code": exit_code, "status": settled["status"],
            "root": str(root), "failure_kind": failure, "input_manifest_stable": inputs_stable}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=("core", "stress", "product", "all"), required=True)
    parser.add_argument("--rounds", default="1,2,3", help="只可选原冻结计划的 1,2,3；没有隐式重试")
    parser.add_argument("--diagnostic", action="store_true", help="显式复用已知SETTLED原预留，追加child证据且累计原预算")
    parser.add_argument("--settle-only", action="store_true", help="只读恢复原active证据与Provider实报并结算，不派发或重放")
    parser.add_argument("--cases", help="只用于单轮core diagnostic的显式缺失case选择，不增加原预算")
    parser.add_argument("--ready-receipt", type=Path, help="免费actual preflight生成的当前source/config绑定收据")
    args = parser.parse_args(argv)
    numbers = [int(value) for value in args.rounds.split(",")]
    if not numbers or len(set(numbers)) != len(numbers) or any(value not in (1, 2, 3) for value in numbers):
        parser.error("rounds must be distinct frozen rounds 1,2,3")
    if args.settle_only and (args.diagnostic or len(numbers) != 1 or args.suite == "all"):
        parser.error("settle-only selects one original suite/round, without diagnostic reopen")
    if args.suite == "all":
        parser.error("use serial named core/stress/product commands with a source-bound actual preflight")
    cases = [value.strip() for value in args.cases.split(",")] if args.cases is not None else None
    if cases is not None and (args.suite != "core" or not args.diagnostic or args.settle_only or len(numbers) != 1
            or not cases or len(set(cases)) != len(cases) or any(value not in {f"B{i:02d}" for i in range(1, 19)} for value in cases)):
        parser.error("cases requires one core diagnostic round and distinct valid B01-B18 identities")
    if not args.settle_only and args.ready_receipt is None:
        parser.error("actual source/config-bound --ready-receipt is required before paid dispatch")
    if args.suite == "product" and not args.settle_only:
        product_dataset(os.environ)
    environment = paid_actor_environment(load_environment(ENVIRONMENT_ROOT / "isolated.env"))
    references = json.loads((ENVIRONMENT_ROOT / "provision.json").read_text(encoding="utf-8"))
    revision = git("rev-parse", "HEAD").decode().strip()
    results = []
    with process_environment(environment):
        if args.settle_only:
            from live_behavior_recovery import settle_only
            original = LIVE_ROOT / args.suite / f"round-{numbers[0]}"
            receipt = json.loads((original / "supervisor-reservation.json").read_text(encoding="utf-8"))
            row = ledger.check_reservation(ledger.LEDGER_PATH, receipt["reservation_id"], query_only=True)
            api = login(references["api"])
            configure_internal_evidence(api, True)
            provider = ProviderReader(api.internal_evidence_reader)
            settled = settle_only(Path(row.get("active_evidence_root", row["evidence_root"])), api, provider)
            print(f"{args.suite} round={numbers[0]} mode=settle-only ledger={settled['status']}", flush=True)
            return 0 if settled["status"] == "SETTLED" else 1
        for number in numbers:
            result = run_round(args.suite, number, environment, references, revision, diagnostic=args.diagnostic,
                               cases=cases, ready_receipt=args.ready_receipt)
            results.append(result)
            print(f"{args.suite} round={number} ledger={result['status']} exit={result['exit_code']}", flush=True)
            if result["status"] != "SETTLED" or not result["input_manifest_stable"]:
                break
    return 0 if all(row["status"] == "SETTLED" and row["exit_code"] == 0 for row in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
