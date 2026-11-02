"""C指标只汇总已绑定人工复核；固定输出由本域dataclass持有，不做自然语言评分。"""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass, field
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).absolute().parent.parent))
import reporting  # noqa: E402


@dataclass
class MetricResult:
    denominator: int
    passed: int
    failed: int
    unknownOrNotRun: int
    failureRate: float | None
    maximumFailureRate: float
    outcome: str


@dataclass
class SuccessMetric(MetricResult):
    successRate: float | None


@dataclass
class SourceSupportMetric(SuccessMetric):
    allOutputsParsed: bool


@dataclass
class CompleteSemanticCases:
    PASS: int
    FAIL: int
    UNKNOWN: int


@dataclass
class GovernanceNotRun:
    outcome: str = "NOT_RUN"
    reason: str = "OWNED_BY_SEPARATE_REAL_GOVERNANCE_ENGINEERING_EVIDENCE"


@dataclass
class CMetrics:
    criticalTemporaryPromotion: MetricResult
    otherTemporaryPromotion: MetricResult
    stableOmission: MetricResult
    criticalStableDiscard: MetricResult
    possibleConflictDetection: SuccessMetric
    sourceSupport: SourceSupportMetric
    completeSemanticCases: CompleteSemanticCases
    wrongAutomaticMergeOrRevision: GovernanceNotRun


@dataclass
class SystemAgentUsage:
    calls: int
    status: str
    inputTokens: int | None
    outputTokens: int | None
    accounting: str = "SYSTEM_AGENT_RUNVIEW_NOT_RUNTIME_ATTEMPT_OR_WIRE_AUDIT"
    cost: str = "UNKNOWN"


@dataclass
class Binding:
    batchSha256: str
    suiteHash: str
    model: dict[str, Any] | None
    prompt: dict[str, Any] | None


@dataclass
class ReviewSummary:
    required: int
    completed: int
    pending: int


@dataclass
class CReport:
    schemaVersion: int
    suiteId: str
    binding: Binding
    metrics: CMetrics
    systemAgentUsage: SystemAgentUsage
    review: ReviewSummary
    fullCAcceptance: str = "NOT_CLAIMED_GOVERNANCE_REQUIRES_SEPARATE_EVIDENCE"


@dataclass
class Thresholds:
    criticalTemporaryPromotionFailures: int
    otherTemporaryPromotionRateMaximum: float
    stableOmissionRateMaximum: float
    sourceSupportRateMinimum: float
    possibleConflictDetectionRateMinimum: float
    criticalStableDiscardFailures: int
    wrongAutomaticMergeOrRevisionFailures: int


@dataclass
class Populations:
    criticalTemporaryPromotion: list[str] = field(default_factory=list)
    otherTemporaryPromotion: list[str] = field(default_factory=list)
    stableOmission: list[str] = field(default_factory=list)
    criticalStableDiscard: list[str] = field(default_factory=list)
    possibleConflictDetection: list[str] = field(default_factory=list)
    completeSemanticCases: list[str] = field(default_factory=list)


@dataclass
class ObservedUsage:
    inputTokens: int | None = None
    outputTokens: int | None = None
    status: str = "UNKNOWN"

    def complete(self) -> bool:
        return all(isinstance(value, int) and not isinstance(value, bool) and value >= 0
                   for value in (self.inputTokens, self.outputTokens))


def measure(values: list[str], maximum_failure_rate: float) -> MetricResult:
    denominator = len(values)
    failures = values.count("FAIL")
    unknown = sum(value not in ("PASS", "FAIL") for value in values)
    rate = failures / denominator if denominator and not unknown else None
    status = "NOT_APPLICABLE" if not denominator else (
        "FAIL" if failures > denominator * maximum_failure_rate else "UNKNOWN" if unknown else "PASS")
    return MetricResult(denominator, values.count("PASS"), failures, unknown, rate, maximum_failure_rate, status)


def metric(values: list[str], maximum_failure_rate: float) -> dict:
    """保留既有离线函数的JSON边界。"""
    return asdict(measure(values, maximum_failure_rate))


def summarize(loaded: dict, reviews: dict) -> dict:
    report = reporting.build_report(loaded, reviews)
    rows = {row["scenarioId"]: row for row in report["samples"]}
    groups = Populations()
    source_support, all_outputs_parsed = [], True
    for scenario in loaded["suite"]["scenarios"]:
        row = rows[scenario["id"]]
        outcomes = {assertion["code"]: assertion["outcome"] for assertion in row["assertions"]}
        expected = scenario["expected"]
        for fact in expected["requiredFacts"]:
            if fact["temporalKind"] == "stable": groups.stableOmission.append(outcomes.get("C_STABLE:" + fact["label"], "UNKNOWN"))
            if fact["criticalStable"]: groups.criticalStableDiscard.append(outcomes.get("C_CRITICAL_STABLE:" + fact["label"], "UNKNOWN"))
        for temporary in expected["temporaryConstraints"]:
            group, prefix = (groups.criticalTemporaryPromotion, "C_TEMPORARY_CRITICAL:") if temporary["critical"] else (groups.otherTemporaryPromotion, "C_TEMPORARY_OTHER:")
            group.append(outcomes.get(prefix + temporary["label"], "UNKNOWN"))
        for target in expected["possibleConflictTargets"]:
            groups.possibleConflictDetection.append(outcomes.get("C_CONFLICT:" + target, "UNKNOWN"))
        groups.completeSemanticCases.append(outcomes.get("C_FORBIDDEN_AND_COMPLETE_SEMANTICS", "UNKNOWN"))
        item = loaded["samples"].get((scenario["id"], 1))
        stages = item["data"].get("stages", []) if item else []
        parsed = [stage["parsedCandidates"] for stage in stages if "parsedCandidates" in stage]
        if len(parsed) != 1: all_outputs_parsed = False
        else:
            for index in range(len(parsed[0])): source_support.append(outcomes.get(f"C_SOURCE_SUPPORT:{index}", "UNKNOWN"))
    thresholds = Thresholds(**loaded["suite"]["thresholds"])
    conflict = measure(groups.possibleConflictDetection, 1 - thresholds.possibleConflictDetectionRateMinimum)
    source = measure(source_support, 1 - thresholds.sourceSupportRateMinimum)
    if not all_outputs_parsed or not source_support:
        if source.outcome != "FAIL": source.outcome = "UNKNOWN"
        source.failureRate = None
    metrics = CMetrics(
        measure(groups.criticalTemporaryPromotion, 0),
        measure(groups.otherTemporaryPromotion, thresholds.otherTemporaryPromotionRateMaximum),
        measure(groups.stableOmission, thresholds.stableOmissionRateMaximum),
        measure(groups.criticalStableDiscard, 0),
        SuccessMetric(**asdict(conflict), successRate=1 - conflict.failureRate if conflict.failureRate is not None else None),
        SourceSupportMetric(**asdict(source), successRate=1 - source.failureRate if source.failureRate is not None else None,
                            allOutputsParsed=all_outputs_parsed),
        CompleteSemanticCases(*(groups.completeSemanticCases.count(key) for key in ("PASS", "FAIL", "UNKNOWN"))),
        GovernanceNotRun())
    calls = loaded["batch"].get("systemAgentCalls", [])
    usages = [ObservedUsage(**call.get("usage", {})) for call in calls]
    complete_usage = bool(calls) and all(usage.complete() for usage in usages)
    usage = SystemAgentUsage(len(calls), "REPORTED" if complete_usage else "UNKNOWN",
                             sum(item.inputTokens for item in usages) if complete_usage else None,
                             sum(item.outputTokens for item in usages) if complete_usage else None)
    return asdict(CReport(1, report["suiteId"], Binding(**report["binding"]), metrics, usage, ReviewSummary(**report["review"])))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch", required=True, type=Path)
    parser.add_argument("--suite", required=True, type=Path)
    parser.add_argument("--review", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        loaded = reporting.load_batch(args.batch, args.suite)
        reviews = reporting.load_review(args.review, loaded)
        result = summarize(loaded, reviews)
        with args.output.open("x", encoding="utf-8") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    except (OSError, ValueError, reporting.ReportError) as failure:
        print(type(failure).__name__, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
