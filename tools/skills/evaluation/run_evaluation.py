"""通过真实 SkillForge Agent 执行冻结评测，保存受理、触发、成果和用量事实。"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]
TERMINAL = {"completed", "failed", "cancelled", "terminated"}
PAUSED = {"suspended", "waiting_hitl", "outcome_unknown"}
ACTIVATION_SCHEMA = "skillforge.runtime.session.skill-activation.v1"
ASSERTIONS = {"answer_contains", "answer_not_contains", "deliverable_format", "deliverable_text_contains", "tool_call", "runtime_status"}


def now():
    return datetime.now(timezone.utc).isoformat()


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def save(path, value):
    """同目录替换避免中断产生半个回执；网络写前必须先落盘意图。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def origin(value):
    url = urllib.parse.urlparse(value)
    if url.username or url.password or url.query or url.fragment or url.path not in ("", "/"):
        raise ValueError("服务地址只能是无凭据的 origin")
    if url.scheme != "https" and not (url.scheme == "http" and url.hostname in ("127.0.0.1", "localhost", "::1")):
        raise ValueError("服务须使用 HTTPS 或本机开发 HTTP")
    return value.rstrip("/")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self, institution, platform=None):
        self.institution = origin(institution)
        self.platform = origin(platform) if platform else None
        self.token = os.environ.get("SKILL_EVAL_INSTITUTION_TOKEN")
        self.platform_token = os.environ.get("SKILL_EVAL_PLATFORM_TOKEN")
        if not self.token:
            raise ValueError("缺少 SKILL_EVAL_INSTITUTION_TOKEN；工具不保存或输出凭据")

    def request(self, route, method="GET", body=None, platform=False):
        base = self.platform if platform else self.institution
        token = self.platform_token if platform else self.token
        if not base or not token:
            raise ValueError("平台观测需要 platform origin 和 SKILL_EVAL_PLATFORM_TOKEN")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        headers["X-Platform-Token" if platform else "Authorization"] = "Bearer " + token
        request = urllib.request.Request(base + "/api/v1" + route, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body, ensure_ascii=False).encode())
        try:
            # 写请求失败不能自动重试，保留本地 unknown 回执用于查询核对。
            with urllib.request.build_opener(NoRedirect).open(request, timeout=45) as response:
                value = json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"HTTP_{error.code} {method} {route.split('?')[0]}") from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            raise RuntimeError(f"OUTCOME_UNKNOWN {method} {route.split('?')[0]}") from None
        if value.get("code") != "OK":
            raise RuntimeError("API_REJECTED " + route.split("?")[0])
        return value.get("data")

    def read_bytes(self, url):
        """正式下载票据可指向 OSS，但禁止跳转、凭据 URL 和无限响应。"""
        parsed = urllib.parse.urlparse(url)
        if parsed.username or parsed.password or parsed.scheme not in ("https", "http"):
            raise ValueError("下载地址不合法")
        if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
            raise ValueError("远程下载必须使用 HTTPS")
        with urllib.request.build_opener(NoRedirect).open(url, timeout=45) as response:
            result = response.read(32 * 1024 * 1024 + 1)
        if len(result) > 32 * 1024 * 1024:
            raise ValueError("单个验收文件超过 32 MiB")
        return result


def validate_suite(suite):
    if not isinstance(suite, dict) or not isinstance(suite.get("variants"), list) or not isinstance(suite.get("cases"), list):
        raise ValueError("suite 必须含 variants、cases 数组")
    origin(suite["institution_origin"])
    if suite.get("platform_origin"):
        origin(suite["platform_origin"])
    if any(not str(suite.get(k, "")).isdigit() or int(suite[k]) <= 0 for k in ("tenant_id", "end_user_id")):
        raise ValueError("tenant_id、end_user_id 必须是明确标识")
    for group in (suite["variants"], suite["cases"]):
        names = [item.get("id") for item in group]
        if not 1 <= len(names) <= 50 or len(set(names)) != len(names) or any(not isinstance(n, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", n) for n in names):
            raise ValueError("case/variant id 必须是唯一安全名称，数量为 1 至 50")
    for variant in suite["variants"]:
        if not str(variant.get("agent_id", "")).isdigit() or int(variant["agent_id"]) <= 0:
            raise ValueError("variant 必须指定真实 agent_id")
        if not isinstance(variant.get("expected_skill_versions", {}), dict):
            raise ValueError("expected_skill_versions 必须为 definition_id→version_id 对象")
        if variant.get("kind", "candidate") not in ("candidate", "baseline"):
            raise ValueError("kind 必须为 candidate 或 baseline")
    for case in suite["cases"]:
        if not isinstance(case.get("prompt"), str) or not case["prompt"].strip():
            raise ValueError("case 必须提供 prompt")
        if not isinstance(case.get("assertions"), list) or not case["assertions"]:
            raise ValueError("case 必须包含可观察断言，不能只由 Agent 自称成功")
        for assertion in case["assertions"]:
            if assertion.get("type") not in ASSERTIONS:
                raise ValueError("assertion type/value 不合法")
            if assertion["type"] == "tool_call":
                if not isinstance(assertion.get("tool_name"), str) or not assertion["tool_name"]:
                    raise ValueError("tool_call 必须指定工具目录中的真实 tool_name")
                if not isinstance(assertion.get("arguments", {}), dict) or not isinstance(assertion.get("result", {}), dict):
                    raise ValueError("tool_call arguments/result 必须为对象")
                for key in ("arguments_absent_paths", "result_contains"):
                    if not isinstance(assertion.get(key, []), list) or any(not isinstance(x, str) or not x for x in assertion.get(key, [])):
                        raise ValueError("tool_call 路径和结果文本必须是非空字符串数组")
                minimum, maximum = assertion.get("min_calls", 1), assertion.get("max_calls", 1)
                if any(isinstance(x, bool) or not isinstance(x, int) or x < 0 for x in (minimum, maximum)) or maximum < minimum:
                    raise ValueError("tool_call 次数范围不合法")
                if "max_executions" in assertion and (type(assertion["max_executions"]) is not int or assertion["max_executions"] < 0):
                    raise ValueError("max_executions 必须是非负整数")
            elif not isinstance(assertion.get("value"), str) or not assertion["value"]:
                raise ValueError("assertion type/value 不合法")
        if not isinstance(case.get("should_activate"), bool):
            raise ValueError("should_activate 必须明确为布尔值")
        if not str(case.get("target_skill_definition_id", "")).isdigit():
            raise ValueError("case 必须指定 target_skill_definition_id")
    repetitions = suite.get("repetitions", 1)
    if isinstance(repetitions, bool) or not isinstance(repetitions, int) or not 1 <= repetitions <= 10:
        raise ValueError("repetitions 必须为 1 至 10")
    if len(suite["variants"]) * len(suite["cases"]) * repetitions > 500:
        raise ValueError("每个冻结批次最多 500 次执行")
    return suite


def agent_snapshot(api, variant):
    aid = str(variant["agent_id"])
    agent = api.request("/agents/detail?" + urllib.parse.urlencode({"agentId": aid}))
    mounts = api.request("/agents/capability-mounts?" + urllib.parse.urlencode({"agentId": aid}))["mounts"]
    if agent["status"] != "published" or agent.get("has_draft_changes") or not agent.get("published_revision"):
        raise ValueError("评测只使用已发布不可变 Agent，工具不创建或发布 Agent")
    published = api.request("/agents/revisions/detail?" + urllib.parse.urlencode({"agentId": aid, "revision": agent["published_revision"]}))
    # revision 查询的 published_at 是展示元信息；执行配置、修订和挂载仍逐值核对。
    execution = lambda value: {k: v for k, v in value.items() if k != "published_at"}
    if execution(published) != execution(agent) or mounts != published["mounts"]:
        raise ValueError("当前 Agent 与已发布修订或挂载不一致")
    mounted = {str(m["capability_definition_id"]): str(m["capability_version_id"]) for m in mounts if m["capability_type"] == "SKILL" and m["status"] == "ACTIVE"}
    if mounted != {str(k): str(v) for k, v in variant.get("expected_skill_versions", {}).items()}:
        raise ValueError("Agent 的完整 Skill 挂载集合与 expected_skill_versions 不一致")
    return {"agent": agent, "published": published, "mounts": sorted(mounts, key=lambda m: m["mount_id"])}


def freeze(api, suite, output):
    document = {"suite": suite, "suite_sha256": digest(suite), "agents": {}}
    for variant in suite["variants"]:
        document["agents"][variant["id"]] = agent_snapshot(api, variant)
    # 比较版本时固定模型、提示和非 Skill 工具配置，避免把执行条件差异算成 Skill 收益。
    controls = []
    for snapshot in document["agents"].values():
        agent = snapshot["agent"]
        mounts = [{k: m.get(k) for k in ("capability_type", "capability_definition_id", "capability_version_id", "priority", "status", "tool_config", "workflow_config", "knowledge_config")}
                  for m in snapshot["mounts"] if m["capability_type"] != "SKILL"]
        controls.append({"model_config_id": agent.get("model_config_id"), "system_prompt": agent.get("system_prompt"),
                         "config": agent.get("config"), "non_skill_mounts": sorted(mounts, key=lambda m: str(m["capability_definition_id"]))})
    if any(value != controls[0] for value in controls[1:]):
        raise ValueError("评测变体的模型、提示、配置或非 Skill 挂载不同，不能比较 Skill 收益")
    path = output / "frozen.json"
    if path.exists():
        existing = load(path)
        if document != {k: v for k, v in existing.items() if k != "frozen_at"}:
            raise ValueError("冻结输入或 Agent 已变化，请使用新的证据目录")
        return existing
    document["frozen_at"] = now()
    save(path, document)
    return document


def select(frozen, variant_id, case_id, repeat):
    suite = frozen["suite"]
    variant = next((v for v in suite["variants"] if v["id"] == variant_id), None)
    case = next((c for c in suite["cases"] if c["id"] == case_id), None)
    if not variant or not case or not 0 <= repeat < suite.get("repetitions", 1):
        raise ValueError("variant、case、repeat 不属于冻结批次")
    return variant, case


def run_directory(output, variant, case, repeat):
    return output / "runs" / variant["id"] / case["id"] / str(repeat)


def submit(api, frozen, output, variant, case, repeat):
    directory = run_directory(output, variant, case, repeat)
    path = directory / "receipt.json"
    if path.exists():
        receipt = load(path)
        if receipt.get("suite_sha256") != frozen["suite_sha256"] or receipt.get("session_request") != {
            "agent_id": str(variant["agent_id"]), "end_user_id": str(frozen["suite"]["end_user_id"])}:
            raise ValueError("运行回执不属于当前冻结套件和 Agent")
        if receipt["state"] != "submitted":
            raise ValueError("未知受理禁止自动重发；先查询/人工核对已有 session 和 client_message_id")
        return receipt
    if agent_snapshot(api, variant) != frozen["agents"][variant["id"]]:
        raise ValueError("评测 Agent 在冻结后变化")
    session_request = {"agent_id": str(variant["agent_id"]), "end_user_id": str(frozen["suite"]["end_user_id"])}
    receipt = {"state": "creating_session", "suite_sha256": frozen["suite_sha256"], "session_request": session_request, "started_at": now()}
    save(path, receipt)
    session = api.request("/chat/sessions", "POST", session_request)
    body = {"session_id": session["session_id"], "agent_id": str(variant["agent_id"]), "client_message_id": str(uuid.uuid4()),
            "content": [{"type": "text", "text": case["prompt"]}], "context": {"end_user_id": str(frozen["suite"]["end_user_id"])}}
    receipt.update(state="submitting", request=body)
    save(path, receipt)
    receipt.update(state="submitted", response=api.request("/chat", "POST", body), accepted_at=now())
    save(path, receipt)
    return receipt


def paginated(api, route, params, platform=False):
    pages, cursor = [], None
    for _ in range(100):
        query = dict(params, **({"cursor": cursor} if cursor else {}))
        page = api.request(route + "?" + urllib.parse.urlencode(query), platform=platform)
        pages.append(page)
        next_cursor = page.get("next_cursor")
        if not next_cursor:
            return pages
        if next_cursor == cursor:
            raise ValueError("分页 cursor 未前进")
        cursor = next_cursor
    raise ValueError("观测超过 100 页，结果未完整，不能用于评分")


def collect(api, frozen, output, variant, case, repeat, wait):
    directory = run_directory(output, variant, case, repeat)
    receipt = load(directory / "receipt.json")
    if receipt["state"] != "submitted" or not receipt.get("response", {}).get("execution_id"):
        raise ValueError("缺少确定受理回执，禁止推断执行标识")
    sid, run_id = receipt["request"]["session_id"], receipt["response"]["execution_id"]
    started = time.monotonic()
    while True:
        status = api.request("/chat/runs/status?" + urllib.parse.urlencode({"session_id": sid, "run_id": run_id}))
        save(directory / "status.json", status)
        if status["status"] in TERMINAL | PAUSED or time.monotonic() - started >= wait:
            break
        time.sleep(min(3, max(0, wait - (time.monotonic() - started))))
    history = api.request("/chat/history?" + urllib.parse.urlencode({"session_id": sid}))
    save(directory / "history.json", history)
    items = api.request("/chat/sessions/deliverables?" + urllib.parse.urlencode({"sessionId": sid, "includeHistory": "true"}))
    deliverables = [item for item in items if run_id in item.get("result_run_ids", [])]
    save(directory / "deliverables.json", deliverables)
    downloads = []
    for item in deliverables:
        if item["status"] != "ready":
            continue
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", item["id"]):
            raise ValueError("正式成果修订标识不合法")
        for entry in item.get("files", []):
            relative = PurePosixPath(entry["path"])
            if relative.is_absolute() or ".." in relative.parts or "\\" in entry["path"] or ":" in entry["path"]:
                raise ValueError("正式成果包含不安全路径")
            ticket = api.request("/chat/deliverables/download-ticket", "POST", {"id": item["id"], "file_id": entry["file_id"]})
            content = api.read_bytes(ticket["url"])
            if len(content) != entry["size"] or hashlib.sha256(content).hexdigest() != entry["sha256"]:
                raise ValueError("下载字节与正式成果摘要不一致")
            path = directory / "files" / item["id"] / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            downloads.append({"path": path.relative_to(directory).as_posix(), "format": entry.get("format"), "sha256": entry["sha256"], "size": entry["size"]})
    save(directory / "downloads.json", downloads)
    observation = {"availability": "UNKNOWN", "reason": "未提供平台观测凭据", "activation_events": [], "usage": None}
    if api.platform and api.platform_token:
        params = {"tenant_id": frozen["suite"]["tenant_id"], "session_id": sid, "root_input_id": receipt["response"]["input_id"], "page_size": 100}
        traces = paginated(api, "/platform/sessions/trace", params, platform=True)
        save(directory / "trace.json", traces)
        activation_events = []
        for page in traces:
            for event in page.get("items", []):
                if event.get("run_id") != run_id or not str(event.get("event_id", "")).startswith("skill-activation:"):
                    continue
                value = api.request("/platform/sessions/trace/content?" + urllib.parse.urlencode({k: v for k, v in params.items() if k != "page_size"} | {"content_ref": event["content_ref"]}), platform=True)
                if value.get("schema_id") == ACTIVATION_SCHEMA and value.get("availability") == "FULL":
                    activation_events.append({"event_id": event["event_id"], "value": value["value"]})
                else:
                    raise ValueError("激活事件正文不可完整读取，不能推断未触发")
        usage = paginated(api, "/platform/sessions/usage", params, platform=True)
        save(directory / "usage.json", usage)
        events = [event for page in traces for event in page.get("items", []) if event.get("run_id") == run_id]
        start_times = [datetime.fromisoformat(e["occurred_at"].replace("Z", "+00:00")) for e in events if e.get("event_type") == "run.started"]
        finish_times = [datetime.fromisoformat(e["occurred_at"].replace("Z", "+00:00")) for e in events if e.get("event_type") in {"run." + s for s in TERMINAL}]
        runtime_seconds = (max(finish_times) - min(start_times)).total_seconds() if start_times and finish_times else None
        observation = {"availability": "FULL", "activation_events": activation_events, "usage": usage[0], "runtime_elapsed_seconds": runtime_seconds}
    save(directory / "observation.json", observation)
    if any(a["type"] == "tool_call" for a in case["assertions"]):
        params = {"session_id": sid, "root_input_id": receipt["response"]["input_id"]}
        pages = paginated(api, "/chat/trajectories/nodes", params | {"page_size": 100})
        nodes = [node for page in pages for node in page.get("items", []) if node.get("run_id") == run_id]
        contents = {}
        # 只读取当前 Run 的模型请求和实际工具结果，保留原 ref 与 action 身份。
        for node in nodes:
            for direction in (("input", "output") if node["kind"] in ("LLM", "TOOL") else ()):
                ref = node.get(direction, {}).get("ref")
                if ref:
                    contents[ref] = api.request("/chat/trajectories/content?" + urllib.parse.urlencode(params | {"content_ref": ref}))
        save(directory / "trajectory.json", {"run_id": run_id, "nodes": nodes, "contents": contents})
        billing = api.request("/billing/traces/detail?" + urllib.parse.urlencode({"root_input_id": receipt["response"]["input_id"]}))
        save(directory / "billing_trace.json", billing)
        observation["institution_usage_summary"] = billing.get("usage_summary")
        save(directory / "observation.json", observation)
    assessment = assess(frozen, variant, case, receipt, status, history, deliverables, downloads, observation, directory)
    save(directory / "assessment.json", assessment)
    return assessment


def json_subset(expected, actual):
    """对象为子集，数组按位置精确长度；数值和布尔值不混同。"""
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(k in actual and json_subset(v, actual[k]) for k, v in expected.items())
    if isinstance(expected, list):
        return isinstance(actual, list) and len(expected) == len(actual) and all(json_subset(a, b) for a, b in zip(expected, actual))
    return type(expected) is type(actual) and expected == actual


def tool_check(assertion, trajectory):
    """绑定真实函数目录、call_id 反馈和实际 TOOL 终态，不采用模型自述。"""
    if not trajectory:
        return {"passed": False, "availability": "UNKNOWN", "reason": "missing_trajectory"}
    nodes, contents = trajectory["nodes"], trajectory["contents"]
    def read(node, direction):
        value = contents.get(node.get(direction, {}).get("ref"), {})
        if value.get("availability") != "FULL":
            return None
        return json.loads(value["value"]) if isinstance(value["value"], str) else value["value"]
    requests, origins, outcome_calls, unknown_model_outcomes = [], {}, {}, []
    for node in sorted(nodes, key=lambda n: int(n["sequence"])):
        if node["kind"] == "LLM":
            value = read(node, "input")
            if value is None:
                return {"passed": False, "availability": "UNKNOWN", "reason": "model_request_unavailable"}
            requests.append(value)
            outcome = read(node, "output")
            if outcome:
                turns = outcome.get("assistant_turn")
                if turns is None:
                    unknown_model_outcomes.append({"action_id": node.get("action_id"), "status": outcome.get("status"), "reason_code": outcome.get("reason_code")})
                    continue
                for turn in turns:
                    if turn.get("call_id"):
                        origins[turn["call_id"]] = node["iteration"]
                        outcome_calls[turn["call_id"]] = {"call_id": turn["call_id"], "wire_name": turn["wire_name"], "arguments": turn["arguments_json"]}
    if not requests:
        return {"passed": False, "availability": "UNKNOWN", "reason": "no_model_requests"}
    names = {}
    for value in requests:
        for tool in value.get("tools", []):
            match = re.search(r'^logical_name=(".*")$', tool.get("description", ""), re.MULTILINE)
            if match:
                name = json.loads(match[1])
                if tool["wire_name"] in names and names[tool["wire_name"]] != name:
                    return {"passed": False, "availability": "FULL", "reason": "conflicting_tool_identity"}
                names[tool["wire_name"]] = name
    calls, feedback = dict(outcome_calls), {}
    for request in requests:
        local_calls = {call["call_id"]: call for m in request.get("messages", []) for call in m.get("tool_calls", [])}
        for call_id, call in local_calls.items():
            identity = {key: call[key] for key in ("call_id", "wire_name", "arguments")}
            if call_id in calls and calls[call_id] != identity:
                return {"passed": False, "availability": "FULL", "reason": "conflicting_call_identity", "call_id": call_id}
            calls[call_id] = identity
        for message in request.get("messages", []):
            if message["role"] == "tool" and message["tool_call_id"] in local_calls:
                # 后续压缩可改变展示正文；采用首次闭合投影，实际结果仍从 action 读取。
                feedback.setdefault(message["tool_call_id"], message)
    candidates = [call for call in calls.values() if names.get(call["wire_name"]) == assertion["tool_name"]]
    physical = [n for n in nodes if n["kind"] == "TOOL" and names.get(n["name"]) == assertion["tool_name"]]
    matched, evidence, failure_reasons = [], [], []
    for call in candidates:
        arguments = json.loads(call["arguments"])
        absent = True
        for path in assertion.get("arguments_absent_paths", []):
            current = arguments
            for key in path.split("."):
                if isinstance(current, dict) and key in current:
                    current = current[key]
                elif isinstance(current, list) and key.isdigit() and int(key) < len(current):
                    current = current[int(key)]
                else:
                    break
            else:
                absent = False
        if not json_subset(assertion.get("arguments", {}), arguments) or not absent:
            continue
        result_message = feedback.get(call["call_id"])
        if result_message is None:
            return {"passed": False, "availability": "UNKNOWN", "reason": "unpaired_tool_call", "call_id": call["call_id"]}
        # 一次实际调用最多消耗一个节点；失败复用保留失败，不能误作缺失证据。
        result_node = next((n for n in physical if n["iteration"] == origins.get(call["call_id"]) and read(n, "input") == arguments), None)
        result = read(result_node, "output") if result_node else None
        actual_status = result_node["status"] if result_node else None
        if result_node:
            physical.remove(result_node)
        reused = result_message.get("content", "").startswith("本次工具与参数和之前的调用相同，以下复用已有结果，未重新执行工具。")
        if result is None and reused:
            original = next((item for item in evidence if item["arguments"] == arguments), None)
            if original:
                result, actual_status = original["result"], original["actual_status"]
        known_failure = actual_status in {"FAILED", "CANCELLED", "TERMINATED", "REJECTED"}
        mcp_error = isinstance(result, dict) and result.get("isError") is True
        if known_failure or mcp_error:
            failure_reasons.append("actual_tool_failed" if known_failure else "mcp_tool_error")
            matched.append(False)
            evidence.append({"call_id": call["call_id"], "arguments": arguments, "action_id": result_node["action_id"] if result_node else None,
                             "reused": reused, "actual_status": actual_status, "result": result})
            continue
        if result is None:
            return {"passed": False, "availability": "UNKNOWN", "reason": "missing_actual_tool_result", "call_id": call["call_id"]}
        texts = "\n".join(p.get("text", "") for p in result.get("content", []) if p.get("type") == "text")
        structured = result.get("structuredContent")
        result_source = "structuredContent"
        if structured is None:
            parts = result.get("content", [])
            result_source = "single_text_json"
            if len(parts) == 1 and parts[0].get("type") == "text":
                try:
                    structured = json.loads(parts[0]["text"])
                except ValueError:
                    structured = None
            if not isinstance(structured, dict):
                if assertion.get("result"):
                    return {"passed": False, "availability": "UNKNOWN", "reason": "ambiguous_or_missing_structured_result", "call_id": call["call_id"]}
                structured = {}
        texts += "\n" + json.dumps(structured, ensure_ascii=False)
        pending = [structured]
        truncated = False
        while pending:
            item = pending.pop()
            if isinstance(item, dict):
                truncated = truncated or item.get("truncated") is True
                pending.extend(item.values())
            elif isinstance(item, list):
                pending.extend(item)
        passed = result.get("isError") is False and result.get("resultType") == "complete" and not truncated and json_subset(assertion.get("result", {}), structured)
        passed = passed and all(text in texts for text in assertion.get("result_contains", []))
        matched.append(passed)
        evidence.append({"call_id": call["call_id"], "arguments": arguments, "action_id": result_node["action_id"] if result_node else None, "reused": reused, "actual_status": actual_status, "result_source": result_source, "result": result})
    executions = sum(n["kind"] == "TOOL" and names.get(n["name"]) == assertion["tool_name"] for n in nodes)
    passed = assertion.get("min_calls", 1) <= len(matched) <= assertion.get("max_calls", 1) and all(matched)
    if "max_executions" in assertion:
        passed = passed and executions <= assertion["max_executions"]
    uncertain = bool(unknown_model_outcomes) and not failure_reasons
    return {"passed": passed and not uncertain, "availability": "UNKNOWN" if uncertain else "FULL",
            "matching_calls": len(matched), "requested_calls": len(candidates), "executions": executions,
            "reason": failure_reasons[0] if failure_reasons else "model_outcome_unknown" if uncertain else None,
            "unknown_model_outcomes": unknown_model_outcomes, "evidence": evidence}


def assess(frozen, variant, case, receipt, status, history, deliverables, downloads, observation, directory):
    run_id = receipt["response"]["execution_id"]
    activities = [a for a in history.get("activities", []) if a.get("node_id") == run_id]
    answer = "\n".join(a.get("output", "") or "" for a in activities)
    checks = []
    for assertion in case["assertions"]:
        kind, value = assertion["type"], assertion.get("value")
        if kind == "tool_call":
            path = directory / "trajectory.json"
            trajectory = load(path) if path.exists() else None
            result = tool_check(assertion, trajectory if trajectory and trajectory["run_id"] == run_id else None)
            checks.append(dict(assertion, **result))
            continue
        if kind == "runtime_status":
            checks.append(dict(assertion, passed=status["status"] == value))
            continue
        if kind == "answer_contains":
            passed = value in answer
        elif kind == "answer_not_contains":
            passed = value not in answer
        elif kind == "deliverable_format":
            passed = any(d["format"] == value for d in downloads)
        else:
            texts = []
            for d in downloads:
                if Path(d["path"]).suffix.lower() in (".txt", ".md", ".json", ".html", ".py", ".yaml", ".yml"):
                    try:
                        texts.append((directory / d["path"]).read_text(encoding="utf-8"))
                    except UnicodeDecodeError:
                        pass
            passed = any(value in t for t in texts)
        checks.append(dict(assertion, passed=passed))
    mounted = next((m for m in frozen["agents"][variant["id"]]["mounts"] if m["capability_type"] == "SKILL" and str(m["capability_definition_id"]) == str(case["target_skill_definition_id"]) and m["status"] == "ACTIVE"), None)
    activated = None
    if observation["availability"] == "FULL":
        activated = False if mounted is None else any(
            result.get("handle") == "skill:" + mounted["mount_id"] and result.get("status") in ("ACTIVATED", "ALREADY_ACTIVE")
            for event in observation["activation_events"] for result in event["value"].get("results", []))
    terminal = status["status"] in TERMINAL
    always = mounted is not None and (mounted.get("skill_config") or {}).get("activation_mode") == "ALWAYS"
    expected_trigger = case["should_activate"] if mounted and not always else None
    usage = observation.get("usage")
    return {"variant": variant["id"], "case": case["id"], "run_id": run_id, "session_id": receipt["request"]["session_id"],
            "runtime_status": status["status"], "terminal": terminal, "success": terminal and status["status"] == "completed" and all(c["passed"] for c in checks),
            "assertions": checks, "expected_activation": expected_trigger, "activated": activated,
            "activation_basis": "ALWAYS uses resource tool evidence, not an activation-event assertion" if always else "explicit activation result",
            "trigger_correct": None if expected_trigger is None or activated is None or not terminal else expected_trigger == activated,
            "formal_files": len(downloads), "usage": None if usage is None else {"coverage": usage["coverage"], "totals": usage["totals"], "cache": usage["cache"]},
            "institution_usage_summary": observation.get("institution_usage_summary"),
            "elapsed_seconds": observation.get("runtime_elapsed_seconds"),
            "collection_elapsed_seconds": (datetime.fromisoformat(now()) - datetime.fromisoformat(receipt["accepted_at"])).total_seconds(),
            "timing_basis": "run.started_to_terminal_event; UNKNOWN without complete lifecycle trace", "observed_at": now(), "suite_sha256": frozen["suite_sha256"]}


def cancel(api, frozen, output, variant, case, repeat):
    directory = run_directory(output, variant, case, repeat)
    receipt = load(directory / "receipt.json")
    if receipt["state"] != "submitted" or not receipt.get("response", {}).get("execution_id"):
        raise ValueError("取消需要确定受理回执，未知受理先查询核对")
    body = {"session_id": receipt["request"]["session_id"], "run_id": receipt["response"]["execution_id"]}
    path = directory / "cancel.json"
    if path.exists():
        return load(path)
    value = {"state": "unknown", "request": body, "requested_at": now()}
    save(path, value)
    value.update(state="accepted", response=api.request("/chat/runs/cancel", "POST", body))
    save(path, value)
    return value


def report(frozen, output):
    assessments = []
    for variant in frozen["suite"]["variants"]:
        for case in frozen["suite"]["cases"]:
            for repeat in range(frozen["suite"].get("repetitions", 1)):
                path = run_directory(output, variant, case, repeat) / "assessment.json"
                if path.exists():
                    value = load(path)
                    if value["suite_sha256"] != frozen["suite_sha256"]:
                        raise ValueError("评测输入摘要不同")
                    if any(a["type"] == "tool_call" for a in case["assertions"]):
                        directory = path.parent
                        required = ("receipt", "status", "history", "deliverables", "downloads", "observation")
                        if any(not (directory / (name + ".json")).exists() for name in required):
                            raise ValueError("工具断言报告缺少原始采集证据，不能采用 assessment 成功摘要")
                        raw = [load(directory / (name + ".json")) for name in required]
                        value = assess(frozen, variant, case, *raw, directory)
                    assessments.append(value)
    variants = []
    for variant in frozen["suite"]["variants"]:
        runs = [r for r in assessments if r["variant"] == variant["id"]]
        completed = [r for r in runs if r["terminal"]]
        triggers = [r for r in runs if r["trigger_correct"] is not None]
        timings = [r["elapsed_seconds"] for r in completed if r["elapsed_seconds"] is not None]
        token_stats = {}
        for key in ("prompt_tokens", "completion_tokens", "cached_input_tokens"):
            reported = [r["usage"]["totals"].get(key) if r.get("usage") else (r.get("institution_usage_summary") or {}).get(key) for r in completed]
            values = [int(value) for value in reported if value is not None]
            token_stats[key] = {"reported_runs": len(values), "mean": statistics.mean(values) if values else None, "unknown_runs": len(completed) - len(values)}
        variants.append({"id": variant["id"], "kind": variant.get("kind", "candidate"), "observed_runs": len(runs), "terminal_runs": len(completed),
                         "successful_runs": sum(r["success"] for r in completed), "success_rate": sum(r["success"] for r in completed) / len(completed) if completed else None,
                         "trigger_evaluable_runs": len(triggers), "trigger_accuracy": sum(r["trigger_correct"] for r in triggers) / len(triggers) if triggers else None,
                         "false_positives": sum(r["activated"] and not r["expected_activation"] for r in triggers),
                         "false_negatives": sum(not r["activated"] and r["expected_activation"] for r in triggers),
                         "timing_reported_runs": len(timings), "elapsed_mean_seconds": statistics.mean(timings) if timings else None,
                         "elapsed_stdev_seconds": statistics.stdev(timings) if len(timings) > 1 else None, "tokens": token_stats})
    expected = len(frozen["suite"]["variants"]) * len(frozen["suite"]["cases"]) * frozen["suite"].get("repetitions", 1)
    manual_runs = sum(case.get("manual_review_required") is True for case in frozen["suite"]["cases"]) * len(frozen["suite"]["variants"]) * frozen["suite"].get("repetitions", 1)
    result = {"suite_sha256": frozen["suite_sha256"], "expected_runs": expected, "observed_runs": len(assessments),
              "success_scope": "automatic_assertions_only", "manual_review_required_runs": manual_runs,
              "complete": len(assessments) == expected and all(r["terminal"] for r in assessments), "variants": variants,
              "limits": ["样本内可观察断言，不代表通用质量或生产成功率", "elapsed 来自 Run 生命周期事件，缺失完整时序时为 UNKNOWN；不等于 Provider 首 Token 延迟", "用量 UNKNOWN 保留为空，不推算费用", "不自动修改 Skill 描述、Agent 挂载或已发布版本"], "generated_at": now()}
    save(output / "report.json", result)
    lines = ["# Skill 评测报告", "", "**本报告成功率仅指自动断言；不代表业务或完整语义验收通过。**", "",
             f"需要独立 semantic-review 的样本：{manual_runs}；须按冻结场景真值审阅回答与实际查询，不能用自动成功数量代替业务通过。", "",
             f"冻结输入：`{result['suite_sha256']}`", "", f"观测 {len(assessments)} / {expected} 次执行；批次完整：{result['complete']}。", "",
             "| 版本 | 终态样本 | 断言成功率 | 触发准确率 | 假阳性 | 假阴性 |", "|---|---:|---:|---:|---:|---:|"]
    for item in variants:
        rate = lambda v: "UNKNOWN" if v is None else f"{v:.1%}"
        lines.append(f"| {item['id']} | {item['terminal_runs']} | {rate(item['success_rate'])} | {rate(item['trigger_accuracy'])} | {item['false_positives']} | {item['false_negatives']} |")
    lines += ["", *["- " + note for note in result["limits"]], ""]
    (output / "report.md").write_text("\n".join(lines), encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("freeze", "submit", "collect", "cancel", "report"))
    parser.add_argument("--suite", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--variant")
    parser.add_argument("--case")
    parser.add_argument("--repeat", type=int, default=0)
    parser.add_argument("--wait", type=int, default=45)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to((ROOT / ".agent-work-state").resolve()) or output == (ROOT / ".agent-work-state").resolve():
        parser.error("证据目录必须是 .agent-work-state 内的独立子目录")
    if not 0 <= args.wait <= 60:
        parser.error("--wait 必须为 0 至 60 秒；超时后只查询原 Run")
    if args.action == "freeze":
        if not args.suite:
            parser.error("freeze 需要 --suite")
        suite = validate_suite(load(args.suite))
        value = freeze(Api(suite["institution_origin"], suite.get("platform_origin")), suite, output)
        print(json.dumps({"suite_sha256": value["suite_sha256"], "variants": list(value["agents"])}))
        return
    frozen = load(output / "frozen.json")
    if args.action == "report":
        print(json.dumps(report(frozen, output), ensure_ascii=False))
        return
    variant, case = select(frozen, args.variant, args.case, args.repeat)
    api = Api(frozen["suite"]["institution_origin"], frozen["suite"].get("platform_origin"))
    if args.action == "submit":
        value = submit(api, frozen, output, variant, case, args.repeat)
        print(json.dumps({"state": value["state"], "session_id": value["request"]["session_id"], "run_id": value["response"]["execution_id"]}))
    elif args.action == "collect":
        print(json.dumps(collect(api, frozen, output, variant, case, args.repeat, args.wait), ensure_ascii=False))
    else:
        print(json.dumps(cancel(api, frozen, output, variant, case, args.repeat), ensure_ascii=False))


if __name__ == "__main__":
    main()
