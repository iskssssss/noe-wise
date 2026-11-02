"""Ten selected live Gradle lanes: read-only READY plan, one ledger, exact attempt settlement.

Default operation never launches Gradle or a provider. --execute is for the sole
supervisor after Java thaw, with a fresh source-bound READY receipt. A missing
send gate, attempt record, usage field, terminal, or artifact stops paid dispatch.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import xml.etree.ElementTree as ET

import budget_ledger
from run_suite import REPO, load_environment
from live_behavior_usage import summarize, UsageUnavailable

RUN_ROOT = REPO / ".runtime-logs" / budget_ledger.RUN_ID
LIVE_ROOT = RUN_ROOT / "live-gradle-acceptance"
ENV_FILE = RUN_ROOT / "environment/isolated.env"
READY_FILE = REPO / ".agent-work-state/full-test-20261012/live-gradle-ready.json"
JAVA_ROOT = "backend-java/skillforge-institution-service/"
PACKAGE = "java/com/skillforge/institution/integration/"
HELPER = JAVA_ROOT + "src/test/" + PACKAGE + "LiveGradleAttemptMeter.java"
PROVIDER_ROOT = "backend-java/agent-runtime-adapter-model/"
PROVIDER_PACKAGE = "java/com/skillforge/runtime/adapter/model/provider/"
PROVIDER_HELPER = PROVIDER_ROOT + "src/test/" + PROVIDER_PACKAGE + "LiveProviderAttemptMeter.java"
COMMON = ("tools/full_project_testing/live_gradle_acceptance.py",
          "tools/full_project_testing/budget_ledger.py", "tools/full_project_testing/live_behavior_usage.py",
          "tools/full_project_testing/run_suite.py", "tools/full_project_testing/force-tests.gradle",
          "tools/full_project_testing/isolate-integration-tests.gradle",
          "tools/full_project_testing/isolated_environment.py", "tools/full_project_testing/connect_services.py",
          "tools/full_project_testing/frozen_sandbox_endpoint.py",
          "backend-java/build.gradle.kts", "backend-java/settings.gradle.kts", "backend-java/gradle.properties",
          "backend-java/gradlew", "backend-java/gradlew.bat",
          "backend-java/gradle/wrapper/gradle-wrapper.properties", "backend-java/gradle/wrapper/gradle-wrapper.jar",
          JAVA_ROOT + "build.gradle.kts", HELPER, PROVIDER_ROOT + "build.gradle.kts", PROVIDER_HELPER)
GENERATED_SOURCE_DIRECTORIES = frozenset(("build", "out", "target", "node_modules", "__pycache__",
                                        ".git", ".idea", ".runtime-logs", ".agent-work-state"))


def java(source, name):
    return JAVA_ROOT + f"src/{source}/{PACKAGE}{name}.java"


SUITES = {
    "conversation": {"label": "同会话续聊", "task": "liveConversationE2E",
        "gate": "SKILLFORGE_LIVE_CONVERSATION", "required": ("SDK4_LIVE_MODEL_KEY",),
        "sources": (java("test", "ChatRuntimeChannelE2ETest"),),
        "original_artifacts": "build/runtime-e2e/live-conversation-*/live-evidence.json",
        "current_gaps": ("measured_usage_sql_lacks_attempt_id", "supervisor_token_gate_missing")},
    "tool-conversation": {"label": "只读 MCP 续聊", "task": "liveToolConversationE2E",
        "gate": "SKILLFORGE_LIVE_TOOL_CONVERSATION", "required": ("SDK4_LIVE_MODEL_KEY",),
        "sources": (java("test", "ChatRuntimeChannelE2ETest"),),
        "original_artifacts": "build/runtime-e2e/live-tool-conversation-*/live*evidence.json",
        "current_gaps": ("measured_usage_sql_lacks_attempt_id", "supervisor_token_gate_missing")},
    "chat-browser": {"label": "真实聊天浏览器", "task": "liveChatProcessBrowserE2E",
        "gate": "SKILLFORGE_LIVE_CHAT_BROWSER", "required": ("SDK4_LIVE_MODEL_KEY",),
        "sources": (java("test", "ChatRuntimeChannelE2ETest"), java("test", "ChatExecutionProcessBrowserE2ETest")),
        "original_artifacts": "build/runtime-e2e/chat-process-browser-*/live-backend-evidence.json",
        "current_gaps": ("success_only_report_lacks_attempt_usage_identity", "supervisor_token_gate_missing")},
    "prompt-cache": {"label": "八 Run Session 前缀", "task": "liveSessionPromptCacheAcceptance",
        "gate": "SKILLFORGE_LIVE_SESSION_PROMPT_CACHE_ACCEPTANCE",
        "required": ("LIVE_PROMPT_CACHE_SOURCE_MODEL_ID", "LIVE_PROMPT_CACHE_SOURCE_MODEL_REVISION"),
        "evidence_env": "LIVE_PROMPT_CACHE_EVIDENCE_FILE",
        "sources": tuple(java("liveChatCapacityAcceptance", name) for name in
            ("SessionPromptCacheLiveAcceptance", "LivePromptCacheBudgets", "PromptCacheRecordingHttpClient")),
        "original_artifacts": "httpCalls + budget.attempts + runtimeCalls",
        "current_gaps": ("standard_pre_dispatch_journal_not_yet_connected",)},
    "operational-config": {"label": "运营配置多步骤", "task": "liveOperationalConfigAcceptance",
        "gate": "SKILLFORGE_LIVE_OPERATIONAL_CONFIG_ACCEPTANCE",
        "required": ("LIVE_CONFIG_SOURCE_MODEL_ID", "LIVE_CONFIG_SOURCE_MODEL_REVISION"),
        "evidence_env": "LIVE_CONFIG_EVIDENCE_FILE",
        "sources": (java("liveChatCapacityAcceptance", "OperationalConfigLiveAcceptance"),),
        "original_artifacts": "modelAttempts/providerInputTokens/providerOutputTokens aggregate",
        "current_gaps": ("aggregate_only_report", "supervisor_token_gate_missing")},
    "capability": {"label": "聊天与协作容量", "task": "liveChatCapacityAcceptance",
        "gate": "SKILLFORGE_LIVE_CHAT_CAPACITY_ACCEPTANCE",
        "required": ("LIVE_CAPACITY_SOURCE_TENANT_ID", "LIVE_CAPACITY_SOURCE_MODEL_ID",
            "LIVE_CAPACITY_SOURCE_MODEL_REVISION", "LIVE_CAPACITY_SOURCE_DATABASE", "SANDBOX_ENDPOINT", "SANDBOX_SERVICE_TOKEN"),
        "evidence_env": "LIVE_CAPACITY_EVIDENCE_FILE",
        "sources": tuple(java("liveChatCapacityAcceptance", name) for name in
            ("ChatCapacityLiveAcceptance", "LiveCapacityBudgets", "LiveCapacityModelAudit", "LiveCapacitySourceSnapshot")),
        "original_artifacts": "budget scene aggregate; owned JDBC facts before teardown",
        "current_gaps": ("aggregate_only_report", "source_database_hardcoded_to_old_development_database")},
    "public": {"label": "公开对话边界", "task": "livePublicAcceptance",
        "gate": "SKILLFORGE_LIVE_PUBLIC_ACCEPTANCE",
        "required": ("LIVE_PUBLIC_SOURCE_TENANT_ID", "LIVE_PUBLIC_SOURCE_MODEL_ID", "LIVE_PUBLIC_SOURCE_MODEL_REVISION",
                     "LIVE_PUBLIC_SOURCE_BUDGET_REVISION"), "evidence_env": "LIVE_PUBLIC_EVIDENCE_FILE",
        "sources": tuple(java("livePublicAcceptance", name) for name in
            ("ChatPublicDefaultModelLiveAcceptance", "LivePublicBudget", "LivePublicModelAudit", "LivePublicSourceSnapshot")),
        "original_artifacts": "budget scene aggregate; owned JDBC including child/auxiliary attempts",
        "current_gaps": ("aggregate_only_report",)},
    "vision": {"label": "用户与工具图片", "task": "liveVisionAcceptance",
        "gate": "SKILLFORGE_LIVE_VISION_ACCEPTANCE", "required": ("LIVE_VISION_MODE", "LIVE_VISION_SOURCE_TENANT_ID"),
        "evidence_env": "LIVE_VISION_EVIDENCE_DIR",
        "sources": (JAVA_ROOT + "src/liveVisionAcceptance/java/com/skillforge/institution/media/ModelVisionLiveAcceptance.java",),
        "original_artifacts": "vision-evidence.json + vision-tool-evidence.json + vision-route.json",
        "current_gaps": ("noop_audit_no_send_cap", "same_attempt_identity_for_two_scenes", "failure_usage_unavailable")},
    "provider-smoke": {"label": "供应商同步与流式协议", "task": ":agent-runtime-adapter-model:test",
        "test_class": "com.skillforge.runtime.adapter.model.provider.LiveModelProviderSmokeTest",
        "gate": "SKILLFORGE_LIVE_DEEPSEEK",
        "gates": {"SKILLFORGE_LIVE_DEEPSEEK": "true", "SKILLFORGE_LIVE_BAILIAN": "true"},
        "required": ("SKILLFORGE_DEEPSEEK_API_KEY", "SKILLFORGE_DEEPSEEK_MODEL",
                     "SKILLFORGE_BAILIAN_API_KEY", "SKILLFORGE_BAILIAN_MODEL",
                     "SKILLFORGE_BAILIAN_SERVICE", "SKILLFORGE_BAILIAN_ENDPOINT"),
        "sources": (PROVIDER_ROOT + "src/test/" + PROVIDER_PACKAGE + "LiveModelProviderSmokeTest.java",),
        "meter": PROVIDER_HELPER, "meter_class": "LiveProviderAttemptMeter",
        "host_environment_required": False,
        "planned_caps": {"max_attempts": 4, "max_tokens": 40000, "max_seconds": 600},
        "original_artifacts": "LIVE_PROVIDER_PASS; standard per-wire-send journal and usage snapshot required",
        "current_gaps": ("wire_send_supervisor_gate_missing", "per_attempt_usage_snapshot_missing")},
    "provider-cache": {"label": "供应商三服务缓存观察", "task": ":agent-runtime-adapter-model:test",
        "test_class": "com.skillforge.runtime.adapter.model.provider.LiveProviderPromptCacheTest",
        "gate": "SKILLFORGE_LIVE_PROMPT_CACHE", "gates": {"SKILLFORGE_LIVE_PROMPT_CACHE": "true"},
        "required": ("SKILLFORGE_PROMPT_CACHE_CREDENTIALS_FILE",),
        "evidence_env": "SKILLFORGE_PROMPT_CACHE_EVIDENCE_DIR", "evidence_directory": True,
        "sources": (PROVIDER_ROOT + "src/test/" + PROVIDER_PACKAGE + "LiveProviderPromptCacheTest.java",),
        "meter": PROVIDER_HELPER, "meter_class": "LiveProviderAttemptMeter",
        "host_environment_required": False,
        "planned_caps": {"max_attempts": 24, "max_tokens": 800000, "max_seconds": 1800},
        "original_artifacts": "three services x eight calls; per-service original reports plus standard wire journal",
        "current_gaps": ("whole_lane_gate_across_junit_methods_missing", "standard_usage_snapshot_not_yet_connected")},
}


class EvidenceError(ValueError):
    """Safe fixed diagnostic codes; never print secrets or supplier payloads."""


def require(condition, code):
    if not condition:
        raise EvidenceError(code)


def natural(value):
    require(type(value) is int and value >= 0, "nonnegative_integer_required")
    return value


def seconds(value):
    require(type(value) in (int, float) and math.isfinite(value) and value >= 0, "finite_elapsed_required")
    return value


def stamp(value):
    require(isinstance(value, str), "timestamp_required")
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(result.tzinfo is not None, "timestamp_timezone_required")
        return result
    except ValueError:
        raise EvidenceError("timestamp_invalid") from None


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate_json_field")
        result[key] = value
    return result


def decode(raw):
    try:
        return json.loads(raw, object_pairs_hook=unique_keys,
            parse_constant=lambda _: (_ for _ in ()).throw(EvidenceError("nonfinite_json_number")))
    except (UnicodeError, json.JSONDecodeError):
        raise EvidenceError("invalid_json_artifact") from None


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_new(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def direct_source_manifest():
    """Keep the exact selected test, meter, and runner inputs visible in the READY receipt."""
    paths = sorted(set(COMMON + tuple(p for s in SUITES.values() for p in s["sources"])))
    return {p: digest(REPO / p) if (REPO / p).is_file() else None for p in paths}


def source_manifest():
    """Bind all backend source/resource/migration and Gradle inputs, pruning generated trees.

    A selected live class consumes production modules and shared test fixtures through
    its Gradle classpath. Hashing only that class would let an old READY authorize a
    changed mapper, permission rule, migration, fixture, or build configuration.
    """
    paths = set(direct_source_manifest())
    backend = REPO / "backend-java"
    for directory, children, names in os.walk(backend, followlinks=False):
        children[:] = sorted(name for name in children
                             if name not in GENERATED_SOURCE_DIRECTORIES
                             and not name.startswith(".gradle"))
        relative_directory = Path(directory).relative_to(backend)
        parts = relative_directory.parts
        source_tree = "src" in parts or "buildSrc" in parts or (parts and parts[0] == "gradle")
        for name in sorted(names):
            gradle_input = (name.endswith((".gradle", ".gradle.kts", ".lockfile"))
                            or name in ("gradle.properties", "gradle.lockfile"))
            if source_tree or gradle_input:
                path = Path(directory) / name
                if path.is_file():
                    paths.add(path.relative_to(REPO).as_posix())
    return {p: digest(REPO / p) if (REPO / p).is_file() else None for p in sorted(paths)}


def suite_caps(suite):
    """A proposed new lane is plan-only until the ledger owner appends its exact caps."""
    return budget_ledger.LIMITS.get(suite, SUITES[suite].get("planned_caps"))


def task_path(suite):
    task = SUITES[suite]["task"]
    return task if task.startswith(":") else ":skillforge-institution-service:" + task


def gradle_command(suite):
    command = [str(REPO / "backend-java/gradlew.bat"), "--gradle-user-home", str(REPO / ".gradle-user-home"), "--no-daemon",
        "--no-parallel", "--max-workers=1", "--console=plain", "--init-script", "../tools/full_project_testing/force-tests.gradle",
        task_path(suite)]
    if SUITES[suite].get("test_class"):
        command += ["--tests", SUITES[suite]["test_class"]]
    return command


def selected_junit_root(suite):
    if SUITES[suite].get("test_class"):
        return REPO / PROVIDER_ROOT / "build/test-results/test"
    return REPO / JAVA_ROOT / "build/test-results" / SUITES[suite]["task"]


def lane_environment(suite, number, root, sources=None):
    require(suite in SUITES and type(number) is int and number in (1, 2, 3), "selected_suite_and_round_required")
    require(ENV_FILE.is_file(), "owned_isolated_env_missing")
    environment = load_environment(ENV_FILE)
    # Enabling one lane cannot accidentally inherit a different paid Test tag.
    for spec in SUITES.values():
        environment.pop(spec["gate"], None)
    for flag in ("SKILLFORGE_LIVE_MEMORY_ACCEPTANCE", "LIVE_KNOWLEDGE_ACCEPTANCE",
                 "SKILLFORGE_LIVE_DEEPSEEK", "SKILLFORGE_LIVE_BAILIAN", "SKILLFORGE_LIVE_PROMPT_CACHE"):
        environment.pop(flag, None)
    spec = SUITES[suite]
    environment.update(spec.get("gates", {spec["gate"]: "1"}))
    environment["LIVE_GRADLE_SUITE"] = suite
    environment["LIVE_GRADLE_EVIDENCE_DIR"] = str(root.resolve())
    caps = suite_caps(suite)
    environment["LIVE_GRADLE_MAX_ATTEMPTS"] = str(caps["max_attempts"])
    environment["LIVE_GRADLE_MAX_TOKENS"] = str(caps["max_tokens"])
    environment["LIVE_GRADLE_MAX_SECONDS"] = str(caps["max_seconds"])
    environment["LIVE_GRADLE_ROUND"] = str(number)
    environment["LIVE_CAPACITY_RUNTIME_REVISION"] = (source_manifest() if sources is None else sources)[JAVA_ROOT + "build.gradle.kts"]
    environment["LIVE_PUBLIC_RUNTIME_REVISION"] = environment["LIVE_CAPACITY_RUNTIME_REVISION"]
    if spec.get("evidence_env"):
        environment[spec["evidence_env"]] = str(root.resolve() if suite == "vision" or spec.get("evidence_directory")
                                              else (root / "original-report.json").resolve())
    if suite == "public":
        environment.update(LIVE_PUBLIC_MAX_CALLS="64", LIVE_PUBLIC_MAX_TOKENS="3000000",
                           LIVE_PUBLIC_BATCH_DIR=str(root.resolve()))
    return environment


def java_code(path):
    """Ignore names mentioned only in comments; preserve literals such as SQL contracts."""
    source = Path(path).read_text(encoding="utf-8")
    return re.sub(r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')|//[^\n]*|/\*.*?\*/',
                  lambda match: match[1] or " ", source, flags=re.S)


def instrumentation_issues(suite, sources):
    """Bind the concrete consumers, persistence, and send gates to the frozen source hashes."""
    spec = SUITES[suite]
    meter = spec.get("meter", HELPER)
    if sources.get(meter) is None or not (REPO / meter).is_file():
        return ["standard_attempt_meter_not_connected", *spec["current_gaps"]]
    helper = java_code(REPO / meter)
    consumer = java_code(REPO / spec["sources"][0])
    common = ("channel.force(true)", "StandardOpenOption.CREATE_NEW", "attempts.jsonl",
              "attempt-evidence.json", "LIVE_GRADLE_RESERVATION_ID", "live_lane_cap_drift")
    if suite.startswith("provider-"):
        gate = ("class GuardedHttpClient", "ticket.state.sent(ticket.id)", "delegate.send(",
                "delegate.sendAsync(", "bodySubscribed.compareAndSet(false, true)", "Redirect.NEVER")
        hooks = ("LiveProviderAttemptMeter.prepare(", "LiveProviderAttemptMeter.client(",
                 "LiveProviderAttemptMeter.observe(", "LiveProviderAttemptMeter.snapshot()", "LiveProviderAttemptMeter.Outcome")
        if suite == "provider-smoke":
            hooks += ("LiveProviderAttemptMeter.result(",)
    else:
        gate = ("state.reserve(", "delegate.start(call, provider)", "state.sent(attempt)", "runtime_budget_attempt_amounts",
                '"RESERVED".equals', "public.external_invocations", "public.usage_events")
        hooks = ("LiveGradleAttemptMeter.Outcome",)
        if suite == "vision":
            hooks += ("LiveGradleAttemptMeter.vision(", "LiveGradleAttemptMeter.visionSnapshot()")
        else:
            hooks += ("LiveGradleAttemptMeter.wrap(original, source)", "LiveGradleAttemptMeter.snapshot(")
    issues = []
    if not all(marker in helper for marker in common + gate):
        issues.append("standard_meter_persistence_or_send_gate_incomplete")
    if not all(marker in consumer for marker in hooks):
        issues.append("standard_meter_consumer_hooks_incomplete")
    if suite == "vision" and "ModelInvocationAudit.noop()" in consumer:
        issues.append("vision_noop_audit_still_active")
    return issues


def private_input_manifest(environment=None):
    """Hash a referenced credential file so changing it cannot silently reuse an old READY receipt."""
    environment = load_environment(ENV_FILE) if environment is None else environment
    result = {}
    for name in ("SKILLFORGE_PROMPT_CACHE_CREDENTIALS_FILE",):
        path = environment.get(name)
        if path and path.strip():
            try:
                result[name] = digest(Path(path))
            except OSError:
                result[name] = None
    return result


def preflight_suite(suite, environment, sources):
    spec, issues = SUITES[suite], []
    host_required = ("SECRET_KEY", "SPRING_DATASOURCE_URL", "PLATFORM_DATASOURCE_USERNAME", "PLATFORM_DATASOURCE_PASSWORD")
    required = (*host_required, *spec["required"]) if spec.get("host_environment_required", True) else spec["required"]
    if suite == "vision":
        mode = environment.get("LIVE_VISION_MODE")
        if mode == "explicit":
            required += ("LIVE_VISION_ENDPOINT", "LIVE_VISION_API_KEY")
        elif mode == "published":
            required += ("LIVE_VISION_SOURCE_MODEL_ID", "LIVE_VISION_SOURCE_MODEL_REVISION")
        else:
            issues.append("vision_explicit_or_published_route_required")
    issues += ["missing_env:" + name for name in required if not environment.get(name, "").strip()]
    if suite not in budget_ledger.LIMITS:
        issues.append("suite_not_yet_appended_to_frozen_ledger")
    elif spec.get("planned_caps") and budget_ledger.LIMITS[suite] != spec["planned_caps"]:
        issues.append("provider_lane_cap_plan_drift")
    issues += instrumentation_issues(suite, sources)
    if suite == "capability":
        source = (REPO / java("liveChatCapacityAcceptance", "LiveCapacitySourceSnapshot")).read_text(encoding="utf-8")
        if 'SOURCE_DATABASE = "skillforge_dev_public_output_20261001"' in source:
            issues.append("owned_source_database_fixture_not_parameterized")
    if suite == "provider-cache" and environment.get("SKILLFORGE_PROMPT_CACHE_CREDENTIALS_FILE", "").strip():
        try:
            credentials = decode(Path(environment["SKILLFORGE_PROMPT_CACHE_CREDENTIALS_FILE"]).read_bytes())
            require(isinstance(credentials, dict), "provider_cache_credentials_object_required")
            for provider in ("deepseek", "bailian"):
                configuration = credentials.get(provider)
                require(isinstance(configuration, dict) and isinstance(configuration.get("api_key"), str)
                        and bool(configuration["api_key"].strip()), "provider_cache_credentials_incomplete")
                for field in ("model", "endpoint"):
                    require(field not in configuration or configuration[field] is None
                            or isinstance(configuration[field], str), "provider_cache_route_field_invalid")
        except (EvidenceError, OSError, ValueError, TypeError):
            issues.append("provider_cache_credentials_readonly_precheck_failed")
    return {"suite": suite, "label": spec["label"], "task": task_path(suite),
        "test_class": spec.get("test_class"), "rounds": [1, 2, 3], "caps": suite_caps(suite), "required_env_names": list(required),
        "original_artifacts": spec["original_artifacts"], "status": "READY" if not issues else "BLOCKED",
        "issues": sorted(set(issues)), "provider_calls_sent": 0}


def prepare_ready():
    """Produces only a plan; current missing instrumentation is an explicit block."""
    sources = source_manifest()
    rows = [preflight_suite(s, lane_environment(s, 1, LIVE_ROOT / "round-1" / s, sources), sources) for s in SUITES]
    ledger = None
    if budget_ledger.LEDGER_PATH.exists():
        value = budget_ledger.load_ledger(budget_ledger.LEDGER_PATH)
        ledger = {"stop_new_dispatch": value["stop_new_dispatch"],
                  "outstanding_reservations": sum(r["status"] == "RESERVED" for r in value["reservations"])}
    return {"schema_version": 1, "run_id": budget_ledger.RUN_ID, "status": "READY_PLAN_ONLY",
        "created_at": datetime.now(timezone.utc).isoformat(), "ledger_path": str(budget_ledger.LEDGER_PATH.resolve()),
        "source_files": sources, "direct_source_files": direct_source_manifest(),
        "source_manifest_scope": "backend_sources_resources_migrations_gradle_and_execution_inputs",
        "environment_sha256": digest(ENV_FILE),
        "private_input_sha256": private_input_manifest(), "rounds": 3, "suites": rows,
        "ledger_snapshot": ledger, "provider_calls_sent": 0, "gradle_launched": False,
        "staging_candidate": {"task": ":skillforge-institution-service:stagingRuntimeE2E",
            "status": "CANDIDATE_READONLY_PREFLIGHT_ONLY", "ledger_suite": None,
            "manifest_env": "STAGING_PROVIDER_PROFILES_FILE", "manifest_must_be_outside_repo": True,
            "fields": ["alias", "tenant_slug", "username_env", "password_env"],
            "minimum_distinct_endpoint_model_pairs": 2,
            "per_profile_budget": {"max_business_model_calls": 6, "max_tool_calls": 1, "max_knowledge_calls": 1,
                "max_total_tokens": 3000000, "max_wall_time_seconds": 480}, "provider_calls_sent": 0}}


def identity(document, receipt):
    expected = {"schema_version": 1, "run_id": budget_ledger.RUN_ID, "suite": receipt["suite"],
                "round": receipt["round"], "reservation_id": receipt["reservation_id"], "batch_id": receipt["batch_id"]}
    require(all(document.get(k) == v and type(document.get(k)) is type(v) for k, v in expected.items()), "artifact_reservation_owner_mismatch")


def standard_measurement(root, receipt, execution):
    """Maps future test-only snapshots to actual usage; no aggregate-as-attempt shortcut."""
    path = Path(root) / "attempt-evidence.json"
    require(path.is_file(), "standard_attempt_snapshot_missing")
    evidence = decode(path.read_bytes())
    identity(evidence, receipt)
    require(evidence.get("caps") == budget_ledger.LIMITS[receipt["suite"]], "artifact_cap_drift")
    require(evidence.get("collection_complete") is True, "attempt_collection_incomplete")
    expected_source = "provider_wire_audit" if receipt["suite"].startswith("provider-") else (
        "vision_model_audit" if receipt["suite"] == "vision" else "owned_testcontainer_jdbc")
    require(evidence.get("source") == expected_source, "actual_provider_source_required")
    require(stamp(execution["started_at"]) <= stamp(evidence["captured_at"]) <= stamp(execution["finished_at"]), "stale_or_unowned_snapshot")
    journal_path = Path(root) / "attempts.jsonl"
    require(journal_path.is_file(), "pre_dispatch_attempt_journal_missing")
    raw = journal_path.read_bytes()
    require(len(raw) <= 32 * 1024 * 1024 and (not raw or raw.endswith(b"\n")), "truncated_or_oversized_attempt_journal")
    attempts, sent, last_sequence = {}, set(), 0
    for line in raw.splitlines():
        row = decode(line)
        identity(row, receipt)
        require(type(row.get("sequence")) is int and row["sequence"] == last_sequence + 1, "attempt_journal_sequence_gap")
        last_sequence = row["sequence"]
        require(stamp(execution["started_at"]) <= stamp(row["time"]) <= stamp(execution["finished_at"]), "attempt_journal_time_outside_execution")
        name = row.get("attempt_id")
        require(isinstance(name, str) and bool(name.strip()), "attempt_identity_required")
        event = row.get("event")
        require(event in ("reserved", "sent", "not_sent", "usage_report", "succeeded", "failed", "unresolved"), "attempt_journal_event_invalid")
        if event == "reserved":
            require(name not in attempts, "duplicate_attempt_reservation")
            natural(row.get("reserved_input_tokens")); natural(row.get("reserved_output_tokens"))
            require(row["reserved_input_tokens"] + row["reserved_output_tokens"] > 0, "positive_attempt_reservation_required")
            attempts[name] = {"row": row, "terminal": None, "revision": 0, "sent": False}
        else:
            require(name in attempts, "orphan_attempt_journal_event")
            item = attempts[name]
            require(row.get("run_id_owned") == item["row"].get("run_id_owned"), "attempt_run_owner_drift")
            if event == "sent":
                require(not item["sent"] and item["terminal"] is None, "attempt_sent_twice")
                item["sent"] = True; sent.add(name)
            elif event == "not_sent":
                require(not item["sent"] and item["terminal"] is None, "not_sent_after_possible_dispatch")
                item["terminal"] = event
            else:
                require(item["sent"] and item["terminal"] is None, "attempt_event_without_active_send")
                if event == "usage_report":
                    require(type(row.get("revision")) is int and row["revision"] > item["revision"], "usage_revision_not_increasing")
                    item["revision"] = row["revision"]
                else:
                    item["terminal"] = event
    supplier_attempts = evidence.get("attempts")
    records = evidence.get("provider_records")
    require(isinstance(supplier_attempts, list) and isinstance(records, list), "per_attempt_provider_lists_required")
    require(len({x.get("attempt_id") for x in supplier_attempts}) == len(supplier_attempts), "duplicate_provider_attempt")
    require({x.get("attempt_id") for x in supplier_attempts} == sent, "provider_and_send_attempt_sets_mismatch")
    for row in supplier_attempts:
        item = attempts[row["attempt_id"]]
        require(item["terminal"] in ("succeeded", "failed", "unresolved"), "attempt_terminal_missing")
        require(row.get("status") == {"succeeded": "SUCCEEDED", "failed": "FAILED", "unresolved": "UNRESOLVED"}[item["terminal"]], "provider_terminal_journal_mismatch")
        require(row.get("operation_type") == "model.chat", "unexpected_paid_operation_for_selected_suite")
    try:
        usage = summarize(supplier_attempts, records)
    except UsageUnavailable:
        raise EvidenceError("provider_attempt_usage_contract_invalid") from None
    known = usage["usage_known"]
    issues = [] if known else ["actual_provider_usage_unknown"]
    for row in usage["attempts"]:
        reserved = attempts[row["attempt_id"]]["row"]
        values = row["provider_values"]
        if row["usage_known"]:
            if values["prompt_tokens"] > reserved["reserved_input_tokens"] or values["completion_tokens"] > reserved["reserved_output_tokens"]:
                # Preserve the actual excess; an exceeded reservation is not zero/unknown usage.
                issues.append("provider_usage_exceeds_attempt_reservation")
    return {"usage_known": known, "observed_attempts": len(sent), "observed_tokens": usage["tokens"],
        "observed_elapsed_seconds": seconds(execution["elapsed_seconds"]), "attempts": usage["attempts"],
        "issues": issues, "acceptance_outcome": evidence.get("acceptance_outcome", "UNKNOWN"),
        "artifacts": [{"path": path.name, "sha256": digest(path)}, {"path": journal_path.name, "sha256": digest(journal_path)}]}


def stop_gate(reason):
    path = budget_ledger.canonical_path(budget_ledger.LEDGER_PATH)
    with budget_ledger.ledger_lock(path):
        ledger = budget_ledger.load_ledger(path)
        ledger["stop_new_dispatch"] = True
        ledger["stop_reason"] = reason
        budget_ledger.save(path, ledger)


def settle_owned(receipt, execution, root):
    held = budget_ledger.check_reservation(budget_ledger.LEDGER_PATH, receipt["reservation_id"], query_only=True)
    require(held == receipt, "reservation_receipt_ledger_drift")
    measured = {"usage_known": False, "observed_elapsed_seconds": seconds(execution["elapsed_seconds"]),
                "issues": [], "acceptance_outcome": "UNKNOWN", "artifacts": []}
    try:
        measured = standard_measurement(root, receipt, execution)
    except (EvidenceError, OSError, KeyError, TypeError, ValueError) as invalid:
        measured["issues"] = [str(invalid) if isinstance(invalid, EvidenceError) else "attempt_evidence_incomplete"]
    if execution.get("return_code") is None or execution.get("timed_out") or execution.get("termination_unconfirmed"):
        measured["usage_known"] = False
        measured["issues"].append("owned_execution_terminal_unconfirmed")
    junit = {"testcases": 0, "failed": 0, "skipped": 0}
    try:
        for path in (Path(root) / "junit").glob("TEST-*.xml"):
            for case in ET.parse(path).getroot().iter("testcase"):
                junit["testcases"] += 1
                junit["failed"] += case.find("failure") is not None or case.find("error") is not None
                junit["skipped"] += case.find("skipped") is not None
        passed = (execution.get("return_code") == 0 and junit["testcases"] > 0
                  and junit["failed"] == 0 and junit["skipped"] == 0
                  and measured["acceptance_outcome"] in ("PASS", "PASSED"))
        measured["acceptance_outcome"] = "PASS" if passed else "FAIL"
        measured["junit"] = junit
    except (ET.ParseError, OSError):
        measured["acceptance_outcome"] = "UNKNOWN"
        measured["issues"].append("fresh_selected_junit_invalid")
    metrics = {"observed_elapsed_seconds" if key == "max_seconds" else key.replace("max_", "observed_", 1): cap
               for key, cap in budget_ledger.LIMITS[receipt["suite"]].items()}
    actual = {"usage_known": measured["usage_known"], **{key: measured[key] for key in metrics if key in measured}}
    must_stop = (not measured["usage_known"] or bool(measured["issues"])
                 or any(key in actual and actual[key] > cap for key, cap in metrics.items()))
    measured.update(schema_version=1, run_id=budget_ledger.RUN_ID, suite=receipt["suite"], round=receipt["round"],
        reservation_id=receipt["reservation_id"], batch_id=receipt["batch_id"], execution=execution,
        stop_new_dispatch=must_stop, monetary_amount=None, monetary_status="NOT_CALCULATED_FROM_TOKENS")
    result_path = Path(root) / "settlement-evidence.json"
    write_new(result_path, measured)
    if must_stop:
        stop_gate("live_gradle_usage_or_execution_unknown_or_exceeded")
    result = budget_ledger.settle(budget_ledger.LEDGER_PATH, receipt["reservation_id"], actual, result_path)
    write_new(Path(root) / "settlement-result.json", {"status": result["status"], "actual": result["actual"],
              "reservation_id": receipt["reservation_id"], "acceptance_outcome": measured["acceptance_outcome"]})
    return result


def dispatch(suite, number, ready_path):
    ready = decode(Path(ready_path).read_bytes())
    require(ready.get("run_id") == budget_ledger.RUN_ID and ready.get("source_files") == source_manifest()
            and ready.get("direct_source_files") == direct_source_manifest()
            and ready.get("environment_sha256") == digest(ENV_FILE), "ready_input_fingerprint_drift")
    require(ready.get("private_input_sha256") == private_input_manifest(), "ready_private_input_fingerprint_drift")
    require(any(row.get("suite") == suite and row.get("status") == "READY" for row in ready.get("suites", [])), "suite_not_ready_no_paid_dispatch")
    root = (LIVE_ROOT / f"round-{number}" / suite).resolve()
    require(not root.exists(), "fresh_round_evidence_required_no_automatic_retry")
    environment = lane_environment(suite, number, root)
    require(preflight_suite(suite, environment, source_manifest())["status"] == "READY", "live_preflight_no_longer_ready")
    receipt = budget_ledger.reserve(budget_ledger.LEDGER_PATH, suite, number,
                 f"{budget_ledger.RUN_ID}:{suite}:round-{number}", root)
    root.mkdir(parents=True, exist_ok=False)
    write_new(root / "reservation.json", receipt)
    environment.update(LIVE_GRADLE_RESERVATION_ID=receipt["reservation_id"], LIVE_GRADLE_BATCH_ID=receipt["batch_id"],
                       LIVE_GRADLE_RUN_ID=budget_ledger.RUN_ID, LIVE_PUBLIC_BATCH_ID=receipt["batch_id"])
    command = gradle_command(suite)
    started = time.monotonic()
    execution = {"started_at": datetime.now(timezone.utc).isoformat(), "command": command, "return_code": None,
                 "timed_out": False, "pid": None}
    write_new(root / "dispatch-start.json", execution)
    try:
        with (root / "command.log").open("x", encoding="utf-8") as log:
            options = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
            process = subprocess.Popen(command, cwd=REPO / "backend-java", env=environment, stdout=log, stderr=subprocess.STDOUT, shell=False, **options)
            execution["pid"] = process.pid
            write_new(root / "owned-process.json", {"pid": process.pid, "reservation_id": receipt["reservation_id"]})
            try:
                execution["return_code"] = process.wait(timeout=receipt["max_seconds"])
            except subprocess.TimeoutExpired:
                execution["timed_out"] = True
                stop_gate("live_gradle_owned_process_timeout")
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=log, stderr=log, check=False)
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                try:
                    execution["return_code"] = process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    execution["termination_unconfirmed"] = True
    except OSError:
        execution["launch_error"] = "owned_process_start_failed"
    execution.update(elapsed_seconds=time.monotonic() - started, finished_at=datetime.now(timezone.utc).isoformat())
    write_new(root / "dispatch-finish.json", execution)
    # Keep exactly the selected fresh XML, including skip/failure evidence.
    expected_xml = "TEST-" + SUITES[suite]["test_class"] + ".xml" if SUITES[suite].get("test_class") else "TEST-*.xml"
    for xml in selected_junit_root(suite).glob(expected_xml):
        if xml.stat().st_mtime >= stamp(execution["started_at"]).timestamp() - 1:
            destination = root / "junit" / xml.name
            destination.parent.mkdir(exist_ok=True)
            shutil.copy2(xml, destination)
    result = settle_owned(receipt, execution, root)
    return {"suite": suite, "round": number, "status": result["status"], "evidence_root": str(root)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ready-file", type=Path, default=READY_FILE)
    parser.add_argument("--suite", choices=SUITES)
    parser.add_argument("--round", type=int, choices=(1, 2, 3))
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--execute", action="store_true")
    actions.add_argument("--settle-only", action="store_true")
    args = parser.parse_args()
    if not args.execute and not args.settle_only:
        value = prepare_ready()
        write_new(args.ready_file, value)
        print(json.dumps({"status": value["status"], "suites_ready": sum(s["status"] == "READY" for s in value["suites"]),
                          "suites_blocked": sum(s["status"] == "BLOCKED" for s in value["suites"]), "provider_calls_sent": 0}))
        return
    require(args.suite is not None and args.round is not None, "one_selected_suite_and_round_required")
    if args.execute:
        print(json.dumps(dispatch(args.suite, args.round, args.ready_file)))
    else:
        root = LIVE_ROOT / f"round-{args.round}" / args.suite
        receipt = decode((root / "reservation.json").read_bytes())
        require(receipt.get("suite") == args.suite and receipt.get("round") == args.round
                and Path(receipt["evidence_root"]).resolve() == root.resolve(), "settle_original_reservation_required")
        execution = decode((root / "dispatch-finish.json").read_bytes())
        print(json.dumps({"status": settle_owned(receipt, execution, root)["status"]}))


if __name__ == "__main__":
    try:
        main()
    except (EvidenceError, ValueError, OSError) as failure:
        print(str(failure) if isinstance(failure, EvidenceError) else "live_gradle_preflight_or_state_error")
        raise SystemExit(2) from None
