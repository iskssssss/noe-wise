"""Strictly collect completed actual browser route evidence without changing original results."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def fingerprint(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def collect(root: Path, host: str):
    counts = read(root / "counts.json")
    summary = read(root / "summary.json")
    services = [row for row in read(root / "services.json") if row["host"] == host]
    records = [row for row in summary if row["host"] == host]
    if not counts.get("completed_at") or counts["records"] != len(summary):
        raise ValueError("Incomplete original cohort")
    if {row["round"] for row in services} != {1, 2, 3} or len(services) != 3:
        raise ValueError("Exactly three owned service rounds required")
    expected_count = 37 if host == "admin" else 32
    if len({row["router_sha256"] for row in services}) != 1:
        raise ValueError("Router roster changed between rounds")
    login_records, captures, cohorts = [], [], []
    seen = set()
    for service in services:
        round_number = service["round"]
        routes = {row["template"] for row in service["routes"]}
        if len(routes) != expected_count:
            raise ValueError("Source route denominator mismatch")
        routes |= {"/", "/403", "/__route_smoke_unknown__"}
        actual = [row for row in records if row["round"] == round_number]
        if {row.get("template") for row in actual} != routes or len(actual) != len(routes):
            raise ValueError("Missing, duplicate or blocked route evidence")
        login_path = root / f"round-{round_number}" / host / "login/result.json"
        login = read(login_path)
        identity = login["identity"]
        me = "/api/v1/auth/me" if host == "admin" else "/api/v1/platform/auth/me"
        if (login["status"] != "PASS" or identity["mfa_state"] != "full" or not identity["roles"]
                or not identity["permissions"] or not any(row["path"] == me and row["status"] == 200
                    and row.get("code") == "OK" for row in login["http"])):
            raise ValueError("Actual full server authentication evidence required")
        login_records.append({"round": round_number, "host": host, "sub": identity["sub"],
            "tenant_id": identity["tenant_id"], "roles": identity["roles"],
            "permissions_count": len(identity["permissions"]), "mfa_state": identity["mfa_state"],
            "evidence": str(login_path), "sha256": fingerprint(login_path)})
        for row in actual:
            key = (round_number, host, row["template"])
            if key in seen:
                raise ValueError("Duplicate route identity")
            seen.add(key)
            evidence_path = root / row["evidence"] / "result.json"
            original = read(evidence_path)
            if row["status"] != original["status"] or original["template"] != row["template"]:
                raise ValueError("Aggregate disagrees with original page evidence")
            if row["business_acceptance"] != "NOT_RUN":
                raise ValueError("Route reading cannot claim full business action acceptance")
            capture_path = root / row["capture"]
            content = capture_path.read_bytes()
            if not content.startswith(b"\x89PNG\r\n\x1a\n") or content[12:16] != b"IHDR":
                raise ValueError("Missing or invalid screenshot artifact")
            width, height = struct.unpack(">II", content[16:24])
            if width != 1440 or height < 1000:
                raise ValueError("Unexpected actual viewport screenshot dimensions")
            captures.append({"round": round_number, "host": host, "template": row["template"],
                "path": row["path"], "status": row["status"], "help_status": row["help_status"],
                "actual_url": row["actual_url"], "fixture_id": row.get("fixture_id"),
                "result": str(evidence_path), "result_sha256": fingerprint(evidence_path),
                "capture": str(capture_path), "capture_sha256": fingerprint(capture_path),
                "width": width, "height": height})
        cohorts.append({"round": round_number, "host": host, "route_count": len(actual),
            "statuses": dict(Counter(row["status"] for row in actual)),
            "help_statuses": dict(Counter(row["help_status"] for row in actual)),
            "http_429": sum(item["status"] == 429 for row in actual for item in row["http"]),
            "blocked_business_writes": sum(len(row["blocked_writes"]) for row in actual),
            "base": service["base"], "backend": service["origin"],
            "router_sha256": service["router_sha256"], "origin_adapter": service.get("origin_adapter"),
            "cors_acceptance": service.get("cors_acceptance"), "api_pacing_ms": service.get("api_pacing_ms")})
    return records, captures, login_records, cohorts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--admin-root", type=Path, required=True)
    parser.add_argument("--platform-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    logs = (repo / ".runtime-logs").resolve()
    for path in (args.admin_root, args.platform_root, args.output):
        if not path.resolve().is_relative_to(logs):
            parser.error("Actual evidence must remain in private logs")
    if args.output.exists():
        parser.error("Refuse to overwrite correction evidence")
    admin = collect(args.admin_root.resolve(), "admin")
    platform = collect(args.platform_root.resolve(), "platform")
    records, captures, logins, cohorts = (admin[index] + platform[index] for index in range(4))
    if len(records) != 225 or len(captures) != 225 or len(logins) != 6:
        raise ValueError("225 actual route records and screenshots plus six auth records required")
    args.output.mkdir()
    payload = {"mechanical_integrity": "PASS", "records": 225, "captures": 225, "actual_authentications": 6,
        "business_acceptance": "NOT_RUN", "statuses": dict(Counter(row["status"] for row in records)),
        "help_statuses": dict(Counter(row["help_status"] for row in records)),
        "cohorts": cohorts, "authentications": logins, "capture_bindings": captures,
        "source_summaries": [{"path": str(root.resolve() / "summary.json"),
            "sha256": fingerprint(root / "summary.json")} for root in (args.admin_root, args.platform_root)]}
    (args.output / "validation.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["", "机械采集器确认完整225条真实页面记录/225截图/6次完整认证，原始结果与SHA256绑定，"
             "具体校验见 `ui-real-combined/validation.json`。mechanical_integrity只表示证据完整性。", "",
             "| lane/轮次 | 路由数 | 烟测状态 | 帮助关联状态 | HTTP429 | 业务写请求 | 实际Vite来源 |", "|---|---|---|---|---|---|---|"]
    for row in cohorts:
        lines.append(f"| {row['host']} 第{row['round']}轮 | {row['route_count']} | {row['statuses']} | "
                     f"{row['help_statuses']} | {row['http_429']} | {row['blocked_business_writes']} | {row['base']} |")
    lines += ["", "帮助PASS仅表示可见控件有唯一、可见、非空的真实aria-describedby目标；邻近容器检查在原DOM证据中另列，"
              "不能据此宣称弹窗/隐藏控件或所有视觉邻近要求已验收。", "",
              "| 路由 | 第1轮 状态/截图 | 第2轮 状态/截图 | 第3轮 状态/截图 |", "|---|---|---|---|"]
    for host in ("admin", "platform"):
        routes = list(dict.fromkeys(row["template"] for row in captures if row["host"] == host))
        for template in routes:
            cells = []
            for round_number in (1, 2, 3):
                row = next(row for row in captures if row["host"] == host and row["template"] == template and row["round"] == round_number)
                cells.append(f"{row['status']} / help:{row['help_status']} / [截图](<{Path(row['capture']).as_posix()}>)")
            lines.append(f"| {host} `{template}` | " + " | ".join(cells) + " |")
    lines += ["", "| 帮助关联缺口路由 | 第1轮可见缺帮助控件 |", "|---|---|"]
    for row in records:
        if row["round"] == 1 and row["help_status"] == "GAP":
            missing = [f"{control['tag']}({control.get('role') or control.get('type') or '-'}) {control.get('label') or '无可见标签'}"
                for control in row["dom"]["help"] if not control["description_ok"]]
            lines.append(f"| `{row['template']}` | " + "; ".join(missing).replace("|", "\\|") + " |")
    lines += ["", "实际FAIL逐项保留在原result.json，当前全业务动作仍为NOT_RUN；该表不替代上文功能生命周期验收计划。", ""]
    with args.report.open("a", encoding="utf-8") as stream:
        stream.write("\n".join(lines))
    print(json.dumps({key: payload[key] for key in ("mechanical_integrity", "records", "captures", "actual_authentications", "statuses", "help_statuses")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
