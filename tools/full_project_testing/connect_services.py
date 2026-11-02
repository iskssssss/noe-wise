"""将本批独立机构/Gateway副本连接到本批沙箱与浏览器，只管理清单所拥有进程。"""
from __future__ import annotations
import argparse
import json
import os
import re
from pathlib import Path
import secrets
import subprocess
import time
from isolated_environment import start_service
from run_suite import load_environment, save


def terminate_owned(row):
    pid = row["pid"]
    if type(pid) is not int or pid <= 0:
        raise ValueError("owned_pid_invalid")
    command = f"Get-CimInstance Win32_Process -Filter 'ProcessId={pid}' | Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"
    raw = subprocess.check_output(["pwsh", "-NoProfile", "-Command", command], text=True, encoding="utf-8").strip()
    if not raw:
        return
    process = json.loads(raw)
    if str(Path(row["jar"]).resolve()) not in (process.get("CommandLine") or ""):
        raise ValueError("pid_ownership_changed")
    result = subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
    if result.returncode:
        raise RuntimeError("owned_service_termination_failed")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        output = subprocess.check_output(["pwsh", "-NoProfile", "-Command", command], text=True, encoding="utf-8").strip()
        if not output:
            return
        time.sleep(.2)
    raise RuntimeError("owned_process_not_quiescent")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", type=Path, required=True)
    parser.add_argument("--sandbox", type=Path, required=True)
    parser.add_argument("--browser", type=Path, required=True)
    parser.add_argument("--receipt-name", default="tools-connection.json")
    args = parser.parse_args()
    root = args.environment.resolve()
    if not root.is_relative_to((Path(__file__).resolve().parents[2] / ".runtime-logs").resolve()):
        raise ValueError("environment_must_be_private")
    state = json.loads((root / "environment.json").read_text(encoding="utf-8"))
    if not re.fullmatch(r"tools-connection(?:-[a-z0-9-]+)?\.json", args.receipt_name):
        raise ValueError("connection_receipt_name_invalid")
    receipt_path = root / args.receipt_name
    if receipt_path.exists():
        raise ValueError("connection_attempt_exists_review_before_repeat")
    environment = load_environment(root / "isolated.env")
    sandbox = json.loads(args.sandbox.read_text(encoding="utf-8"))
    browser = json.loads(args.browser.read_text(encoding="utf-8"))
    for configuration in (sandbox, browser):
        if configuration["batch_id"] != "full-test-20261012":
            raise ValueError("tool_batch_mismatch")
    old = {name: dict(state["services"][name]) for name in ("institution-service", "gateway")}
    save(receipt_path, {"status": "CONNECTING", "prior_services": old})
    terminate_owned(old["gateway"])
    terminate_owned(old["institution-service"])
    environment.update({"SANDBOX_ENDPOINT": sandbox["endpoint"], "SANDBOX_SERVICE_TOKEN": sandbox["token"],
                        "BROWSER_SERVICE_URL": browser["endpoint"], "BROWSER_SERVICE_TOKEN": browser["token"],
                        "ARTIFACT_TICKET_KEY": secrets.token_hex(32)})
    institution = start_service(root, state, "institution-service", environment, ("--spring.flyway.enabled=false",))
    environment.update({"INSTITUTION_URI": f"http://127.0.0.1:{institution}", "INSTITUTION_WS_URI": f"ws://127.0.0.1:{institution}"})
    start_service(root, state, "gateway", environment)
    modified = ("SANDBOX_ENDPOINT", "SANDBOX_SERVICE_TOKEN", "BROWSER_SERVICE_URL", "BROWSER_SERVICE_TOKEN", "ARTIFACT_TICKET_KEY",
                "INSTITUTION_URI", "INSTITUTION_WS_URI")
    with (root / "isolated.env").open("a", encoding="utf-8") as stream:
        stream.write("".join(f"{key}={environment[key]}\n" for key in modified))
    receipt = json.loads((root / "provision.json").read_text(encoding="utf-8"))
    receipt.update(api=state["services"]["institution-service"]["base_url"] + "/api/v1",
                   gateway=state["services"]["gateway"]["base_url"] + "/api/v1")
    save(root / "provision.json", receipt)
    save(receipt_path, {"status": "READY", "prior_services": old, "current_services": state["services"],
        "sandbox_endpoint": sandbox["endpoint"], "browser_endpoint": browser["endpoint"], "paid_model_calls": 0})
    print(f"isolated connections READY institution={institution} gateway={state['services']['gateway']['port']}")


if __name__ == "__main__":
    main()
