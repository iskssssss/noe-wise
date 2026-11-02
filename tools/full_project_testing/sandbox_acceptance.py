"""真实隔离 Sandbox 的四项复杂验收；独立用例失败不会掩盖后续证据。"""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "execution"))
from run_sandbox_complex import execute


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service-config", type=Path, required=True)
    parser.add_argument("--batch-id", required=True)
    parser.add_argument("--evidence", type=Path, default=os.environ.get("SANDBOX_EVIDENCE_ROOT"))
    args = parser.parse_args()
    if args.evidence is None:
        parser.error("需要本轮独立的 --evidence 或 SANDBOX_EVIDENCE_ROOT")
    args.evidence.mkdir(parents=True, exist_ok=False)

    class SandboxAcceptance(unittest.TestCase):
        """每个方法真实提交新任务，检查独立 oracle 或拒绝契约。"""

    for case in ("python", "node", "corrupt-input", "invalid-manifest"):
        def verify(self, selected=case):
            receipt = execute(args.service_config, args.batch_id, args.evidence / selected, selected, 45)
            self.assertIs(receipt.get("passed"), True, "真实任务未在观察窗口内完成完整契约验证")
            self.assertEqual(receipt["paid_model_calls"], 0)
        setattr(SandboxAcceptance, "test_" + case.replace("-", "_"), verify)
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(SandboxAcceptance))
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
