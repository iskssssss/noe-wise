"""通过真实 SkillForge API 运行多机构接入场景；业务模拟不替代 Runtime。"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import time
import threading
from pathlib import Path
from urllib.request import Request, urlopen

from client import ApiFailure, Client, Credential, ManagementCredential, read_sse, read_until
from business_client import BusinessClient
from state import State, TERMINAL
from business_service import create_server
from websocket_transport import roundtrip
from settlement_contract import WalletClient, POLICY

LOG = logging.getLogger(__name__)
HERE = Path(__file__).resolve().parent
EXECUTION_SCOPES = ["end_users.write", "sessions.write", "sessions.read", "sessions.events.read", "artifacts.read", "business.scopes.write", "resources.read", "resources.write"]


def billing_configuration(config: dict, state: State) -> dict:
    """场景alias只能经已完成的真实开通step映射到固定钱包账户，不自动创建账户。"""
    billing = config["billing"]
    accounts = {}
    for alias, binding in billing["accounts"].items():
        step = state.get("step:tenant:" + alias)
        tenant = str(step["data"]["tenant_id"]) if step and step["state"] == "done" else alias
        if not tenant.isdecimal() or tenant.startswith("0") or tenant in accounts:
            raise ValueError("COMPLETED_TENANT_WALLET_BINDING_REQUIRED")
        accounts[tenant] = dict(binding)
    if not accounts:
        raise ValueError("EXPLICIT_WALLET_ACCOUNTS_REQUIRED")
    return billing | {"accounts": accounts}


class Scenario:
    def __init__(self, config: dict, state: State):
        """显式本地配置只引用平台已启用模型；凭据来自环境或一次签发结果。"""
        LOG.debug("初始化多机构场景: phase=entry")
        self.config, self.state = config, state
        self.namespace = config["scenario_namespace"]
        management_key = os.environ.get("SKILLFORGE_SCENARIO_MANAGEMENT_KEY")
        management_secret = os.environ.get("SKILLFORGE_SCENARIO_MANAGEMENT_SECRET")
        if bool(management_key) != bool(management_secret):
            raise ValueError("MANAGEMENT_KEY_AND_SECRET_REQUIRED_TOGETHER")
        self.platform = Client(config["platform_origin"],
            management_credential=ManagementCredential(management_key, management_secret) if management_key else None,
            platform_token=None if management_key else os.environ.get("SKILLFORGE_SCENARIO_PLATFORM_TOKEN"))
        self.institution_origin = config["institution_origin"]
        self.deployments = Client(config.get("deployment_origin", self.institution_origin),
            management_credential=self.platform.management_credential, platform_token=self.platform.platform_token)
        self.business_client = BusinessClient(config["business_origin"]) if config.get("business_origin") else None
        self.wallet_client = None
        if not self.namespace.strip():
            raise ValueError("EXPLICIT_NAMESPACE_REQUIRED")
        LOG.debug("初始化多机构场景: phase=exit")

    def once(self, key: str, operation):
        """记录写前意图；未知结果暂停，避免重复创建或丢失一次可见凭证。"""
        LOG.debug("执行场景持久步骤: phase=entry")
        existing = self.state.get("step:" + key)
        if existing:
            if existing["state"] != "done":
                raise RuntimeError("SETUP_OUTCOME_UNKNOWN: reconcile step " + key + " before reuse")
            return existing["data"]
        self.state.put("step:" + key, {"state": "pending"})
        result = operation()
        self.state.put("step:" + key, {"state": "done", "data": result})
        LOG.debug("执行场景持久步骤: phase=exit")
        return result

    def management_client(self) -> Client:
        """使用独立管理主体；不从执行凭据或业务正文推导平台授权。"""
        if not (self.platform.platform_token or self.platform.management_credential):
            raise RuntimeError("EXPLICIT_PLATFORM_MANAGEMENT_IDENTITY_REQUIRED")
        return self.platform

    def write(self, key: str, path: str, body: dict, method="POST"):
        """一次受理的平台写入使用稳定幂等键；结果未知时保留 pending 供核验。"""
        self.management_client()
        client = self.deployments if path.startswith("/platform/agent-deployments") else self.platform
        return self.once(key, lambda: client.request(method, path, body,
            idempotency_key=self.namespace + ":" + key))

    def tenant_client(self, tenant: str) -> Client:
        """加载固定 Tenant/integration 的执行身份，不能由请求正文改写。"""
        LOG.debug("加载机构客户端: phase=entry")
        issued = self.state.get("step:tenant-credential:" + tenant)["data"]
        result = Client(self.institution_origin, Credential(issued["api_key"], issued["signing_secret"]))
        LOG.debug("加载机构客户端: phase=exit")
        return result

    def expect_denied(self, operation):
        """验证真实授权拒绝；404 防枚举同样有效，认证失败不算资源隔离通过。"""
        LOG.debug("核验接入隔离: phase=entry")
        try:
            operation()
        except ApiFailure as failure:
            if failure.status not in {403, 404}:
                raise AssertionError("AUTHORIZATION_DENIAL_REQUIRED") from None
        else:
            raise AssertionError("CROSS_TENANT_ACCESS_WAS_ACCEPTED")
        LOG.debug("核验接入隔离: phase=exit")

    def definition(self, revision: str) -> dict:
        """最小公共定义引用真实模型，并通过参数展示机构实例身份。"""
        LOG.debug("构造场景公共定义: phase=entry")
        result = {"system_prompt": "你是{{parameters.role}}。用一句中文回答用户的问题。版本" + revision,
                  "role_description": "多机构接入示例助手", "model_config_id": str(self.config["model_config_id"]),
                  "execution_config": {}, "capabilities": [], "resource_slots": [], "delegation_slots": []}
        LOG.debug("构造场景公共定义: phase=exit")
        return result

    def setup(self):
        """平台直接开通四个独立机构；机构直授后部署稳定实例，执行凭据固定租户与集成域。"""
        LOG.info("建立多机构接入场景: phase=entry")
        self.management_client()
        definition = self.write("definition", "/platform/agent-definitions",
            {"name": self.namespace + ":公共助手", "price_type": "free", "price_cents": 0,
             "distribution_policy": "AUTHORIZED_DEPLOY", "definition": self.definition("v1")})
        definition_id = str(definition["definition_id"])
        self.write("publish:v1", "/platform/agent-definitions/publish",
            {"definition_id": definition_id, "expected_revision": definition["draft_revision"]})
        for tenant in ("institution-a", "institution-b", "platform-business", "other-institution"):
            body = {"namespace": self.namespace, "external_institution_ref": tenant,
                    "name": self.namespace + ":" + tenant, "default_plan_code": "free"}
            association = self.write("tenant:" + tenant, "/platform/institutions/provision", body)
            replay = self.platform.request("POST", "/platform/institutions/provision", body,
                idempotency_key=self.namespace + ":tenant:" + tenant)
            if replay["tenant_id"] != association["tenant_id"]:
                raise AssertionError("PROVISION_REPLAY_CHANGED_TENANT")
            tenant_id, integration = str(association["tenant_id"]), association["integration_id"]
            self.write("tenant-credential:" + tenant, "/platform/institution-credentials/issue",
                {"tenant_id": tenant_id, "integration_id": integration, "scopes": EXECUTION_SCOPES})
            client = self.tenant_client(tenant)
            if tenant == "institution-a":
                for store in ("store-1", "store-2"):
                    self.once("store:" + store, lambda store=store: client.request("POST", "/open-platform/business-scopes/ensure",
                        {"scope_type": "store", "external_ref": store, "display_name": store},
                        idempotency_key=self.namespace + ":store:" + store))
            if tenant != "other-institution":
                grant = self.write("grant:" + tenant, "/platform/agent-grants",
                    {"definition_id": definition_id, "target_tenant_id": tenant_id, "actions": ["deploy", "execute"],
                     "expires_at": None, "allow_regrant": False, "remaining_depth": 0})
                for role in ("assistant", "service"):
                    self.write("deployment:" + tenant + ":" + role, "/platform/agent-deployments",
                        {"tenant_id": tenant_id, "payload": {"definition_id": definition_id, "grant_id": grant["grant_id"],
                            "integration_id": integration, "external_agent_ref": role, "name": tenant + ":" + role,
                            "resource_bindings": [], "delegation_bindings": [], "parameters": {"role": role}}})
        grant = self.state.get("step:grant:institution-a")["data"]
        self.expect_denied(lambda: self.tenant_client("other-institution").request("POST", "/open-platform/agent-deployments",
            {"definition_id": definition_id, "grant_id": grant["grant_id"],
             "integration_id": self.state.get("step:tenant:other-institution")["data"]["integration_id"],
             "external_agent_ref": "unauthorized", "resource_bindings": [], "delegation_bindings": [], "parameters": {}},
             idempotency_key=self.namespace + ":unauthorized-deployment"))
        self.state.put("setup_complete", True)
        LOG.info("建立多机构接入场景: phase=exit")

    def conversation(self, tenant: str, role: str, version: str):
        """稳定外部room+Agent映射；发送幂等消息，SSE断开后仅补拉已有事件。"""
        LOG.info("运行机构聊天: phase=entry tenant=%s role=%s revision=%s", tenant, role, version)
        client = self.tenant_client(tenant)
        deployment = self.state.get("step:deployment:" + tenant + ":" + role)["data"]
        end_user = self.once("enduser:" + tenant, lambda: client.request("POST", "/open-platform/end-users/ensure",
            {"external_user_ref": self.namespace + ":customer", "display_name": "场景客户"}))
        if not isinstance(end_user["end_user_id"], str):
            raise AssertionError("END_USER_ID_MUST_PRESERVE_STRING_PRECISION")
        body = {"external_user_ref": self.namespace + ":customer", "external_conversation_ref": "room-1:" + role,
                "agent_id": deployment["agent_id"], "end_user_id": end_user["end_user_id"]}
        if tenant == "institution-a":
            body.update({"business_scope_type": "store", "business_scope_ref": "store-1"})
        session = self.once("session:" + tenant + ":" + role, lambda: client.request("POST", "/open-platform/sessions", body))
        session_id = session["session_id"]
        # 合法边界值同时验证HTTP幂等键到Runtime输入身份的长度收敛；input_id是服务端opaque值。
        key = (self.namespace + ":" + tenant + ":" + role + ":" + version).ljust(256, "x")
        if len(key) != 256:
            raise ValueError("SCENARIO_NAMESPACE_TOO_LONG_FOR_BOUNDARY_KEY")
        self.admit(tenant, hashlib.sha256(key.encode()).hexdigest())
        message = {"session_id": session_id, "payload": {"content": [{"type": "text", "text": "你好，请介绍一下你的职责。"}],
                   "context": {"business_case": "multi-institution-chat"}, "references": []}}
        receipt = self.once("message:" + key, lambda: client.request("POST", "/open-platform/sessions/messages", message, idempotency_key=key))
        replay = client.request("POST", "/open-platform/sessions/messages", message, idempotency_key=key)
        if receipt["input_id"] != replay["input_id"] or receipt["run_id"] != replay["run_id"]:
            raise AssertionError("MESSAGE_REPLAY_CREATED_ANOTHER_RUN")
        changed = message | {"payload": message["payload"] | {"content": [{"type": "text", "text": "同幂等键的不同正文"}]}}
        try:
            client.request("POST", "/open-platform/sessions/messages", changed, idempotency_key=key)
        except ApiFailure as conflict:
            if conflict.status != 409:
                raise AssertionError("IDEMPOTENCY_BODY_CHANGE_REQUIRES_CONFLICT") from None
        else:
            raise AssertionError("IDEMPOTENCY_BODY_CHANGE_WAS_ACCEPTED")
        # 主动只读一个SSE事件然后关连接，业务Run继续；随后走同一Outbox的cursor恢复。
        if not self.state.get("stream_seen:" + key):
            with client.request("GET", "/open-platform/sessions/stream",
                                query={"sessionId": session_id, "cursor": self.state.get("event_cursor:" + session_id)}, stream=True) as response:
                for event in read_sse(response):
                    self.state.consume_event(session_id, event)
                    self.state.put("stream_seen:" + key, True)
                    break
        run_id = receipt["run_id"]
        deadline = time.monotonic() + self.config.get("run_timeout_seconds", 180)
        finals = []
        while time.monotonic() < deadline:
            self.pull_events(client, session_id, deadline)
            status = read_until(client, "/open-platform/sessions/status", {"sessionId": session_id, "runId": run_id}, deadline)
            if status["status"].lower() in TERMINAL:
                # 状态可能恰在本轮pull之后提交；终态再补拉，避免漏掉最后一条final快照。
                self.pull_events(client, session_id, deadline)
                self.state.put("completed:" + key, status)
                if status["status"].lower() != "completed":
                    raise AssertionError("SCENARIO_RUN_DID_NOT_COMPLETE: " + status["status"])
                finals = [item for item in self.state.messages(session_id)
                          if item.get("phase") == "final" and item.get("state") == "completed"
                          and (item.get("run_id") or item.get("root_run_id")) == run_id]
                if finals:
                    break
                # 状态与公开projection可以短暂错开；在原Run上等已有final，不重发输入。
            if status["status"].lower() == "waiting_hitl" or status.get("pending_interactions"):
                raise AssertionError("BASE_CHAT_UNEXPECTEDLY_REQUIRES_INPUT")
            time.sleep(min(2.0, max(0.0, deadline-time.monotonic())))
        else:
            raise RuntimeError("RUN_TIMEOUT: read status/events; do not resend with another key")
        history = read_until(client, "/open-platform/sessions/history", {"sessionId": session_id}, deadline)
        self.state.put("history:" + key, history)
        self.state.put("business:final:" + key, finals[-1])
        self.verify_business_outputs(tenant, role, session_id, key, finals[-1])
        self.usage()
        LOG.info("运行机构聊天: phase=exit tenant=%s role=%s", tenant, role)
        return session_id

    def verify_business_outputs(self, tenant: str, role: str, session_id: str, key: str, final: dict):
        """把真实公开快照送入模拟WS，验证人工暂停和最终企微业务投递的幂等。"""
        LOG.debug("核验业务输出适配: phase=entry")
        server, thread = None, None
        if self.business_client:
            port = self.business_client.port
        else:
            server = create_server(0, self.state.path.with_name("business.sqlite3"))
            port = server.server_port
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()

        def post(path: str, body: dict):
            """模拟企微仅经真实本地HTTP；正文来自已授权Runtime公开最终结果。"""
            if self.business_client:
                return self.business_client.request(path, body)
            raw = json.dumps(body, ensure_ascii=False).encode()
            with urlopen(Request("http://127.0.0.1:" + str(server.server_port) + path, data=raw,
                                 headers={"Content-Type": "application/json"}), timeout=5) as response:
                return json.load(response)

        try:
            for event in self.state.events(session_id):
                if event["event"].startswith("message.") and event["data"].get("message_id"):
                    projected = roundtrip(port, {"tenant_ref": tenant, "room_ref": "room-1", "agent_ref": role,
                        "session_id": session_id, "event": event})
                    if int(projected["message"]["message_version"]) < int(event["data"]["message_version"]):
                        raise AssertionError("WEBSOCKET_SNAPSHOT_VERSION_REGRESSED")
            route = {"tenant_ref": tenant, "room_ref": "room-1"}
            delivery = route | {"delivery_id": key, "session_id": session_id, "run_status": "completed",
                                "phase": "final", "content": final["content"]}
            # 独立暂停探针从不真正发送；实际delivery保留同一身份，崩溃后可读取原发送回执。
            post("/delivery/pause", route | {"paused": True})
            paused = post("/delivery/wecom", delivery | {"delivery_id": key + ":pause-probe"})
            if paused["sent"]:
                raise AssertionError("MANUAL_PAUSE_MUST_PREVENT_BUSINESS_SEND")
            post("/delivery/pause", route | {"paused": False})
            first = post("/delivery/wecom", delivery)
            if not first["sent"] or first != post("/delivery/wecom", delivery):
                raise AssertionError("FINAL_BUSINESS_DELIVERY_MUST_BE_IDEMPOTENT")
            self.state.put("delivered:" + key, first)
        finally:
            if server:
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)
        LOG.debug("核验业务输出适配: phase=exit")

    def pull_events(self, client: Client, session_id: str, deadline: float | None = None):
        """连续消费当前已提交Outbox页，满页继续，不能因分页跳过终态消息。"""
        LOG.debug("恢复会话游标: phase=entry")
        while True:
            query = {"sessionId": session_id, "cursor": self.state.get("event_cursor:" + session_id), "limit": 100}
            page = (read_until(client, "/open-platform/sessions/events", query, deadline) if deadline is not None
                    else client.request("GET", "/open-platform/sessions/events", query=query))
            for event in page["events"]:
                self.state.consume_event(session_id, event)
            if len(page["events"]) < 100:
                break
        LOG.debug("恢复会话游标: phase=exit")

    def admit(self, tenant: str, request_id: str):
        """调用前由应用钱包自行准入；示例客户价和余额不进入SkillForge供应商账单。"""
        LOG.debug("模拟钱包准入: phase=entry")
        association = self.state.get("step:tenant:" + tenant)["data"]
        tenant_id = str(association["tenant_id"])
        if self.business_client:
            self.business_billing()
            self.business_client.request("/api/wallet/register", {"tenant_id": tenant_id,
                "name": association.get("tenant_name") or "机构 " + tenant_id})
            admission = self.business_client.request("/api/wallet/admit", {"tenant_id": tenant_id, "request_id": request_id})
        else:
            billing = self.local_billing()
            account = billing["accounts"][tenant_id]
            admission = self.wallet_client.request("POST", "/api/v1/admissions", {
                "admission_id": request_id, "business_root_ref": request_id, "policy": POLICY,
                "execution_tenant_id": tenant_id, **account}, idempotency_key=request_id)
        if not admission["admitted"]:
            raise RuntimeError("EXTERNAL_WALLET_INSUFFICIENT_BALANCE")
        LOG.debug("模拟钱包准入: phase=exit")

    def usage(self):
        """平台单流按当前管理授权消费；重复页不重复扣款，游标不透明且只原样续读。"""
        LOG.info("拉取机构用量: phase=entry")
        client = self.management_client()
        if self.business_client:
            self.business_billing()
        tenants = {str(json.loads(row[0])["data"]["tenant_id"]) for row in self.state.db.execute(
            "SELECT value FROM kv WHERE key LIKE 'step:tenant:%'") if json.loads(row[0])["state"] == "done"}
        for tenant_id in sorted(tenants):
            stream_key = "platform:" + tenant_id
            while True:
                cursor = (self.business_client.request("/api/cursor", query={"stream_key": stream_key})["cursor"]
                          if self.business_client else self.state.get("usage_cursor:" + stream_key))
                page = client.request("GET", "/platform/usage/events", query={"tenant_id": tenant_id, "cursor": cursor, "limit": 100})
                if any(str(event["tenant_id"]) != tenant_id for event in page["items"]):
                    raise ValueError("USAGE_TENANT_FILTER_MISMATCH")
                if self.business_client:
                    consumed = self.business_client.request("/api/usage/consume", {"stream_key": stream_key, "page": page, "expected_cursor": cursor})
                    if consumed["next_cursor"] != page["next_cursor"]:
                        raise AssertionError("DEMO_USAGE_CHECKPOINT_MISMATCH")
                    self.state.observe_usage(stream_key, page, expected_cursor=self.state.get("usage_cursor:" + stream_key))
                else:
                    self.local_billing()
                    self.state.consume_usage(stream_key, page, expected_cursor=cursor)
                    self.state.billing.dispatch(self.wallet_client)
                if not page["has_more"]:
                    break
        LOG.info("拉取机构用量: phase=exit")

    def business_billing(self):
        """真实开通的tenant映射与Demo预建账户必须一致，防止另一计价配置混入准入。"""
        billing = billing_configuration(self.config, self.state)
        display = self.business_client.request("/api/snapshot")
        if display["billing"]["pricing"] != billing["pricing"] or display["billing"]["accounts"] != billing["accounts"]:
            raise ValueError("DEMO_AND_SCENARIO_BILLING_MISMATCH")
        return display

    def billing_status(self):
        """钱包确认余额和客户应收分别展示，状态查询沿既有唯一owner读取。"""
        if self.business_client:
            display = self.business_billing()
            return {"wallet_balances_cents": {wallet["tenant_id"]: wallet["balance_cents"] for wallet in display["wallets"]},
                    "customer_receivable_cents": {wallet["tenant_id"]: wallet["receivable_cents"] for wallet in display["wallets"]},
                    "settlement": display["settlement"]}
        self.local_billing()
        wallets = {tenant: self.wallet_client.request("GET", "/api/v1/accounts/detail", query=account)["balance_cents"]
                   for tenant, account in billing_configuration(self.config, self.state)["accounts"].items()}
        return {"wallet_balances_cents": wallets, "customer_receivable_cents": self.state.totals(),
                "settlement": self.state.billing.snapshot()}

    def local_billing(self):
        """无业务Demo时本地Adapter是唯一计价owner，钱包映射须经独立服务核验。"""
        billing = billing_configuration(self.config, self.state)
        if self.wallet_client is None:
            token = os.environ.get(billing["wallet_token_env"])
            if not token:
                raise ValueError("WALLET_SERVICE_CREDENTIAL_REQUIRED")
            wallet_client = WalletClient(billing["wallet_origin"], token)
            for tenant, account in billing["accounts"].items():
                remote = wallet_client.request("GET", "/api/v1/accounts/detail", query=account)
                if remote["execution_tenant_id"] != tenant or remote["currency"] != account["currency"]:
                    raise ValueError("WALLET_ACCOUNT_BINDING_MISMATCH")
            self.state.configure_billing(billing["pricing"], billing["accounts"])
            self.wallet_client = wallet_client
        return billing

    def run(self):
        """运行版本升级与机构隔离场景；旧会话继续使用稳定Agent，新Run跟随发布。"""
        LOG.info("执行完整多机构场景: phase=entry")
        if not self.state.get("setup_complete"):
            self.setup()
        sessions = {}
        for tenant in ("institution-a", "institution-b", "platform-business"):
            for role in ("assistant", "service"):
                sessions[tenant + ":" + role] = self.conversation(tenant, role, "v1")
        definition_id = str(self.state.get("step:definition")["data"]["definition_id"])
        detail = self.platform.request("GET", "/platform/agent-definitions/detail", query={"definition_id": definition_id})
        updated = self.write("definition:v2", "/platform/agent-definitions/update",
            {"definition_id": definition_id, "expected_revision": detail["draft_revision"],
             "payload": {"definition": self.definition("v2")}}, method="PUT")
        publication = self.write("publish:v2", "/platform/agent-definitions/publish",
            {"definition_id": definition_id, "expected_revision": updated["draft_revision"]})
        self.conversation("institution-a", "assistant", "v2")
        tenant_id = str(self.state.get("step:tenant:institution-a")["data"]["tenant_id"])
        deployments = self.deployments.request("GET", "/platform/agent-deployments", query={"tenant_id": tenant_id})
        if any(not item["ready"] or item["definition_version"] != publication["version"] for item in deployments["items"]):
            raise AssertionError("NEW_DEPLOYMENT_VIEW_MUST_FOLLOW_PUBLISHED_VERSION")
        self.state.put("deployment_after_publish", deployments)
        self.state.put("published_version", publication["version"])
        for other in ("institution-b", "other-institution"):
            self.expect_denied(lambda other=other: self.tenant_client(other).request("GET", "/open-platform/sessions/events",
                query={"sessionId": sessions["institution-a:assistant"], "limit": 1}))
        self.usage()
        self.state.put("scenario_complete", True)
        print(json.dumps({"state": "completed", "institutions": 3, "agent_instances": 6, "definition_versions": 2,
                          **self.billing_status()}, ensure_ascii=False))
        LOG.info("执行完整多机构场景: phase=exit")


def main():
    """命令入口；不输出一次签发密钥，也不自动启动、迁移或重启 SkillForge。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("setup", "run", "usage", "status", "media-demo"))
    parser.add_argument("--config", type=Path, default=HERE / "config.local.json")
    parser.add_argument("--billing-config", type=Path)
    parser.add_argument("--state", type=Path, default=HERE / ".state" / "scenario.sqlite3")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    config = json.loads(args.config.read_text(encoding="utf-8"))
    if args.billing_config:
        config["billing"] = json.loads(args.billing_config.read_text(encoding="utf-8"))
    state = State(args.state)
    try:
        if args.command == "media-demo":
            from media_demo import MediaDemo
            MediaDemo(config, state).run()
            return
        scenario = Scenario(config, state)
        if args.command == "status":
            print(json.dumps({"setup_complete": state.get("setup_complete", False),
                              "scenario_complete": state.get("scenario_complete", False), **scenario.billing_status()}))
        else:
            getattr(scenario, args.command)()
    finally:
        state.close()


if __name__ == "__main__":
    main()
