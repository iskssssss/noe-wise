"""媒体 Demo 经正式 Connector、能力生命周期和机构资源槽进入真实 Session/Run。"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import time

from business_client import BusinessClient
from client import ApiFailure, Client, read_until
from scenario import EXECUTION_SCOPES, Scenario
from state import TERMINAL
from websocket_transport import roundtrip

LOG = logging.getLogger(__name__)
MEDIA = {"media_transcribe": "语音转写", "media_synthesize": "语音合成", "video_analyze": "视频分析"}


def media_run_identity(namespace: str, name: str, attempt_ref: str | None = None) -> dict:
    """只有显式的新尝试标识才创建独立输入；缺省与首次场景的持久身份完全一致。"""
    LOG.debug("构造媒体尝试身份: phase=entry tool=%s", name)
    if attempt_ref is not None and (not isinstance(attempt_ref, str)
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,31}", attempt_ref)):
        raise ValueError("MEDIA_ATTEMPT_REF_REQUIRES_SAFE_ASCII_IDENTIFIER_MAX_32")
    suffix = "" if attempt_ref is None else ":" + attempt_ref
    result = {"attempt_ref": attempt_ref,
              "request_id": "media-" + hashlib.sha256((namespace + ":" + name + suffix).encode()).hexdigest()[:32],
              "session_step": "media:session:" + name + suffix,
              "message_step": "media:message:" + name + suffix,
              "conversation_ref": "media-demo:" + name + suffix,
              "status_key": "media:status:" + name + suffix}
    LOG.debug("构造媒体尝试身份: phase=exit tool=%s", name)
    return result


def require_media_execution(receipt: dict, events: list[dict], snapshot: dict, name: str, arguments: dict) -> dict:
    """实际 Run 的成功工具过程与精确模拟回执同时存在，才确认媒体 Runtime 链路。"""
    LOG.debug("核验真实媒体执行: phase=entry tool=%s", name)
    latest = {}
    for event in events:
        data = event["data"]
        if (event["event"].startswith("process.") and data.get("type") == "tool"
                and data.get("run_id") == receipt["run_id"]):
            latest[data["process_id"]] = event
    successful = [event for event in latest.values()
                  if event["data"].get("status") == "succeeded" and event["data"].get("runtime_event_id")
                  and event.get("event_id") and event["data"].get("finished_at")]
    mock = [item for item in snapshot.get("media_receipts", [])
            if item["name"] == name and item["request_id"] == arguments["request_id"]]
    if (len(latest) != 1 or len(successful) != 1 or len(mock) != 1
            or mock[0].get("arguments") != arguments or mock[0].get("simulated") is not True
            or mock[0].get("result", {}).get("simulated") is not True):
        raise AssertionError("ACTUAL_RUNTIME_TOOL_AND_EXACT_MOCK_RECEIPT_REQUIRED: " + name)
    result = {"tool": name, "request_id": arguments["request_id"], "run_id": receipt["run_id"],
              "input_id": receipt["input_id"], "process": successful[0], "mock_receipt": mock[0],
              "runtime_tool_executed": True, "business_result_simulated": True}
    LOG.debug("核验真实媒体执行: phase=exit tool=%s", name)
    return result


class MediaDemo(Scenario):
    def __init__(self, config, state):
        """已有真实机构的管理身份负责资源发布，治理和执行身份仍独立签发。"""
        LOG.debug("初始化媒体Demo: phase=entry")
        super().__init__(config, state)
        self.attempt_ref = config.get("media_attempt_ref")
        if "media_attempt_ref" in config and self.attempt_ref is None:
            raise ValueError("MEDIA_ATTEMPT_REF_REQUIRES_SAFE_ASCII_IDENTIFIER_MAX_32")
        media_run_identity(self.namespace, "media_transcribe", self.attempt_ref)
        self.tenant_id = config.get("demo_tenant_id", "")
        if not isinstance(self.tenant_id, str) or not re.fullmatch(r"[1-9][0-9]{0,63}", self.tenant_id):
            raise ValueError("EXISTING_DEMO_TENANT_DECIMAL_STRING_REQUIRED")
        token = os.environ.get("SKILLFORGE_SCENARIO_INSTITUTION_TOKEN")
        if not token or not (self.platform.platform_token or self.platform.management_credential):
            raise RuntimeError("PLATFORM_AND_INSTITUTION_ADMIN_TOKENS_REQUIRED_FOR_MEDIA_DEMO")
        self.member = Client(self.institution_origin, institution_token=token)
        self.business_client = BusinessClient(config.get("business_origin", "http://127.0.0.1:8098"))
        self.business_origin = self.business_client.origin
        self.evidence = state.get("media:evidence", {"state": "pending", "business_services_simulated": True, "runs": []})
        self.evidence["attempt_ref"] = self.attempt_ref
        self.current_run = None
        self.current_status_key = None
        LOG.debug("初始化媒体Demo: phase=exit")

    def business(self, path: str, body=None, query=None):
        """本地 Demo JSON 传输无凭据；失败只输出状态，不转储任意业务载荷。"""
        LOG.debug("调用媒体Demo业务接口: phase=entry path=%s", path)
        try:
            return self.business_client.request(path, body, query)
        finally:
            LOG.debug("调用媒体Demo业务接口: phase=exit path=%s", path)

    def checkpoint(self):
        """证据文件只含本场景公开事实与模拟回执，凭据仍留在被忽略的 SQLite。"""
        LOG.debug("保存媒体Demo证据: phase=entry")
        self.state.put("media:evidence", self.evidence)
        destination = self.state.path.with_name("media-evidence.json")
        temporary = destination.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(self.evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(destination)
        LOG.debug("保存媒体Demo证据: phase=exit")

    def definition(self, revision="v1"):
        """公共定义仅声明媒体资源槽，本地 Connector 与部署由机构自行绑定。"""
        LOG.debug("构造媒体公共定义: phase=entry")
        result = {"system_prompt": "你是通用媒体接入验收助手。必须实际调用用户指定的一个工具，严格使用用户给出的JSON参数。"
                  "只调用一次指定工具，不得调用其他工具，不得编造工具执行结果。工具完成后用一句中文说明结果来自模拟服务。",
                  "role_description": "通用媒体接入Demo", "model_config_id": str(self.config["model_config_id"]),
                  "execution_config": {"max_iterations": 8, "max_tool_calls": 1},
                  "capabilities": [], "resource_slots": [
                      {"key": name, "label": label, "description": "机构本地模拟" + label, "capability_type": "TOOL"}
                      for name, label in MEDIA.items()], "delegation_slots": []}
        LOG.debug("构造媒体公共定义: phase=exit")
        return result

    def setup(self):
        """注册发现 MCP，发布三能力，平台关联已有机构并直接授权稳定实例。"""
        LOG.info("建立媒体Runtime场景: phase=entry")
        principal = self.member.request("GET", "/auth/me")
        if str(principal.get("tenant_id")) != self.tenant_id or principal.get("role") not in {"admin", "developer"}:
            raise AssertionError("MEDIA_RESOURCE_OWNER_MUST_MATCH_AUTHENTICATED_TENANT")
        snapshot = self.business("/api/snapshot")
        if not snapshot.get("business_services_simulated") or self.config["billing"]["pricing"] != snapshot["billing"]["pricing"]:
            raise AssertionError("SIMULATED_DEMO_WITH_MATCHING_CUSTOMER_PRICE_REQUIRED")
        identity = {"namespace": self.namespace, "tenant_id": self.tenant_id, "business_origin": self.business_origin,
                    "platform_origin": self.platform.origin, "institution_origin": self.institution_origin,
                    "model_config_id": str(self.config["model_config_id"])}
        if self.state.get("media:identity", identity) != identity:
            raise AssertionError("MEDIA_CHECKPOINT_CONFIGURATION_CHANGED")
        self.state.put("media:identity", identity)
        association = self.write("tenant:media", "/platform/institutions/associate",
            {"tenant_id": self.tenant_id, "namespace": self.namespace, "external_institution_ref": "media-demo"})
        self.business_billing()
        self.write("tenant-credential:media", "/platform/institution-credentials/issue",
            {"tenant_id": self.tenant_id, "integration_id": association["integration_id"], "scopes": EXECUTION_SCOPES})
        self.business("/api/wallet/register", {"tenant_id": self.tenant_id,
            "name": association.get("tenant_name") or "机构 " + self.tenant_id})
        connector = self.once("media:connector", lambda: self.member.request("POST", "/connectors/instances",
            {"executor_type": "mcp", "name": self.namespace + ":媒体模拟服务", "base_url": self.business_origin + "/mcp",
             "auth_type": "none", "headers": {}}))
        instance_id = connector["instance_id"]
        catalog = self.member.request("GET", "/connectors/instances/mcp-tools", query={"instanceId": instance_id})
        self.state.put("media:catalog", catalog)
        tools = {item["name"]: item for item in catalog["items"]}
        if not MEDIA.keys() <= tools.keys():
            raise AssertionError("ACTUAL_MCP_MEDIA_TOOL_DISCOVERY_REQUIRED")
        bindings, resources = [], []
        for name in MEDIA:
            mount, resource = self.publish_tool(name, tools[name], instance_id)
            bindings.append({"slot_key": name, "mount": mount})
            resources.append(resource)
        definition = self.write("media:definition", "/platform/agent-definitions",
            {"name": self.namespace + ":媒体公共助手", "price_type": "free", "price_cents": 0,
             "distribution_policy": "AUTHORIZED_DEPLOY", "definition": self.definition()})
        definition_id = str(definition["definition_id"])
        publication = self.write("media:publication", "/platform/agent-definitions/publish",
            {"definition_id": definition_id, "expected_revision": definition["draft_revision"]})
        grant = self.write("media:grant", "/platform/agent-grants",
            {"definition_id": definition_id, "target_tenant_id": self.tenant_id, "actions": ["deploy", "execute"],
             "expires_at": None, "allow_regrant": False, "remaining_depth": 0})
        unbound = self.write("media:unbound", "/platform/agent-deployments",
            {"tenant_id": self.tenant_id, "payload": {"definition_id": definition_id, "grant_id": grant["grant_id"],
             "integration_id": association["integration_id"], "external_agent_ref": "media-assistant", "name": "通用媒体助手",
             "resource_bindings": [], "delegation_bindings": [], "parameters": {}}})
        if unbound["ready"] or not unbound["issues"]:
            raise AssertionError("DECLARED_RESOURCE_SLOTS_MUST_REQUIRE_LOCAL_BINDINGS")
        bound = self.write("deployment:media:assistant", "/platform/agent-deployments/bindings",
            {"tenant_id": self.tenant_id, "payload": {"deployment_id": unbound["deployment_id"],
             "expected_revision": unbound["deployment_revision"], "resource_bindings": bindings,
             "delegation_bindings": [], "parameters": {}}}, method="PUT")
        readback = self.deployments.request("GET", "/platform/agent-deployments/detail",
            query={"tenant_id": self.tenant_id, "deployment_id": bound["deployment_id"]})
        if not readback["ready"] or readback["agent_id"] != unbound["agent_id"] or len(readback["resource_bindings"]) != 3:
            raise AssertionError("MEDIA_DEPLOYMENT_READINESS_AND_BINDING_READBACK_REQUIRED")
        self.evidence.update({"tenant_id": self.tenant_id, "association": association,
            "connector": {"instance_id": instance_id, "server_identity": catalog["server_identity"], "tools": [tools[name] for name in MEDIA]},
            "resources": resources, "definition_id": definition_id, "publication": publication, "unbound": unbound, "bound": readback})
        self.checkpoint()
        LOG.info("建立媒体Runtime场景: phase=exit")

    def publish_tool(self, name: str, discovered: dict, connector: str):
        """采用实际发现 schema 发布机构能力，工具挂载引用业务 deployment_key 和真实修订。"""
        LOG.debug("发布机构媒体能力: phase=entry tool=%s", name)
        key = "media-demo-" + hashlib.sha256((self.namespace + ":" + name).encode()).hexdigest()[:24]
        definition = self.once("media:definition:" + name, lambda: self.member.request("POST", "/capabilities/definitions",
            {"capability_type": "TOOL", "capability_key": key, "name": MEDIA[name], "description": discovered["description"]}))
        descriptor = {"input_schema": discovered["input_schema"], "output_schema": discovered.get("output_schema") or {},
            "effect": "NON_IDEMPOTENT_WRITE" if name == "media_synthesize" else "READ_ONLY",
            "idempotent": name != "media_synthesize",
            "timeout_ms": 30000, "tool_name": name, "model_invocable": True, "user_invocable": False,
            "confirmation_policy": "NEVER", "parallel_safe": False}
        content_hash = hashlib.sha256(json.dumps(descriptor, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        draft = self.once("media:version:" + name, lambda: self.member.request("POST", "/capabilities/definitions/versions",
            {"definition_id": definition["id"], "payload": {"version_key": "v1", "name": MEDIA[name],
             "description": discovered["description"], "content_hash": content_hash, "descriptor": descriptor}}))
        validation = self.once("media:validation:" + name, lambda: self.member.request("POST", "/capabilities/definitions/versions/validate",
            {"definition_id": definition["id"], "version_id": draft["id"]}))
        if not validation["valid"]:
            raise AssertionError("MEDIA_TOOL_DESCRIPTOR_MUST_VALIDATE: " + name)
        published = self.once("media:published:" + name, lambda: self.member.request("POST", "/capabilities/definitions/versions/publish",
            {"definition_id": definition["id"], "version_id": draft["id"]}, if_match=int(validation["definition_revision"])))
        if published["status"] != "PUBLISHED":
            raise AssertionError("MEDIA_TOOL_PUBLICATION_REQUIRED")
        self.once("media:tool-deployment:" + name, lambda: self.member.request("POST", "/capabilities/deployments",
            {"deployment_key": key, "tool_version_id": draft["id"], "provider_type": "MCP", "provider_ref": connector,
             "environment": "PRODUCTION", "status": "ACTIVE"}))
        deployment = self.member.request("GET", "/capabilities/deployments/detail", query={"deploymentKey": key})
        if (deployment["deployment_key"] != key or deployment["provider_ref"] != connector
                or deployment["status"] != "ACTIVE" or str(deployment["tool_version_id"]) != str(draft["id"])):
            raise AssertionError("MEDIA_TOOL_DEPLOYMENT_READBACK_REQUIRED")
        mount = {"capability_type": "TOOL", "capability_definition_id": str(definition["id"]), "capability_version_id": str(draft["id"]),
            "tool_config": {"tool_deployment_id": key, "tool_deployment_revision": deployment["revision"]}, "priority": 0}
        LOG.debug("发布机构媒体能力: phase=exit tool=%s", name)
        return mount, {"tool": name, "definition_id": definition["id"], "published_version": published, "deployment": deployment}

    def usage(self):
        """真实用量页同时进入场景 checkpoint 和业务 Demo 原子钱包消费者。"""
        LOG.debug("同步媒体Demo真实用量: phase=entry")
        client = self.management_client()
        stream_key = "platform:" + self.tenant_id
        while True:
            cursor = self.business("/api/cursor", query={"stream_key": stream_key})["cursor"]
            page = client.request("GET", "/platform/usage/events", query={"tenant_id": self.tenant_id, "cursor": cursor, "limit": 100})
            if any(str(event["tenant_id"]) != self.tenant_id for event in page["items"]):
                raise ValueError("USAGE_TENANT_FILTER_MISMATCH")
            local_cursor = self.state.get("usage_cursor:" + stream_key)
            payload = {"stream_key": stream_key, "expected_cursor": cursor, "page": page}
            consumed = self.business("/api/usage/consume", payload)
            if consumed["next_cursor"] != page["next_cursor"]:
                raise AssertionError("MEDIA_WALLET_CHECKPOINT_MISMATCH")
            self.state.observe_usage(stream_key, page, expected_cursor=local_cursor)
            if not page["has_more"]:
                break
        LOG.debug("同步媒体Demo真实用量: phase=exit")

    def conversation(self, name: str):
        """每个媒体工具用独立真实 Session/Run 和唯一请求，保留过程事件而非只看回答文本。"""
        LOG.info("运行媒体Runtime工具: phase=entry tool=%s", name)
        client = self.tenant_client("media")
        identity = media_run_identity(self.namespace, name, self.attempt_ref)
        request_id = identity["request_id"]
        arguments = {"request_id": request_id, **({"text": "这是一条通用媒体演示消息。"} if name == "media_synthesize"
                    else {"media_ref": "fixture:voice:sample" if name == "media_transcribe" else "fixture:video:sample"})}
        end_user = self.once("media:enduser", lambda: client.request("POST", "/open-platform/end-users/ensure",
            {"external_user_ref": self.namespace + ":media-user", "display_name": "媒体演示用户"}))
        if not isinstance(end_user["end_user_id"], str):
            raise AssertionError("MEDIA_END_USER_DECIMAL_STRING_REQUIRED")
        agent = self.state.get("step:deployment:media:assistant")["data"]["agent_id"]
        session = self.once(identity["session_step"], lambda: client.request("POST", "/open-platform/sessions",
            {"agent_id": agent, "end_user_id": end_user["end_user_id"], "external_user_ref": self.namespace + ":media-user",
             "external_conversation_ref": identity["conversation_ref"]}))
        session_id = session["session_id"]
        self.usage()
        admission = self.business("/api/wallet/admit", {"tenant_id": self.tenant_id, "request_id": request_id})
        if not admission["admitted"]:
            raise RuntimeError("MEDIA_EXTERNAL_WALLET_INSUFFICIENT_BALANCE")
        if not self.state.get("step:" + identity["message_step"]):
            # 成功工具必须是本场景 Run 产生的新回执；已有同 request_id 需要显式核对来源。
            if any(item["request_id"] == request_id for item in self.business("/api/snapshot")["media_receipts"]):
                raise AssertionError("MEDIA_REQUEST_RECEIPT_PREDATES_RUNTIME_INPUT")
        message = {"session_id": session_id, "payload": {"content": [{"type": "text", "text":
            "请实际调用 " + name + "，参数严格使用这个JSON：" + json.dumps(arguments, ensure_ascii=False) + "。只调用一次，然后说明实际返回结果来自模拟服务。"}],
            "context": {"business_case": "generic-media-demo"}, "references": []}}
        receipt = self.once(identity["message_step"], lambda: client.request("POST", "/open-platform/sessions/messages", message,
                                                                          idempotency_key=request_id))
        self.current_run = {"tool": name, "attempt_ref": self.attempt_ref, "session_id": session_id,
                            "receipt": receipt, "arguments": arguments}
        self.current_status_key = identity["status_key"]
        self.evidence["runs"] = [item for item in self.evidence["runs"]
                                if (item["tool"], item.get("attempt_ref")) != (name, self.attempt_ref)] + [self.current_run]
        self.checkpoint()
        replay = client.request("POST", "/open-platform/sessions/messages", message, idempotency_key=request_id)
        if receipt["run_id"] != replay["run_id"] or receipt["input_id"] != replay["input_id"]:
            raise AssertionError("MEDIA_MESSAGE_REPLAY_CREATED_ANOTHER_RUN")
        run_id, status, final = receipt["run_id"], None, None
        deadline = time.monotonic() + self.config.get("run_timeout_seconds", 180)
        while time.monotonic() < deadline:
            self.pull_events(client, session_id, deadline)
            status = read_until(client, "/open-platform/sessions/status", {"sessionId": session_id, "runId": run_id}, deadline)
            self.state.put(identity["status_key"], status)
            if status["status"].lower() in TERMINAL:
                self.pull_events(client, session_id, deadline)
                finals = [item for item in self.state.messages(session_id) if item.get("phase") == "final"
                          and item.get("state") == "completed" and (item.get("run_id") or item.get("root_run_id")) == run_id]
                if finals:
                    final = finals[-1]
                if status["status"].lower() != "completed" or final:
                    break
            if status["status"].lower() == "suspended" or status.get("pending_interactions"):
                break
            time.sleep(min(2.0, max(0.0, deadline-time.monotonic())))
        history = read_until(client, "/open-platform/sessions/history", {"sessionId": session_id}, deadline)
        events = self.state.events(session_id)
        snapshot = self.business("/api/snapshot")
        run_evidence = {"tool": name, "attempt_ref": self.attempt_ref, "session_id": session_id, "receipt": receipt, "status": status,
                        "arguments": arguments, "history": history, "events": events,
                        "mock_receipts": [item for item in snapshot["media_receipts"] if item["request_id"] == request_id]}
        self.current_run = run_evidence
        self.evidence["runs"] = [item for item in self.evidence["runs"]
                                if (item["tool"], item.get("attempt_ref")) != (name, self.attempt_ref)] + [run_evidence]
        self.checkpoint()
        if not status or status["status"].lower() != "completed" or final is None:
            raise AssertionError("MEDIA_RUN_AND_FINAL_MUST_COMPLETE: " + name)
        run_evidence["execution_proof"] = require_media_execution(receipt, events, snapshot, name, arguments)
        self.deliver_final(name, session_id, request_id, final, events)
        self.usage()
        self.checkpoint()
        LOG.info("运行媒体Runtime工具: phase=exit tool=%s", name)

    def deliver_final(self, name, session_id, request_id, final, events):
        """真实公开最终消息经 Demo WebSocket 投影并验证人工暂停和幂等模拟发送。"""
        LOG.debug("投递媒体公开结果: phase=entry tool=%s", name)
        port = self.business_client.port
        route = {"tenant_ref": self.tenant_id,
                 "room_ref": media_run_identity(self.namespace, name, self.attempt_ref)["conversation_ref"]}
        for event in events:
            if event["event"].startswith("message.") and event["data"].get("message_id"):
                roundtrip(port, route | {"agent_ref": "media-assistant", "session_id": session_id, "event": event})
        delivery = route | {"delivery_id": request_id, "session_id": session_id, "run_status": "completed",
                            "phase": "final", "content": final["content"]}
        self.business("/delivery/pause", route | {"paused": True})
        paused = self.business("/delivery/wecom", delivery | {"delivery_id": request_id + ":pause-probe"})
        if paused["sent"]:
            raise AssertionError("MEDIA_PAUSE_MUST_PREVENT_DELIVERY")
        self.business("/delivery/pause", route | {"paused": False})
        sent = self.business("/delivery/wecom", delivery)
        if not sent["sent"] or sent != self.business("/delivery/wecom", delivery):
            raise AssertionError("MEDIA_FINAL_DELIVERY_MUST_BE_IDEMPOTENT")
        self.state.put("media:delivery:" + name, sent)
        LOG.debug("投递媒体公开结果: phase=exit tool=%s", name)

    def run(self):
        """独立媒体命令；不扩大既有 run 的模型预算，全部媒体完成后才标成功。"""
        LOG.info("执行媒体Runtime Demo: phase=entry")
        try:
            self.setup()
            for name in MEDIA:
                self.conversation(name)
            self.evidence["state"] = "completed"
            self.evidence.pop("failure", None)
            self.state.put("media_demo_complete", True)
            self.checkpoint()
            print(json.dumps({"state": "completed", "runtime_media_tools": list(MEDIA), "business_results_simulated": True,
                              "evidence": str(self.state.path.with_name("media-evidence.json"))}, ensure_ascii=False))
        except Exception as failure:
            if self.current_run:
                self.current_run["events"] = self.state.events(self.current_run["session_id"])
                self.current_run["status"] = self.state.get(self.current_status_key)
            self.evidence["state"] = "incomplete"
            self.evidence["failure"] = {"type": type(failure).__name__}
            if isinstance(failure, ApiFailure):
                self.evidence["failure"].update({"http_status": failure.status, "code": failure.code})
            self.checkpoint()
            raise
        finally:
            LOG.info("执行媒体Runtime Demo: phase=exit")
