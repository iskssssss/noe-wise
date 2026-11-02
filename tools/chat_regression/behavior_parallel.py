"""本地双路评测：一个调度器、一份预算；副作用场景独占执行。"""
from __future__ import annotations

import concurrent.futures
import os
from pathlib import Path
import threading
import time
from contextlib import contextmanager, nullcontext

from behavior_capture import TERMINALS, flatten
from behavior_contract import EVALUATOR_VERSION, digest, experiments, issue, now, require, transition
from behavior_environment import ConfigurationMonitor, configuration, resolve_model
from behavior_runner import ExecutionBudget, StopBatch, cancel, execute_case, final_report, read_run_status
from client import Api, ApiError, collect_trace, login, query
from behavior_internal_evidence import configure_internal_evidence
from main import batch_lock
from mcp_fixture import McpFixtureServer
from provision import provision
from runner import read, save


SERIAL_CASES = frozenset({"B06", "B14", "B15", "B16", "B17", "B18", "S03", "S04", "S05"})


class SharedBudget(ExecutionBudget):
    """原子计入输入与各 Session 累计用量，熔断后继续结算在途消耗。"""
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.lock = threading.RLock()

    def stop(self, reason):
        with self.lock:
            if self.stopped is None:
                self.stopped = reason

    def before_turn(self):
        with self.lock:
            try:
                super().before_turn()
            except StopBatch as exc:
                self.stop(exc.reason)
                raise

    def observe(self, session, usage):
        with self.lock:
            super().observe(session, usage)
            if self.stopped:
                raise StopBatch(self.stopped)

    def state(self):
        with self.lock:
            value = super().state()
            value["sessions"] = dict(value["sessions"])
            return value

    @contextmanager
    def dispatch(self):
        """将实际发送与 stop 线性排序，等待节流的请求不算已派发。"""
        with self.lock:
            if self.stopped:
                raise StopBatch(self.stopped)
            if self.remaining() <= 0 or sum(self.sessions.values()) >= self.max_tokens:
                self.stop(issue("budget_exceeded", "dispatch_budget_exhausted"))
                raise StopBatch(self.stopped)
            yield


def settle_experiment(api, fixture, folder, budget, seconds, case_key):
    """异常后只按已保存的请求身份取消、查询和结算，不重发用户输入。"""
    state_path = folder / "state.json"
    if not state_path.exists():
        return
    state = read(state_path)
    if not state.get("session_id") or not state.get("turns"):
        return
    sid = state["session_id"]
    ids = {entry["request"].get("hitl_response", {}).get("execution_id") or
           "run:" + entry["request"]["client_message_id"] for entry in state["turns"].values()}
    deadline = time.monotonic() + seconds
    statuses, trace, unresolved, cancelled = {}, None, True, set()
    errors = []
    while True:
        try:
            for rid in ids:
                try:
                    status = read_run_status(api, sid, rid)
                except ApiError as exc:
                    if exc.status_code == 404:
                        statuses[rid] = "not_observed"
                        continue
                    raise
                statuses[rid] = status["status"]
                if status["status"] not in TERMINALS and rid not in cancelled:
                    cancel(api, sid, rid)
                    cancelled.add(rid)
            trace = collect_trace(api, sid, full_content=True)
            try:
                budget.observe(sid, trace.get("usage", {}))
            except StopBatch:
                pass  # 熔断状态保持，observe 仍合并了迟到的已知消耗。
            audit = fixture.request("GET", query("/audit", case_key=case_key))
            events = flatten(trace)[1]
            attempts = {a["attemptId"]: a for e in events for a in e.get("detail", {}).get("attempts", [])}
            quiet = (audit.get("complete") is True and all(c.get("completed_at") is not None for c in audit["calls"])
                     and all(e.get("detail", {}).get("currentActionState") in
                             {None, "SUCCEEDED", "FAILED", "OUTCOME_UNKNOWN"} for e in events)
                     and all(a.get("usage") and all(u.get("usageKnown") is True for u in a["usage"])
                             for a in attempts.values() if a.get("callKind") in {"MODEL_GENERATE", "MODEL_STREAM"}))
            # 404 仅表示未观察到，不作为“未派发”证明，报告仍 incomplete。
            unresolved = (any(status not in TERMINALS for status in statuses.values()) or
                          type(trace.get("usage", {}).get("tokens")) is not int or not quiet or
                          bool(trace.get("usage", {}).get("unknown_usage_calls", 0)))
            if not unresolved:
                break
        except Exception as exc:
            errors.append(type(exc).__name__)
            break
        if time.monotonic() >= deadline:
            break
        time.sleep(min(0.25, max(0, deadline - time.monotonic())))
    if trace is not None:
        save(folder / "interruption-trace.json", trace)
    save(folder / "interruption-settlement.json", {"statuses": statuses, "unresolved": unresolved,
         "errors": errors, "observed_at": now(), "frozen_evaluation_unchanged": True})


def guarded_case(api, fixture, plan, case, repetition, resources, root, budget, monitor):
    """异常归属到本实验并收尾；不静止的 future 不能打开下一波屏障。"""
    folder = Path(root) / "cases" / case["id"] / str(repetition)
    try:
        result = execute_case(api, fixture, plan, case, repetition, resources, root, budget, monitor)
        snapshot = read(folder / "evidence.json")
        if (not snapshot.get("completeness", {}).get("quiescence") or
                any(r["code"] in {"incomplete", "invalid_environment"} for r in result["blocking_reasons"])):
            raise StopBatch(issue("incomplete", "parallel_experiment_not_settled"))
        return result
    except Exception as exc:
        budget.stop(exc.reason if isinstance(exc, StopBatch) else
                    issue("invalid_environment", "parallel_worker_failed"))
        try:
            settle_experiment(api, fixture, folder, budget, plan["settlement_seconds"],
                              f"{plan['batch']}:{case['id']}:r{repetition}")
        except Exception as cleanup_error:
            save(folder / "interruption-error.json", {"type": type(cleanup_error).__name__, "incomplete": True})
        raise


def waves(plan):
    """每波最多两个只读实验；敏感场景前后形成静止屏障。"""
    pending = []
    for experiment in experiments(plan):
        if experiment[0] in SERIAL_CASES:
            if pending:
                yield pending
                pending = []
            yield [experiment]
        else:
            pending.append(experiment)
            if len(pending) == 2:
                yield pending
                pending = []
    if pending:
        yield pending


class LaneMonitor:
    """任一路配置漂移阻止两路继续；请求指纹仍按各自工具身份校验。"""
    def __init__(self, local, monitors):
        self.local, self.monitors = local, monitors
        self.request_fingerprints = {}

    @property
    def reasons(self):
        return [reason for monitor in self.monitors for reason in monitor.reasons]

    def check(self):
        self.local.check()
        return not self.reasons


def run_waves(plan, budget, invoke, completed):
    """只提交当前波，故障先全局熔断，再等待两个在途实验完成结算。"""
    def work(lane, experiment):
        try:
            return invoke(lane, experiment)
        except StopBatch as exc:
            budget.stop(exc.reason)
            raise
        except Exception:
            budget.stop(issue("invalid_environment", "parallel_worker_failed"))
            raise

    with concurrent.futures.ThreadPoolExecutor(max_workers=2, thread_name_prefix="behavior-lane") as pool:
        for wave in waves(plan):
            if budget.stopped:
                raise StopBatch(budget.stopped)
            futures = {pool.submit(work, lane, item): item for lane, item in enumerate(wave)}
            failure = None
            for future in concurrent.futures.as_completed(futures):
                try:
                    completed(futures[future], future.result())
                except Exception as exc:
                    failure = failure or exc
                    budget.stop(exc.reason if isinstance(exc, StopBatch) else
                                issue("invalid_environment", "parallel_result_collection_failed"))
            if failure:
                raise failure


def local_parallel_run(args, root, plan):
    # 锁未取得不能生成终态，更不能覆盖另一个运行器的预算与报告。
    with batch_lock(Path(root)):
        require(not (Path(root) / "batch.json").exists(), "batch_already_started_use_new_batch_after_diagnosis")
        return _local_parallel_locked(args, root, plan)


def _local_parallel_locked(args, root, plan):
    """与串行报告共用断言，但独立冻结并行策略、两路配置与资源身份。"""
    from behavior_main import publish, outcome_exit_code

    require(args.command == "local-run", "parallel_requires_local_run")
    root = Path(root)
    policy = {"workers": 2, "serial_cases": sorted(SERIAL_CASES), "scheduler_version": 1}
    servers, monitors, lanes = [], [], []
    budget = SharedBudget(args.max_tokens, args.max_turns, args.max_seconds)
    batch = {"lifecycle": "planned", "started_at": now(), "budget": budget.state(), "blocking_reasons": [],
             "binding": {"deployment_revision": args.deployment_revision, "suite_version": plan["suite_version"],
                         "evaluator_version": EVALUATOR_VERSION, "model_id": args.model_id, "model_version": None,
                         "settlement_seconds": plan["settlement_seconds"], "validation_scope": "local",
                         "execution_policy": policy}}
    cleanup = True
    save(root / "environment.json", {"validation_scope": "local", "network_isolation_verified": False})
    try:
        transition(batch, "preparing")
        save(root / "batch.json", batch)
        api, platform = login(args.api), login(args.platform, True)
        configure_internal_evidence(api, getattr(args, "internal_evidence", False))
        batch["binding"]["evidence_mode"] = ("local-postgres-readonly-v1" if vars(api).get("internal_evidence_reader")
                                               else "public_trace")
        api.dispatch_gate = lambda method, path: budget.dispatch() if method == "POST" and path == "/chat" else nullcontext()
        model = resolve_model(platform, args.model_id)
        selected = {cid for cid, _ in experiments(plan)}
        profiles = {c["profile"] for c in plan["cases"] if c["id"] in selected}
        for index in range(2):
            server = McpFixtureServer(0, plan["batch"], os.environ.get("MCP_FIXTURE_TOKEN"))
            thread = threading.Thread(target=server.serve_forever, name=f"behavior-fixture-{index}")
            thread.start()
            servers.append((server, thread))
            endpoint = f"http://127.0.0.1:{server.server_address[1]}"
            fixture = Api(endpoint, os.environ.get("MCP_FIXTURE_TOKEN"))
            lane_root = root / "lanes" / str(index)
            resources = provision(api, platform, lane_root, [p for p in plan["profiles"] if p["key"] in profiles],
                plan["batch"] + f"-lane{index}", str(model["id"]), endpoint + "/mcp", user_count=len(experiments(plan)))
            resources["resolved_model"] = model
            save(lane_root / "resources.json", resources)
            baseline = configuration(api, platform, resources)
            monitor = ConfigurationMonitor(lambda r=resources: configuration(api, platform, r), baseline)
            monitors.append(monitor)
            lanes.append((fixture, resources, baseline, monitor))
        frozen = {"execution_policy": policy, "lanes": [lane[2] for lane in lanes]}
        save(root / "configuration.json", frozen)
        batch["binding"].update(config_digest=digest(frozen), prompt_hash=digest(plan["profiles"]),
                                tool_schema_version="fixture-script-3")
        for monitor in monitors:
            monitor.start()
        lane_monitors = [LaneMonitor(m, monitors) for m in monitors]
        transition(batch, "running")
        save(root / "batch.json", batch)

        def invoke(index, experiment):
            if budget.stopped:
                raise StopBatch(budget.stopped)
            for monitor in monitors:
                if monitor.reasons:
                    raise StopBatch(monitor.reasons[0])
            cid, repetition = experiment
            case = next(c for c in plan["cases"] if c["id"] == cid)
            fixture, resources, _, _ = lanes[index]
            folder = root / "cases" / cid / str(repetition)
            save(folder / "lane.json", {"lane": index})
            return guarded_case(api, fixture, plan, case, repetition, resources, root, budget, lane_monitors[index])

        def completed(experiment, result):
            batch["budget"] = budget.state()
            save(root / "batch.json", batch)
            print(*experiment, result["behavior_status"], flush=True)

        run_waves(plan, budget, invoke, completed)
    except StopBatch as exc:
        batch["blocking_reasons"].append(exc.reason)
    except Exception as exc:
        batch["blocking_reasons"].append(issue("invalid_environment", "parallel_run_failed:" + type(exc).__name__))
    finally:
        for monitor in monitors:
            try:
                if monitor.thread.ident is not None:
                    monitor.close()
            except Exception:
                cleanup = False
                batch["blocking_reasons"].append(issue("invalid_environment", "parallel_monitor_cleanup_failed"))
            batch["blocking_reasons"].extend(monitor.reasons)
        for server, thread in servers:
            try:
                server.shutdown()
                server.server_close()
                thread.join(10)
                cleanup = cleanup and not thread.is_alive()
            except Exception:
                cleanup = False
        batch["budget"] = budget.state()
        batch["config_samples"] = [m.samples for m in monitors]
        batch["configuration_failures"] = [getattr(m, "failures", []) for m in monitors]
        save(root / "configuration-checks.json", {"lanes": [{"samples": m.samples, "reasons": m.reasons,
                                                            "failures": getattr(m, "failures", [])} for m in monitors]})
        transition(batch, "settling")
    report = final_report(root, plan, batch, cleanup)
    publish(root, report)
    return outcome_exit_code(report, True)
