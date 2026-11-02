"""将真实完整机构进程强杀恢复验收绑定到本轮独立证据并统计已完成场景。"""
import json
import os
from pathlib import Path
import sys
from process_recovery import main

if __name__ == "__main__":
    root = Path(os.environ["PROCESS_RECOVERY_EVIDENCE_ROOT"])
    sys.argv = [sys.argv[0], "--environment-root", ".runtime-logs/full-project-test-20261012/environment", "--output", str(root)]
    code = main()
    receipt = json.loads((root / "result.json").read_text(encoding="utf-8"))
    scenarios = receipt.get("scenarios", [])
    if code == 0 and receipt.get("status") == "PASS" and len(scenarios) == 3:
        print(f"Ran {len(scenarios)} tests; complete application process kill/restart scenarios verified")
    else:
        code = 1
    raise SystemExit(code)
