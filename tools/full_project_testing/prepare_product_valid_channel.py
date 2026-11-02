"""Owned nonpaid channel fixture for the real Product bridge's valid-profile preflight."""
from __future__ import annotations

import json
from pathlib import Path
import uuid
import sys

from run_suite import REPO, load_environment, save
sys.path.insert(0, str(REPO / "tools/chat_regression"))
from client import Api, query, failure_diagnostic


def main():
    root = REPO / ".runtime-logs/full-project-test-20261012/product-valid-channel-preflight"
    root.mkdir(exist_ok=False)
    environment_root = root.parent / "environment"
    environment = load_environment(environment_root / "isolated.env")
    refs = json.loads((environment_root / "provision.json").read_text(encoding="utf-8"))
    source_root = root.parent / "live-behavior/core/round-3/diagnostic-1"
    resources = json.loads((source_root / "resources.json").read_text(encoding="utf-8"))
    agent_id = resources["agents"]["behavior"]["id"]
    auth = Api(refs["api"]).request("POST", "/auth/login", {"tenant_slug": environment["SF_TENANT"],
        "username": environment["SF_ADMIN_USER"], "password": environment["SF_ADMIN_PASSWORD"]}, _rate_retry=True)
    api = Api(refs["api"], auth["access_token"])
    agent = api.request("GET", query("/agents/detail", agentId=agent_id))
    if str(agent.get("id")) != str(agent_id) or agent.get("status") != "published" or not agent.get("agent_key", "").startswith("full-core-r3-"):
        raise ValueError("owned_source_agent_identity_unverified")
    save(root / "fixture-intent.json", {"agent_id": str(agent_id), "source": str(source_root),
         "channel_name": "full-product-valid-channel-preflight-20261012", "model_calls": 0, "existing_agent_config_changed": False})
    try:
        channel = api.request("POST", "/channels", {"name": "full-product-valid-channel-preflight-20261012",
                              "channel_type": "web_widget"}, _rate_retry=True)
        private = {"api": refs["api"], "channel_id": str(channel["id"]), "channel_token": channel["channel_token"],
                   "agent_id": str(agent_id), "client_ref": str(uuid.uuid4()),
                   "admin_tenant": environment["SF_TENANT"], "admin_username": environment["SF_ADMIN_USER"],
                   "admin_password": environment["SF_ADMIN_PASSWORD"]}
        save(root / "fixture-private.json", private)
        save(root / "channel-binding-intent.json", {"channel_id": private["channel_id"], "agent_id": str(agent_id)})
        api.request("PUT", "/channels/agents", {"channel_id": private["channel_id"], "payload": [
            {"agent_id": int(agent_id), "is_default": True, "priority": 100, "traffic_percentage": 100}]}, _rate_retry=True)
        save(root / "fixture-ready.json", {"channel_id": private["channel_id"], "agent_id": str(agent_id),
             "model_calls": 0, "writes": ["create_owned_channel", "bind_owned_channel_to_existing_owned_agent"],
             "paid_actor_used": False, "existing_agent_config_changed": False})
        print("product-valid-channel fixture READY; no model dispatch", flush=True)
    except Exception as error:
        save(root / "fixture-failure.json", {**failure_diagnostic(error), "no_unknown_write_replay": True})
        raise


if __name__ == "__main__":
    main()
