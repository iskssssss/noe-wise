package com.skillforge.institution.integration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.institution.integration.SemanticMemoryExtractionReports.*;
import com.skillforge.runtime.api.ResultRef;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** C的薄评估入口；固定报告采用本域类型，模型任务与候选校验复用正式生产能力。 */
public final class SemanticMemoryExtractionEvaluation {
    private SemanticMemoryExtractionEvaluation() { }

    public static void evaluate(SystemAgentExecution execution, ObjectMapper configuredJson, Path suiteFile, Path outputDir,
            String isolatedTenant, Limits limits, Configuration configuration) throws Exception {
        ObjectMapper json = reportJson(configuredJson);
        var parser = new MemoryExtractionCandidates(json);
        byte[] suiteBytes = Files.readAllBytes(suiteFile); Suite suite = json.readValue(suiteBytes, Suite.class);
        validateSuite(suite, parser);
        if (isolatedTenant == null || isolatedTenant.isBlank() || configuration == null)
            throw new IllegalArgumentException("semantic_c_configuration_missing");
        Files.createDirectories(outputDir);
        try (var paths = Files.list(outputDir)) {
            if (paths.findAny().isPresent()) throw new IllegalArgumentException("semantic_c_evidence_directory_not_empty");
        }
        Files.write(outputDir.resolve("suite.json"), suiteBytes, StandardOpenOption.CREATE_NEW);
        var batch = new Batch();
        batch.suiteId = suite.suiteId(); batch.batchId = "semantic-c-" + suite.split() + "-" + UUID.randomUUID();
        batch.suiteHash = digest(suiteBytes); batch.startedAt = Instant.now().toString();
        batch.plannedSamples = suite.scenarios().size(); batch.model = configuration;
        batch.prompt = new Prompt("FROZEN_SYSTEM_AGENT_CONFIGURATION", limits.configurationRef());
        batch.budget = limits.evidence(); batch.sourceBindings = sourceBindings();
        for (var scenario : suite.scenarios()) batch.samples.add(new SampleIndex(scenario.id() + "-1", "sample-" + scenario.id() + "-1.json", "NOT_RUN"));
        persist(json, outputDir.resolve("batch.json"), batch);
        long started = System.nanoTime(), charged = 0; int calls = 0; String stop = null;
        for (int position = 0; position < suite.scenarios().size(); position++) {
            Scenario scenario = suite.scenarios().get(position); SampleIndex item = batch.samples.get(position);
            Duration remaining = limits.batchTimeout().minusNanos(System.nanoTime() - started);
            if (calls >= limits.maxModelCalls() || limits.tokenReservationPerCall() > limits.maxTotalReservedTokens() - charged
                    || remaining.isNegative() || remaining.isZero()) { stop = "SEMANTIC_C_BATCH_BUDGET_EXHAUSTED"; break; }
            Path samplePath = outputDir.resolve(item.path());
            var input = parser.typedInput(scenario.input().userId(), scenario.input().sources(), scenario.input().existingFacts());
            String encodedInput = parser.encodeInput(input);
            var sample = new Sample(); sample.sampleId = item.sampleId(); sample.scenario = scenario;
            sample.inputSha256 = digest(encodedInput.getBytes(StandardCharsets.UTF_8));
            sample.attempt = new Attempt("STARTED_NO_AUTOMATIC_REPLAY", batch.batchId + ":" + item.sampleId());
            Files.write(samplePath, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(sample), StandardOpenOption.CREATE_NEW);
            charged = Math.addExact(charged, limits.tokenReservationPerCall()); calls++;
            Duration timeout = remaining.compareTo(limits.callTimeout()) < 0 ? remaining : limits.callTimeout();
            try {
                var result = execution.executePurposeControlled("memory-extraction", isolatedTenant, sample.attempt.key(), encodedInput,
                        new SystemAgentExecution.ExecutionLimits(limits.maxOutputTokens(), timeout,
                                () -> System.nanoTime() - started >= limits.batchTimeout().toNanos(), new ResultRef(batch.batchId)));
                var run = result.run(); var stage = new Stage();
                stage.terminal = run.status(); stage.input = input; stage.runId = run.id(); stage.systemAgentId = run.agentId();
                stage.systemAgentVersion = run.version(); stage.configurationRef = result.configurationRef().value();
                stage.invocationRef = result.invocationRef().value(); stage.rawOutput = run.output(); stage.errorCode = run.errorCode();
                stage.durationMs = run.durationMs(); stage.usage = Usage.observed(run.inputTokens(), run.outputTokens()); sample.stages.add(stage);
                if (run.output() != null) stage.rawOutputSha256 = digest(run.output().getBytes(StandardCharsets.UTF_8));
                batch.systemAgentCalls.add(new SystemAgentCall(sample.sampleId, run.id(), run.status(), stage.configurationRef,
                        stage.invocationRef, batch.batchId + ":" + run.id(), run.errorCode(), stage.usage, run.durationMs()));
                boolean knownUsage = run.inputTokens() != null && run.outputTokens() != null
                        && run.inputTokens() >= 0 && run.outputTokens() >= 0;
                boolean knownFailed = false;
                if ("FAILED".equals(run.status()) && run.output() != null && knownUsage) {
                    try { parser.parse(run.output(), input); }
                    catch (IllegalArgumentException protocolFailure) {
                        knownFailed = protocolFailure.getMessage().equals(run.errorCode());
                    }
                }
                deterministic(json, sample.assertions, "C_FROZEN_CONFIGURATION", limits.configurationRef().equals(stage.configurationRef),
                        limits.configurationRef(), stage.configurationRef);
                sample.assertions.add(assertion(json, "C_SYSTEM_AGENT_COMPLETED", "DETERMINISTIC",
                        "COMPLETED".equals(run.status()) ? "PASS" : knownFailed ? "FAIL" : "UNKNOWN", "COMPLETED", run.status()));
                if (run.inputTokens() == null || run.outputTokens() == null) stop = "SEMANTIC_C_USAGE_UNKNOWN";
                else {
                    long actual = Math.addExact(run.inputTokens(), run.outputTokens());
                    if (run.inputTokens() < 0 || run.outputTokens() < 0 || actual > limits.tokenReservationPerCall()) stop = "SEMANTIC_C_USAGE_EXCEEDS_RESERVATION";
                    else charged = Math.addExact(charged - limits.tokenReservationPerCall(), actual);
                }
                if (!limits.configurationRef().equals(stage.configurationRef)) stop = "SEMANTIC_C_CONFIGURATION_DRIFT";
                if (run.durationMs() >= timeout.toMillis()) stop = "SEMANTIC_C_CALL_TIMEOUT";
                if (!"COMPLETED".equals(run.status())) {
                    if (knownFailed) {
                        // 已收到供应商输出及用量，协议错误属于确定FAIL；不重试，也不伪造空候选。
                        batch.protocolFailures++;
                    } else if (stop == null) stop = "SEMANTIC_C_SYSTEM_AGENT_NOT_COMPLETED";
                } else {
                    stage.parsedCandidates = parser.parse(run.output(), input);
                    semanticAssertions(json, sample.assertions, scenario.expected(), stage.parsedCandidates);
                }
                sample.outcome = sample.assertions.stream().anyMatch(a -> "FAIL".equals(a.outcome())) ? "FAIL" : "UNKNOWN";
            } catch (Exception failure) {
                // 派发或解析失败不冒充零候选；保存未知并停止后继，不调用第二次。
                stop = "SEMANTIC_C_EXECUTION_OR_PARSE_UNKNOWN"; sample.failureClass = failure.getClass().getName(); sample.outcome = "UNKNOWN";
                sample.assertions.add(assertion(json, "C_EXECUTION_OR_PARSE_UNKNOWN", "DETERMINISTIC", "UNKNOWN",
                        "已确定且可解析的系统任务回执", failure.getClass().getName()));
            }
            sample.finishedAt = Instant.now().toString(); persist(json, samplePath, sample);
            batch.samples.set(position, new SampleIndex(item.sampleId(), item.path(), sample.outcome));
            batch.callsAttempted = calls; batch.chargedOrReservedTokens = charged; batch.stopCode = stop;
            batch.protocolOutcome = batch.protocolFailures > 0 ? "FAIL" : stop == null ? "PASS" : "UNKNOWN";
            persist(json, outputDir.resolve("batch.json"), batch);
            if (stop != null) break;
        }
        batch.callsAttempted = calls; batch.chargedOrReservedTokens = charged; batch.stopCode = stop;
        batch.finishedAt = Instant.now().toString(); batch.outcome = stop == null ? "COMPLETED" : "STOPPED";
        batch.protocolOutcome = batch.protocolFailures > 0 ? "FAIL" : stop == null ? "PASS" : "UNKNOWN";
        persist(json, outputDir.resolve("batch.json"), batch);
        if (stop != null) throw new IllegalStateException(stop);
    }

    static void validateSuite(Suite suite, MemoryExtractionCandidates parser) {
        if (suite.schemaVersion() != 1 || suite.repeats() != 1 || !List.of("dev", "heldout").contains(suite.split())
                || suite.scenarios() == null || suite.scenarios().size() != 16) throw new IllegalArgumentException("semantic_c_suite_invalid");
        var ids = new HashSet<String>();
        for (var scenario : suite.scenarios()) {
            if (scenario.id() == null || scenario.id().isBlank() || !ids.add(scenario.id()) || !suite.split().equals(scenario.split())
                    || scenario.expected() == null || scenario.input().schemaVersion() != 2) throw new IllegalArgumentException("semantic_c_scenario_invalid");
            parser.typedInput(scenario.input().userId(), scenario.input().sources(), scenario.input().existingFacts());
        }
    }

    static void semanticAssertions(ObjectMapper json, List<Assertion> assertions, Expected expected,
            List<MemoryExtractionCandidates.Candidate> candidates) {
        for (var fact : expected.requiredFacts()) {
            review(json, assertions, "C_FACT:" + fact.label(), fact, candidates);
            if ("stable".equals(fact.temporalKind())) review(json, assertions, "C_STABLE:" + fact.label(), fact, candidates);
            if (fact.criticalStable()) review(json, assertions, "C_CRITICAL_STABLE:" + fact.label(), fact, candidates);
        }
        for (var temporary : expected.temporaryConstraints()) review(json, assertions,
                (temporary.critical() ? "C_TEMPORARY_CRITICAL:" : "C_TEMPORARY_OTHER:") + temporary.label(), temporary, candidates);
        for (var target : expected.possibleConflictTargets()) review(json, assertions, "C_CONFLICT:" + target, target, candidates);
        for (int i = 0; i < candidates.size(); i++) review(json, assertions, "C_SOURCE_SUPPORT:" + i,
                "每一原子声明均由对应sourceId/quote在原上下文支持，引用字面存在不足以证明蕴含", candidates.get(i));
        review(json, assertions, "C_FORBIDDEN_AND_COMPLETE_SEMANTICS", expected, candidates);
    }
    private static void review(ObjectMapper json, List<Assertion> assertions, String code, Object expected, Object observed) {
        assertions.add(assertion(json, code, "SEMANTIC_REVIEW_REQUIRED", "UNKNOWN", expected, observed));
    }
    private static void deterministic(ObjectMapper json, List<Assertion> assertions, String code, boolean matches, Object expected, Object observed) {
        assertions.add(assertion(json, code, "DETERMINISTIC", matches ? "PASS" : "FAIL", expected, observed));
    }
    private static Assertion assertion(ObjectMapper json, String code, String method, String outcome, Object expected, Object observed) {
        return new Assertion("EXTRACTION", code, method, outcome, json.valueToTree(expected), json.valueToTree(observed));
    }
    static Map<String, String> sourceBindings() throws Exception {
        Path root = Path.of("").toAbsolutePath(); while (root != null && !Files.exists(root.resolve(".git"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("semantic_c_repository_missing");
        var bindings = new LinkedHashMap<String, String>();
        for (String file : List.of("backend-java/skillforge-core-agent/src/main/java/com/skillforge/core/agent/systemagent/MemoryExtractionCandidates.java",
                "backend-java/skillforge-core-agent/src/main/java/com/skillforge/core/agent/memory/MemoryContent.java",
                "backend-java/skillforge-core-agent/src/main/java/com/skillforge/core/agent/systemagent/SystemAgentExecution.java",
                "backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/SemanticMemoryExtractionEvaluation.java",
                "backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/SemanticMemoryExtractionReports.java"))
            bindings.put(file, digest(Files.readAllBytes(root.resolve(file))));
        return bindings;
    }
    /** 报告协议固定camelCase/ISO时间，不继承宿主HTTP的snake_case设置。 */
    static ObjectMapper reportJson(ObjectMapper configured) {
        return configured.copy().setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS)
                // 树观测不保留Java int/long宽度，避免相同JSON整数在typed往返后被误判为不同。
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void persist(ObjectMapper json, Path path, Object value) throws Exception {
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8);
    }

    public record Limits(int maxModelCalls, int maxOutputTokens, long tokenReservationPerCall, long maxTotalReservedTokens,
            Duration callTimeout, Duration batchTimeout, String configurationRef) {
        public Limits {
            if (maxModelCalls < 1 || maxModelCalls > 32 || maxOutputTokens < 1 || tokenReservationPerCall < maxOutputTokens
                    || maxTotalReservedTokens < tokenReservationPerCall || callTimeout == null || callTimeout.isNegative() || callTimeout.isZero()
                    || batchTimeout == null || batchTimeout.isNegative() || batchTimeout.isZero() || configurationRef == null || configurationRef.isBlank())
                throw new IllegalArgumentException("semantic_c_limits_invalid");
        }
        Budget evidence() { return new Budget(maxModelCalls, maxOutputTokens, tokenReservationPerCall, maxTotalReservedTokens,
                callTimeout.toMillis(), batchTimeout.toMillis(), "CONTROLLED_RUNNER_COUNTERS_NOT_PRODUCTION_DATABASE_BUDGET"); }
    }
}
