"""C02：同一 Session 的真实附件、代码核对、正式成果及修订；模型派发归调用方。"""
from __future__ import annotations

import csv
from decimal import Decimal
import hashlib
import io
import json
from pathlib import Path
import urllib.request
from urllib.parse import urljoin, urlsplit

from behavior_contract import require
from behavior_product_facts import _arguments, _tool_output
from behavior_product_runner import ensure_d08_attachments
from client import query
from runner import save


REPORT_NAME = "reconciliation-report.json"
PROFILE = {"key": "c02-artifact", "skills": [],
           "tools": ["code_execute", "list_artifacts", "read_context_reference"],
           "prompt": "只处理当前会话合成评估资料，不修改业务记录。通过代码读取真实附件，不复制CSV正文到input_files。"
                     "核对报告用代码生成reconciliation-report.json，再正式发布为可下载成果。"
                     "报告JSON字段为selected_ids、raw_total、unique_total、ledger_total、difference、duplicate_ids、"
                     "differences（id到orders减ledger差额）、source_sha256（orders/ledger文件摘要）。"
                     "总额使用JSON数字，id列表排序；差额表只含非零项。每次修订读取原附件并重新计算，"
                     "沿用同一成果身份创建新版，保留旧版；如实说明不能完成的步骤。"}
TURNS = ["用代码核对这两份附件，orders按id去重，与ledger逐项比较，生成可下载的对账报告。不要修改业务记录。",
         "修改报告，这次只统计A和B，排除C，保留上一版供查看。"]
CSV = {"orders": b"id,total\nA,100\nB,120\nB,120\nC,150\n",
       "ledger": b"id,total\nA,100\nB,120\nC,140\n"}


def oracle(contents, selected=None):
    """从固定输入字节独立用十进制计算，不消费模型答案或报告给出的预期。"""
    values, duplicates, raw = {}, [], Decimal(0)
    for ref in ("orders", "ledger"):
        rows = list(csv.DictReader(io.StringIO(contents[ref].decode("utf-8"))))
        require(rows and all(set(row) == {"id", "total"} for row in rows), "c02_fixture_columns_invalid")
        unique = {}
        for row in rows:
            if selected is not None and row["id"] not in selected:
                continue
            amount = Decimal(row["total"])
            if ref == "orders": raw += amount
            if row["id"] in unique:
                require(unique[row["id"]] == amount, "c02_fixture_conflicting_duplicate")
                if ref == "orders": duplicates.append(row["id"])
            unique[row["id"]] = amount
        values[ref] = unique
    require(set(values["orders"]) == set(values["ledger"]), "c02_fixture_ids_mismatch")
    totals = {ref: sum(rows.values(), Decimal(0)) for ref, rows in values.items()}
    return {"selected_ids": sorted(values["orders"]), "raw_total": int(raw),
            "unique_total": int(totals["orders"]), "ledger_total": int(totals["ledger"]),
            "difference": int(totals["orders"] - totals["ledger"]), "duplicate_ids": sorted(set(duplicates)),
            "differences": {key: int(value - values["ledger"][key]) for key, value in values["orders"].items()
                            if value != values["ledger"][key]},
            "source_sha256": {key: hashlib.sha256(value).hexdigest() for key, value in contents.items()}}


def provision_case(root, *, contents=None):
    """只落固定附件和供正式上传器使用的资源描述；不创建模型或调用。"""
    root = Path(root)
    bindings = []
    contents = CSV if contents is None else contents
    require(set(contents) == {"orders", "ledger"}, "c02_fixture_inputs_invalid")
    for ref, content in contents.items():
        path = root / "c02" / "inputs" / (ref + ".csv")
        path.parent.mkdir(parents=True, exist_ok=True)
        require(not path.exists() or path.read_bytes() == content, "c02_fixture_drift")
        path.write_bytes(content)
        bindings.append({"ref": ref, "kind": "csv", "path": path.relative_to(root).as_posix(),
                         "sha256": hashlib.sha256(content).hexdigest(), "size": len(content)})
    return {"material_bindings": bindings, "attachment_transport": {
        "source": "session_attachment", "refs": ["orders", "ledger"],
        "upload_endpoint": "/chat/sessions/attachments/upload", "list_endpoint": "/chat/sessions/attachments"}}


def _download(api, revision, file, destination=None):
    ticket = api.request("POST", "/chat/deliverables/download-ticket", {"id": revision["id"], "file_id": file["file_id"]})
    def local(value):
        parsed = urlsplit(value)
        require(parsed.scheme in {"http", "https"} and parsed.hostname in {"127.0.0.1", "localhost", "::1"}
                and parsed.username is None and parsed.password is None, "c02_download_url_rejected")
        return value
    class LocalRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return super().redirect_request(req, fp, code, msg, headers, local(newurl))
    with urllib.request.build_opener(LocalRedirect).open(local(urljoin(api.base, ticket["url"])), timeout=30) as response:
        content = response.read(1024 * 1024 + 1)
    require(len(content) <= 1024 * 1024 and len(content) == file["size"]
            and hashlib.sha256(content).hexdigest() == file["sha256"], "c02_download_digest_mismatch")
    report = json.loads(content.decode("utf-8"))
    require(isinstance(report, dict), "c02_report_not_object")
    if destination is not None:
        Path(destination).write_bytes(content)
    return {"report": report, "sha256": file["sha256"], "size": len(content)}


def collect_revision(api, session_id, run_id, folder):
    rows = api.request("GET", query("/chat/sessions/deliverables", sessionId=session_id, includeHistory="true"))
    matches = [(row, file) for row in rows if row.get("session_id") == session_id and row.get("run_id") == run_id
               and run_id in row.get("result_run_ids", []) and row.get("status") == "ready"
               and row.get("id") and row.get("deliverable_id") and type(row.get("revision")) is int and row["revision"] > 0
               for file in row.get("files", []) if file.get("path", "").split("/")[-1] == REPORT_NAME
               and file.get("file_id") and file.get("artifact_id")]
    require(len(matches) == 1, "c02_report_missing_or_ambiguous")
    revision, file = matches[0]
    result = {"revision": revision, "file": file,
              **_download(api, revision, file, Path(folder) / REPORT_NAME)}
    save(Path(folder) / "report-download.json", result)
    return result


def _code_handles(turn, code_handle):
    """稳定挂载身份只经同 Run 冻结 controls 映射到请求声明，不推断 opaque handle。"""
    events = turn.get("rich_events", [])
    handles = set()
    for event in events:
        if event.get("run_id") != turn["run_id"] or event.get("event_type") != "agent.context-snapshot": continue
        payload = event.get("payload") or {}
        tools = (payload.get("modelRequest") or {}).get("tools", [])
        suffix = event.get("event_id", "").removeprefix("model-context-snapshot:")
        controls = [row.get("payload") or {} for row in events
                    if row.get("run_id") == turn["run_id"] and row.get("event_type") == "agent.preparation-controls"
                    and row.get("event_id") == "preparation-controls:" + suffix]
        identities = controls[0].get("identities", {}) if len(controls) == 1 else {}
        for tool in tools:
            handle = tool.get("toolHandle")
            if not isinstance(handle, str) or tool.get("kind") != "TOOL_CALL": continue
            if code_handle is not None:
                if handle == code_handle or identities.get(handle) == code_handle: handles.add(handle)
            elif tool.get("wireName") == "code_execute" or tool.get("name") == "code_execute" \
                    or tool.get("qualifiedName", "").rsplit("/", 1)[-1] == "code_execute":
                handles.add(handle)
    # 明确提供的执行 handle 仍可直接使用；稳定身份须有冻结映射，不拼接 Run 后缀。
    if not handles and code_handle is not None:
        exact = {row.get("payload", {}).get("toolHandle") for row in events
                 if row.get("run_id") == turn["run_id"] and row.get("event_type") == "agent.tool-result"}
        if code_handle in exact: handles.add(code_handle)
    return handles if len(handles) == 1 else set()


def code_sources(turn, uploads, code_handle, report):
    """绑定真实成功调用、原附件元数据和生成报告的沙箱成果，拒绝报告自述来源替代执行事实。"""
    expected = [uploads[ref]["metadata"] for ref in ("orders", "ledger")]
    ids = {row["id"] for row in expected}
    handles = _code_handles(turn, code_handle)
    selected = report["file"]
    # artifact_inputs 是上游产物来源图；本次发布的文件身份来自正式 files。
    produced = {(row.get("artifact_id"), row.get("path")) for row in report["revision"].get("files", [])
                if all(row.get(key) == selected.get(key) for key in ("file_id", "artifact_id", "path"))}
    for event in turn.get("rich_events", []):
        payload = event.get("payload") or {}
        if event.get("event_type") != "agent.tool-result" or event.get("run_id") != turn["run_id"] \
                or payload.get("toolHandle") not in handles or payload.get("status") != "SUCCEEDED": continue
        args, output = _arguments(event), _tool_output(event)
        if not isinstance(args, dict) or not isinstance(output, dict): continue
        attached = args.get("attachment_ids")
        actual = output.get("attachment_inputs", [])
        if not isinstance(attached, list) or len(attached) != 2 or set(attached) != ids \
                or args.get("input_files") not in (None, {}) or output.get("success") is not True: continue
        if not isinstance(actual, list) or len(actual) != 2: continue
        if not all(any(all(row.get(key) == source.get(key) for key in ("id", "name", "input_path", "sha256", "size"))
                           for row in actual if isinstance(row, dict)) for source in expected): continue
        artifacts = output.get("artifacts", [])
        generated = {(row.get("id"), path) for row in artifacts for path in row.get("paths", [])
                     if Path(path).name == REPORT_NAME}
        if generated & produced:
            return {"verified": True, "event_id": event.get("event_id"),
                    "artifact_ids": sorted({artifact_id for artifact_id, _ in generated & produced})}
    return {"verified": False, "reason": "c02_attachment_code_report_source_unproven"}


def _matches_report(report, expected):
    return isinstance(report, dict) and all(report.get(key) == value
        and (type(report.get(key)) in (int, float) if type(value) is int else True)
        for key, value in expected.items())


def validate_case(evidence, *, contents=None):
    """只比较已收集事实，缺证不填绿；不调用模型或网络。"""
    contents = CSV if contents is None else contents
    turns = evidence.get("turns", [])
    if len(turns) != 2: return {"status": "UNKNOWN", "reason": "c02_two_turn_evidence_missing"}
    for index, turn in enumerate(turns):
        if str(turn.get("status", "")).upper() != "COMPLETED": return {"status": "FAILED", "reason": "c02_run_not_completed"}
        report = turn.get("artifact")
        if not report or not turn.get("code_source", {}).get("verified"):
            return {"status": "UNKNOWN", "reason": "c02_source_evidence_missing"}
        expected = oracle(contents, None if index == 0 else {"A", "B"})
        if not _matches_report(report["report"], expected):
            return {"status": "FAILED", "reason": "c02_numeric_or_source_oracle_mismatch", "turn": index}
        if report["revision"].get("session_id") != evidence["session_id"] or report["revision"].get("run_id") != turn["run_id"]:
            return {"status": "FAILED", "reason": "c02_revision_owner_mismatch"}
    first, second = (turn["artifact"] for turn in turns)
    if first["revision"]["deliverable_id"] != second["revision"]["deliverable_id"] \
            or first["revision"]["id"] == second["revision"]["id"] \
            or first["revision"]["revision"] >= second["revision"]["revision"] \
            or turns[0]["run_id"] == turns[1]["run_id"]:
        return {"status": "FAILED", "reason": "c02_revision_lineage_mismatch"}
    if evidence.get("retained_first_sha256") != first["sha256"]:
        return {"status": "UNKNOWN", "reason": "c02_old_revision_retention_unproven"}
    return {"status": "PASS", "reason": "c02_download_oracles_sources_and_revision_verified",
            "browser": "NOT_RUN", "readability_review": "PENDING"}


def run_case(api, *, agent_id, end_user_id, root, send_turn, code_handle=None, session_id=None,
             contents=None, turns=None):
    """send_turn 负责预算、真实派发及终态等待；未完成 intent 禁止自动重发。建议每轮上限 600 秒。"""
    root = Path(root)
    folder = root / "c02"
    contents = CSV if contents is None else contents
    turns = TURNS if turns is None else turns
    require(len(turns) == 2 and all(isinstance(text, str) and text.strip() for text in turns),
            "c02_two_turn_questions_required")
    resource = provision_case(root, contents=contents)
    declaration = {"turns": list(turns), "source_sha256": {
        key: hashlib.sha256(value).hexdigest() for key, value in contents.items()},
        "selected_ids": [None, ["A", "B"]]}
    declaration_path = folder / "case.json"
    require(not declaration_path.exists() or json.loads(declaration_path.read_text(encoding="utf-8")) == declaration,
            "c02_frozen_case_drift")
    if not declaration_path.exists(): save(declaration_path, declaration)
    path = folder / "state.json"
    state = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"turns": []}
    require(state.get("agent_id", agent_id) == agent_id and state.get("end_user_id", end_user_id) == end_user_id,
            "c02_owner_drift")
    state.update(agent_id=agent_id, end_user_id=end_user_id)
    if session_id is not None:
        require(state.get("session_id", session_id) == session_id, "c02_session_drift")
        state["session_id"] = session_id
    if "session_id" not in state:
        require(not state.get("session_creating"), "c02_session_creation_unknown")
        state["session_creating"] = True
        save(path, state)
        session = api.request("POST", "/chat/sessions", {"agent_id": agent_id, "end_user_id": end_user_id})
        state.update(session_id=session["session_id"], session_creating=False)
        save(path, state)
    attachments = ensure_d08_attachments(api, resource, state, path, root)
    for index, text in enumerate(turns):
        if len(state["turns"]) <= index:
            require(state.get("pending_turn") is None, "c02_turn_outcome_unknown")
            state["pending_turn"] = {"turn": index, "text": text, "attachment_ids": attachments}
            save(path, state)
            result = send_turn(session_id=state["session_id"], agent_id=agent_id, end_user_id=end_user_id,
                               text=text, attachment_ids=attachments, turn=index)
            # 派发结果先落盘，终态失败或后续只读下载失败也不能重发模型请求。
            result = dict(result)
            state["turns"].append(result)
            state["pending_turn"] = None
            save(path, state)
        turn = state["turns"][index]
        require(str(turn.get("status", "")).upper() == "COMPLETED" and turn.get("run_id"), "c02_turn_not_completed")
        turn_folder = folder / str(index)
        turn_folder.mkdir(exist_ok=True)
        if "artifact" not in turn:
            turn["artifact"] = collect_revision(api, state["session_id"], turn["run_id"], turn_folder)
        # 重算既有执行事实，允许修正观察器契约错误，不重发已完成轮次。
        turn["code_source"] = code_sources(turn, state["attachment_uploads"], code_handle, turn["artifact"])
        save(path, state)
        if not turn["code_source"]["verified"]:
            state["evaluation"] = {"status": "UNKNOWN", "reason": "c02_attachment_code_report_source_unproven"}
            save(path, state)
            return state
        require(_matches_report(turn["artifact"]["report"], oracle(contents, None if index == 0 else {"A", "B"})),
                "c02_numeric_or_source_oracle_mismatch")
    first = state["turns"][0]["artifact"]
    state["retained_first_sha256"] = _download(api, first["revision"], first["file"])["sha256"]
    state["evaluation"] = validate_case(state, contents=contents)
    save(path, state)
    return state
