package com.skillforge.runtime.adapter.jdbc.memory.search;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.adapter.jdbc.memory.MemorySearchEmbedding;
import com.skillforge.runtime.adapter.jdbc.memory.MemoryEmbeddingWork;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.MemoryPort.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;

/** 复用所属 JDBC 套件的完整迁移/受限角色/来源，不建立第二套数据库设施。 */
public final class SemanticMemoryRetrievalDatabaseEvaluation {
    private static final int K = 5;
    private static final List<Double> UNIT_VECTOR = List.of(1.0, 0.0, 0.0);
    private static final MemoryRetrievalPolicy HYBRID = new MemoryRetrievalPolicy(
            "memory-controlled-geometry", "1", MemorySearchDocument.VERSION, MemorySearchAnalyzer.VERSION,
            64, 60, 0.7, Optional.of(new MemoryRetrievalPolicy.EmbeddingModel("controlled-unit-vector", 1, 3)),
            MemoryRetrievalPolicy.VectorFailurePolicy.FAIL);
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());

    private SemanticMemoryRetrievalDatabaseEvaluation() { }

    /** 由已完成来源提交的单个现有 JDBC @Test 调用；本方法不创建容器、角色或来源事件。 */
    public static void evaluate(DataSource source, PortCallContext personal, PortCallContext group,
            EndUserId memberSubject, PortCallContext foreignPersonal) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(result.next()); assertTrue(result.getBoolean(1), "评估必须使用受限数据库角色");
        }
        Path repository = repositoryRoot();
        String requested = System.getenv("SEMANTIC_B_RETRIEVAL_EVIDENCE_DIR");
        Path output = requested == null || requested.isBlank()
                ? repository.resolve("backend-java/agent-runtime-adapter-jdbc/build/reports/semantic-retrieval-" + UUID.randomUUID())
                : Path.of(requested).toAbsolutePath().normalize();
        Files.createDirectories(output);
        assertFalse(Files.exists(output.resolve("report.json")), "不得覆盖既有评估证据");
        var realVectors = SemanticMemoryLiveVectors.configured(repository, JSON);
        if (realVectors.isPresent()) realVectors.orElseThrow().copyEvidence(output);
        var realQueryVectorLookups = new AtomicInteger();
        JdbcMemoryPort realPort = realVectors.map(vectors -> new JdbcMemoryPort(source,
                (session, command, current) -> true,
                fact -> new MemorySearchDocument(readBody(fact), MemorySearchDocument.VERSION),
                tenant -> vectors.policy(), request -> {
                    realQueryVectorLookups.incrementAndGet();
                    return new MemorySearchEmbedding.Outcome.Available(vectors.vector("QUERY", request.text(), request.model()));
                })).orElse(null);
        var controlledQueryInvocations = new AtomicInteger();
        var port = new JdbcMemoryPort(source, (session, command, current) -> true,
                fact -> new MemorySearchDocument(readBody(fact), MemorySearchDocument.VERSION),
                tenant -> HYBRID, request -> {
                    controlledQueryInvocations.incrementAndGet();
                    return new MemorySearchEmbedding.Outcome.Available(UNIT_VECTOR);
                });
        var reports = new LinkedHashMap<String, Object>();
        for (String split : List.of("dev", "heldout")) {
            Path input = repository.resolve("tools/memory_evaluation/semantic-b/" + split + ".json");
            byte[] bytes = Files.readAllBytes(input); JsonNode suite = JSON.readTree(bytes);
            Path folder = output.resolve(split); Files.createDirectory(folder);
            Files.write(folder.resolve("suite.json"), bytes, StandardOpenOption.CREATE_NEW);
            assertEquals(split, suite.path("split").asText());
            var seeded = seed(port, suite, split, personal, group, memberSubject, foreignPersonal);
            Set<TenantId> tenants = Set.of(personal.runIdentity().owner().sessionRef().tenantId(),
                    foreignPersonal.runIdentity().owner().sessionRef().tenantId());
            var indexing = index(port, tenants, work -> UNIT_VECTOR);
            var realIndexing = new ArrayList<Map<String, Object>>();
            var laneNames = new ArrayList<>(List.of("LEXICAL_DATABASE", "HYBRID_CONTROLLED_CONSTANT_VECTOR"));
            if (realVectors.isPresent()) {
                var vectors = realVectors.orElseThrow();
                for (TenantId tenant : tenants) realPort.enqueueMissing(tenant, vectors.policy(), vectors.policy().candidateLimit());
                realIndexing.addAll(index(realPort, tenants, work -> vectors.vector("DOCUMENT", work.text(), work.model())));
                laneNames.add("REAL_PROVIDER_VECTORS_DATABASE");
            }
            var lanes = new LinkedHashMap<String, Object>();
            for (String lane : laneNames) {
                var rows = new ArrayList<Map<String, Object>>();
                for (JsonNode query : suite.path("queries")) {
                    boolean real = "REAL_PROVIDER_VECTORS_DATABASE".equals(lane);
                    var policy = real ? realVectors.orElseThrow().policy()
                            : "LEXICAL_DATABASE".equals(lane) ? MemoryRetrievalPolicy.lexicalV1() : HYBRID;
                    Map<String, Object> row = query(real ? realPort : port, query, split, lane, policy, seeded, personal, group, memberSubject);
                    write(folder.resolve(lane.toLowerCase(java.util.Locale.ROOT) + "-" + query.path("id").asText() + ".json"), row);
                    rows.add(row);
                    assertEquals("RETRIEVED", row.get("status"), "正式查询失败应保留原结果，不能当作空命中计分");
                    assertEquals(List.of(), row.get("unexpectedHits"), "不能混入本集合之外的事实");
                    assertEquals(List.of(), row.get("forbiddenHits"), "范围或状态禁止的事实不能进入任一召回腿");
                }
                lanes.put(lane, summarize(rows));
            }
            var splitReport = new LinkedHashMap<String, Object>();
            splitReport.put("suiteHash", digest(bytes)); splitReport.put("sourceSuite", input.toString());
            splitReport.put("split", split); splitReport.put("facts", seeded.values().stream().map(Seeded::evidence).toList());
            splitReport.put("indexingReceipts", indexing); splitReport.put("realProviderIndexingReceipts", realIndexing);
            splitReport.put("lanes", lanes);
            write(folder.resolve("report.json"), splitReport); reports.put(split, splitReport);
            // 正式迁移退出前一集合，防止下一集合混入前一集合的活跃事实；不直接 SQL 改 Memory。
            for (var fact : seeded.values()) if (fact.fact().status() == FactStatus.ACTIVE)
                applied(port.submit(new MemoryCommand.Transition(fact.context(), new IdempotencyKey("semantic-archive-" + split + "-" + fact.label()),
                        fact.fact().ref(), fact.fact().revision(), FactStatus.ARCHIVED, "semantic_fixture_split_completed")));
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1); report.put("evidenceLane", "ACTUAL_JDBC_CONTROLLED_INPUTS");
        report.put("splits", reports); report.put("httpAuthentication", "NOT_RUN"); report.put("memoryRuntimePolicies", "NOT_RUN");
        report.put("memoryExtractionSemantics", "NOT_RUN");
        report.put("modelSemantics", realVectors.isEmpty() ? "NOT_RUN" : "REAL_EMBEDDING_VECTORS_ON_THIS_SYNTHETIC_CORPUS_ONLY");
        report.put("controlledEmbedding", Map.of("documentVector", UNIT_VECTOR, "queryVector", UNIT_VECTOR,
                "independentOfRelevanceLabels", true, "queryInvocations", controlledQueryInvocations.get(),
                "qualification", "PGVECTOR_GEOMETRY_AND_RRF_ONLY_NOT_MODEL_QUALITY"));
        report.put("realProviderVectors", realVectors.isEmpty() ? Map.of("status", "NOT_RUN") : realVectors.orElseThrow().evidence());
        report.put("realVectorQueryLookups", realQueryVectorLookups.get());
        report.put("providerCallsInThisJvm", 0); report.put("thresholdsRequiredToPass", false); report.put("engineeringForbiddenHitsRequired", 0);
        write(output.resolve("report.json"), report);
    }

    private static Map<String, Seeded> seed(JdbcMemoryPort port, JsonNode suite, String split,
            PortCallContext personal, PortCallContext group, EndUserId member, PortCallContext foreign) throws Exception {
        var seeded = new LinkedHashMap<String, Seeded>();
        for (JsonNode input : suite.path("facts")) {
            String label = input.path("id").asText();
            assertFalse(label.isBlank()); assertTrue(input.path("body").isTextual());
            PortCallContext context = "other-tenant".equals(input.path("tenantKey").asText()) ? foreign
                    : input.path("scope").asText().startsWith("GROUP") ? group : personal;
            SessionOwner session = context.runIdentity().owner();
            MemoryOwner owner = switch (MemoryScope.valueOf(input.path("scope").asText())) {
                case USER_AGENT -> new MemoryOwner.UserAgent(session.sessionRef().tenantId(), session.requireUser(), context.runIdentity().agentId());
                case GROUP -> new MemoryOwner.Group(((SessionSubject.Group) session.subject()).groupBindingRef());
                case GROUP_MEMBER -> new MemoryOwner.GroupMember(((SessionSubject.Group) session.subject()).groupBindingRef(), member);
                default -> throw new IllegalArgumentException("unsupported semantic fixture scope");
            };
            var object = JSON.createObjectNode().put("text", input.path("body").asText());
            if (input.has("nonBodyMetadata")) object.set("nonBodyMetadata", input.path("nonBodyMetadata"));
            boolean reviewed = input.path("revision").asInt(1) > 1;
            String type = input.path("type").asText();
            var draft = new MemoryFactDraft(owner, new MemoryTypeRef(type), new RetentionClassRef("standard"), "v1",
                    "controlled-subject", "controlled-relation", new TypedPayload("skillforge.memory." + type + ".v1", object.toString()),
                    List.of(new EventId("source-" + session.sessionRef().sessionId())), session.sessionRef(), Optional.empty(), Optional.empty(),
                    "controlled-semantic-b-source-v1", Instant.now().minusSeconds(5), Optional.empty(), Optional.empty(), 0.9, 0.6,
                    reviewed, Optional.empty(), reviewed ? FactStatus.PENDING_CONFIRM : FactStatus.ACTIVE);
            MemoryFact fact = applied(port.submit(new MemoryCommand.Create(context, new IdempotencyKey("semantic-create-" + split + "-" + label), draft)));
            if (reviewed) fact = applied(port.submit(new MemoryCommand.Transition(context, new IdempotencyKey("semantic-review-" + split + "-" + label),
                    fact.ref(), fact.revision(), FactStatus.ACTIVE, "semantic_fixture_reviewed")));
            if ("ARCHIVED".equals(input.path("status").asText())) fact = applied(port.submit(new MemoryCommand.Transition(context,
                    new IdempotencyKey("semantic-inactive-" + split + "-" + label), fact.ref(), fact.revision(), FactStatus.ARCHIVED, "semantic_fixture_inactive")));
            assertNull(seeded.put(label, new Seeded(label, context, fact)), "人工事实标签必须唯一");
        }
        return seeded;
    }

    private static List<Map<String, Object>> index(JdbcMemoryPort port, Set<TenantId> tenants,
            Function<MemoryEmbeddingWork, List<Double>> vectors) {
        var receipts = new ArrayList<Map<String, Object>>();
        for (TenantId tenant : tenants) {
            int maximum = 100;
            while (maximum-- > 0) {
                var claimed = port.claimEmbedding(tenant, "semantic-controlled-worker", Duration.ofSeconds(30));
                if (claimed.isEmpty()) break;
                var work = claimed.orElseThrow();
                receipts.add(Map.of("tenant", tenant.value(), "factId", work.factId(), "contentRevision", work.contentRevision(),
                        "invocationId", work.invocationId(), "accepted", port.completeEmbedding(work, vectors.apply(work)),
                        "model", JSON.valueToTree(work.model()), "textDigest", work.textDigest(),
                        "projectionVersion", work.projectionVersion(), "analyzerVersion", work.analyzerVersion()));
            }
            assertTrue(maximum >= 0, "有界样本索引不能耗尽轮询上限");
        }
        return receipts;
    }

    private static Map<String, Object> query(JdbcMemoryPort port, JsonNode input, String split, String lane, MemoryRetrievalPolicy policy,
            Map<String, Seeded> seeded, PortCallContext personal, PortCallContext group, EndUserId member) {
        boolean inGroup = "own-group".equals(input.path("ownerKey").asText());
        PortCallContext context = inGroup ? group : personal;
        SessionOwner session = context.runIdentity().owner();
        MemoryOwner owner = inGroup ? new MemoryOwner.Group(((SessionSubject.Group) session.subject()).groupBindingRef())
                : new MemoryOwner.UserAgent(session.sessionRef().tenantId(), session.requireUser(), context.runIdentity().agentId());
        Set<MemoryScope> scopes = input.has("scopes") ? strings(input.path("scopes")).stream().map(MemoryScope::valueOf)
                .collect(java.util.stream.Collectors.toSet()) : Set.of(MemoryScope.USER_AGENT);
        var query = new MemoryQuery("semantic-" + split + "-" + lane + "-" + input.path("id").asText(), owner,
                input.has("subjectKey") ? Optional.of(member) : Optional.empty(), input.path("text").asText(), Optional.empty(), scopes,
                Set.of(new MemoryTypeRef("preference"), new MemoryTypeRef("episode")), MemoryQueryProfile.DEFAULT,
                Instant.now(), policy, K, 20_000, 80_000, true);
        long started = System.nanoTime();
        var outcome = port.query(new MemoryQueryCall(context, query));
        long elapsedNanos = System.nanoTime() - started;
        if (!(outcome instanceof MemoryQueryOutcome.Retrieved retrieved)) return Map.of(
                "query", input, "lane", lane, "status", "QUERY_FAILED", "actualOutcome", JSON.valueToTree(outcome),
                "queryPolicy", JSON.valueToTree(policy), "elapsedMilliseconds", elapsedNanos / 1_000_000.0);
        var result = retrieved.result();
        var labels = new HashMap<String, String>(); seeded.values().forEach(value -> labels.put(value.fact().ref().factId(), value.label()));
        var selected = result.candidates().stream().map(fact -> label(labels, fact.ref().factId())).toList();
        var diagnostic = result.diagnostics().candidates().stream().map(candidate -> label(labels, candidate.fact().factId())).toList();
        var unexpected = result.diagnostics().candidates().stream().map(candidate -> candidate.fact().factId())
                .filter(fact -> !labels.containsKey(fact)).toList();
        var forbidden = strings(input.path("expectedForbidden"));
        var hits = diagnostic.stream().filter(forbidden::contains).distinct().toList();
        var grades = new LinkedHashMap<String, Integer>(); input.path("relevanceGrades").fields().forEachRemaining(entry -> {
            assertTrue(seeded.containsKey(entry.getKey()), "相关标签必须存在正式已种事实");
            assertTrue(entry.getValue().isIntegralNumber() && entry.getValue().asInt() >= 0 && entry.getValue().asInt() <= 2);
            grades.put(entry.getKey(), entry.getValue().asInt());
        });
        assertTrue(forbidden.stream().allMatch(seeded::containsKey));
        assertTrue(java.util.Collections.disjoint(grades.keySet(), forbidden));
        var row = new LinkedHashMap<String, Object>();
        row.put("query", input); row.put("lane", lane); row.put("status", "RETRIEVED");
        row.put("selectedLabels", selected); row.put("candidateLabels", diagnostic); row.put("unexpectedHits", unexpected);
        row.put("actualResult", JSON.valueToTree(result)); row.put("queryPolicy", JSON.valueToTree(policy));
        row.put("elapsedMilliseconds", elapsedNanos / 1_000_000.0); row.put("forbiddenHits", hits);
        row.put("metrics", metrics(selected, grades)); return row;
    }

    /** 标准人工分级指标；只消费真实返回顺序，不生成或修改检索分数。零相关查询不进入均值分母。 */
    static Map<String, Object> metrics(List<String> selected, Map<String, Integer> grades) {
        assertEquals(selected.size(), new HashSet<>(selected).size(), "事实身份不能重复计分");
        long relevant = grades.values().stream().filter(value -> value > 0).count();
        var result = new LinkedHashMap<String, Object>(); result.put("relevantCount", relevant);
        result.put("returnedAt5", selected.stream().limit(K).toList());
        if (relevant == 0) {
            result.put("recallAt5", null); result.put("ndcgAt5", null);
            result.put("zeroRelevantNonempty", !selected.isEmpty()); return result;
        }
        long recalled = selected.stream().limit(K).filter(label -> grades.getOrDefault(label, 0) > 0).count();
        double dcg = 0; for (int index = 0; index < Math.min(K, selected.size()); index++)
            dcg += (Math.pow(2, grades.getOrDefault(selected.get(index), 0)) - 1) / (Math.log(index + 2) / Math.log(2));
        var ideal = grades.values().stream().filter(value -> value > 0).sorted(Comparator.reverseOrder()).limit(K).toList();
        double idcg = 0; for (int index = 0; index < ideal.size(); index++)
            idcg += (Math.pow(2, ideal.get(index)) - 1) / (Math.log(index + 2) / Math.log(2));
        result.put("recallAt5", (double) recalled / relevant); result.put("ndcgAt5", dcg / idcg); return result;
    }

    private static Map<String, Object> summarize(List<Map<String, Object>> rows) {
        double recall = 0, ndcg = 0; int relevantQueries = 0, zeroQueries = 0, nonemptyZero = 0;
        for (var row : rows) {
            var metrics = (Map<?, ?>) row.get("metrics");
            if (metrics.get("recallAt5") == null) { zeroQueries++; if (Boolean.TRUE.equals(metrics.get("zeroRelevantNonempty"))) nonemptyZero++; }
            else { relevantQueries++; recall += ((Number) metrics.get("recallAt5")).doubleValue(); ndcg += ((Number) metrics.get("ndcgAt5")).doubleValue(); }
        }
        var result = new LinkedHashMap<String, Object>(); result.put("queryCount", rows.size()); result.put("relevantQueryDenominator", relevantQueries);
        result.put("macroRecallAt5", relevantQueries == 0 ? null : recall / relevantQueries);
        result.put("macroNdcgAt5", relevantQueries == 0 ? null : ndcg / relevantQueries);
        result.put("zeroRelevantQueryCount", zeroQueries); result.put("zeroRelevantNonemptyCount", nonemptyZero);
        result.put("zeroRelevantNonemptyRate", zeroQueries == 0 ? null : (double) nonemptyZero / zeroQueries);
        result.put("queries", rows); return result;
    }

    private record Seeded(String label, PortCallContext context, MemoryFact fact) {
        Map<String, Object> evidence() { return Map.of("label", label, "fact", JSON.valueToTree(fact)); }
    }
    private static String label(Map<String, String> labels, String actual) {
        return labels.getOrDefault(actual, "UNSEEDED:" + actual);
    }
    private static String readBody(MemoryFact fact) {
        try { return JSON.readTree(fact.object().canonicalJson()).path("text").asText(); }
        catch (Exception failure) { throw new IllegalArgumentException("controlled_projection_invalid", failure); }
    }
    private static MemoryFact applied(MemoryMutationResult outcome) { return assertInstanceOf(MemoryMutationResult.Applied.class, outcome).fact(); }
    private static Set<String> strings(JsonNode input) { var result = new HashSet<String>(); input.forEach(value -> result.add(value.asText())); return result; }
    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath(); while (path != null && !Files.exists(path.resolve(".git"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("repository root missing"); return path;
    }
}
