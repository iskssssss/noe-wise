"""报告同时列出未执行与失败；重跑不覆盖首次失败。"""
from collections import Counter
import html
import json
import math
from pathlib import Path


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values)*fraction)-1)] if values else None


def build(root):
    root = Path(root)
    plan = json.loads((root / "plan.json").read_text(encoding="utf-8"))
    cases, timings, firsts, usage, groups = [], [], [], {}, {}
    phase_totals = Counter()
    for case in plan["cases"]:
        folder = root / "cases" / case["id"]
        path = folder / "result.json"
        result = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {
            "status": "interrupted" if (folder / "interruption.json").exists() or (folder / "state.json").exists() else "not_run",
            "turns": [json.loads(p.read_text(encoding="utf-8")) for p in sorted(folder.glob("turn-??.json"))]}
        errors = [error for turn in result["turns"] for error in turn["errors"]]
        for turn in result["turns"]:
            phase_totals.update(turn.get("phase_timing", {}).get("external_seconds_by_call_kind", {}))
            if not turn.get("resumed"):
                timings.append(turn["seconds"])
                if turn.get("first_output_seconds") is not None: firsts.append(turn["first_output_seconds"])
            usage[turn["session_id"]] = turn.get("usage", {})
        row = {"case_id": case["id"], "category": case["category"], "profile": case["profile"], "status": result["status"], "errors": errors}
        cases.append(row)
        groups.setdefault(case["category"], Counter())[row["status"]] += 1
    counts = Counter(c["status"] for c in cases)
    amounts = Counter()
    for item in usage.values(): amounts.update(item.get("amount_by_currency", {}))
    summary = {"planned_sessions": len(cases), "planned_turns": sum(len(c["turns"]) for c in plan["cases"]),
               "counts": counts, "by_category": groups, "measured_turns": len(timings),
               "completed_pass_rate": counts["passed"] / (counts["passed"] + counts["failed"]) if counts["passed"] + counts["failed"] else None,
               "latency_seconds": {f"p{p}": percentile(timings, p/100) for p in [50,95,99]},
               "first_output_seconds": {f"p{p}": percentile(firsts, p/100) for p in [50,95,99]},
               "tokens": sum(u.get("tokens",0) for u in usage.values()), "amount_by_currency_cents": amounts,
               "external_seconds_by_call_kind": phase_totals,
               "unknown_usage_calls": sum(u.get("unknown_usage_calls",0) for u in usage.values()),
               "limitations": ["续跑等待时间不计入请求延迟分位数", "金额沿用接口分单位；缺失不等于零成本", "阶段详情见原始trace，未归因耗时不作推断"]}
    (root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (root / "results.jsonl").write_text("".join(json.dumps(c,ensure_ascii=False)+"\n" for c in cases),encoding="utf-8")
    rows = "".join("<tr>" + "".join("<td>"+html.escape(str(c[k]))+"</td>" for k in ["case_id","category","profile","status","errors"]) + "</tr>" for c in cases)
    (root / "report.html").write_text("<!doctype html><meta charset=utf-8><title>会话回归报告</title><style>body{font:14px system-ui;margin:24px}td,th{border:1px solid #ddd;padding:6px}table{border-collapse:collapse}pre{white-space:pre-wrap}</style><h1>会话回归报告</h1><pre>" + html.escape(json.dumps(summary,ensure_ascii=False,indent=2)) + "</pre><table><tr><th>用例</th><th>场景</th><th>智能体</th><th>状态</th><th>失败原因</th></tr>"+rows+"</table>",encoding="utf-8")
    return summary
