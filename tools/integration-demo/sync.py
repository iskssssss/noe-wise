"""只读场景凭据，经真实 SkillForge 用量 API 将证据同步到本地业务 Demo。"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sqlite3
from pathlib import Path
from urllib.parse import urlencode, urlparse
from urllib.request import Request, build_opener

from client import Client, ManagementCredential, NoRedirect

LOG = logging.getLogger(__name__)


def demo_request(origin: str, path: str, body=None) -> dict:
    """只向明确 loopback Demo 传递无凭据的投影，不转发 SkillForge HMAC 头。"""
    LOG.debug("调用本地Demo接口进入")
    parsed = urlparse(origin)
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parsed.username or parsed.password or parsed.path not in {"", "/"} or parsed.query or parsed.fragment):
        raise ValueError("EXPLICIT_LOOPBACK_DEMO_REQUIRED")
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    with build_opener(NoRedirect()).open(Request(origin.rstrip("/") + path, data=data, headers=headers,
                                                method="GET" if body is None else "POST"), timeout=30) as response:
        result = json.load(response)
    LOG.debug("调用本地Demo接口退出")
    return result


def sync(config: dict, scenario_path: Path, demo_origin: str) -> dict:
    """消费真实导出事实；场景 DB 只读打开，凭据仅驻留此进程，游标由 Demo 原子消费结果推进。"""
    LOG.debug("同步真实用量至Demo进入")
    display = demo_request(demo_origin, "/api/snapshot")
    if config["billing"]["pricing"] != display["billing"]["pricing"]:
        raise ValueError("DEMO_AND_SCENARIO_BILLING_PRICE_MISMATCH")
    source = sqlite3.connect(scenario_path.resolve().as_uri() + "?mode=ro", uri=True)
    count = 0
    try:
        rows = source.execute("SELECT key,value FROM kv WHERE key LIKE 'step:tenant:%' ORDER BY key").fetchall()
        if not rows:
            raise ValueError("COMPLETED_INSTITUTION_SETUP_REQUIRED")
        key = os.environ.get("SKILLFORGE_SCENARIO_MANAGEMENT_KEY")
        secret = os.environ.get("SKILLFORGE_SCENARIO_MANAGEMENT_SECRET")
        if bool(key) != bool(secret):
            raise ValueError("MANAGEMENT_KEY_AND_SECRET_REQUIRED_TOGETHER")
        token = None if key else os.environ.get("SKILLFORGE_SCENARIO_PLATFORM_TOKEN")
        if not key and not token:
            raise ValueError("EXPLICIT_PLATFORM_MANAGEMENT_IDENTITY_REQUIRED")
        client = Client(config["platform_origin"],
            management_credential=ManagementCredential(key, secret) if key else None, platform_token=token)
        tenants = set()
        aliases = {}
        for step_key, serialized in rows:
            step = json.loads(serialized)
            if step["state"] != "done":
                raise ValueError("COMPLETED_INSTITUTION_SETUP_REQUIRED")
            tenants.add(str(step["data"]["tenant_id"]))
            aliases[step_key[len("step:tenant:"):]] = str(step["data"]["tenant_id"])
        accounts = {aliases.get(alias, alias): binding for alias, binding in config["billing"]["accounts"].items()}
        if accounts != display["billing"]["accounts"] or not tenants <= accounts.keys():
            raise ValueError("DEMO_AND_SCENARIO_WALLET_BINDING_MISMATCH")
        # 本地 checkpoint 只给出目标；当前授权目录仍须经过真实平台查询确认。
        for tenant in sorted(tenants):
            listing = client.request("GET", "/platform/institutions", query={"tenant_id": tenant, "page": 1, "page_size": 200})
            associations = listing["items"]
            if not associations or any(str(item["tenant_id"]) != tenant for item in associations):
                raise ValueError("CURRENT_AUTHORIZED_INSTITUTION_MAPPING_REQUIRED")
            association = associations[0]
            demo_request(demo_origin, "/api/wallet/register", {"tenant_id": tenant,
                "name": association.get("tenant_name") or "机构 " + tenant})
            stream_key = "platform:" + tenant
            while True:
                cursor = demo_request(demo_origin, "/api/cursor?" + urlencode({"stream_key": stream_key}))["cursor"]
                page = client.request("GET", "/platform/usage/events", query={"tenant_id": tenant, "cursor": cursor, "limit": 100})
                if any(str(event["tenant_id"]) != tenant for event in page["items"]):
                    raise ValueError("USAGE_TENANT_FILTER_MISMATCH")
                payload = {"stream_key": stream_key, "expected_cursor": cursor, "page": page}
                checkpoint = demo_request(demo_origin, "/api/usage/consume", payload)
                if checkpoint["next_cursor"] != page["next_cursor"]:
                    raise AssertionError("DEMO_USAGE_CHECKPOINT_MISMATCH")
                count += len(page["items"])
                if not page["has_more"]:
                    break
    finally:
        source.close()
    result = {"consumed_events": count, "business_services_simulated": True}
    LOG.debug("同步真实用量至Demo退出 count=%s", count)
    return result


def main():
    """单次同步命令不创建模型请求、机构、Agent 或付款，只消费已存在真实用量。"""
    LOG.debug("Demo同步命令进入")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--billing-config", type=Path)
    parser.add_argument("--scenario-state", type=Path, required=True)
    parser.add_argument("--demo-origin", default="http://127.0.0.1:8098")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    config = json.loads(args.config.read_text(encoding="utf-8"))
    if args.billing_config:
        config["billing"] = json.loads(args.billing_config.read_text(encoding="utf-8"))
    result = sync(config, args.scenario_state, args.demo_origin)
    print(json.dumps(result, ensure_ascii=False))
    LOG.debug("Demo同步命令退出")


if __name__ == "__main__":
    main()
