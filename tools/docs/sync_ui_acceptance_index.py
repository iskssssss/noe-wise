#!/usr/bin/env python3
"""同步逐控件规格的稳定编号与来源；实现、验收和证据状态保持人工记录。"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from generate_platform_ui_control_inventory import PAGE_SOURCES

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs" / "03-规范与术语"
INDEX = ROOT / "docs" / "08-数据与工具" / "ui-acceptance-index.json"
CONTROL_INVENTORY = ROOT / "docs" / "08-数据与工具" / "platform-ui-control-inventory.json"
ROW = re.compile(r"^\|\s*(P\d{2}(?:\.UI\d+[a-z]?|-C\d+[a-z]?))\s*\|\s*([^|]+)")
NUMBERED_RULE = re.compile(r"^-\s+([A-Z]\d{2})\s+(.+)$")
REFINEMENT = re.compile(r"^\|\s*([A-J]\d{2})\s*\|\s*([^|]+)\|\s*([^|]+)\|\s*([^|]+)\|")
RETIRED_P05_CONTROLS = re.compile(r"P05(?:-C(?:0[1-9]|[12]\d|3[0-2])[a-z]?|\.UI(?:0[1-9]|1\d|2[0-2]))")
P05_RETIREMENT_SOURCE = "../03-规范与术语/后台控件/P05-模型费用账单.md#已撤销控件编号"
P05_RETIREMENT_REASON = "独立销售定价和商业出账已撤销；旧编号仅保留规则与证据追溯，新只读模型费用合同从C33/UI23起编号。"


def contracts(paths: list[Path]) -> dict[str, dict]:
    found: dict[str, dict] = {}
    for path in paths:
        source = "../03-规范与术语/" + path.relative_to(DOCS).as_posix()
        for line in path.read_text(encoding="utf-8").splitlines():
            match = ROW.match(line)
            if not match:
                continue
            identifier, title = match.groups()
            # 源码链接属于来源文档；不能原样复制到不同目录的 JSON 标题中。
            title = re.split(r"[；;]\s*源码[：:]", title, maxsplit=1)[0]
            title = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", title).strip()
            page = re.match(r"P\d{2}", path.name)
            if page and identifier[:3] != page.group():
                raise ValueError(f"控件编号与页面不一致：{identifier} 位于 {path.name}")
            if identifier in found:
                raise ValueError(f"控件编号重复：{identifier}")
            locations = [location for location in PAGE_SOURCES[identifier[:3]][1]
                         if (ROOT / location).is_file()]
            historical_locations: list[str] = []
            for link in re.findall(r"\]\(([^)]+)\)", line):
                if link.startswith(("http", "#")):
                    continue
                target = (path.parent / link.split("#")[0]).resolve()
                if target.suffix in {".vue", ".ts", ".java", ".xml", ".sql"} and target.is_relative_to(ROOT):
                    location = target.relative_to(ROOT).as_posix()
                    if not target.is_file():
                        if location not in historical_locations:
                            historical_locations.append(location)
                    elif location not in locations:
                        locations.append(location)
            found[identifier] = {"title": title, "source": source, "locations": locations,
                                 "historical_locations": historical_locations}
    return found


def synchronize(index: dict, found: dict[str, dict]) -> dict:
    records = {record["id"]: record for record in index["records"]}
    for identifier, record in records.items():
        if identifier not in found and RETIRED_P05_CONTROLS.fullmatch(identifier):
            record["implementation_status"] = "retired"
            record["applicability_status"] = "retired"
            record["retirement_reason"] = P05_RETIREMENT_REASON
            record["source"] = P05_RETIREMENT_SOURCE
    for identifier, contract in found.items():
        record = records.get(identifier)
        if record is None:
            record = {
                "id": identifier,
                "requirement": contract["title"],
                "origin": "actual_control_contract" if "-C" in identifier else "supplemental_page_refinement",
                "implementation_status": "not_assessed",
                "verification_status": "not_executed",
                "implementation_locations": [],
                "contract_bindings": [],
                "test_ids": [],
                "evidence": [],
            }
            index["records"].append(record)
        # 规格内容留在唯一正文；本索引只同步稳定对象名和可定位的来源。
        record["control_title"] = contract["title"]
        record["requirement"] = contract["title"]
        record["source"] = contract["source"]
        current_locations = []
        historical_locations = list(contract.get("historical_locations", []))
        for location in record.get("implementation_locations", []):
            if (ROOT / location.split("#")[0]).is_file():
                current_locations.append(location)
            else:
                historical_locations.append(location)
        record["implementation_locations"] = current_locations
        for location in contract.get("locations", []):
            if location not in record.setdefault("implementation_locations", []):
                record["implementation_locations"].append(location)
        for location in historical_locations:
            reference = {"path": location, "status": "removed_from_current_tree"}
            historical = record.setdefault("historical_source_references", [])
            if reference not in historical:
                historical.append(reference)
    orphaned = [record["id"] for record in index["records"]
                if record["origin"] in {"actual_control_contract", "supplemental_page_refinement"}
                and record.get("applicability_status") != "retired"
                and record["id"] not in found]
    if orphaned:
        raise ValueError("规格删除了已登记控件，须先明确处理原记录：" + ", ".join(orphaned))
    counts = index["counts"]
    counts["baseline_indexed_rule_records"] = 664
    counts["indexed_rule_records"] = len(index["records"])
    counts["actual_control_contracts"] = sum(r["origin"] == "actual_control_contract" for r in index["records"])
    counts["supplemental_page_refinements"] = sum(r["origin"] == "supplemental_page_refinement" for r in index["records"])
    counts["retired_rule_records"] = sum(r.get("applicability_status") == "retired" for r in index["records"])
    return index


def synchronize_rules(index: dict, rules: str, refinements: str) -> None:
    """刷新现行规则的来源文本，不变更原实现、验收或证据状态。"""
    records = {record["id"]: record for record in index["records"]}
    for line in rules.splitlines():
        match = NUMBERED_RULE.match(line)
        if not match:
            continue
        identifier, requirement = match.groups()
        record = records.get(identifier)
        if record is not None and record["origin"] == "original_numbered_rule":
            record["requirement"] = requirement.strip()
            record["source"] = "../03-规范与术语/05-后台系统交互规范.md"
    for line in refinements.splitlines():
        match = REFINEMENT.match(line)
        if not match:
            continue
        identifier, title, requirement, acceptance = match.groups()
        record = records.get(identifier)
        if record is not None and record["origin"] == "new_refinement":
            record["object"] = title.strip()
            record["requirement"] = requirement.strip()
            record["acceptance"] = acceptance.strip()
            record["source"] = "../03-规范与术语/06-后台逐控件规格.md"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    paths = [DOCS / "06-后台逐控件规格.md", *sorted((DOCS / "后台控件").glob("P*.md"))]
    before = INDEX.read_text(encoding="utf-8")
    index = synchronize(json.loads(before), contracts(paths))
    synchronize_rules(index, (DOCS / "05-后台系统交互规范.md").read_text(encoding="utf-8"),
                      paths[0].read_text(encoding="utf-8"))
    index["counts"]["current_static_control_entries"] = json.loads(
        CONTROL_INVENTORY.read_text(encoding="utf-8"))["summary"]["control_count"]
    after = json.dumps(index, ensure_ascii=False, indent=2) + "\n"
    if args.check and after != before:
        print("[FAIL] 控件验收索引已过期，请运行 tools/docs/sync_ui_acceptance_index.py")
        return 1
    if not args.check:
        INDEX.write_text(after, encoding="utf-8")
    print("[OK] 控件编号和来源已同步；旧P05显式退役，既有验收与证据状态保留")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
