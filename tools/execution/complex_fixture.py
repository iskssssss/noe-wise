"""生成执行、附件与成果共用的72行多格式真值；只写显式的本批目录。"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path


def fixture() -> tuple[dict[str, bytes], dict]:
    """返回固定业务资料与独立聚合oracle，不依赖模型或被测服务结果。"""
    records = []
    for number in range(72):
        records.append({"record_id": f"sale-{number + 1:03}", "department": f"部门{number % 6 + 1}",
                        "month": f"2026-{number // 6 + 1:02}", "amount_cents": 12050 + (number % 9) * 125,
                        "status": "待审批" if number % 4 == 0 else "已确认", "note":
                        '中文, quoted "value"\n第二行' if number % 12 == 0 else "=待确认" if number % 7 == 0 else "常规记录",
                        "owner": None if number % 10 == 0 else f"负责人{number % 3 + 1}"})
    files = {}
    for filename, separator in (("sales-72.csv", ","), ("sales-72.tsv", "\t")):
        stream = io.StringIO(newline="")
        writer = csv.DictWriter(stream, fieldnames=records[0].keys(), delimiter=separator, lineterminator="\n")
        writer.writeheader()
        writer.writerows(records)
        files[filename] = stream.getvalue().encode("utf-8")
    files["data.json"] = (json.dumps({"records": records, "references": {"csv": "sales-72.csv", "brief": "brief.md"}}, ensure_ascii=False, indent=2) + "\n").encode()
    files["brief.md"] = ("# 部门验收资料\n\n金额单位为分；保留全部72条记录，不按金额去重。预算、发布日期和审批人待确认。\n\n"
                         "|记录|部门|月份|分|状态|\n|---|---|---|---:|---|\n" + "\n".join(
                             f"|{r['record_id']}|{r['department']}|{r['month']}|{r['amount_cents']}|{r['status']}|" for r in records) + "\n").encode()
    files["records.txt"] = ("\n".join(f"{r['record_id']}\t{r['department']}\t{r['month']}\t{r['amount_cents']}" for r in records) + "\n").encode()
    files["index.html"] = ("<!doctype html><html lang=\"zh\"><meta charset=\"utf-8\"><title>部门验收资料</title><table><tbody>" +
                           "".join(f"<tr data-id=\"{r['record_id']}\"><td>{r['department']}</td><td>{r['month']}</td><td>{r['amount_cents']}</td></tr>" for r in records) +
                           "</tbody></table></html>").encode()
    # 聚合值由固定算式求得，不从CSV解析器输出反推预期。
    totals = {f"部门{department + 1}": sum(12050 + ((month * 6 + department) % 9) * 125 for month in range(12)) for department in range(6)}
    oracle = {"fixture_version": 1, "seed": "full-test-20261012-execution-v1", "rows": 72,
              "unique_record_ids": 72, "duplicate_amounts_preserved": True, "null_owners": 8,
              "pending_rows": 18, "confirmed_rows": 54, "department_totals_cents": totals,
              "total_cents": sum(totals.values()), "months": 12,
              "files": [{"path": name, "size": len(content), "sha256": hashlib.sha256(content).hexdigest()} for name, content in sorted(files.items())]}
    return files, oracle


def write_fixture(output: Path) -> dict:
    """拒绝覆盖已有资料，防止把修改输入与既有执行回执混用。"""
    output.mkdir(parents=True, exist_ok=False)
    files, oracle = fixture()
    for name, content in files.items():
        (output / name).write_bytes(content)
    (output / "oracle.json").write_text(json.dumps(oracle, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return oracle


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    result = write_fixture(parser.parse_args().output)
    print(json.dumps({"rows": result["rows"], "total_cents": result["total_cents"], "files": len(result["files"])}))
