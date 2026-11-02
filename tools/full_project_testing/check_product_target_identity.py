"""Revalidate only the confirmed original empty target; never allocate/replay an input."""
import json
import re
import sys
from run_suite import REPO, load_environment, save
sys.path.insert(0, str(REPO / "tools/chat_regression"))
from client import Api, ApiError, query, failure_diagnostic

parent = REPO / ".runtime-logs/full-project-test-20261012"
root = parent / "product-target-native-preflight"
diagnostic = next((value.split("=", 1)[1] for value in sys.argv[1:] if re.fullmatch(r"--diagnostic=[1-9][0-9]*", value)), "1")
target = root / ("recovery-diagnostic-" + diagnostic)
target.mkdir(exist_ok=False)
private = json.loads((root / "fixture-private.json").read_text(encoding="utf-8"))
receipt = json.loads((root / "target-session-receipt.json").read_text(encoding="utf-8"))
assert receipt["status"] == "CONFIRMED" and receipt["root_input_count"] == 0
expected = json.loads((root / "official-platform-session-identity.json").read_text(encoding="utf-8"))
env = load_environment(parent / "environment/isolated.env")
refs = json.loads((parent / "environment/provision.json").read_text(encoding="utf-8"))
auth = Api(refs["platform"]).request("POST", "/platform/auth/login", {
    "username": env["SF_PLATFORM_USER"], "password": env["SF_PLATFORM_PASSWORD"]}, _rate_retry=True)
platform = Api(refs["platform"], auth["access_token"], True)
detail = platform.request("GET", query("/platform/sessions/detail", tenant_id=expected["tenant_id"], session_id=expected["session_id"]))
keys = ("tenant_id", "session_id", "channel_id", "agent_id", "user_id", "message_count", "root_input_count")
assert all(detail.get(key) == expected[key] for key in keys)
channel = Api(refs["api"]); channel.headers["X-Channel-Token"] = private["channel_token"]
result = {"scope": "confirmed_actual_product_d09_target_identity", "model_calls": 0,
          "new_session_created": False, "new_chat_inputs": 0, "identity": {key: detail[key] for key in keys}}
try:
    channel.request("POST", "/chat/deliverables/recovery-inputs", {"revision_id": "full-product-native-free-nonexistent"})
except ApiError as error:
    assert error.status_code == 410
    result.update(status="PASS", formal_recovery_missing_revision="REFUSED", diagnostic=failure_diagnostic(error),
                  original_failed_assertion_preserved=True)
else:
    raise AssertionError("formal_missing_revision_was_accepted")
save(target / "result.json", result)
if diagnostic != "1":
    source = parent / "live-behavior/core/round-1/diagnostic-3/cases/B08/1/state.json"
    source_state = json.loads(source.read_text(encoding="utf-8"))
    admin_auth = Api(refs["api"]).request("POST", "/auth/login", {"tenant_slug": env["SF_TENANT"],
        "username": env["SF_ADMIN_USER"], "password": env["SF_ADMIN_PASSWORD"]}, _rate_retry=True)
    admin = Api(refs["api"], admin_auth["access_token"])
    artifacts = admin.request("GET", query("/chat/sessions/deliverables", sessionId=source_state["session_id"]))
    actual = [row for row in artifacts if row.get("session_id") == source_state["session_id"] and row.get("current") is True and row.get("status") == "ready"]
    cross = {"scope": "native_channel_cannot_recover_other_actual_institution_session", "model_calls": 0,
             "source_session_id": source_state["session_id"], "actual_ready_revisions": len(actual)}
    if actual:
        foreign = actual[0]
        cross["revision_id"] = foreign["id"]
        source_detail = platform.request("GET", query("/platform/sessions/detail", tenant_id=expected["tenant_id"], session_id=source_state["session_id"]))
        assert source_detail["channel_id"] is None
        try:
            channel.request("POST", "/chat/deliverables/recovery-inputs", {"revision_id": foreign["id"]})
        except ApiError as error:
            assert error.status_code == 403
            cross.update(status="PASS", diagnostic=failure_diagnostic(error), actual_foreign_revision_refused=True)
        else:
            raise AssertionError("foreign_actual_revision_was_accepted_by_native_channel")
    else:
        cross.update(status="NOT_AVAILABLE", reason="completed_core_session_has_no_ready_published_revision")
    save(target / "cross-session-recovery-control.json", cross)
print(json.dumps({"status": result["status"], "model_calls": 0, "new_session_created": False, "actual_http_status": 410}))
