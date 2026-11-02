"""GI-04发送服务受控HTTP边界；本地令牌只映射服务端预配置SP，不接受自报权限。"""
from __future__ import annotations

import hmac
import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, build_opener

from client import Client, Credential, NoRedirect
from group_sender import Endpoint, GroupSender, SenderError, SenderStore, UnknownSend, identity

HTTP_TIMEOUT_SECONDS = 30
MAX_REQUEST_BYTES = 1024 * 1024
ROOT = "/open-platform/groups"
LOCAL_INTENTS = "/api/v1/local-control-intents"
DELIVERIES = "/api/v1/group-deliveries"
SENDER = "/api/v1/group-sender"


class RemoteControlPlane:
    """只调用正式群API，Client保留当前HMAC、稳定键及网络UNKNOWN语义。"""
    def __init__(self, client: Client):
        self.client = client

    def authorize(self, group, action):
        result = self.client.request("POST", ROOT + "/controls/authorize", {"group_binding_id": group, "action": action})
        if result.get("authorized") is not True or result.get("group_binding_id") != group or result.get("action") != action:
            raise SenderError("CURRENT_AUTHORIZATION_REQUIRED", 403)
        return result

    def current(self, group):
        result = self.client.request("GET", ROOT + "/controls/current", query={"group_binding_id": group})
        if result.get("group_binding_id") != group:
            raise SenderError("REMOTE_GROUP_IDENTITY_MISMATCH", 503)
        return result

    def submit(self, command):
        return self.client.request("POST", ROOT + "/controls", command, idempotency_key=command["command_id"])

    def detail(self, group, command):
        return self.client.request("GET", ROOT + "/controls/detail", query={"group_binding_id": group, "command_id": command})

    def terminalize(self, command, key):
        return self.client.request("POST", ROOT + "/controls/terminalize", command, idempotency_key=key)

    def release(self, command, key):
        return self.client.request("POST", ROOT + "/controls/release", command, idempotency_key=key)

    def register(self, command):
        return self.client.request("POST", ROOT + "/sender-endpoints/register", command,
                                   idempotency_key="sender-register:" + command["endpoint_id"] + ":" + str(command["generation"]))

    def retire(self, command):
        return self.client.request("POST", ROOT + "/sender-endpoints/retire", command, idempotency_key=command["retirement_receipt_ref"])

    def ack(self, command):
        return self.client.request("POST", ROOT + "/sender-endpoints/ack", command,
                                   idempotency_key=command["applied_boundary_ref"] + ":" + str(command["generation"]))

    def qualify(self, command):
        return self.client.request("POST", ROOT + "/deliveries/qualify", command,
                                   idempotency_key="sender-qualify:" + command["delivery_id"])


class HttpDeliverySink:
    """外部业务能力明确模拟：真实loopback HTTP，按原delivery查询回执，不自行重发。"""
    def __init__(self, origin: str):
        checked = Client(origin).origin
        if urlparse(checked).scheme != "http":
            raise SenderError("LOOPBACK_HTTP_SINK_REQUIRED", 400)
        self.origin, self.opener = checked, build_opener(NoRedirect())

    def _request(self, method, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        try:
            with self.opener.open(Request(self.origin + path, data=data, headers=headers, method=method), timeout=HTTP_TIMEOUT_SECONDS) as response:
                return json.load(response)
        except HTTPError as error:
            try:
                if method == "GET" and error.code == 404:
                    return None
                raise UnknownSend("SINK_RECEIPT_UNCONFIRMED") from None
            finally:
                error.close()
        except (URLError, OSError, TimeoutError, ValueError):
            raise UnknownSend("SINK_OUTCOME_UNKNOWN") from None

    def send(self, delivery):
        return self._request("POST", "/send", delivery)

    def receipt(self, delivery_id):
        return self._request("GET", "/receipts?" + urlencode({"delivery_id": delivery_id}))


@dataclass(frozen=True, repr=False)
class LocalActor:
    actor_id: str
    token: str
    remote: RemoteControlPlane


class SenderApplication:
    """每请求单独SQLite连接；generation、sink和SP映射只来自服务端启动配置。"""
    def __init__(self, state_path: Path, endpoint: Endpoint, sender: RemoteControlPlane,
                 sink: HttpDeliverySink, actors: tuple[LocalActor, ...]):
        if not actors or len({actor.token for actor in actors}) != len(actors):
            raise SenderError("UNIQUE_LOCAL_OPERATOR_TOKENS_REQUIRED", 400)
        self.state_path, self.endpoint, self.sender, self.sink, self.actors = state_path, endpoint, sender, sink, actors

    @staticmethod
    def from_config(path: Path, state_path: Path):
        config = json.loads(path.read_text(encoding="utf-8"))
        allowed = {"skillforge_origin", "sink_origin", "group_binding_id", "endpoint_id", "generation", "expected_generation", "sender_credential", "operators"}
        if set(config) != allowed:
            raise SenderError("EXACT_SENDER_CONFIGURATION_REQUIRED", 400)

        def environment(name):
            value = os.environ.get(identity(name, "environment_variable"))
            if not value:
                raise SenderError("SENDER_SECRET_ENVIRONMENT_REQUIRED", 400)
            return value

        def remote(definition):
            if set(definition) != {"api_key_env", "signing_secret_env"}:
                raise SenderError("CREDENTIAL_ENVIRONMENT_REFERENCES_REQUIRED", 400)
            return RemoteControlPlane(Client(config["skillforge_origin"], Credential(environment(definition["api_key_env"]), environment(definition["signing_secret_env"]))))

        actors = []
        for operator in config["operators"]:
            if set(operator) != {"actor_id", "token_env", "credential"}:
                raise SenderError("EXACT_LOCAL_OPERATOR_CONFIGURATION_REQUIRED", 400)
            actors.append(LocalActor(identity(operator["actor_id"], "actor_id"), environment(operator["token_env"]), remote(operator["credential"])))
        endpoint = Endpoint(config["group_binding_id"], config["endpoint_id"], config["generation"], config["expected_generation"])
        return SenderApplication(state_path, endpoint, remote(config["sender_credential"]), HttpDeliverySink(config["sink_origin"]), tuple(actors))

    def actor(self, authorization: str | None) -> LocalActor:
        supplied = authorization[len("Bearer "):] if authorization and authorization.startswith("Bearer ") else ""
        for actor in self.actors:
            if hmac.compare_digest(actor.token, supplied):
                return actor
        raise SenderError("LOCAL_AUTHENTICATION_REQUIRED", 401)

    @staticmethod
    def delivery_view(row: dict) -> dict:
        """控制读权只看投递事实，不由sender的发送权向本地操作者附赠正文读取权。"""
        result = {name: row[name] for name in ("group_id", "endpoint_id", "delivery_id", "request", "status", "generation", "control_revision", "blocked_refs")}
        receipt = row.get("receipt")
        result["receipt"] = {name: receipt[name] for name in ("delivery_id", "status", "result_ref") if name in receipt} if receipt else None
        return result

    def request(self, method: str, target: str, authorization: str | None, body: dict | None, key: str | None):
        actor = self.actor(authorization)
        parsed = urlparse(target)
        query = parse_qs(parsed.query, strict_parsing=True) if parsed.query else {}
        store = SenderStore(self.state_path)
        try:
            own_sender = GroupSender(store, self.endpoint, self.sender, self.sink)
            operator = GroupSender(store, self.endpoint, actor.remote, self.sink)
            if method == "GET":
                if parsed.path == LOCAL_INTENTS + "/commands/detail":
                    actor.remote.authorize(self.endpoint.group_binding_id, "reconcile")
                    if set(query) != {"operation", "request_key"} or any(len(values) != 1 for values in query.values()):
                        raise SenderError("EXACT_LOCAL_COMMAND_QUERY_REQUIRED", 400)
                    return asdict(store.command_receipt(actor.actor_id, self.endpoint.group_binding_id, query["operation"][0], query["request_key"][0]))
                actor.remote.authorize(self.endpoint.group_binding_id, "read")
                if parsed.path == SENDER + "/snapshot" and not query:
                    return store.snapshot(self.endpoint.group_binding_id)
                if parsed.path == LOCAL_INTENTS + "/detail" and set(query) == {"intent_id"} and len(query["intent_id"]) == 1:
                    intent = store.intent(query["intent_id"][0])
                    if intent["group_id"] != self.endpoint.group_binding_id:
                        raise SenderError("LOCAL_INTENT_OUTSIDE_GROUP", 403)
                    return intent
                if parsed.path == DELIVERIES + "/detail" and set(query) == {"delivery_id"} and len(query["delivery_id"]) == 1:
                    result = store.delivery(self.endpoint, query["delivery_id"][0])
                    if result is None:
                        raise SenderError("DELIVERY_NOT_FOUND", 404)
                    return self.delivery_view(result)
                raise SenderError("SENDER_ROUTE_NOT_FOUND", 404)
            if method != "POST" or query or not isinstance(body, dict):
                raise SenderError("EXACT_SENDER_JSON_REQUEST_REQUIRED", 400)
            if parsed.path == SENDER + "/endpoints/start" and not body:
                actor.remote.authorize(self.endpoint.group_binding_id, "manage")
                return own_sender.start()
            if parsed.path == SENDER + "/controls/apply" and body == {"group_binding_id": self.endpoint.group_binding_id}:
                actor.remote.authorize(self.endpoint.group_binding_id, "ack")
                return own_sender.apply()
            if parsed.path == DELIVERIES:
                actor.remote.authorize(self.endpoint.group_binding_id, "read")
                return self.delivery_view(own_sender.deliver(body))
            if parsed.path == DELIVERIES + "/reconcile" and set(body) == {"delivery_id"}:
                actor.remote.authorize(self.endpoint.group_binding_id, "reconcile")
                return self.delivery_view(own_sender.reconcile_delivery(body["delivery_id"]))
            request_key = identity(key, "idempotency_key")
            if parsed.path == LOCAL_INTENTS:
                return operator.create_intent(actor.actor_id, body, request_key)
            if parsed.path == LOCAL_INTENTS + "/reconcile":
                return operator.reconcile_intent(actor.actor_id, body, request_key)
            if parsed.path == LOCAL_INTENTS + "/release":
                return operator.release_intent(actor.actor_id, body, request_key)
            raise SenderError("SENDER_ROUTE_NOT_FOUND", 404)
        finally:
            store.close()
