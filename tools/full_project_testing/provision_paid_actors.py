"""Create this authorized batch's two dedicated paid actors through formal IAM APIs.

Only adds private SF_PAID_* keys. Free-lane credentials and current admin users stay intact.
One pass only: durable write intents prevent silently repeating an uncertain account creation.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import secrets
import sys

from run_suite import REPO, load_environment, save
from live_paid_actors import paid_actor_environment

sys.path.insert(0, str(REPO / "tools/chat_regression"))
from client import Api, query, failure_diagnostic

ENV_ROOT = REPO / ".runtime-logs/full-project-test-20261012/environment"


def authenticate(base, environment, platform=False, paid=False):
    prefix = ("SF_PAID_PLATFORM" if platform else "SF_PAID_ADMIN") if paid else ("SF_PLATFORM" if platform else "SF_ADMIN")
    body = {"username": environment[prefix + "_USER"], "password": environment[prefix + "_PASSWORD"]}
    if not platform:
        body["tenant_slug"] = environment["SF_TENANT"]
    auth = Api(base).request("POST", "/platform/auth/login" if platform else "/auth/login", body, _rate_retry=True)
    return Api(base, auth["access_token"], platform, expires_in=auth.get("expires_in"), refresh_token=auth.get("refresh_token"))


def provision(root=ENV_ROOT):
    env_path, owned = root / "isolated.env", root / "paid-actors"
    owned.mkdir(exist_ok=False)
    original = env_path.read_bytes()
    with (owned / "original-isolated.env").open("xb") as stream:
        stream.write(original)
    environment = load_environment(env_path)
    references = json.loads((root / "provision.json").read_text(encoding="utf-8"))
    keys = {"SF_PAID_ADMIN_USER": "full-paid-20261012-tenant", "SF_PAID_ADMIN_PASSWORD": "Paid9!" + secrets.token_urlsafe(24),
            "SF_PAID_PLATFORM_USER": "full-paid-20261012-platform", "SF_PAID_PLATFORM_PASSWORD": "Paid9!" + secrets.token_urlsafe(24)}
    if any(key in environment for key in keys):
        raise ValueError("paid_actor_keys_already_present_no_reprovision")
    save(owned / "private-credentials.json", keys)
    result = {"owner": "full-project-test-20261012", "accounts": [], "paid_calls": 0,
              "original_credentials_changed": False, "new_keys_used_only_by_explicit_paid_environment": True}
    try:
        for platform in (False, True):
            name = "platform" if platform else "institution"
            base = references["platform" if platform else "api"]
            api = authenticate(base, environment, platform)
            endpoint = "/admin/foundation/access" if platform else "/tenant/foundation/access"
            me_endpoint = "/platform/auth/me" if platform else "/auth/me"
            old_me = api.request("GET", me_endpoint)
            old_snapshot = api.request("GET", endpoint + "/snapshot")
            expected_role = "platform_admin" if platform else "tenant_admin"
            roles = api.request("GET", endpoint + "/roles")
            matches = [row for row in roles if row["code"] == expected_role and row["administrator"] and row["enabled"]]
            if len(matches) != 1 or expected_role not in old_snapshot["roles"]:
                raise ValueError("paid_actor_original_administrator_role_unverified")
            role = matches[0]
            prefix = "SF_PAID_PLATFORM" if platform else "SF_PAID_ADMIN"
            username = keys[prefix + "_USER"]
            page = api.request("GET", query(endpoint + "/members", keyword=username, page_size=100))
            if any(row["username"] == username for row in page["items"]):
                raise ValueError("paid_actor_name_already_exists_no_blind_reuse")
            body = {"username": username, "display_name": "Full project paid lane " + name,
                    "email": None, "password": keys[prefix + "_PASSWORD"], "department_id": None,
                    "position_id": None, "role_ids": [role["id"]]}
            save(owned / (name + "-create-intent.json"), {"method": "POST", "endpoint": endpoint + "/member",
                 "username": username, "role_id": role["id"], "password_saved_in_private_checkpoint": True,
                 "outcome": "dispatching", "implicit_retry_allowed": False})
            actor = api.request("POST", endpoint + "/member", body, _rate_retry=True)
            if actor.get("username") != username or actor.get("status") != "active" or actor.get("role_ids") != [role["id"]]:
                raise ValueError("paid_actor_creation_receipt_identity_invalid")
            save(owned / (name + "-create-receipt.json"), {"id": actor["id"], "username": username,
                 "role_ids": actor["role_ids"], "status": actor["status"], "role": expected_role})
            dedicated = authenticate(base, {**environment, **keys}, platform, paid=True)
            new_me = dedicated.request("GET", me_endpoint)
            new_snapshot = dedicated.request("GET", endpoint + "/snapshot")
            if new_me.get("sub") != actor["id"] or new_me.get("username") != username or new_me.get("tenant_id") != old_me.get("tenant_id"):
                raise ValueError("paid_actor_login_owner_identity_invalid")
            if sorted(new_snapshot["roles"]) != sorted(old_snapshot["roles"]) or sorted(new_snapshot["permissions"]) != sorted(old_snapshot["permissions"]):
                raise ValueError("paid_actor_permissions_not_equal_to_original")
            result["accounts"].append({"host": name, "id": actor["id"], "username": username, "role": expected_role,
                                      "tenant_id": new_me.get("tenant_id"), "same_permissions": True,
                                      "permissions_sha256": hashlib.sha256(json.dumps(sorted(new_snapshot["permissions"])).encode()).hexdigest(),
                                      "original_login_remains_valid": api.request("GET", me_endpoint).get("sub") == old_me.get("sub")})
        paid_actor_environment({**environment, **keys})
        if env_path.read_bytes() != original:
            raise ValueError("private_environment_changed_during_actor_provision")
        updated = original.decode("utf-8-sig").rstrip("\r\n") + "\n" + "\n".join(key + "=" + value for key, value in keys.items()) + "\n"
        temporary = env_path.with_suffix(".paid-actors.tmp")
        with temporary.open("x", encoding="utf-8") as stream:
            stream.write(updated)
        temporary.replace(env_path)
        result["status"] = "READY"
        save(owned / "result.json", result)
        print("dedicated-paid-actors READY accounts=2 new-model-calls=0 original-env-backed-up", flush=True)
    except Exception as error:
        save(owned / "failure.json", {"status": "UNKNOWN_OR_FAILED_NO_RETRY", **failure_diagnostic(error),
                                      "original_environment_changed": env_path.read_bytes() != original})
        raise


if __name__ == "__main__":
    provision()
