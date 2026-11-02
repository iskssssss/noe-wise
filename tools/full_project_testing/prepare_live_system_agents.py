"""仅配置本批隔离平台的必要系统 Agent；保留正式默认 Prompt/预算，不调用供应商。"""
from copy import deepcopy
import hashlib
import json
from pathlib import Path

import live_behavior as live
from client import login
from run_suite import save

PURPOSES = ("task-context", "memory-extraction", "context-summary")
ROOT = live.ENVIRONMENT_ROOT / "system-agent-provision"


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    journal_path = ROOT / "write-journal.json"
    journal = json.loads(journal_path.read_text(encoding="utf-8")) if journal_path.exists() else {"writes": []}
    if any(row["phase"] == "intent" for row in journal["writes"]):
        raise ValueError("unknown_configuration_write_query_only_do_not_replay")
    references = json.loads((live.ENVIRONMENT_ROOT / "provision.json").read_text(encoding="utf-8"))
    model_id = str(references["models"]["llm"]["id"])
    with live.process_environment(live.load_environment(live.ENVIRONMENT_ROOT / "isolated.env")):
        platform = login(references["platform"], True)
        models = platform.request("GET", "/admin/models")
        model = next((row for row in models if str(row.get("id")) == model_id), None)
        if not model or not model.get("enabled") or model.get("kind") != "LLM" or not model.get("credential_configured"):
            raise ValueError("published_batch_llm_required")

        def write(method, path, body, purpose):
            row = {"sequence": len(journal["writes"]) + 1, "method": method, "path": path,
                   "purpose": purpose, "agent_id": body["agent_id"], "expected_revision": body["expected_revision"],
                   "body_sha256": fingerprint(body), "phase": "intent"}
            journal["writes"].append(row)
            save(journal_path, journal)
            result = platform.request(method, path, body)
            if (result.get("id") != body["agent_id"] or result.get("revision") != body["expected_revision"] + 1):
                raise ValueError("configuration_write_receipt_identity_mismatch")
            row.update(phase="receipt", revision=result["revision"], status=result["status"],
                       published_version=result.get("published_version"), response_sha256=fingerprint(result))
            save(journal_path, journal)
            return result

        results = []
        for purpose in PURPOSES:
            rows = platform.request("GET", "/admin/system-agents")
            selected = [row for row in rows if row.get("purpose") == purpose]
            if len(selected) != 1:
                raise ValueError("exactly_one_formal_system_agent_required")
            current = selected[0]
            draft = deepcopy(current["draft"])
            before = {"id": current["id"], "revision": current["revision"],
                      "status": current["status"], "config": draft, "config_sha256": fingerprint(draft)}
            path = ROOT / f"{purpose}-before.json"
            if not path.exists():
                save(path, before)
            draft["model_config_id"] = model_id
            if current["draft"] != draft:
                current = write("PUT", "/admin/system-agents/update", {"agent_id": current["id"],
                    "expected_revision": current["revision"], "config": draft}, purpose)
            if current.get("status") != "published" or current.get("published") != draft:
                current = write("POST", "/admin/system-agents/publish", {"agent_id": current["id"],
                    "expected_revision": current["revision"]}, purpose)
            if current.get("published") != draft or current.get("status") != "published":
                raise ValueError("system_agent_published_config_readback_mismatch")
            # Defaults must survive model binding unchanged, including the actual provider deadline.
            if {k: v for k, v in current["published"].items() if k != "model_config_id"} != {
                    k: v for k, v in before["config"].items() if k != "model_config_id"}:
                raise ValueError("formal_default_system_config_changed")
            result = {"purpose": purpose, "id": current["id"], "revision": current["revision"],
                      "published_version": current["published_version"], "model_config_id": model_id,
                      "config_sha256": fingerprint(draft), "prompt_sha256": hashlib.sha256(draft["system_prompt"].encode()).hexdigest(),
                      "timeout_seconds": draft["timeout_seconds"], "max_business_model_calls": draft["max_business_model_calls"], "supplier_calls": 0}
            save(ROOT / f"{purpose}-receipt.json", result)
            results.append(result)
        save(ROOT / "summary.json", {"platform": references["platform"], "supplier_calls": 0,
                                     "formal_default_config_preserved": True, "system_agents": results})
    print(json.dumps({"configured_system_agents": len(results), "supplier_calls": 0, "receipt_root": str(ROOT)}))


if __name__ == "__main__":
    main()
