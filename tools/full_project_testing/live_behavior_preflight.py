"""Free actual preflight: no chat, model, ingestion, new Session, or ledger writes."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

from run_suite import REPO, load_environment, save
from live_paid_actors import paid_actor_environment
from live_behavior_inputs import capture_inputs, file_hash, ready_binding, product_dataset

sys.path.insert(0, str(REPO / "tools/chat_regression"))
from behavior_environment import configuration
from client import Api, failure_diagnostic


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--product-ui-diagnostic", type=int, required=True,
                        help="fresh positive diagnostic ordinal on the confirmed original empty D09 target")
    args = parser.parse_args(argv)
    root = args.output.resolve()
    allowed = (REPO / ".runtime-logs/full-project-test-20261012").resolve()
    if not root.is_relative_to(allowed) or root == allowed or args.product_ui_diagnostic < 1:
        parser.error("preflight requires a new owned private subdirectory and positive diagnostic ordinal")
    product_dataset(os.environ)
    root.mkdir(parents=True, exist_ok=False)
    env_root = allowed / "environment"
    environment = load_environment(env_root / "isolated.env")
    refs = json.loads((env_root / "provision.json").read_text(encoding="utf-8"))
    result = {"schema_version": 1, "run_id": "full-project-test-20261012", "status": "NOT_READY",
              "started_at": datetime.now(timezone.utc).isoformat(), "paid_dispatches": 0,
              "paid_actor_used_for_authentication": False, "new_chat_inputs": 0,
              "new_sessions": 0, "ledger_mutated": False}
    try:
        auth = Api(refs["api"]).request("POST", "/auth/login", {"tenant_slug": environment["SF_TENANT"],
            "username": environment["SF_ADMIN_USER"], "password": environment["SF_ADMIN_PASSWORD"]}, _rate_retry=True)
        platform_auth = Api(refs["platform"]).request("POST", "/platform/auth/login", {
            "username": environment["SF_PLATFORM_USER"], "password": environment["SF_PLATFORM_PASSWORD"]}, _rate_retry=True)
        api = Api(refs["api"], auth["access_token"])
        platform = Api(refs["platform"], platform_auth["access_token"], True)
        result["stage"] = "actual_required_model_configuration"
        snapshot = configuration(api, platform, {"model_id": str(refs["models"]["llm"]["id"]), "agents": {}})
        save(root / "configuration.json", snapshot)
        result["configuration_binding"] = ready_binding(snapshot)

        # Exact original D09 allocator fixture, already CONFIRMED. The Node gate only
        # permits free authentication/profile and GET; chat and Session POST are denied.
        fixture = allowed / "product-target-native-preflight"
        result["stage"] = "actual_product_ui_readback"
        receipt = json.loads((fixture / "target-session-receipt.json").read_text(encoding="utf-8"))
        if receipt.get("status") != "CONFIRMED" or receipt.get("root_input_count") != 0:
            raise ValueError("confirmed_empty_original_d09_fixture_required")
        ui_root = fixture / f"diagnostic-{args.product_ui_diagnostic}"
        if ui_root.exists():
            raise ValueError("product_ui_diagnostic_already_exists_no_replay")
        child_environment = dict(environment, PRODUCT_VALID_PREFLIGHT_FIXTURE=str(fixture),
                                 PRODUCT_VALID_PREFLIGHT_USE_RUNNER_SESSION="1")
        with (root / "product-ui-command.log").open("w", encoding="utf-8") as log:
            child = subprocess.run(["node", str(REPO / "tools/full_project_testing/check_product_valid_channel.mjs"),
                f"--diagnostic={args.product_ui_diagnostic}"], cwd=REPO, env=child_environment,
                stdout=log, stderr=log, timeout=300, check=False)
        ui_file = ui_root / "result.json"
        ui = json.loads(ui_file.read_text(encoding="utf-8"))
        if child.returncode != 0 or ui.get("status") != "PASS" or ui.get("model_calls") != 0:
            raise ValueError("actual_product_ui_preflight_not_passed")
        checks = ui.get("checks", {})
        required_checks = ("actual_admin_and_native_history_identity", "actual_admin_artifact_endpoint",
                           "actual_product_runner_allocated_session_readback")
        if any(checks.get(name) != "PASS" for name in required_checks):
            raise ValueError("actual_product_ui_required_consumer_checks_missing")
        result["actual_product_ui"] = {"status": "PASS", "source": str(ui_file.relative_to(REPO)),
                                        "source_sha256": file_hash(ui_file), "checks": checks,
                                        "model_calls": 0, "new_session_created": False}
        # Capture the exact paid environment without authenticating with its credentials.
        # The same explicit mapping is used by the eventual serial paid wrapper.
        paid_environment = paid_actor_environment(environment)
        result["stage"] = "actual_execution_input_manifests"
        manifests = {suite: capture_inputs(REPO, suite, paid_environment) for suite in ("core", "stress", "product")}
        for suite, manifest in manifests.items():
            save(root / f"{suite}-input-manifest.json", manifest)
        result["input_fingerprints"] = {suite: value["fingerprint"] for suite, value in manifests.items()}
        result.update(status="READY", finished_at=datetime.now(timezone.utc).isoformat())
    except Exception as error:
        result.update(finished_at=datetime.now(timezone.utc).isoformat(), failure=failure_diagnostic(error))
    save(root / "ready.json", result)
    print(json.dumps({"status": result["status"], "ready_receipt": str(root / "ready.json"), "paid_dispatches": 0}))
    return 0 if result["status"] == "READY" else 1


if __name__ == "__main__":
    raise SystemExit(main())
