"""Only an explicit paid wrapper receives dedicated credentials."""
import unittest
from live_paid_actors import paid_actor_environment


class PaidActorEnvironmentTest(unittest.TestCase):
    def test_free_environment_is_unchanged_and_paid_identity_is_explicit(self):
        original = {"SF_ADMIN_USER": "free-admin", "SF_USER": "free-admin", "SF_ADMIN_PASSWORD": "old",
                    "SF_PLATFORM_USER": "free-platform", "SF_PAID_ADMIN_USER": "owned-paid-admin",
                    "SF_PAID_ADMIN_PASSWORD": "new", "SF_PAID_PLATFORM_USER": "owned-paid-platform",
                    "SF_PAID_PLATFORM_PASSWORD": "new-platform", "SF_TENANT": "same-tenant"}
        paid = paid_actor_environment(original)
        self.assertEqual(original["SF_ADMIN_USER"], "free-admin")
        self.assertEqual(paid["SF_ADMIN_USER"], "owned-paid-admin")
        self.assertEqual(paid["SF_PLATFORM_USER"], "owned-paid-platform")
        self.assertEqual(paid["SF_TENANT"], original["SF_TENANT"])

    def test_shared_or_missing_actor_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "required"):
            paid_actor_environment({})
        with self.assertRaisesRegex(ValueError, "share"):
            paid_actor_environment({"SF_ADMIN_USER": "shared", "SF_PAID_ADMIN_USER": "shared",
                                    "SF_PAID_ADMIN_PASSWORD": "new", "SF_PAID_PLATFORM_USER": "owned",
                                    "SF_PAID_PLATFORM_PASSWORD": "new"})


if __name__ == "__main__":
    unittest.main()
