"""通过真实认证与管理 API 初始化隔离验收身份和模型，不访问已有服务。"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import sys
from run_suite import REPO, load_environment, save

sys.path.insert(0, str(REPO / "tools/chat_regression"))
from client import Api


def main():
    """创建三个机构与固定发布模型；将引用写入私有收据。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_relative_to((REPO / ".runtime-logs").resolve()):
        parser.error("仅允许本批私有环境目录")
    state = json.loads((root / "environment.json").read_text(encoding="utf-8"))
    environment = load_environment(root / "isolated.env")
    platform_url = state["services"]["platform-service"]["base_url"] + "/api/v1"
    institution_url = state["services"]["institution-service"]["base_url"] + "/api/v1"
    gateway_url = state["services"]["gateway"]["base_url"] + "/api/v1"
    data = Api(platform_url).request("POST", "/platform/auth/login", {
        "username": environment["SF_PLATFORM_USER"], "password": environment["SF_PLATFORM_PASSWORD"]})
    platform = Api(platform_url, data["access_token"], True)
    receipt = {"api": institution_url, "platform": platform_url, "gateway": gateway_url, "tenants": [], "models": {}}
    save(root / "provision.json", receipt)
    for suffix in ("a", "b", "c"):
        tenant = platform.request("POST", "/admin/tenants", {
            "tenant_name": f"复杂测试机构-{suffix.upper()}", "tenant_slug": f"full-test-{suffix}", "plan_code": "free",
            "admin_username": "tenant-admin", "admin_password": environment["SF_PASSWORD"],
            "admin_email": f"tenant-admin-{suffix}@example.test"})
        receipt["tenants"].append(tenant)
        save(root / "provision.json", receipt)
    model = platform.request("POST", "/admin/models", {
        "name": "三轮隔离验收 DeepSeek Flash", "kind": "LLM", "enabled": True,
        "provider_id": "deepseek", "service_id": "deepseek-api", "profile_version": "2026-09-13",
        "protocol": "openai-chat-completions", "authentication_scheme": "bearer-api-key",
        "endpoint": "https://api.deepseek.com", "model_id": "deepseek-flash", "api_key": environment["SDK4_LIVE_MODEL_KEY"],
        "context_window_tokens": 1000000, "supported_max_output_tokens": 32768, "max_output_tokens": 4096,
        "temperature": .2, "reasoning": "DISABLED", "timeout_seconds": 120,
        "input_price_per_million_cents": "30", "output_price_per_million_cents": "120", "currency": "USD"})
    published = platform.request("POST", "/admin/models/publish", {"id": model["id"], "expected_revision": model["revision"]})
    receipt["models"]["llm"] = {"id": published["id"], "revision": published["revision"], "model_id": published["model_id"],
                                "currency": "USD", "price_basis": "peak public list price; cache/time discounts settled separately",
                                "price_source": "https://api-docs.deepseek.com/quick_start/pricing"}
    save(root / "provision.json", receipt)
    data = Api(institution_url).request("POST", "/auth/login", {"tenant_slug": "full-test-a", "username": "tenant-admin", "password": environment["SF_PASSWORD"]})
    institution = Api(institution_url, data["access_token"])
    institution.request("PUT", "/settings/model-selections", {"llm_model_config_id": published["id"]})
    # 仅追加受控引用和测试账号字段，临时密钥保持原私有环境文件归属。
    with (root / "isolated.env").open("a", encoding="utf-8") as stream:
        stream.write(f"SF_ADMIN_USER=tenant-admin\nSF_ADMIN_PASSWORD={environment['SF_PASSWORD']}\n")
        stream.write(f"LIVE_MEMORY_SOURCE_MODEL_ID={published['id']}\nLIVE_MEMORY_SOURCE_MODEL_REVISION={published['revision']}\n")
        stream.write(f"LIVE_PROMPT_CACHE_SOURCE_MODEL_ID={published['id']}\nLIVE_PROMPT_CACHE_SOURCE_MODEL_REVISION={published['revision']}\n")
        stream.write(f"LIVE_CONFIG_SOURCE_MODEL_ID={published['id']}\nLIVE_CONFIG_SOURCE_MODEL_REVISION={published['revision']}\n")
    receipt["authentication"] = {"platform_login": "PASS", "institution_login": "PASS", "selection": "PASS"}
    save(root / "provision.json", receipt)
    print(json.dumps({"tenants": len(receipt["tenants"]), "model_id": published["id"], "model_revision": published["revision"], "authentication": "PASS"}))


if __name__ == "__main__":
    main()
