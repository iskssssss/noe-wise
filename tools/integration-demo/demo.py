"""通用本地业务 Demo：唯一计价适配器连接独立钱包，展示收件箱与模拟媒体回执。"""
from __future__ import annotations

import argparse
import json
import logging
import hmac
import re
import sys
import os
from pathlib import Path
from urllib.parse import parse_qs, urlparse, unquote
from dataclasses import asdict

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "integration-scenario"))
from business_service import create_server as create_business_server
from state import State
from settlement_contract import (WalletClient, WalletFailure, WalletUnknown, SettlementError, POLICY, money,
                                 fingerprint, identity as contract_identity)
from client import ApiFailure
from group_console import GroupConsole, ConsoleFailure, load_contexts

LOG = logging.getLogger(__name__)
MAX_BODY = 1024 * 1024


def identity(value, max_length: int = 128) -> str:
    """标识保留原字符串精度，接口不允许浮点数或空身份。"""
    LOG.debug("校验模拟身份进入")
    if not isinstance(value, str) or not value.strip() or len(value) > max_length:
        raise ValueError("BOUNDED_STRING_IDENTITY_REQUIRED")
    LOG.debug("校验模拟身份退出")
    return value


def wallet_identity(value) -> str:
    """机构使用正式十进制字符串身份，禁止分隔符造成钱包键碰撞。"""
    LOG.debug("校验钱包主体身份进入")
    result = identity(value)
    if not re.fullmatch(r"[1-9][0-9]{0,63}", result):
        raise ValueError("DECIMAL_WALLET_IDENTITY_REQUIRED")
    LOG.debug("校验钱包主体身份退出")
    return result


def reconciliation_credential(token_env, wallet_token_env):
    """管理授权每次读取当前环境；只允许独立于钱包执行身份的凭据。"""
    token = os.environ.get(token_env) if isinstance(token_env, str) else None
    if not token or token_env == wallet_token_env or token == os.environ.get(wallet_token_env):
        return None
    return token


class DemoState(State):
    """唯一Adapter持有应收和outbox；独立钱包持有账户、准入和真实扣款。"""

    def __init__(self, path: Path, billing: dict):
        super().__init__(path)
        try:
            self.reconciliation_token_env = billing.get("reconciliation_token_env")
            self.wallet_token_env = billing["wallet_token_env"]
            if self.reconciliation_token_env == billing["wallet_token_env"]:
                raise ValueError("INDEPENDENT_RECONCILIATION_CREDENTIAL_REQUIRED")
            token = os.environ.get(billing["wallet_token_env"])
            if not token:
                raise ValueError("WALLET_SERVICE_CREDENTIAL_REQUIRED")
            self.wallet_client = WalletClient(billing["wallet_origin"], token)
            self.accounts = {}
            for alias, account in billing["accounts"].items():
                remote = self.wallet_client.request("GET", "/api/v1/accounts/detail", query=account)
                tenant = wallet_identity(remote["execution_tenant_id"])
                if ((alias.isdecimal() and alias != tenant) or remote["currency"] != account["currency"]
                        or remote["wallet_account_id"] != account["wallet_account_id"] or tenant in self.accounts):
                    raise ValueError("WALLET_ACCOUNT_BINDING_MISMATCH")
                self.accounts[tenant] = dict(account)
            if not self.accounts:
                raise ValueError("EXPLICIT_WALLET_ACCOUNTS_REQUIRED")
            self.pricing = billing["pricing"]
            self.configure_billing(self.pricing, self.accounts)
        except BaseException:
            self.close()
            raise

    def register_wallet(self, request: dict) -> dict:
        """仅核对预建独立账户；没有开户、充值或本地初始余额。"""
        if set(request) - {"tenant_id", "name", "initial_balance_cents"}:
            raise ValueError("FIXED_WALLET_REGISTRATION_REQUIRED")
        tenant = wallet_identity(request["tenant_id"])
        identity(request["name"])
        remote = self.wallet(tenant)
        if ("initial_balance_cents" in request
                and money(request["initial_balance_cents"]) != money(remote["initial_balance_cents"])):
            raise ValueError("WALLET_INITIAL_BALANCE_MISMATCH")
        return remote

    def wallet(self, tenant: str) -> dict:
        tenant = wallet_identity(tenant)
        if tenant not in self.accounts:
            raise ValueError("EXPLICIT_WALLET_ACCOUNT_REQUIRED")
        remote = self.wallet_client.request("GET", "/api/v1/accounts/detail", query=self.accounts[tenant])
        if remote["execution_tenant_id"] != tenant:
            raise ValueError("WALLET_ACCOUNT_BINDING_MISMATCH")
        return remote | {"tenant_id": tenant, "receivable_cents": self.totals().get(tenant), "wallet_state": "confirmed"}

    def admit(self, request: dict) -> dict:
        tenant = wallet_identity(request["tenant_id"])
        request_id = identity(request["request_id"], max_length=256)
        if tenant not in self.accounts:
            raise ValueError("EXPLICIT_WALLET_ACCOUNT_REQUIRED")
        return self.wallet_client.request("POST", "/api/v1/admissions", {
            "admission_id": request_id, "business_root_ref": request_id, "policy": POLICY,
            "execution_tenant_id": tenant, **self.accounts[tenant]}, idempotency_key=request_id)

    def consume(self, stream_key: str, page: dict, expected_cursor=None) -> dict:
        """本地应收/outbox先提交，再由唯一Adapter发送稳定钱包指令。"""
        identity(stream_key)
        self.consume_usage(stream_key, page, expected_cursor=expected_cursor)
        self.billing.dispatch(self.wallet_client)
        return {"next_cursor": self.get("usage_cursor:" + stream_key), "external_wallet": True}

    def dispatch(self):
        """显式恢复原outbox，UNKNOWN由Adapter沿原身份查询，页面不构造新扣款身份。"""
        return self.billing.dispatch(self.wallet_client)

    def reconcile_rejected(self, object_id: str, rejection_id: str, authorization_ref: str,
                           expected_revision=None, reconciliation_request=None):
        """仅供本地操作者显式授权；先以当前钱包身份核验固定scope，再沿原对象重结算。"""
        identity(object_id, max_length=256)
        identity(rejection_id, max_length=256)
        identity(authorization_ref, max_length=256)
        row = self.db.execute("SELECT execution_tenant_id,wallet_account_id,currency FROM settlement_objects "
                              "WHERE object_id=?", (object_id,)).fetchone()
        if row is None:
            raise ValueError("CURRENT_REJECTED_OBJECT_REQUIRED")
        tenant, account_id, currency = row
        if self.accounts.get(tenant) != {"wallet_account_id": account_id, "currency": currency}:
            raise ValueError("WALLET_ACCOUNT_BINDING_MISMATCH")
        remote = self.wallet(tenant)
        if remote["wallet_account_id"] != account_id or remote["currency"] != currency:
            raise ValueError("WALLET_ACCOUNT_BINDING_MISMATCH")
        instruction = self.billing.reconcile_rejection(object_id, rejection_id, authorization_ref,
            expected_revision=expected_revision, reconciliation_request=reconciliation_request)
        return {"instruction_id": instruction["instruction_id"], "dispatch": self.dispatch()}

    def reconcile_managed(self, request: dict, idempotency_key: str):
        """已鉴权管理入口只把受控actor和请求指纹传入Adapter原子审计，不信任正文授权声明。"""
        if (not isinstance(request, dict) or set(request) != {"object_id", "expected_revision", "rejected_instruction_id", "reason"}
                or isinstance(request["expected_revision"], bool) or not isinstance(request["expected_revision"], int)
                or request["expected_revision"] < 0):
            raise ValueError("EXPLICIT_RECONCILIATION_REQUEST_REQUIRED")
        key, reason = contract_identity(idempotency_key), contract_identity(request["reason"])
        actor = "demo-management"
        digest = fingerprint(request)
        authorization_ref = "operator:" + fingerprint([actor, key, digest])
        return self.reconcile_rejected(request["object_id"], request["rejected_instruction_id"], authorization_ref,
            expected_revision=request["expected_revision"], reconciliation_request={
                "actor": actor, "idempotency_key": key, "request_fingerprint": digest, "reason": reason})

    def snapshot(self) -> dict:
        """仅导出允许的展示事实，禁止暴露通用 kv、治理凭据和一次签发秘密。"""
        LOG.debug("读取模拟业务展示进入")
        wallets = [self.wallet(tenant) for tenant in sorted(self.accounts)]
        settlement = self.billing.snapshot()
        events = [json.loads(row[0]) for row in self.db.execute("SELECT payload FROM usage ORDER BY rowid DESC LIMIT 100")]
        inbox, pauses, media = [], [], []
        for key, payload in self.db.execute(
                "SELECT key,value FROM kv WHERE key LIKE 'delivery:%' OR key LIKE 'pause:%' "
                "OR key LIKE 'tool:media_transcribe:%' OR key LIKE 'tool:media_synthesize:%' "
                "OR key LIKE 'tool:video_analyze:%' ORDER BY rowid DESC LIMIT 250"):
            value = json.loads(payload)
            if key.startswith("delivery:"):
                inbox.append(value["request"] | {"receipt": value["result"]})
            elif key.startswith("pause:"):
                pauses.append({"route": key[len("pause:"):], "paused": bool(value)})
            else:
                _, name, request_id = key.split(":", 2)
                media.append({"name": name, "request_id": request_id, "arguments": value["request"],
                              "result": value["result"], "simulated": True})
        result = {"wallets": wallets, "settlement": settlement, "usage_events": events, "inbox": inbox,
                  "pauses": pauses, "media_receipts": media, "billing": {"pricing": self.pricing, "accounts": self.accounts},
                  "capabilities": {"settlement_reconciliation": bool(reconciliation_credential(
                      self.reconciliation_token_env, self.wallet_token_env))},
                  "business_services_simulated": True}
        LOG.debug("读取模拟业务展示退出 wallets=%s inbox=%s", len(wallets), len(inbox))
        return result


def create_server(port: int, path: Path, billing: dict, group_config: Path | None = None):
    """扩展既有 loopback MCP/WS/投递服务，媒体与消息仍复用唯一业务幂等实现。"""
    LOG.debug("创建通用本地业务Demo进入")
    with_state = DemoState(path, billing)
    with_state.close()
    server = create_business_server(port, path)
    original_handler = server.RequestHandlerClass
    groups = GroupConsole(load_contexts(group_config), Path(path).with_suffix(".groups.sqlite3"))
    server.group_console = groups

    class Handler(original_handler):
        def respond(self, code: int, data, content_type="application/json"):
            """输出有界展示响应；浏览器不缓存账本，也不向第三方开放跨域数据。"""
            LOG.debug("输出Demo响应进入 status=%s", code)
            raw = data.encode() if isinstance(data, str) else json.dumps(data, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(raw)
            LOG.debug("输出Demo响应退出 status=%s", code)

        def do_GET(self):
            """主页与展示 JSON 同源；既有 /ws 交给真实 WebSocket 帧实现。"""
            LOG.debug("读取Demo HTTP进入")
            parsed = urlparse(self.path)
            if parsed.path.startswith("/api/groups/"):
                self.group_get(parsed)
            elif parsed.path in {"/group-console.js", "/group-console.css"}:
                resource = HERE / parsed.path.removeprefix("/")
                if resource.is_file():
                    self.respond(200, resource.read_text(encoding="utf-8"), "text/javascript; charset=utf-8" if resource.suffix == ".js" else "text/css; charset=utf-8")
                else:
                    self.respond(404, {"error": "GROUP_UI_NOT_READY"})
            elif parsed.path == "/":
                self.respond(200, (HERE / "index.html").read_text(encoding="utf-8"), "text/html; charset=utf-8")
            elif parsed.path == "/health":
                self.respond(200, {"status": "ready", "business_services_simulated": True})
            elif parsed.path in {"/api/snapshot", "/api/cursor"}:
                state = None
                try:
                    state = DemoState(path, billing)
                    if parsed.path == "/api/snapshot":
                        self.respond(200, state.snapshot())
                    else:
                        stream_key = identity(parse_qs(parsed.query)["stream_key"][0])
                        self.respond(200, {"stream_key": stream_key, "cursor": state.get("usage_cursor:" + stream_key)})
                except WalletUnknown:
                    self.respond(503, {"error": "WALLET_OUTCOME_UNKNOWN"})
                except WalletFailure as failure:
                    self.respond(failure.status, {"error": failure.code})
                except (ValueError, KeyError, TypeError, ArithmeticError):
                    self.respond(422, {"error": "INVALID_DEMO_QUERY"})
                finally:
                    if state is not None:
                        state.close()
            else:
                super().do_GET()
            LOG.debug("读取Demo HTTP退出")

        def group_get(self, parsed):
            """读取section各自保留权限和失败事实，浏览器不能覆写固定服务执行域。"""
            try:
                try:
                    query = parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True) if parsed.query else {}
                except ValueError:
                    raise ConsoleFailure("GROUP_QUERY_INVALID", 400) from None
                if any(len(values) != 1 for values in query.values()):
                    raise ConsoleFailure("GROUP_QUERY_DUPLICATED", 400)
                route = [unquote(item) for item in parsed.path.strip("/").split("/")]
                if route == ["api", "groups", "contexts"] and not query:
                    result = groups.listing()
                elif len(route) == 4 and route[:2] == ["api", "groups"] and route[3] == "snapshot":
                    allowed = {"run_id", "members_after", "agents_after", "history_cursor", "memory_status", "subject_end_user_id"}
                    if set(query) - allowed:
                        raise ConsoleFailure("GROUP_QUERY_FIELD_INVALID", 400)
                    result = groups.snapshot(route[2], **{name: value[0] for name, value in query.items()})
                elif len(route) == 5 and route[:2] == ["api", "groups"] and route[3] == "commands" and not query:
                    result = groups.command(route[2], route[4])
                else:
                    raise ConsoleFailure("GROUP_ROUTE_NOT_FOUND", 404)
                self.respond(200, result)
            except (ConsoleFailure, ApiFailure) as failure:
                self.respond(failure.status, {"error": failure.code})
            except (RuntimeError, ValueError, KeyError, TypeError, AttributeError):
                self.respond(503, {"error": "GROUP_UPSTREAM_READ_FAILED"})

        def do_POST(self):
            """钱包接口仅接收有界 JSON；其它 MCP、暂停与投递路径保留既有正式 Demo 协议。"""
            LOG.debug("写入Demo HTTP进入")
            origin = self.headers.get("Origin")
            if (self.headers.get_content_type() != "application/json"
                    or (origin and urlparse(origin).netloc != self.headers.get("Host"))):
                self.close_connection = True
                self.respond(403, {"error": "SAME_ORIGIN_JSON_REQUIRED"})
                LOG.debug("写入Demo HTTP退出 accepted=false")
                return
            if urlparse(self.path).path.startswith("/api/groups/"):
                self.group_post()
                return
            managed_reconciliation = self.path == "/api/v1/settlement-reconciliations"
            if managed_reconciliation:
                expected = reconciliation_credential(billing.get("reconciliation_token_env"), billing["wallet_token_env"])
                supplied = self.headers.get("Authorization", "")
                if not expected or not hmac.compare_digest(supplied.encode(), ("Bearer " + expected).encode()):
                    self.close_connection = True
                    self.respond(403, {"error": "CURRENT_RECONCILIATION_MANAGEMENT_IDENTITY_REQUIRED"})
                    return
            if self.path not in {"/api/wallet/register", "/api/wallet/admit", "/api/usage/consume", "/api/settlements/dispatch", "/api/v1/settlement-reconciliations"}:
                super().do_POST()
                LOG.debug("写入Demo HTTP退出 forwarded=true")
                return
            state = None
            try:
                state = DemoState(path, billing)
                size = int(self.headers.get("Content-Length", "0"))
                if size < 1 or size > MAX_BODY or self.headers.get_content_type() != "application/json":
                    raise ValueError("BOUNDED_JSON_REQUIRED")
                request = json.loads(self.rfile.read(size))
                if managed_reconciliation:
                    result = state.reconcile_managed(request, self.headers.get("Idempotency-Key"))
                elif self.path == "/api/wallet/register":
                    result = state.register_wallet(request)
                elif self.path == "/api/wallet/admit":
                    result = state.admit(request)
                elif self.path == "/api/settlements/dispatch":
                    if request:
                        raise ValueError("EMPTY_DISPATCH_REQUEST_REQUIRED")
                    result = state.dispatch()
                else:
                    result = state.consume(request["stream_key"], request["page"], request.get("expected_cursor"))
                self.respond(200, result)
            except WalletUnknown:
                self.respond(503, {"error": "WALLET_OUTCOME_UNKNOWN"})
            except WalletFailure as failure:
                self.respond(failure.status, {"error": failure.code})
            except SettlementError as failure:
                self.respond(failure.status, {"error": failure.code})
            except (ValueError, KeyError, TypeError, ArithmeticError):
                self.respond(422, {"error": "INVALID_OR_CONFLICTING_DEMO_REQUEST"})
            finally:
                if state is not None:
                    state.close()
                LOG.debug("写入Demo HTTP退出")

        def group_post(self):
            """单次派发及持久原回执；网络未知/损坏成功回包不自动重发或换键。"""
            try:
                parsed = urlparse(self.path)
                route = [unquote(item) for item in parsed.path.strip("/").split("/")]
                if parsed.query or len(route) not in (4, 5) or route[:2] != ["api", "groups"]:
                    raise ConsoleFailure("GROUP_ROUTE_NOT_FOUND", 404)
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    raise ConsoleFailure("GROUP_CONTENT_LENGTH_INVALID", 400) from None
                if size < 1 or size > MAX_BODY:
                    raise ConsoleFailure("GROUP_BOUNDED_JSON_REQUIRED", 413)
                try:
                    request = json.loads(self.rfile.read(size))
                except (ValueError, UnicodeDecodeError):
                    raise ConsoleFailure("GROUP_JSON_INVALID", 400) from None
                status, receipt = groups.submit(route[2], "/".join(route[3:]), request, self.headers.get("Idempotency-Key"))
                self.respond(status, asdict(receipt))
            except (ConsoleFailure, ApiFailure) as failure:
                self.respond(failure.status, {"error": failure.code})
            except (RuntimeError, ValueError, KeyError, TypeError, AttributeError):
                self.respond(503, {"error": "GROUP_REQUEST_NOT_DISPATCHED"})

    server.RequestHandlerClass = Handler
    LOG.debug("创建通用本地业务Demo退出")
    return server


def main():
    """仅启动外部模拟服务；SkillForge 的模型、迁移和宿主由验收任务独立管理。"""
    LOG.debug("本地业务Demo命令进入")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8098)
    parser.add_argument("--state", type=Path, default=HERE / ".state" / "demo.sqlite3")
    parser.add_argument("--billing-config", type=Path, required=True)
    parser.add_argument("--group-config", type=Path, help="固定群执行上下文；文件仅保存凭据环境变量名称")
    parser.add_argument("--dispatch-only", action="store_true", help="仅恢复原结算outbox，不消费事件或运行模型")
    parser.add_argument("--reconcile-object")
    parser.add_argument("--reconcile-rejection")
    parser.add_argument("--authorization-ref", help="本地操作者显式提供的拒绝重结算授权审计引用")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    reconciliation = (args.reconcile_object, args.reconcile_rejection, args.authorization_ref)
    if (any(reconciliation) and not all(reconciliation)) or (args.dispatch_only and any(reconciliation)):
        parser.error("拒绝重结算须同时提供object、rejection和authorization-ref，且不能与dispatch-only组合")
    billing = json.loads(args.billing_config.read_text(encoding="utf-8"))
    if args.dispatch_only or all(reconciliation):
        state = DemoState(args.state, billing)
        try:
            result = state.dispatch() if args.dispatch_only else state.reconcile_rejected(*reconciliation)
            print(json.dumps(result, ensure_ascii=False))
        finally:
            state.close()
        return
    server = create_server(args.port, args.state, billing, args.group_config)
    print("业务模拟Demo已就绪: http://127.0.0.1:" + str(server.server_port), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        LOG.debug("本地业务Demo命令退出")


if __name__ == "__main__":
    main()
