"""执行现有真实沙箱强杀/取消验收，并从完成产物统计实际场景。"""
from pathlib import Path
import json
import os
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sandbox"))
from verify_lifecycle import main, Lifecycle

if __name__ == "__main__":
    root = Path(os.environ["SANDBOX_LIFECYCLE_EVIDENCE_ROOT"])
    root.mkdir(parents=True, exist_ok=False)
    # Docker为随机绑定分配的新端口是每次重启的宿主事实；先核完整owner/volume后取当前映射。
    owned = Lifecycle()
    owned.service(refresh_endpoint=True)
    os.environ["SANDBOX_VERIFY_ENDPOINT"] = owned.endpoint
    (root / "input-binding.json").write_text(json.dumps({"container_id": owned.service_id,
        "endpoint": owned.endpoint, "owner": owned.owner, "scope_verified": True}, indent=2), encoding="utf-8")
    sys.argv = [sys.argv[0], "--output", str(root)]
    code = main()
    receipt = json.loads((root / "result.json").read_text(encoding="utf-8"))
    cases = receipt.get("scenarios", [])
    if code == 0 and receipt["status"] == "passed" and cases and all(row.get("passed") is True for row in cases):
        print(f"Ran {len(cases)} tests; all actual lifecycle scenarios passed")
    else:
        code = 1
    raise SystemExit(code)
