package com.skillforge.institution.integration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Candidate;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.ExtractionInput;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Relation;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** C评估文件协议。固定字段由本域类型持有，开放的人工语义观测保留JSON值。 */
public final class SemanticMemoryExtractionReports {
    public static final String LANE = "REAL_MODEL_SYSTEM_AGENT_CANDIDATE_V2";
    public static final String USAGE_ACCOUNTING = "SYSTEM_AGENT_RUNVIEW_NOT_RUNTIME_ATTEMPT_OR_WIRE_AUDIT";
    private SemanticMemoryExtractionReports() { }

    public record Suite(int schemaVersion, String suiteId, String split, int repeats, String language,
            String evidenceStatus, String evidenceLane, String annotationProtocol, String exposure,
            PlannedBudget budget, Thresholds thresholds, List<Scenario> scenarios) {
        public Suite { scenarios = List.copyOf(scenarios); }
    }
    public record PlannedBudget(String approval, int maxModelCalls, int repeats, Configuration modelConfiguration, String price) { }
    public record Thresholds(int criticalTemporaryPromotionFailures, double otherTemporaryPromotionRateMaximum,
            double stableOmissionRateMaximum, double sourceSupportRateMinimum, double possibleConflictDetectionRateMinimum,
            int criticalStableDiscardFailures, int wrongAutomaticMergeOrRevisionFailures) { }
    public record Scenario(String id, String name, String family, String split, String kind, List<String> dimensions,
            ExtractionInput input, Expected expected, String annotationRationale) {
        public Scenario { dimensions = List.copyOf(dimensions); }
    }
    public record Expected(List<RequiredFact> requiredFacts, List<TemporaryConstraint> temporaryConstraints,
            List<String> forbiddenClaims, List<String> possibleConflictTargets, boolean noCandidates,
            List<String> governanceNotEvaluated, @JsonInclude(JsonInclude.Include.NON_NULL) String allowedOmission) {
        public Expected {
            requiredFacts = List.copyOf(requiredFacts); temporaryConstraints = List.copyOf(temporaryConstraints);
            forbiddenClaims = List.copyOf(forbiddenClaims); possibleConflictTargets = List.copyOf(possibleConflictTargets);
            governanceNotEvaluated = List.copyOf(governanceNotEvaluated);
        }
    }
    public record RequiredFact(String label, String meaning, String subject, String type, String scope, String temporalKind,
            List<String> conditions, Relation relation, boolean sensitive, boolean criticalStable, List<String> sourceIds,
            String temporalFrom, String temporalTo) {
        public RequiredFact { conditions = List.copyOf(conditions); sourceIds = List.copyOf(sourceIds); }
    }
    public record TemporaryConstraint(String label, String meaning, boolean critical) { }

    public record Configuration(String modelConfigId, long modelRevision, String provider, String modelId, String endpoint,
            String modelConfigSha256WithoutCredential, String systemAgentId, long publishedVersion, String configurationRef,
            String promptSource, String promptSha256, String http, String billingWiring, String batchBudget,
            String externalWallet, String price) { }
    /** 仅用于配置指纹，覆盖原非凭据模型字段；没有可被序列化的凭据成员。 */
    public record ModelConfigurationFingerprint(String id, long revision, String name, String kind, boolean enabled,
            String providerId, String serviceId, String profileVersion, String protocol, String authenticationScheme,
            String dialectVersion, String endpoint, String modelId, Integer supportedMaxOutputTokens,
            Integer contextWindowTokens, Integer maxOutputTokens, Double temperature, String reasoning, Integer timeoutSeconds,
            Integer dimensions, String path, BigDecimal inputPricePerMillionCents, BigDecimal outputPricePerMillionCents,
            BigDecimal pricePerQuantityCents, String currency, ModelImagePolicy imagePolicy) {
        public static ModelConfigurationFingerprint from(PlatformModelConfig model) {
            return new ModelConfigurationFingerprint(model.id(), model.revision(), model.name(), model.kind(), model.enabled(),
                    model.providerId(), model.serviceId(), model.profileVersion(), model.protocol(), model.authenticationScheme(),
                    model.dialectVersion(), model.endpoint(), model.modelId(), model.supportedMaxOutputTokens(), model.contextWindowTokens(),
                    model.maxOutputTokens(), model.temperature(), model.reasoning(), model.timeoutSeconds(), model.dimensions(), model.path(),
                    model.inputPricePerMillionCents(), model.outputPricePerMillionCents(), model.pricePerQuantityCents(), model.currency(), model.imagePolicy());
        }
    }
    public record Prompt(String binding, String configurationRef) { }
    public record Budget(int maxModelCalls, int maxOutputTokens, long tokenReservationPerCall, long maxTotalReservedTokens,
            long callTimeoutMillis, long batchTimeoutMillis, String budgetLane) { }
    public record ApprovedLimits(int maxModelCalls, int maxOutputTokens, long perCallReservation, long maxTotalReservedTokens,
            long callTimeoutSeconds, long batchTimeoutSeconds) { }
    public record Usage(Long inputTokens, Long outputTokens, String status) {
        public static Usage observed(Long input, Long output) { return new Usage(input, output, input == null || output == null ? "UNKNOWN" : "REPORTED"); }
    }
    public record Attempt(String state, String key) { }
    public record Assertion(String dimension, String code, String method, String outcome, JsonNode expected, JsonNode observed) {
        public Assertion { expected = expected.deepCopy(); observed = observed.deepCopy(); }
        @Override public JsonNode expected() { return expected.deepCopy(); }
        @Override public JsonNode observed() { return observed.deepCopy(); }
    }
    public record SystemAgentCall(String sampleId, String runId, String status, String configurationRef,
            String invocationRef, String externalInvocationId, String errorCode, Usage usage, long durationMs) { }
    public record SampleIndex(String sampleId, String path, String outcome) { }

    /** 主线程持有的采集进度，序列化字段与外部既有报告器协议一一对应。 */
    public static final class Batch {
        public int schemaVersion = 1;
        public String suiteId;
        public String batchId;
        public String suiteHash;
        public String startedAt;
        public int plannedSamples;
        public List<SampleIndex> samples = new ArrayList<>();
        public Configuration model;
        public Prompt prompt;
        public Budget budget;
        public String lane = LANE;
        public String outcome = "RUNNING";
        public String stopCode;
        public List<Void> runtimeCalls = List.of();
        public List<Void> wireCalls = List.of();
        public List<SystemAgentCall> systemAgentCalls = new ArrayList<>();
        public String usageAccounting = USAGE_ACCOUNTING;
        public String governance = "NOT_RUN_DIRECT_SYSTEM_AGENT_OUTPUT_ONLY";
        public int protocolFailures;
        public String protocolOutcome = "NOT_RUN";
        public Map<String, String> sourceBindings;
        @JsonInclude(JsonInclude.Include.NON_NULL) public Integer callsAttempted;
        @JsonInclude(JsonInclude.Include.NON_NULL) public Long chargedOrReservedTokens;
        @JsonInclude(JsonInclude.Include.NON_NULL) public String finishedAt;
        @JsonInclude(JsonInclude.Include.NON_NULL) public SourceCapture sourceCapture;
    }
    public static final class Sample {
        public int schemaVersion = 1;
        public String sampleId;
        public Scenario scenario;
        public int repeat = 1;
        public String lane = LANE;
        public String outcome = "UNKNOWN";
        public List<Assertion> assertions = new ArrayList<>();
        public List<String> failures = List.of();
        public List<Void> runtimeCalls = List.of();
        public List<Void> wireCalls = List.of();
        public List<Stage> stages = new ArrayList<>();
        public String inputSha256;
        public Attempt attempt;
        @JsonInclude(JsonInclude.Include.NON_NULL) public String failureClass;
        @JsonInclude(JsonInclude.Include.NON_NULL) public String finishedAt;
        @JsonInclude(JsonInclude.Include.NON_NULL) public SourceSample sourceSample;
    }
    public static final class Stage {
        public String kind = "SYSTEM_AGENT_EXTRACTION_V2";
        public String terminal;
        public ExtractionInput input;
        public String runId;
        public String systemAgentId;
        public Long systemAgentVersion;
        public String configurationRef;
        public String invocationRef;
        public String rawOutput;
        public String errorCode;
        public long durationMs;
        public Usage usage;
        @JsonInclude(JsonInclude.Include.NON_NULL) public String rawOutputSha256;
        @JsonInclude(JsonInclude.Include.NON_NULL) public List<Candidate> parsedCandidates;
    }

    public record SuiteHashes(String dev, String heldout) {
        public String forSplit(String split) {
            return switch (split) { case "dev" -> dev; case "heldout" -> heldout; default -> throw new IllegalArgumentException("semantic_c_split_invalid"); };
        }
    }
    /** 原始真实采集的不可变引用；离线成功不能改写这些原终态。 */
    public record SourceCapture(String capturePath, String captureSha256, String originalState, String originalProtocolOutcome,
            int originalProtocolFailures, String batchPath, String batchSha256, String suiteSha256) { }
    public record SourceSample(String samplePath, String sampleSha256, String batchSha256, String inputSha256,
            String originalRunId, String originalRunStatus, String originalErrorCode, String originalInvocationRef,
            String rawOutputSha256, Usage originalUsage, long originalDurationMs, Attempt originalAttempt) { }
    public record ReplaySplit(String split, SourceCapture sourceCapture, String batchSha256, int samples,
            int protocolFailures, String protocolOutcome) { }
    public record ReplayCapture(int schemaVersion, String lane, String sourceCapturePath, String sourceCaptureSha256,
            int plannedSamples, int callsAttempted, long chargedOrReservedTokens, int protocolFailures,
            String protocolOutcome, List<ReplaySplit> splits, String startedAt, String finishedAt, String qualification) { }
    public static final class Capture {
        public int schemaVersion = 1;
        public String state = "STARTED";
        public String startedAt;
        public String modelId;
        public SuiteHashes suiteHashes;
        public int plannedSamples;
        public String inputRoot;
        public int modelCallsAttempted;
        public long chargedOrReservedTokens;
        public int protocolFailures;
        public String protocolOutcome = "NOT_RUN";
        public List<SystemAgentCall> systemAgentCalls = new ArrayList<>();
        public ApprovedLimits approvedLimits;
        @JsonInclude(JsonInclude.Include.NON_NULL) public DatabaseReceipts databasePreflight;
        @JsonInclude(JsonInclude.Include.NON_NULL) public String tenantId;
        @JsonInclude(JsonInclude.Include.NON_NULL) public Configuration configuration;
        @JsonInclude(JsonInclude.Include.NON_NULL) public String lastSplit;
        @JsonInclude(JsonInclude.Include.NON_NULL) public String lastBatchOutcome;
        @JsonInclude(JsonInclude.Include.NON_NULL) public DatabaseReceipts databaseReceipts;
        @JsonInclude(JsonInclude.Include.NON_NULL) public String failureClass;
        @JsonInclude(JsonInclude.Include.NON_NULL) public String finishedAt;
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DatabaseReceipts(List<SystemAgentRun> systemAgentRuns, List<ExternalInvocation> externalInvocations,
            List<UsageEvent> usageEvents, List<BudgetAttempt> budgetAttempts, String state, String failureClass) {
        public DatabaseReceipts {
            if (systemAgentRuns != null) systemAgentRuns = List.copyOf(systemAgentRuns);
            if (externalInvocations != null) externalInvocations = List.copyOf(externalInvocations);
            if (usageEvents != null) usageEvents = List.copyOf(usageEvents);
            if (budgetAttempts != null) budgetAttempts = List.copyOf(budgetAttempts);
        }
        public static DatabaseReceipts unknown(Exception failure) { return new DatabaseReceipts(null, null, null, null, "UNKNOWN", failure.getClass().getName()); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SystemAgentRun(long id, String status, Long inputTokens, Long outputTokens, String errorCode) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExternalInvocation(String invocationId, String attemptId, String status, String operationType, String errorCode) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UsageEvent(String eventId, String attemptId, String usageEventKind, String usageSource, Long promptTokens, Long completionTokens) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BudgetAttempt(String attemptId, String category, String state) { }
}
