package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A 的历史离线观测入口：旧记忆输入已退役，仅交付解析可在当前版本继续观测。
 * JUnit 成功仅表示冻结采集完成；不代表真实模型效果、生产发布或后续目标达标。
 */
class SemanticDecisionBaseline {
    private static final String LANE = "CONTROLLED_PRODUCTION_RULE_BASELINE";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void recordsCurrentProductionRulesWithoutChangingTheirSemanticOutcomes() throws Exception {
        String requested = System.getenv("SEMANTIC_A_BASELINE_DIR");
        assertNotNull(requested, "必须显式选择新的 SEMANTIC_A_BASELINE_DIR");
        Path output = Path.of(requested).toAbsolutePath().normalize();
        Path root = repositoryRoot();
        Path suites = root.resolve("tools/memory_evaluation/semantic-a");
        Files.createDirectories(output);
        assertFalse(Files.exists(output.resolve("baseline.json")), "不得覆盖历史观测");
        var caseLabels = new HashSet<String>();
        var suiteInputs = new LinkedHashMap<String, JsonNode>();
        for (String split : List.of("dev", "heldout")) {
            JsonNode suite = json.readTree(Files.readAllBytes(suites.resolve(split + ".json")));
            assertEquals(1, suite.path("schemaVersion").asInt());
            assertEquals(split, suite.path("split").asText());
            assertEquals(1, suite.path("repeats").asInt());
            assertFalse(suite.path("scenarios").isEmpty());
            var ids = new HashSet<String>();
            for (var scenario : suite.path("scenarios")) {
                assertEquals(split, scenario.path("split").asText());
                assertTrue(ids.add(scenario.path("id").asText()), "样本 ID 必须唯一");
                assertFalse(scenario.path("family").asText().isBlank(), "案例标签不得为空");
                assertTrue(caseLabels.add(scenario.path("family").asText()), "案例标签不得重复；此检查不证明机制隔离");
                assertTrue(scenario.path("expected").isObject());
                if ("MEMORY_CANDIDATE_FILTER".equals(scenario.path("kind").asText())) {
                    assertTrue(scenario.path("candidates").isArray());
                    assertTrue(scenario.path("expected").path("acceptedCandidates").isIntegralNumber());
                    assertTrue(scenario.path("expected").path("expectedCandidates").isArray());
                    assertEquals(scenario.path("expected").path("acceptedCandidates").asInt(),
                            scenario.path("expected").path("expectedCandidates").size(), "人工数量和全字段预期必须一致");
                } else {
                    assertEquals("OUTPUT_REQUIREMENT_PARSE", scenario.path("kind").asText());
                    assertTrue(scenario.path("expected").path("required").isBoolean());
                    assertTrue(scenario.path("expected").path("formats").isArray());
                    assertTrue(scenario.path("expected").path("singleFileWebsiteRequired").isBoolean());
                }
                validateExpectationCoverage(scenario);
            }
            suiteInputs.put(split, suite);
        }
        var bindings = new LinkedHashMap<String, String>();
        for (String relative : List.of(
                "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/artifact/DeliverableRequirements.java",
                "backend-java/skillforge-core-agent/src/main/java/com/skillforge/core/agent/systemagent/MemoryExtractionCandidates.java",
                "backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/artifact/SemanticDecisionBaseline.java",
                "tools/memory_evaluation/semantic-a/dev.json", "tools/memory_evaluation/semantic-a/heldout.json")) {
            bindings.put(relative, digest(Files.readAllBytes(root.resolve(relative))));
        }
        var splitResults = new LinkedHashMap<String, Object>();
        for (var entry : suiteInputs.entrySet()) {
            splitResults.put(entry.getKey(), collect(entry.getValue(), suites.resolve(entry.getKey() + ".json"),
                    output.resolve(entry.getKey()), bindings));
        }
        write(output.resolve("baseline.json"), Map.of(
                "schemaVersion", 1, "evidenceLane", LANE, "gitRevision", gitRevision(root),
                "productionSourceSha256", bindings, "splits", splitResults,
                "modelSemantics", "NOT_RUN", "retrievalSemantics", "NOT_RUN",
                "candidateInput", "RETIRED_V1_HUMAN_CONTROLLED_JSON_NOT_ADAPTED_TO_V2",
                "productionRulesChanged", true,
                "qualification", "MEMORY_V1_BASELINE_RETIRED_ONLY_DELIVERABLE_BRANCH_EXECUTABLE_NOT_FULL_A_RERUN"));
    }

    private Map<String, Object> collect(JsonNode suite, Path source, Path destination,
            Map<String, String> bindings) throws Exception {
        Files.createDirectory(destination);
        byte[] suiteBytes = Files.readAllBytes(source);
        Files.write(destination.resolve("suite.json"), suiteBytes, StandardOpenOption.CREATE_NEW);
        String started = Instant.now().toString();
        var index = new ArrayList<Map<String, Object>>();
        var counts = new LinkedHashMap<String, Integer>();
        for (String outcome : List.of("PASS", "FAIL", "UNKNOWN", "NOT_RUN")) counts.put(outcome, 0);
        int unsupported = 0;
        for (JsonNode scenario : suite.path("scenarios")) {
            String id = scenario.path("id").asText() + "-1";
            var assertions = new ArrayList<Map<String, Object>>();
            var observation = observe(scenario, assertions);
            String outcome = assertions.stream().anyMatch(value -> "FAIL".equals(value.get("outcome"))) ? "FAIL"
                    : assertions.stream().anyMatch(value -> "UNKNOWN".equals(value.get("outcome"))) ? "UNKNOWN" : "PASS";
            if (!scenario.path("expected").path("unsupportedFeatures").isEmpty()) unsupported++;
            counts.compute(outcome, (_key, value) -> value + 1);
            String relative = "sample-" + id + ".json";
            var sample = new LinkedHashMap<String, Object>();
            sample.put("schemaVersion", 1); sample.put("sampleId", id); sample.put("scenario", scenario);
            sample.put("repeat", 1); sample.put("split", suite.path("split").asText()); sample.put("lane", LANE);
            sample.put("outcome", outcome); sample.put("assertions", assertions); sample.put("failures", List.of());
            sample.put("runtimeCalls", List.of()); sample.put("wireCalls", List.of());
            sample.put("stages", List.of(Map.of("kind", scenario.path("kind").asText(), "terminal", "OBSERVED",
                    "input", scenario, "actualProductionOutput", observation)));
            write(destination.resolve(relative), sample);
            index.add(Map.of("sampleId", id, "path", relative, "outcome", outcome));
        }
        var batch = new LinkedHashMap<String, Object>();
        batch.put("schemaVersion", 1); batch.put("suiteId", suite.path("suiteId").asText());
        batch.put("batchId", "semantic-a-" + suite.path("split").asText() + "-" + UUID.randomUUID());
        batch.put("startedAt", started); batch.put("finishedAt", Instant.now().toString());
        batch.put("suiteHash", digest(suiteBytes)); batch.put("plannedSamples", index.size());
        batch.put("samples", index); batch.put("outcome", "COMPLETED"); batch.put("stopCode", null);
        batch.put("model", Map.of("status", "NOT_RUN", "reason", "CONTROLLED_RULE_INPUT_NO_PROVIDER"));
        batch.put("prompt", Map.of("status", "NOT_RUN")); batch.put("budget", suite.path("budget"));
        batch.put("runtimeCalls", List.of()); batch.put("wireCalls", List.of());
        batch.put("lane", LANE); batch.put("productionSourceSha256", bindings);
        batch.put("split", suite.path("split").asText()); batch.put("semanticTargetsRequiredToPass", false);
        write(destination.resolve("batch.json"), batch);
        return Map.of("plannedSamples", index.size(), "observedOutcomes", counts, "unsupportedSamples", unsupported,
                "executionComplete", true, "semanticAcceptance", "NOT_CLAIMED");
    }

    private Object observe(JsonNode scenario, List<Map<String, Object>> assertions) throws Exception {
        String kind = scenario.path("kind").asText();
        JsonNode expected = scenario.path("expected");
        String dimension = "MEMORY_CANDIDATE_FILTER".equals(kind) ? "EXTRACTION" : "GOVERNANCE";
        Object actual;
        try {
            switch (kind) {
                case "MEMORY_CANDIDATE_FILTER" -> {
                    // 不为旧候选补造来源/条件等v2字段，旧A的原始采集只由既有冻结报告证明。
                    actual = Map.of("status", "RETIRED_INPUT_SCHEMA", "execution", "NOT_RUN", "originalCandidates", scenario.path("candidates"));
                    assertions.add(Map.of("dimension", dimension, "code", "A_MEMORY_V1_INPUT_SCHEMA_RETIRED",
                            "method", "DETERMINISTIC", "outcome", "UNKNOWN", "expected", expected, "observed", actual));
                }
                case "OUTPUT_REQUIREMENT_PARSE" -> {
                    actual = Map.of("status", "RETIRED_INPUT_SCHEMA", "execution", "NOT_RUN", "originalText", scenario.path("text"));
                    assertions.add(Map.of("dimension", dimension, "code", "A_OUTPUT_REGEX_RETIRED",
                            "method", "DETERMINISTIC", "outcome", "UNKNOWN", "expected", expected, "observed", actual));
                }
                default -> throw new IllegalStateException("unknown semantic baseline kind");
            }
        } catch (IllegalArgumentException rejected) {
            actual = Map.of("status", "PRODUCTION_REJECTED", "reasonCode", String.valueOf(rejected.getMessage()));
            check(assertions, dimension, "PRODUCTION_REJECTED_CONTROLLED_INPUT", false, expected, actual);
        }
        if (!Set.of("MEMORY_CANDIDATE_FILTER","OUTPUT_REQUIREMENT_PARSE").contains(kind) && !expected.path("unsupportedFeatures").isEmpty()) {
            check(assertions, dimension, "SEMANTIC_CONTRACT_UNSUPPORTED", false,
                    expected.path("unsupportedFeatures"), Map.of("status", "UNSUPPORTED", "actual", actual,
                            "boundary", "CURRENT_PARSE_OR_CANDIDATE_FILTER_ONLY_FULL_SEMANTIC_CHAIN_NOT_RUN"));
        }
        expected.path("notEvaluated").fields().forEachRemaining(entry -> assertions.add(Map.of(
                "dimension", dimension, "code", "EXPECTATION_NOT_RUN_" + entry.getKey(), "method", "DETERMINISTIC",
                "outcome", "UNKNOWN", "expected", entry.getValue(),
                "observed", Map.of("status", "NOT_RUN", "field", entry.getKey()))));
        return actual;
    }

    /** 未比较的预期必须显式声明缺失能力或未执行，不能靠已有局部断言升级为完整语义通过。 */
    private static void validateExpectationCoverage(JsonNode scenario) {
        JsonNode expected = scenario.path("expected");
        var compared = new HashSet<>("MEMORY_CANDIDATE_FILTER".equals(scenario.path("kind").asText())
                ? Set.of("acceptedCandidates", "expectedCandidates")
                : Set.of("required", "formats", "singleFileWebsiteRequired", "filenames"));
        if ("ALL_OF".equals(expected.path("combination").asText())) compared.add("combination");
        compared.add("unsupportedFeatures"); compared.add("notEvaluated");
        expected.fieldNames().forEachRemaining(field -> assertTrue(compared.contains(field)
                || !expected.path("unsupportedFeatures").isEmpty() || expected.path("notEvaluated").has(field),
                () -> "预期字段未比较且未声明UNSUPPORTED/NOT_RUN: " + scenario.path("id").asText() + ":" + field));
    }

    /** 人工预期和实际生产输出做精确比较，不复制自然语言解释或相关性算法。 */
    private static void check(List<Map<String, Object>> assertions, String dimension, String code,
            boolean matches, Object expected, Object actual) {
        assertions.add(Map.of("dimension", dimension, "code", code, "method", "DETERMINISTIC",
                "outcome", matches ? "PASS" : "FAIL", "expected", expected, "observed", actual));
    }
    private static Set<String> strings(JsonNode values) {
        var result = new HashSet<String>(); values.forEach(value -> result.add(value.asText())); return Set.copyOf(result);
    }
    private void write(Path path, Object value) throws Exception {
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String gitRevision(Path root) throws Exception {
        var process = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD").redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "git revision读取必须有界");
            assertEquals(0, process.exitValue(), "git revision读取失败");
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "git revision子进程必须退出");
            }
        }
    }
    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(".git"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("repository root missing"); return root;
    }
}
