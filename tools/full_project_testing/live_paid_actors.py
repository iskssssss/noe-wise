"""Paid-lane identities are separate from free UI/host/auth credentials."""
from __future__ import annotations


def paid_actor_environment(environment):
    keys = ("SF_PAID_ADMIN_USER", "SF_PAID_ADMIN_PASSWORD", "SF_PAID_PLATFORM_USER", "SF_PAID_PLATFORM_PASSWORD")
    if any(not isinstance(environment.get(key), str) or not environment[key] for key in keys):
        raise ValueError("dedicated_paid_actor_credentials_required")
    if environment["SF_PAID_ADMIN_USER"] in {environment.get("SF_ADMIN_USER"), environment.get("SF_USER")} or environment["SF_PAID_PLATFORM_USER"] == environment.get("SF_PLATFORM_USER"):
        raise ValueError("dedicated_paid_actor_must_not_share_free_login_identity")
    result = dict(environment)
    result.update(SF_ADMIN_USER=environment["SF_PAID_ADMIN_USER"], SF_ADMIN_PASSWORD=environment["SF_PAID_ADMIN_PASSWORD"],
                  SF_USER=environment["SF_PAID_ADMIN_USER"], SF_PASSWORD=environment["SF_PAID_ADMIN_PASSWORD"],
                  SF_PLATFORM_USER=environment["SF_PAID_PLATFORM_USER"], SF_PLATFORM_PASSWORD=environment["SF_PAID_PLATFORM_PASSWORD"])
    return result
