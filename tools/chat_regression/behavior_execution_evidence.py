"""已采纳 Execution 材料的离线证据投影；不执行模型或工具。"""
import json

from behavior_contract import require

MODE = "execution-adopted-materials-v1"
MODEL_REQUEST = "skillforge.runtime.execution.prepared-model-request.v2"
MODEL_MANIFEST = "skillforge.runtime.execution.model-request-manifest.v1"
MODEL_MESSAGE = "skillforge.runtime.execution.model-message.v1"
MODEL_TOOLS = "skillforge.runtime.execution.model-tool-set.v1"
MODEL_INNER = "skillforge.runtime.execution.model-request.v2"
OUTCOME = "skillforge.runtime.execution.portal-outcome.v1"
MODEL_OUTCOME = "skillforge.runtime.portal.model-outcome.v5"
TOOL_REQUEST = "skillforge.runtime.execution.tool-request.v2"


def document(schema, raw, wanted):
    require(schema == wanted, "execution_evidence_schema_mismatch")
    value = json.loads(raw)
    require(isinstance(value, dict), "execution_evidence_object_required")
    return value


def embedded(value, wanted):
    return document(value["schemaId"], value["canonicalJson"], wanted)


def collect(cursor, tenant_id, session_id, run_ids):
    # 仅使用本会话 API 已返回的 Run；材料必须经其 Action/Attempt 外键链到达。
    cursor.execute("""SELECT a.run_id,a.action_id,a.action_ordinal,a.kind,a.status,
        a.selected_attempt,t.attempt_id,t.adopted,t.result_kind,
        q.schema_id,q.canonical_json,z.schema_id,z.canonical_json
        FROM public.agent_runtime_execution_runs r
        JOIN public.agent_runtime_execution_actions a ON a.tenant_id=r.tenant_id AND a.run_id=r.run_id
        JOIN public.agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id
            AND t.run_id=a.run_id AND t.action_id=a.action_id AND t.attempt_id=a.current_attempt
        JOIN public.agent_runtime_execution_materials q ON q.tenant_id=t.tenant_id AND q.reference=t.request_ref
        LEFT JOIN public.agent_runtime_execution_materials z ON z.tenant_id=t.tenant_id AND z.reference=t.result_ref
        WHERE r.tenant_id=%s AND r.session_id=%s AND r.run_id=ANY(%s)
            AND a.kind IN ('MODEL','TOOL') ORDER BY a.run_id,a.action_ordinal""",
        (tenant_id, session_id, sorted(run_ids)))
    rows = cursor.fetchall()
    require(len(rows) <= 100000, "execution_evidence_action_limit")
    require(sum(len(str(row[10]).encode("utf-8")) + len(str(row[12]).encode("utf-8")) for row in rows)
            <= 256 * 1024 * 1024, "execution_evidence_material_limit")
    # Manifest 的引用只能来自上述已核验的 Attempt；一次租户批读恢复叶子，避免每条消息一个查询。
    references = set()
    for row in rows:
        if row[3] == "MODEL" and row[7] is True and row[5] == row[6] and row[8] == "SUCCEEDED" and row[9] == MODEL_MANIFEST:
            manifest = document(row[9], row[10], MODEL_MANIFEST)
            references.update(manifest["messageRefs"])
            references.add(manifest["toolsRef"])
    materials = {}
    if references:
        cursor.execute("""SELECT reference,schema_id,canonical_json
            FROM public.agent_runtime_execution_materials
            WHERE tenant_id=%s AND reference=ANY(%s)""", (tenant_id, sorted(references)))
        for reference, schema, raw in cursor.fetchall():
            require(reference in references and reference not in materials, "execution_evidence_material_reference_mismatch")
            materials[reference] = (schema, raw)
        require(sum(len(raw.encode("utf-8")) for _, raw in materials.values())
                + sum(len(str(row[10]).encode("utf-8")) + len(str(row[12]).encode("utf-8")) for row in rows)
                <= 256 * 1024 * 1024, "execution_evidence_material_limit")
    return project(rows, run_ids, materials)


def model_request(schema, raw, materials):
    if schema == MODEL_REQUEST:
        return embedded(document(schema, raw, MODEL_REQUEST)["request"], MODEL_INNER)
    manifest = document(schema, raw, MODEL_MANIFEST)
    request = dict(manifest["request"])
    def leaf(reference, wanted):
        require(reference in materials, "execution_evidence_material_missing")
        actual_schema, actual_raw = materials[reference]
        return document(actual_schema, actual_raw, wanted)
    request["messages"] = [leaf(reference, MODEL_MESSAGE)["message"] for reference in manifest["messageRefs"]]
    request["tools"] = leaf(manifest["toolsRef"], MODEL_TOOLS)["tools"]
    return request


def project(rows, run_ids, materials=None):
    materials = {} if materials is None else materials
    runs = {rid: {"calls": [], "models_complete": True, "tools_complete": True} for rid in run_ids}
    models = {rid: [] for rid in run_ids}
    for rid, action, ordinal, kind, status, selected, attempt, adopted, result_kind, qs, qr, zs, zr in rows:
        require(rid in runs and kind in {"MODEL", "TOOL"}, "execution_evidence_scope_mismatch")
        run = runs[rid]
        accepted = adopted is True and selected == attempt and result_kind in {"SUCCEEDED", "FAILED", "UNKNOWN"}
        if not accepted:
            run["models_complete" if kind == "MODEL" else "tools_complete"] = False
            continue
        require(status == result_kind, "execution_evidence_result_mismatch")
        identity = {"run_id": rid, "action_id": action, "attempt_id": attempt, "ordinal": ordinal,
                    "source": MODE, "event_id": "execution-attempt:" + attempt}
        if kind == "TOOL":
            request = document(qs, qr, TOOL_REQUEST)
            run["calls"].append({**identity, "tool": request["wireName"], "call_id": request["callId"],
                                 "state": result_kind.lower(), "pointer": "/result_kind"})
            continue
        if result_kind != "SUCCEEDED":
            run["models_complete"] = False
            continue
        request = model_request(qs, qr, materials)
        outcome = document(zs, zr, OUTCOME)
        require(outcome["callKind"] in {"MODEL_GENERATE", "MODEL_STREAM"}, "execution_evidence_model_kind")
        result = embedded(outcome["outcome"], MODEL_OUTCOME)
        require(outcome["outcomeType"] == result["outcomeType"] == "Completed", "execution_evidence_model_result")
        turn = result.get("assistantTurn")
        if not isinstance(turn, dict):
            run["models_complete"] = False
            continue
        calls = [v for v in turn["contents"] if v.get("type") == "tool_call"]
        require(len({v["callId"] for v in calls}) == len(calls), "execution_evidence_duplicate_call")
        models[rid].append((identity, request, calls))
    for rid, entries in models.items():
        run = runs[rid]
        for identity, request, calls in entries:
            ordinal = identity["ordinal"]
            successors = [req for ident, req, _ in entries if ident["ordinal"] > ordinal]
            for call in calls:
                raw_args = json.loads(call["argumentsJson"])
                matched_receipts = []
                for successor in successors:
                    # 完整原始调用必须由该模型 Action 新增，不能借用输入历史或同名调用。
                    assistant_id = "execution-assistant-" + str(ordinal)
                    receipt_id = "execution-admission-rejected:" + str(ordinal) + ":" + call["callId"]
                    original_ids = {m["messageId"] for m in request["messages"]}
                    assistants = [m for m in successor["messages"] if m["messageId"] == assistant_id
                                  and m["messageId"] not in original_ids and m["role"] == "ASSISTANT"]
                    receipts = [m for m in successor["messages"] if m["messageId"] == receipt_id
                                and m["messageId"] not in original_ids and m["role"] == "TOOL"
                                and m.get("toolCallId") == call["callId"]]
                    if len(assistants) == 1 and len(receipts) == 1 and any(
                            c["callId"] == call["callId"] and c["wireName"] == call["wireName"]
                            and embedded(c["arguments"], "application/json") == raw_args
                            for c in assistants[0]["toolCalls"]):
                        matched_receipts.append(receipt_id)
                run["calls"].append({**identity, "tool": call["wireName"], "call_id": call["callId"],
                    "state": "attempted", "refused": bool(matched_receipts),
                    "pointer": "/outcome/assistantTurn/contents", "receipt_ids": sorted(set(matched_receipts))})
    return {"source": MODE, "runs": runs}
