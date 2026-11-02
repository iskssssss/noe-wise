"""行为评测 CLI：离线操作与显式付费运行分开，任何缺口都不能生成发布通过。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import sys
import threading
from urllib.parse import urlsplit

from behavior_contract import (EVALUATOR_VERSION, VERSION, aggregate, digest, issue,
                               release_check, require, validate_plan, experiments, SUITE_VERSION, STRESS_SUITE_VERSION, suite_case_ids)
from behavior_environment import resolve_model, verify_environment
from behavior_internal_evidence import configure_internal_evidence
from behavior_eval import evaluate_case
from behavior_policy import (parse_impacts, select_case_ids, selection_metadata,
                             validate_selection_metadata)
from behavior_reporting import write_reports, compare_reports, calibration_report
from behavior_runner import run_batch, final_report, execution_metrics
from behavior_suite import make_plan, materialize
from client import Api, login
from main import batch_lock
from provision import provision
from runner import read, save


def private_root(root):
    """原始输入和配置只能写入仓库忽略的运行目录，不能误写成可提交测试数据。"""
    root = root.resolve()
    repo = Path(__file__).resolve().parents[2]
    allowed = [repo / "tools" / "chat_regression" / "runs", repo / ".runtime-logs"]
    require(any(root.is_relative_to(p.resolve()) and root != p.resolve() for p in allowed), "root_must_be_private_run_subdirectory")
    return root


def create_plan(root, batch=None, local_selection=None, *, suite="core", impact_selection=None):
    require(not (root / "plan.json").exists(), "plan_already_frozen")
    require(impact_selection is None or local_selection is not None, "impact_selection_requires_local_run")
    batch = batch or "behavior-" + secrets.token_hex(8)
    require(re.fullmatch(r"[a-z0-9][a-z0-9-]{0,39}", batch) is not None, "invalid_batch_name")
    canary = "SF-PRIVATE-" + secrets.token_hex(16)
    require(suite in {"core", "stress", "product"}, "unsupported_suite_name")
    if suite == "product":
        from behavior_dataset import load_dataset
        from behavior_product_suite import make_product_plan
        plan = make_product_plan(batch, load_dataset(root))
    elif suite == "stress":
        from behavior_stress_suite import make_stress_plan
        plan = make_stress_plan(batch, canary=canary)
    else:
        plan = make_plan(batch, canary=canary)
    if local_selection is not None:
        plan["local_selection"] = local_selection
        plan["fingerprint"] = digest({k: v for k, v in plan.items() if k != "fingerprint"})
        validate_plan(plan)
    if impact_selection is not None:
        impact_selection = validate_selection_metadata(impact_selection, plan["suite_version"])
        require(impact_selection["case_ids"] == plan["local_selection"]["case_ids"],
                "impact_selection_case_ids_mismatch")
    save(root / "private.json", {"sensitive_values": [canary]})
    save(root / "plan.json", plan)
    if impact_selection is not None:
        save(root / "selection.json", impact_selection)
    return plan


def publish(root, report):
    private = read(root / "private.json")
    values = private["sensitive_values"] + [v for k, v in os.environ.items()
            if v and len(v) >= 8 and any(t in k for t in ("PASSWORD", "API_KEY", "TOKEN", "SECRET"))]
    return write_reports(root, report, values)


def prepare(args, root, plan, fixture):
    api, platform = login(args.api), login(args.platform, True)
    configure_internal_evidence(api, args.internal_evidence)
    model = resolve_model(platform, args.model_id)
    require(fixture.request("GET", "/health").get("batch") == plan["batch"], "fixture_batch_mismatch")
    selected = {cid for cid, _ in experiments(plan)}
    profiles = {c["profile"] for c in plan["cases"] if c["id"] in selected}
    resources = provision(api, platform, root, [p for p in plan["profiles"] if p["key"] in profiles],
                          plan["batch"], str(model["id"]), args.fixture + "/mcp", user_count=len(experiments(plan)))
    resources["resolved_model"] = model
    save(root / "resources.json", resources)
    return api, platform, resources


def local_endpoints(args):
    for value in (args.api, args.platform, args.fixture):
        endpoint = urlsplit(value)
        require(endpoint.scheme == "http" and endpoint.hostname in {"127.0.0.1", "localhost", "::1"}
                and endpoint.username is None and endpoint.password is None
                and not endpoint.query and not endpoint.fragment, "live_endpoints_must_be_loopback_http")


def require_live_args(args):
    local_endpoints(args)
    require(bool(args.model_id and args.deployment_revision and (args.command == "local-run" or args.environment_receipt)), "live_model_revision_environment_receipt_required")
    require(all(isinstance(v, int) and v > 0 for v in (args.max_tokens, args.max_turns, args.max_seconds)), "explicit_positive_live_budgets_required")


def local_selection(args, suite_version=None):
    suite_version = suite_version or (STRESS_SUITE_VERSION if args.suite == "stress" else SUITE_VERSION)
    impacts = parse_impacts(args.impact) if args.impact is not None else None
    case_ids = (select_case_ids(impacts, suite_version) if impacts is not None else
                ([x.strip() for x in args.cases.split(",")] if args.cases is not None else suite_case_ids(suite_version)))
    require(bool(case_ids), "impact_selection_empty")
    selection = {"case_ids": case_ids,
            "repetitions": args.repetitions if args.repetitions is not None else 3,
            "turn_timeout_seconds": args.turn_timeout_seconds if args.turn_timeout_seconds is not None else 540}
    if args.workers is not None:
        selection["workers"] = args.workers
    return selection


def impact_selection(args, suite_version=None):
    if args.impact is None:
        return None
    suite_version = suite_version or (STRESS_SUITE_VERSION if args.suite == "stress" else SUITE_VERSION)
    return selection_metadata(parse_impacts(args.impact), suite_version)


def validate_frozen_impact_selection(root, plan, requested):
    """已冻结的标签和对应 ID 必须与本次 local-run 显式一致。"""
    path = root / "selection.json"
    if not path.exists():
        require(requested is None, "frozen_impact_selection_mismatch")
        return
    frozen = validate_selection_metadata(read(path), plan["suite_version"])
    require(requested is not None and frozen == requested
            and frozen["case_ids"] == plan.get("local_selection", {}).get("case_ids"),
            "frozen_impact_selection_mismatch")


def outcome_exit_code(report, local=False):
    if local:
        return 0 if report["behavior_status"] == "passed" and not report["blocking_reasons"] else 1
    return 0 if report["release_eligible"] else 1


def ci_run(args, root):
    from mcp_fixture import McpFixtureServer
    require_live_args(args)
    require(not (root / "batch.json").exists(), "batch_already_started_use_new_batch_after_diagnosis")
    is_local = args.command == "local-run"
    require(not (args.cases is not None and args.impact is not None), "impact_and_cases_are_mutually_exclusive")
    require(is_local or args.suite is None, "suite_selection_requires_local_run")
    require(is_local or all(v is None for v in (args.cases, args.impact, args.repetitions, args.turn_timeout_seconds, args.workers)), "selection_requires_local_run")
    requested_impact_selection = impact_selection(args)
    plan = (read(root / "plan.json") if (root / "plan.json").exists() else
            create_plan(root, args.batch, local_selection(args) if is_local else None, suite=args.suite or "core",
                        impact_selection=requested_impact_selection))
    validate_plan(plan)
    from behavior_regression import validate_regression_lineage
    validate_regression_lineage(root, plan)
    require(is_local or ("local_selection" not in plan and plan["suite_version"] == SUITE_VERSION), "ci_requires_full_release_plan")
    if args.suite is not None:
        require(plan["suite_version"] == (STRESS_SUITE_VERSION if args.suite == "stress" else SUITE_VERSION), "frozen_suite_mismatch")
    if is_local:
        wanted = local_selection(args, plan["suite_version"])
        for arg, field in ((args.cases, "case_ids"), (args.repetitions, "repetitions"), (args.turn_timeout_seconds, "turn_timeout_seconds"), (args.workers, "workers")):
            require(arg is None or plan.get("local_selection", {}).get(field) == wanted[field], "frozen_local_selection_mismatch")
        validate_frozen_impact_selection(root, plan, impact_selection(args, plan["suite_version"]))
    if plan.get("local_selection", {}).get("workers", 1) == 2:
        from behavior_parallel import local_parallel_run
        return local_parallel_run(args, root, plan)
    # 任何接口资源写入之前核实环境收据。
    try:
        receipt = ({"validation_scope": "local", "deployment_verified": False, "network_isolation_verified": False}
                   if args.command == "local-run" else
                   verify_environment(args.environment_receipt, args.deployment_revision, args.api, args.platform))
    except (ValueError, OSError, RuntimeError) as exc:
        batch = {"lifecycle": "settling", "binding": {"deployment_revision": args.deployment_revision,
                 "suite_version": plan["suite_version"], "evaluator_version": EVALUATOR_VERSION,
                 "model_id": args.model_id, "model_version": None, "config_digest": None,
                 "settlement_seconds": plan["settlement_seconds"]}, "budget": {"turns": 0, "sessions": {}},
                 "blocking_reasons": [issue("invalid_environment", "environment_preflight_failed:" + type(exc).__name__)]}
        publish(root, final_report(root, plan, batch, True))
        return 1
    save(root / "environment.json", receipt)
    require(not (root / "resources.json").exists(), "ci_run_requires_new_resources_and_fixture")
    server = McpFixtureServer(0, plan["batch"], os.environ.get("MCP_FIXTURE_TOKEN"))
    thread = threading.Thread(target=server.serve_forever, name="behavior-mcp")
    thread.start()
    args.fixture = f"http://127.0.0.1:{server.server_address[1]}"
    fixture = Api(args.fixture, os.environ.get("MCP_FIXTURE_TOKEN"))
    batch = {"lifecycle": "settling", "binding": {"deployment_revision": args.deployment_revision,
             "suite_version": plan["suite_version"], "evaluator_version": EVALUATOR_VERSION,
             "model_id": args.model_id, "model_version": None, "config_digest": None,
             "settlement_seconds": plan["settlement_seconds"]},
             "budget": {"turns": 0, "sessions": {}}, "blocking_reasons": []}
    cleanup = False
    try:
        with batch_lock(root):
            api, platform, resources = prepare(args, root, plan, fixture)
            batch = run_batch(api, platform, fixture, plan, resources, root, args.deployment_revision,
                              args.max_tokens, args.max_turns, args.max_seconds)
    except Exception as exc:
        status = getattr(exc, "status_code", None)
        detail = "prepare_or_run_failed:" + type(exc).__name__ + (":http_" + str(status) if isinstance(status, int) else "")
        batch["blocking_reasons"].append(issue("invalid_environment", detail))
    finally:
        server.shutdown()
        server.server_close()
        thread.join(10)
        cleanup = not thread.is_alive()
    if batch is not None:
        if args.command == "local-run":
            batch["binding"]["validation_scope"] = "local"
        report = final_report(root, plan, batch, cleanup)
        report["binding"]["ci_run_id"] = os.environ.get("GITHUB_RUN_ID")
        report["binding"]["ci_repository"] = os.environ.get("GITHUB_REPOSITORY")
        report["binding"]["ci_run_attempt"] = int(os.environ.get("GITHUB_RUN_ATTEMPT", "0"))
        save(root / "evaluation.json", report)
        publish(root, report)
        return outcome_exit_code(report, is_local)
    return 2


def evaluate_existing(root, plan):
    if plan["suite_version"] == "product-tasks-1":
        from behavior_product_eval import evaluate_product
        from behavior_dataset import load_dataset
        require(load_dataset(root)["fingerprint"] == plan["dataset_fingerprint"], "product_dataset_drift")
        batch = read(root / "batch.json")
        require(batch["lifecycle"] == "finalized", "cannot_evaluate_unfinalized_batch")
        rows = [evaluate_product(case, read(root / "cases" / case["id"] / str(rep) / "evidence.json"))
                for cid, rep in experiments(plan) for case in plan["cases"] if case["id"] == cid
                and (root / "cases" / cid / str(rep) / "evidence.json").exists()]
        result = aggregate(plan, rows, batch["binding"], batch.get("blocking_reasons", []))
        require(not (root / "reevaluation.json").exists(), "reevaluation_already_exists")
        save(root / "reevaluation.json", result)
        return result
    results = []
    for case_id, repetition in experiments(plan):
        case = next(c for c in plan["cases"] if c["id"] == case_id)
        path = root / "cases" / case_id / str(repetition) / "evidence.json"
        if path.exists():
            specific, _ = materialize(case, plan["batch"], repetition)
            results.append(evaluate_case(specific, read(path)))
    batch = read(root / "batch.json")
    require(batch["lifecycle"] == "finalized", "cannot_evaluate_unfinalized_batch")
    binding = {**batch["binding"], "source_evaluator_version": batch["binding"].get("evaluator_version"),
               "evaluator_version": EVALUATOR_VERSION}
    result = aggregate(plan, results, binding, batch["blocking_reasons"])
    execution_metrics(root, result, batch)
    # 离线重评产生独立文件，不能覆盖首次冻结报告。
    destination = root / "reevaluation.json"
    require(not destination.exists(), "reevaluation_already_exists")
    save(destination, result)
    return result


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=["dataset-generate", "dataset-validate", "dataset-freeze", "dataset-derive", "dataset-repair", "select", "regression-plan", "failure-export", "export-opik", "import-opik", "plan", "prepare", "run", "ci-run", "local-run", "evaluate", "report", "release-check", "compare", "experiment-freeze", "experiment-compare", "judge", "calibration", "collect-late"])
    p.add_argument("--root", type=Path)
    p.add_argument("--batch")
    p.add_argument("--suite", choices=("core", "stress", "product"), help="plan/local-run: choose frozen suite")
    p.add_argument("--generator-model-id")
    p.add_argument("--dataset-version")
    p.add_argument("--parent-root", type=Path, help="dataset-derive/dataset-repair: frozen source dataset")
    p.add_argument("--max-generator-tokens", type=int, default=300000)
    p.add_argument("--max-generator-requests", type=int, default=24)
    p.add_argument("--max-generator-seconds", type=int, default=1800)
    p.add_argument("--cases", help="local-run only: comma-separated case IDs")
    p.add_argument("--impact", help="select/local-run only: comma-separated impact labels")
    p.add_argument("--workers", type=int, choices=(1, 2), help="local-run only: freeze one or two evaluation lanes")
    p.add_argument("--repetitions", type=int, help="local-run only: 1-100 repetitions")
    p.add_argument("--turn-timeout-seconds", type=int, help="local-run only: per-input observation deadline, 1-540 seconds")
    p.add_argument("--api", default="http://127.0.0.1:8000/api/v1")
    p.add_argument("--platform", default="http://127.0.0.1:8100/api/v1")
    p.add_argument("--fixture", default="http://127.0.0.1:18765")
    p.add_argument("--model-id")
    p.add_argument("--judge-model-id")
    p.add_argument("--deployment-revision")
    p.add_argument("--environment-receipt", type=Path)
    p.add_argument("--max-tokens", type=int)
    p.add_argument("--max-turns", type=int)
    p.add_argument("--max-seconds", type=int)
    p.add_argument("--run-max-total-tokens", type=int,
                   help="product local-run only: isolated Agent Run cumulative total Token budget")
    p.add_argument("--expected-binding", type=Path)
    p.add_argument("--ci-run-id")
    p.add_argument("--ci-repository")
    p.add_argument("--regression-dataset-root", type=Path, help="regression-plan: current frozen Product dataset")
    p.add_argument("--triage", type=Path, help="failure-export: reviewed failure classifications")
    p.add_argument("--baseline", type=Path)
    p.add_argument("--experiment", type=Path)
    p.add_argument("--comparison-mode", choices=("repeat", "prompt"), default="repeat")
    p.add_argument("--prompt-source", action="append", default=[])
    p.add_argument("--human-scores", type=Path)
    p.add_argument("--out", type=Path, help="export-opik/regression-plan: new independent private directory")
    p.add_argument("--opik-url", default="http://127.0.0.1:15173/api")
    p.add_argument("--supplement", type=Path, help="export-opik only: independent readback evidence directory")
    p.add_argument("--internal-evidence", action="store_true",
                   help="explicit local read-only PostgreSQL evidence; DSN from SKILLFORGE_EVAL_INTERNAL_DSN")
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        if args.command in {"experiment-freeze", "experiment-compare"}:
            require(args.out is not None and not args.internal_evidence, "offline_command_out_required")
            out = private_root(args.out)
            if args.command == "experiment-freeze":
                from behavior_comparison import freeze_experiment
                require(args.root is not None and args.baseline is not None, "experiment_roots_required")
                result = freeze_experiment(private_root(args.baseline), private_root(args.root), out,
                                           args.comparison_mode, args.prompt_source)
                print(json.dumps({"fingerprint": result["fingerprint"], "new_model_inputs": 0}))
                return 0
            if args.command == "experiment-compare":
                from behavior_comparison import compare_experiment
                require(args.experiment is not None, "experiment_required")
                result = compare_experiment(args.experiment, out)
                print(json.dumps({"quality": result["quality"], "new_model_inputs": 0}, ensure_ascii=False))
                return 0 if result["quality"]["comparable"] else 1
        if args.command == "select":
            require(args.impact is not None, "impact_required")
            print(json.dumps(impact_selection(args), ensure_ascii=False))
            return 0
        require(args.root is not None, "root_required")
        require(not args.internal_evidence or args.command in {"local-run", "ci-run", "run", "prepare", "collect-late"},
                "internal_evidence_requires_collection_command")
        root = private_root(args.root)
        product_budget_scope = args.command == "local-run" and args.suite == "product"
        require(args.run_max_total_tokens is None or
                (product_budget_scope and args.run_max_total_tokens > 0),
                "product_total_budget_requires_positive_local_run")
        require(args.supplement is None or args.command == "export-opik", "supplement_requires_opik_export")
        require(args.parent_root is None or args.command in {"dataset-derive", "dataset-repair"}, "parent_requires_dataset_derive")
        if args.command in {"dataset-derive", "dataset-repair"}:
            from behavior_dataset import derive_dataset
            from behavior_dataset_repair import repair_dataset
            require(args.parent_root is not None and args.dataset_version is not None,
                    "dataset_derivation_parent_and_version_required")
            require(args.suite is None and not args.internal_evidence, "dataset_command_options_invalid")
            derive = repair_dataset if args.command == "dataset-repair" else derive_dataset
            result = derive(private_root(args.parent_root), root, args.dataset_version)
            print(json.dumps({"dataset_version": result["dataset_version"],
                              "fingerprint": result["fingerprint"], "new_model_inputs": 0}, ensure_ascii=False))
            return 0
        if args.command in {"dataset-generate", "dataset-validate", "dataset-freeze"}:
            from behavior_dataset import validate_candidates, freeze_dataset
            require(args.suite is None and not args.internal_evidence, "dataset_command_options_invalid")
            root.mkdir(parents=True, exist_ok=True)
            with batch_lock(root):
                if args.command == "dataset-generate":
                    local_endpoints(args)
                    from behavior_dataset_generator import generate_dataset
                    result = generate_dataset(args, root)
                elif args.command == "dataset-validate":
                    result = validate_candidates(read(root / "candidates.json"))
                    save(root / "validation.json", result)
                else:
                    result = freeze_dataset(root, args.dataset_version)
            print(json.dumps({"command": args.command, "gaps": result.get("gaps", []),
                              "fingerprint": result.get("fingerprint"), "selected": len(result.get("selected", []))}, ensure_ascii=False))
            return 1 if result.get("gaps") else 0
        require(args.command in {"local-run", "regression-plan"} or args.cases is None,
                "cases_requires_local_run_or_regression_plan")
        require(args.command == "local-run" or all(v is None for v in (args.impact, args.repetitions, args.turn_timeout_seconds, args.workers)), "selection_requires_local_run")
        require(args.suite is None or args.command in {"plan", "local-run"}, "suite_selection_requires_plan_or_local_run")
        require(not (args.cases is not None and args.impact is not None), "impact_and_cases_are_mutually_exclusive")
        require(args.regression_dataset_root is None or args.command == "regression-plan", "dataset_requires_regression_plan")
        require(args.triage is None or args.command == "failure-export", "triage_requires_failure_export")
        if args.command == "failure-export":
            from behavior_failure_export import export_failures
            require(args.out is not None, "failure_export_out_required")
            result = export_failures(root, private_root(args.out), read(args.triage) if args.triage else {})
            print(json.dumps(result, ensure_ascii=False))
            return 0
        if args.command == "regression-plan":
            from behavior_regression import regression_plan
            require(args.out is not None and args.batch is not None, "regression_out_and_batch_required")
            result = regression_plan(root, private_root(args.out), args.batch,
                                     case_ids=[cid.strip() for cid in args.cases.split(",")] if args.cases is not None else None,
                                     dataset_root=private_root(args.regression_dataset_root) if args.regression_dataset_root else None)
            print(json.dumps(result, ensure_ascii=False))
            return 0
        if args.command == "export-opik":
            from behavior_opik_export import export_bundle
            require(args.out is not None, "opik_export_out_required")
            if args.supplement is not None:
                from behavior_opik_supplement import export_supplement
                result = export_supplement(root, private_root(args.supplement), private_root(args.out))
            else:
                result = export_bundle(root, private_root(args.out))
            print(json.dumps(result, ensure_ascii=False))
            return 0
        if args.command == "import-opik":
            from behavior_opik_import import import_bundle
            result = import_bundle(root, args.opik_url)
            print(json.dumps(result, ensure_ascii=False))
            return 0
        if args.command == "plan":
            plan = create_plan(root, args.batch, suite=args.suite or "core")
            print(json.dumps({"batch": plan["batch"], "cases": len(plan["cases"]), "experiments": len(experiments(plan))}))
            return 0
        if args.command in {"ci-run", "local-run"}:
            if args.suite == "product":
                require(args.command == "local-run", "product_is_local_only")
                from behavior_product_runner import run_product
                return run_product(args, root)
            return ci_run(args, root)
        plan = validate_plan(read(root / "plan.json"))
        if plan["suite_version"] == "product-tasks-1":
            require(args.command not in {"run", "prepare", "judge", "calibration", "collect-late"}, "product_use_local_run_no_judge")
        if args.command == "prepare":
            local_endpoints(args)
            require(bool(args.model_id and args.deployment_revision and args.environment_receipt), "prepare_requires_model_revision_receipt")
            verify_environment(args.environment_receipt, args.deployment_revision, args.api, args.platform)
            with batch_lock(root):
                prepare(args, root, plan, Api(args.fixture, os.environ.get("MCP_FIXTURE_TOKEN")))
            return 0
        if args.command == "run":
            require_live_args(args)
            verify_environment(args.environment_receipt, args.deployment_revision, args.api, args.platform)
            with batch_lock(root):
                api, platform = login(args.api), login(args.platform, True)
                configure_internal_evidence(api, args.internal_evidence)
                resources = read(root / "resources.json")
                model = resolve_model(platform, args.model_id)
                require(str(model["id"]) == str(resources["model_id"]), "prepared_model_mismatch")
                batch = run_batch(api, platform, Api(args.fixture, os.environ.get("MCP_FIXTURE_TOKEN")), plan,
                                  resources, root, args.deployment_revision, args.max_tokens, args.max_turns, args.max_seconds)
                # 外部夹具不属于本进程，不能伪称已关闭；CI 完整路径由 ci-run 管理。
                report = final_report(root, plan, batch, False)
                publish(root, report)
            return 1
        if args.command == "evaluate":
            result = evaluate_existing(root, plan)
            return 0 if result["release_eligible"] else 1
        if args.command == "report":
            publish(root, read(root / "evaluation.json"))
            return 0
        if args.command == "collect-late":
            from behavior_runner import collect_late
            api = login(args.api)
            configure_internal_evidence(api, args.internal_evidence)
            result = collect_late(api, root)
            print(json.dumps({"cases": len(result["cases"]), "late_events": sum(c["late_event_count"] for c in result["cases"])}))
            return 0
        if args.command == "release-check":
            from behavior_release import GitHub, verify_ci
            require(args.expected_binding is not None, "expected_binding_required")
            expected = read(args.expected_binding)
            raw = (root / "public" / "summary.json").read_bytes()
            result = release_check(json.loads(raw), expected, plan)
            if not result["qualified"]:
                print(json.dumps(result, ensure_ascii=False))
                return 1
            provenance = verify_ci(raw, args.ci_repository, args.ci_run_id, expected["deployment_revision"],
                                   GitHub(os.environ.get("GH_TOKEN")))
            result["provenance"] = provenance
            print(json.dumps(result, ensure_ascii=False))
            return 0 if result["qualified"] else 1
        if args.command == "compare":
            require(args.baseline is not None, "baseline_required")
            result = compare_reports(read(args.baseline), read(root / "evaluation.json"))
            print(json.dumps(result, ensure_ascii=False))
            return 0 if result["comparable"] else 1
        if args.command == "judge":
            from behavior_judge import run_judge
            require(args.judge_model_id is not None, "judge_model_id_required")
            return run_judge(args, root)
        if args.command == "calibration":
            require(args.human_scores is not None, "human_scores_required")
            result = calibration_report(read(args.human_scores), read(root / "judge" / "scores.json"))
            save(root / "judge" / "calibration.json", result)
            print(json.dumps(result, ensure_ascii=False))
            return 0
    except (ValueError, OSError, KeyError, RuntimeError) as exc:
        # API/原始模型异常可能包含业务内容，只输出稳定异常类型。
        detail = str(exc) if type(exc) is ValueError and re.fullmatch(r"[a-z0-9_:]+", str(exc)) else type(exc).__name__
        print("behavior_command_failed:" + detail, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
