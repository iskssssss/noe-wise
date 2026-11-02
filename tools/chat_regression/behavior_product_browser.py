"""机构端真实操作与独立聊天端回读的本地桥接；凭据只经子进程环境传递。"""
from __future__ import annotations

from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

from behavior_contract import require
from behavior_product_suite import task_message
from behavior_product_usage import collect_product_usage
from behavior_runner import cancel
from client import query
from runner import read, save


SCRIPT = Path(__file__).resolve().parents[1] / "frontend" / "check-product-sessions.mjs"


def _environment(channel, client_ref, output, privacy_markers=(), identity_token=""):
    env = os.environ.copy()
    env.update(PRODUCT_PROCESS_ADMIN_ORIGIN="http://localhost:3000",
               PRODUCT_PROCESS_CHAT_ORIGIN="http://localhost:3200",
               PRODUCT_PROCESS_ADMIN_TENANT=os.environ.get("SF_TENANT", "demo"),
               PRODUCT_PROCESS_ADMIN_USERNAME=os.environ["SF_ADMIN_USER"],
               PRODUCT_PROCESS_ADMIN_PASSWORD=os.environ["SF_ADMIN_PASSWORD"],
               PRODUCT_PROCESS_CHANNEL_TOKEN=channel["channel_token"],
               PRODUCT_PROCESS_CHANNEL_ID=str(channel["id"]), PRODUCT_PROCESS_CLIENT_REF=client_ref,
               PRODUCT_PROCESS_IDENTITY_TOKEN=identity_token,
               PRODUCT_PROCESS_OUTPUT=str(output))
    if privacy_markers:
        env["PRODUCT_PROCESS_PRIVACY_MARKERS"] = json.dumps(list(privacy_markers), ensure_ascii=False)
    return env


def _privacy_markers(root, private):
    """从受限运行字段生成去重标记，标记本身只经子进程环境传递。"""
    private_path = root / "private.json"
    values = read(private_path).get("sensitive_values", []) if private_path.exists() else []
    candidates = [*values, private.get("channel", {}).get("channel_token"), os.environ.get("SF_ADMIN_PASSWORD")]
    markers = tuple(dict.fromkeys(value for value in candidates if isinstance(value, str) and value))
    return markers


def _privacy_complete(output, required, expected_markers=()):
    """旧输出缺少脱敏浏览器快照时只读重采，不能把空标记当作通过。"""
    if not required:
        return output.exists()
    if not output.exists():
        return False
    bundles = read(output).get("bundles", [])
    privacy = bundles[0].get("privacy", {}) if bundles else {}
    import hashlib
    expected = sorted({hashlib.sha256(value.encode("utf-8")).hexdigest() for value in expected_markers})
    markers = privacy.get("marker_digests")
    snapshots = privacy.get("public_snapshots")
    required_sources = {"institution:dom", "institution:response", "chat:dom", "chat:response"}
    return (isinstance(markers, list) and markers
            and all(isinstance(value, str) and len(value) == 64 for value in markers)
            and len(markers) == len(set(markers))
            and bool(expected) and sorted(markers) == expected
            and privacy.get("marker_count") == len(markers) and isinstance(snapshots, list)
            and len(snapshots) >= len(required_sources)
            and {item.get("source") for item in snapshots if isinstance(item, dict)} == required_sources
            and all(isinstance(item, dict) and item.get("observed") is True
                    and item.get("kind") == item.get("source", "").split(":", 1)[-1]
                    and isinstance(item.get("content_sha256"), str) and len(item["content_sha256"]) == 64
                    and type(item.get("character_count")) is int and item["character_count"] >= 0
                    and type(item.get("contains_forbidden")) is bool for item in snapshots))


def _invoke(env, timeout=120, observe=None):
    # 不把浏览器异常正文送到公开报告；完整凭据不进入命令行或磁盘配置。
    with subprocess.Popen(["node", str(SCRIPT)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
        deadline = time.monotonic() + timeout
        try:
            while True:
                try:
                    process.communicate(timeout=min(3, max(.1, deadline - time.monotonic())))
                    break
                except subprocess.TimeoutExpired:
                    if observe:
                        observe()
                    if time.monotonic() >= deadline:
                        raise TimeoutError("product_browser_timeout")
            require(process.returncode == 0, "product_browser_failed_inspect_local_state")
        except BaseException:
            process.kill()
            process.communicate()
            raise


def prepare_browser_users(api, root, resources, batch):
    """先注册渠道用户，再为两端共享的会话使用其身份；未知创建不能盲重发。"""
    path = root / "browser-resources-private.json"
    state = read(path) if path.exists() else {"profiles": {}, "channel_agent_ids": []}
    parent_ids = [resources["cases"][cid]["agent_id"] for cid in ("D09", "D10", "D11", "D12")]
    child_ids = (resources["cases"]["D11"].get("team") or {}).get("child_agent_ids", [])
    agent_ids = list(dict.fromkeys([*parent_ids, *child_ids]))
    require(state["channel_agent_ids"] in ([], agent_ids), "browser_agent_binding_changed")
    if "channel" not in state:
        require(not state.get("creating_channel"), "browser_channel_creation_unknown")
        state["creating_channel"] = True
        save(path, state)
        channel = api.request("POST", "/channels", {"name": batch + " product browser", "channel_type": "web_widget"})
        require(channel.get("id") and channel.get("channel_token"), "browser_channel_response_incomplete")
        state.update(channel={"id": str(channel["id"]), "channel_token": channel["channel_token"]}, creating_channel=False)
        save(path, state)
    if not state["channel_agent_ids"]:
        require(not state.get("binding_channel"), "browser_channel_binding_unknown")
        state["binding_channel"] = True
        save(path, state)
        api.request("PUT", "/channels/agents", {"channel_id": state["channel"]["id"], "payload": [
            {"agent_id": int(aid), "is_default": index == 0, "priority": 100 - index, "traffic_percentage": 100}
            for index, aid in enumerate(agent_ids)]})
        state.update(channel_agent_ids=agent_ids, binding_channel=False)
        save(path, state)
    result = deepcopy(resources)
    for cid in ("D09", "D10", "D11", "D12"):
        profile = state["profiles"].setdefault(cid, {"client_ref": str(uuid.uuid4())})
        save(path, state)
        if "end_user_id" not in profile:
            output = root / f"browser-profile-{cid}-private.json"
            env = _environment(state["channel"], profile["client_ref"], output)
            env.update(PRODUCT_PROCESS_PREPARE_PROFILE="1", PRODUCT_PROCESS_AGENT_ID=result["cases"][cid]["agent_id"])
            _invoke(env)
            data = read(output)["profiles"][0]
            require(str(data["channel_id"]) == state["channel"]["id"], "browser_profile_channel_mismatch")
            profile["end_user_id"] = data["end_user_id"]
            profile["identity_token"] = data["identity_token"]
            save(path, state)
        result["cases"][cid]["user_id"] = profile["end_user_id"]
    return result


def recover_browser_turns(api, output, sid, turns, budget, observe):
    """意图没有回执时停止；有回执只查原 Run，未发送轮次可继续。"""
    import json
    from behavior_runner import read_run_status
    journal = output.with_suffix(output.suffix + ".journal.jsonl")
    rows = [json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines() if line.strip()] if journal.exists() else []
    recovered = []
    for index, turn in enumerate(turns):
        matches = [row for row in rows if row.get("session_id") == sid and row.get("turn_index") == index]
        if not matches:
            continue
        receipts = [row for row in matches if row.get("phase") == "receipt"]
        require(len(receipts) == 1, "browser_input_unknown_do_not_resend")
        receipt = receipts[0]
        # D10 首轮允许等待人工确认；委派等暂态不能作为消息轮次的完成证据。
        resumed = bool(turn.get("hitl_action"))
        waits_for_hitl = index + 1 < len(turns) and bool(turns[index + 1].get("hitl_action"))
        terminal_states = {"completed", "cancelled", "failed"}
        accepted_states = terminal_states | ({"waiting_hitl"} if waits_for_hitl else set())
        def belongs_to_turn(value):
            return not resumed or value.get("status") != "waiting_hitl" and value.get("event") != "hitl.required"
        terminals = [row for row in matches if row.get("phase") == "terminal"
                     and row.get("status") in accepted_states]
        terminals = [row for row in terminals if belongs_to_turn(row)]
        terminal = terminals[-1] if terminals else None
        if terminal is None:
            cursor = None
            persisted = []
            for _ in range(100):
                page = api.request("GET", query("/chat/events", session_id=sid, cursor=cursor, limit=500))
                persisted.extend({**row["data"], "event": row["event"], "event_id": row.get("event_id"),
                                  "source": "persistent_output_readback"}
                                 for row in page.get("events", []) if row.get("event") == "execution.completed"
                                 and row.get("data", {}).get("run_id") == receipt["run_id"]
                                 and row.get("data", {}).get("status") in accepted_states
                                 and belongs_to_turn({**row.get("data", {}), "event": row.get("event")}))
                next_cursor = page.get("next_cursor")
                if not next_cursor or next_cursor == cursor:
                    break
                cursor = next_cursor
            terminal = persisted[-1] if persisted else {**read_run_status(api, sid, receipt["run_id"]), "event": "run.status"}
        cancel_next = index + 1 < len(turns) and turns[index + 1].get("cancel") is True
        deadline = time.monotonic() + min(540, max(1, budget.remaining()))
        while terminal.get("status") not in accepted_states and not cancel_next:
            require(time.monotonic() < deadline, "browser_original_run_not_terminal")
            observe()
            time.sleep(1)
            terminal = read_run_status(api, sid, receipt["run_id"])
        recovered.append({"turn_index": index, "receipt": receipt, "terminal": terminal})
    return recovered


def execute_browser_case(api, case, resource, state, folder, root, budget, monitor):
    private = read(root / "browser-resources-private.json")
    privacy_markers = _privacy_markers(root, private)
    cid, sid = case["id"], state["session_id"]
    output = folder / "browser-bundle.json"
    privacy_output = folder / "privacy-readback.json"
    scenario = folder / "browser-scenario.json"
    turns = [{"text": task_message(case, index, resource)} for index in range(len(case["turns"]))]
    if cid == "D10":
        turns[1] = {"hitl_action": "确认"}
    if cid == "D12":
        turns.append({"cancel": True})
    # 整个场景只预记一次预算；恢复只允许发送没有 intent 的后续轮次。
    if not state.get("browser_dispatch_intent"):
        for _ in turns:
            budget.before_turn()
        save(root / "budget.json", budget.state())
        state["browser_dispatch_intent"] = True
        save(folder / "state.json", state)
        save(scenario, {"sessions": [{"case_id": cid, "session_id": sid, "end_user_id": resource["user_id"],
                                      "agent_id": resource["agent_id"], "turns": turns}]})

    def observe():
        usage = collect_product_usage(api, sid)
        try:
            budget.observe(sid, usage)
            require(not monitor.reasons, "product_configuration_changed")
        except Exception:
            journal = output.with_suffix(output.suffix + ".journal.jsonl")
            if journal.exists():
                import json
                rows = [json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines() if line.strip()]
                for row in reversed(rows):
                    if row.get("run_id"):
                        cancel(api, sid, row["run_id"])
                        break
            raise

    try:
        primary_capture = not output.exists()
        privacy_required = cid == "D12" and bool(privacy_markers)
        privacy_readback = privacy_required and not _privacy_complete(output, True, privacy_markers)
        if primary_capture or (privacy_readback and not _privacy_complete(privacy_output, True, privacy_markers)):
            target_output = output if primary_capture else privacy_output
            if primary_capture:
                recovered = recover_browser_turns(api, output, sid, turns, budget, observe)
                value = read(scenario)
                value["sessions"][0]["recovered_turns"] = recovered
                save(scenario, value)
            require(private["profiles"][cid].get("identity_token"), "browser_profile_credential_missing_reconcile_do_not_restore_by_ref")
            env = _environment(private["channel"], private["profiles"][cid]["client_ref"], target_output, privacy_markers,
                               private["profiles"][cid]["identity_token"])
            env.update(PRODUCT_PROCESS_SESSIONS=str(scenario),
                       PRODUCT_PROCESS_RUN_TIMEOUT_MS=str(int(min(540, max(1, budget.remaining())) * 1000)))
            if not primary_capture:
                env["PRODUCT_PROCESS_READ_ONLY"] = "1"
                env["PRODUCT_PROCESS_PRIVACY_SAMPLE_SOURCE"] = "privacy-readback"
            _invoke(env, timeout=min(1620, max(1, budget.remaining())), observe=observe)
        require(output.exists(), "browser_dispatch_outcome_unknown_use_readonly_recovery")
    finally:
        # 浏览器失败也须结算已发请求的尾账，不因异常跳过用量。
        budget.observe(sid, collect_product_usage(api, sid))
        save(root / "budget.json", budget.state())
    bundle = read(output)["bundles"][0]
    require(bundle["session"]["session_id"] == sid, "browser_bundle_session_mismatch")
    if privacy_required and not _privacy_complete(output, True, privacy_markers):
        require(_privacy_complete(privacy_output, True, privacy_markers), "privacy_readback_outcome_unknown")
        privacy_bundle = read(privacy_output)["bundles"][0]
        require(privacy_bundle.get("session", {}).get("session_id") == sid, "privacy_readback_session_mismatch")
        bundle = {**bundle, "privacy": {**privacy_bundle["privacy"], "sample_source": "privacy-readback"}}
    save(folder / "browser.json", bundle["browser"])
    terminals = bundle.get("terminal_facts", [])
    journal = output.with_suffix(output.suffix + ".journal.jsonl")
    if journal.exists():
        import json
        terminals += [json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines()
                      if line.strip() and json.loads(line).get("phase") == "terminal"]
    for receipt in bundle.get("network_requests", []):
        if receipt.get("kind") not in {"message", "hitl"}:
            continue
        index = str(receipt["turn_index"])
        candidates = [t for t in terminals if t.get("run_id") == receipt["run_id"]]
        if cid == "D10":
            wanted = "waiting_hitl" if receipt["kind"] == "message" else "completed"
            candidates = [t for t in candidates if t.get("status") == wanted or wanted == "waiting_hitl" and t.get("event") == "hitl.required"]
        terminal = candidates[-1] if candidates else {}
        if not terminal:
            from behavior_runner import read_run_status
            terminal = read_run_status(api, sid, receipt["run_id"])
        if terminal.get("event") == "hitl.required":
            terminal = {**terminal, "status": "waiting_hitl"}
        state["turns"][index] = {"client_message_id": receipt["input_id"], "state": "observed",
                                "result": {"terminal": {**terminal, "run_id": receipt["run_id"]}, "browser": True}}
    save(folder / "state.json", state)
    observed = state["turns"]
    ordered = [observed.get(str(index)) for index in range(len(observed))]
    stopped_on_failure = (0 < len(ordered) < len(case["turns"]) and all(ordered)
                          and ordered[-1]["result"]["terminal"].get("status") in {"failed", "cancelled"})
    # 已知前置失败允许进入评估，后续断言仍缺证据/失败；未知派发继续阻断。
    require(len(observed) == len(case["turns"]) or stopped_on_failure, "browser_inputs_incomplete")
    return bundle
