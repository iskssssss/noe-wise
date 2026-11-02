"""Run the real D09 Session allocation boundary without a chat/model dispatch."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import sys
from unittest.mock import patch

from run_suite import REPO, load_environment, save
sys.path.insert(0, str(REPO / "tools/chat_regression"))
from client import Api, ApiError, query, failure_diagnostic
from provision import provision
from behavior_product_resources import _profiles
import behavior_product_runner as runner
import behavior_product_browser as browser


class DispatchBoundaryReached(Exception):
    pass


def main():
    parent = REPO / ".runtime-logs/full-project-test-20261012"
    root = parent / "product-target-native-preflight"
    root.mkdir(exist_ok=False)
    env = load_environment(parent / "environment/isolated.env")
    refs = json.loads((parent / "environment/provision.json").read_text(encoding="utf-8"))
    known_root = parent / "product-valid-channel-preflight"
    private = json.loads((known_root / "fixture-private.json").read_text(encoding="utf-8"))
    profile = json.loads((known_root / "diagnostic-2/actual-profile.json").read_text(encoding="utf-8"))["profiles"][0]
    client_ref = json.loads((known_root / "diagnostic-2/client-ref-fixture.json").read_text(encoding="utf-8"))["client_ref"]
    assert profile["channel_id"] == private["channel_id"]
    auth = Api(refs["api"]).request("POST", "/auth/login", {"tenant_slug": env["SF_TENANT"],
        "username": env["SF_ADMIN_USER"], "password": env["SF_ADMIN_PASSWORD"]}, _rate_retry=True)
    api = Api(refs["api"], auth["access_token"])
    platform_auth = Api(refs["platform"]).request("POST", "/platform/auth/login", {
        "username": env["SF_PLATFORM_USER"], "password": env["SF_PLATFORM_PASSWORD"]}, _rate_retry=True)
    platform = Api(refs["platform"], platform_auth["access_token"], True)
    result = {"scope": "actual_product_d09_session_target_boundary", "status": "RUNNING", "model_calls": 0,
              "paid_actor_used": False, "core_resource_identity_changed": False, "checks": {}}
    try:
        # Exact Product profile + provision path, narrowed to D09; no knowledge ingestion or model work.
        profile_spec = next(row for row in _profiles() if row["key"] == "d09-report")
        resources = provision(api, platform, root, [profile_spec], "full-product-native-free-20261012",
            "858530980831237", "http://127.0.0.1/unused-product-fixture", user_count=1, publish_agents=True)
        agent_id = str(resources["agents"]["d09-report"]["id"])
        save(root / "channel-binding-intent.json", {"channel_id": private["channel_id"], "original_agent_id": private["agent_id"],
            "new_d09_agent_id": agent_id, "core_agent_config_changed": False})
        api.request("PUT", "/channels/agents", {"channel_id": private["channel_id"], "payload": [
            {"agent_id": int(private["agent_id"]), "is_default": False, "priority": 90, "traffic_percentage": 100},
            {"agent_id": int(agent_id), "is_default": True, "priority": 100, "traffic_percentage": 100}]})
        target = {"agent_id": agent_id, "user_id": profile["end_user_id"]}
        resources["cases"] = {"D09": target}
        save(root / "browser-resources-private.json", {"channel": {"id": private["channel_id"], "channel_token": private["channel_token"]},
            "profiles": {"D09": {"client_ref": client_ref, "end_user_id": profile["end_user_id"], "channel_id": private["channel_id"]}}})
        creation = []
        class SessionOnlyApi(Api):
            def request(self, method, path, body=None, **kwargs):
                assert method == "POST" and path == "/chat/sessions", "preflight_no_chat_or_other_dispatch_allowed"
                assert self.headers.get("X-Channel-Token") == private["channel_token"]
                assert not any(key.lower() == "authorization" for key in self.headers)
                assert body == {"agent_id": target["agent_id"], "end_user_id": target["user_id"]}
                value = super().request(method, path, body, **kwargs)
                creation.append({"method": method, "path": path, "auth_mode": "channel_token", "authorization_header": False,
                    "body": body, "response_keys": list(value), "response": {key: value.get(key) for key in
                        ("session_id", "channel_id", "agent_id", "end_user_id") if key in value}})
                save(root / "actual-runner-session-create.json", creation[-1])
                return value
        def stop(*_args, **_kwargs):
            raise DispatchBoundaryReached()
        with patch.object(runner, "Api", SessionOnlyApi), patch.object(browser, "execute_browser_case", stop):
            try:
                runner.execute_product_case(api, {"id": "D09"}, 1, {}, resources, root, object(), object())
            except DispatchBoundaryReached:
                pass
            else:
                raise AssertionError("actual_browser_dispatch_boundary_not_reached")
        assert len(creation) == 1
        state = json.loads((root / "cases/D09/1/state.json").read_text(encoding="utf-8"))
        assert state["session_creating"] is False and state["turns"] == {}
        session_id = state["session_id"]
        detail = platform.request("GET", query("/platform/sessions/detail", tenant_id="858530973937669", session_id=session_id))
        expected = {"tenant_id": "858530973937669", "session_id": session_id, "channel_id": private["channel_id"],
                    "agent_id": agent_id, "user_id": profile["end_user_id"]}
        assert all(detail.get(key) == value for key, value in expected.items())
        assert detail["root_input_count"] == 0 and detail["message_count"] == 0
        save(root / "official-platform-session-identity.json", {**expected, "root_input_count": 0, "message_count": 0,
            "source": "/platform/sessions/detail", "actual_runner_creation_confirmed": True})
        session = {"session_id": session_id, "agent_id": agent_id, "end_user_id": profile["end_user_id"]}
        save(root / "target-session-receipt.json", {"status": "CONFIRMED", "case_id": "D09", "session": session,
            "channel_id": private["channel_id"], "root_input_count": 0, "model_calls": 0,
            "source": "behavior_product_runner.execute_product_case_before_execute_browser_case",
            "source_sha256": hashlib.sha256(Path(runner.__file__).read_bytes()).hexdigest()})
        save(root / "fixture-private.json", {**private, "agent_id": agent_id, "client_ref": client_ref})
        # The formal recovery selector must refuse nonexistent revisions even on this correct channel.
        channel = Api(refs["api"]); channel.headers["X-Channel-Token"] = private["channel_token"]
        try:
            channel.request("POST", "/chat/deliverables/recovery-inputs", {"revision_id": "full-product-native-free-nonexistent"})
        except ApiError as error:
            assert error.status_code in {400, 404, 403, 410}
            result["recovery_identity_negative_control"] = failure_diagnostic(error)
        else:
            raise AssertionError("nonexistent_revision_was_accepted")
        result["checks"] = {"actual_product_profile_published": "PASS", "actual_runner_channel_session_creation": "PASS",
            "official_platform_channel_session_user_agent_identity": "PASS", "empty_session_no_inputs": "PASS",
            "formal_recovery_nonexistent_revision_refused": "PASS"}
        result.update(status="PASS", session_id=session_id, agent_id=agent_id, channel_id=private["channel_id"],
                      end_user_id=profile["end_user_id"], creation_response_channel_id_present="channel_id" in creation[0]["response_keys"])
    except Exception as error:
        result.update(status="FAILED", failure=failure_diagnostic(error)); raise
    finally:
        save(root / "result.json", result)
    print(json.dumps({"status": result["status"], "checks": result["checks"], "model_calls": 0}))


if __name__ == "__main__":
    main()
