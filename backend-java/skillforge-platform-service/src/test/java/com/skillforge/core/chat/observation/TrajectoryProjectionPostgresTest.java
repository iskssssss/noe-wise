package com.skillforge.core.chat.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.application.BillingTraceQueryService;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.integration.publicread.RuntimeCompletedAnswerReader;
import com.skillforge.core.chat.session.SessionTraceService;
import com.skillforge.core.chat.trajectory.TrajectoryMapper;
import com.skillforge.core.chat.trajectory.TrajectoryProjectionService;
import com.skillforge.core.chat.trajectory.TrajectoryQuery;
import com.skillforge.core.chat.trajectory.TrajectoryRepository;
import com.skillforge.core.chat.trajectory.TrajectorySnapshotCache;
import com.skillforge.core.chat.trajectory.TrajectorySourceAuthorizer;
import com.skillforge.core.chat.trajectory.TrajectoryView;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationAuthorizedSqlRule;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.foundation.access.PlatformObservationTargetMapper;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.knowledge.KnowledgeObservationAccess;
import com.skillforge.core.model.ModelAuthorizedSqlRule;
import com.skillforge.core.model.invocation.ModelCallRecordMapper;
import com.skillforge.core.model.invocation.ModelCallRecordCodec;
import com.skillforge.core.model.invocation.ModelCallRecords;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 完整迁移与真实typed Store生产者验证新Mapper、源水位与严格租户关联。 */
@Testcontainers
class TrajectoryProjectionPostgresTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("trajectory_projection").withUsername("skillforge").withPassword("test-only");
    static TrajectoryProjectionService service;
    static TransactionTemplate transactions;
    static FoundationAccess foundation;
    static TrajectoryMapper trajectoryMapper;
    static DriverManagerDataSource writer;
    static ModelCallRecords recordedModels;
    static ModelCallRecordMapper modelRecordMapper;

    @BeforeAll
    static void initialize() throws Exception {
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        writer = source;
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target) VALUES(910000,'trajectory-test','轨迹测试套餐',0.9900)");
        new SessionObservationFixture(jdbc).populate(201, "trajectory-session", "root-a", "root-b", 9007199254740993L, 5, 3);
        new SessionObservationFixture(jdbc).populate(202, "other-session", "root-a", "root-c", 200, 5, 3);
        jdbc.execute("CREATE ROLE trajectory_projection_reader LOGIN PASSWORD 'test-only-reader' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO trajectory_projection_reader");
        jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO trajectory_projection_reader");
        // V319为正式业务角色显式授予该RLS布尔函数；隔离夹具角色复用同一精确权限。
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO trajectory_projection_reader");
        var reader = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "trajectory_projection_reader", "test-only-reader");
        assertFalse(new JdbcTemplate(reader).queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(reader);
        var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(new TenantRlsInterceptor(List.of(new FoundationAuthorizedSqlRule(), new ChatObservationAuthorizedSqlRule(), new ModelAuthorizedSqlRule())));
        factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/TrajectoryMapper.xml"), new ClassPathResource("mapper/SessionObservationMapper.xml"),
                new ClassPathResource("mapper/models/ModelCallRecordMapper.xml"));
        var template = new SqlSessionTemplate(factory.getObject());
        trajectoryMapper = template.getMapper(TrajectoryMapper.class);
        modelRecordMapper = template.getMapper(ModelCallRecordMapper.class);
        var recordFactory = new SqlSessionFactoryBean(); recordFactory.setDataSource(writer);
        recordFactory.setMapperLocations(new ClassPathResource("mapper/models/ModelCallRecordMapper.xml"));
        recordedModels = new ModelCallRecords(new SqlSessionTemplate(recordFactory.getObject()).getMapper(ModelCallRecordMapper.class));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(reader));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transactions.setReadOnly(true);
        foundation = mock(FoundationAccess.class);
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "99"));
        var reads = new PlatformObservationAccess(foundation, mock(PlatformObservationTargetMapper.class));
        var json = new ObjectMapper().findAndRegisterModules(); var audit = mock(ObservationReadAudit.class);
        var publicMessages = new SessionObservationService(new SessionObservationRepository(template.getMapper(SessionObservationMapper.class), reads),
                mock(SessionTraceService.class), audit, json, new RuntimeCompletedAnswerReader(json), new PublicOutputGuard("./build/trajectory-private-artifacts"), reads);
        var properties = new SkillForgeProperties(); properties.setSecretKey("trajectory-integration-secret-0123456789abcdef0123456789");
        var billing = mock(BillingTraceQueryService.class);
        when(billing.effectiveUsageForSession(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of());
        service = new TrajectoryProjectionService(new TrajectoryRepository(trajectoryMapper, reads),
                new SessionObservationAuthorizer(foundation), publicMessages, new CryptoService(properties), json, audit, reads, billing,
                mock(TrajectorySnapshotCache.class),
                mock(ObjectProvider.class), mock(KnowledgeObservationAccess.class),
                new ModelCallRecords(modelRecordMapper), 8_388_608, 100_000, 128);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void graphReadsExcludeIgnoredBodiesBeforePagingButDetailKeepsExactTerminationAndRunBounds() {
        String root = "graph-event-window-" + java.util.UUID.randomUUID();
        var reference = auxiliaryParent(201, "trajectory-session", root);
        var store = new com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore(writer,
                new com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort(writer, Map.of()),
                (session, actual, actor, prepared) -> actual.identity().runRef().tenantId().equals(reference.tenantId()));
        var claimed = store.claim(new com.skillforge.runtime.session.spi.ExecutionStore.Claim(reference, "graph-reader-fixture", "claim:" + root,
                java.time.Duration.ofMinutes(5))).orElseThrow();
        var identity = claimed.identity();
        var events = new java.util.ArrayList<com.skillforge.runtime.session.spi.ExecutionStore.Event>();
        for (int index = 0; index < 20; index++) events.add(new com.skillforge.runtime.session.spi.ExecutionStore.Event(
                new com.skillforge.runtime.api.EventId("ignored:" + root + ":" + index), "execution.fixture.ignored",
                new TypedPayload("application/json", "{\"text\":\"" + "ignored-graph-body".repeat(400) + "\"}")));
        var fact = new com.skillforge.runtime.session.ExecutionObservation.Fact("guard:" + root,
                com.skillforge.runtime.session.ExecutionObservation.agentId(reference.runId()),
                com.skillforge.runtime.session.ExecutionObservation.Kind.GUARDRAIL, "原始安全观测", com.skillforge.runtime.session.ExecutionObservation.Status.SUCCEEDED,
                reference.runId(), null, null, null, null, root, root, identity.trace().traceId(), identity.trace().spanId(),
                com.skillforge.runtime.session.ExecutionObservation.Stage.AGENT_OUTPUT, null, 1_000_000L);
        events.add(new com.skillforge.runtime.session.spi.ExecutionStore.Event(new com.skillforge.runtime.api.EventId("graph-fact:" + root),
                com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE, com.skillforge.runtime.session.ExecutionObservation.encode(fact, 1_000_000, 10_000, 128)));
        var terminal = new ObjectMapper().createObjectNode().put("status", "TERMINATED").put("reason", "fixture_graph_filter")
                .put("text", "ignored-terminal-body".repeat(6000)).put("inputId", root).put("rootInputId", root)
                .put("traceId", identity.trace().traceId()).put("spanId", identity.trace().spanId()).put("agentId", identity.agentId().value());
        terminal.putArray("evidence");
        events.add(new com.skillforge.runtime.session.spi.ExecutionStore.Event(com.skillforge.runtime.session.AgentExecutionProgram.finishedEventId(reference),
                com.skillforge.runtime.session.AgentExecutionProgram.COMPLETED_EVENT,
                new TypedPayload(com.skillforge.runtime.session.AgentExecutionProgram.COMPLETED_SCHEMA, terminal.toString())));
        store.finish(new com.skillforge.runtime.session.spi.ExecutionStore.Finish(reference, claimed.revision(), claimed.lease().orElseThrow(),
                com.skillforge.runtime.api.RunStatus.TERMINATED, events));
        TenantContext.setTenantId("201");
        var retained = transactions.execute(status -> {
            var runs = trajectoryMapper.runs("201", "trajectory-session", root);
            var full = trajectoryMapper.events("201", runs, null);
            var graph = trajectoryMapper.events("201", runs, com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE);
            assertEquals(23, full.size()); assertEquals(1, graph.size());
            assertEquals(full.stream().filter(event -> com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE.equals(event.eventType()) || "SESSION".equals(event.source())).toList(), graph);
            assertEquals("graph-fact:" + root, graph.getFirst().eventId());
            long fullBytes = full.stream().mapToLong(event -> event.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
            long graphBytes = graph.stream().mapToLong(event -> event.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
            assertTrue(fullBytes > graphBytes * 10);
            var run = runs.getFirst();
            var earlier = new TrajectoryRepository.RunFact(run.runId(), run.sessionId(), run.rootInputId(), run.inputId(), run.traceId(), run.spanId(),
                    run.agentId(), run.status(), run.phase(), run.createdSecond(), run.createdNano(), run.updatedSecond(), run.updatedNano(),
                    graph.getFirst().sequence() - 1, run.memoryUpper());
            assertTrue(trajectoryMapper.events("201", List.of(earlier), com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE).isEmpty());
            assertTrue(trajectoryMapper.events("202", runs, com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE).isEmpty());
            System.out.printf("trajectory-graph same-run: fullRows=%d graphRows=%d fullPayloadBytes=%d graphPayloadBytes=%d%n", full.size(), graph.size(), fullBytes, graphBytes);
            return full;
        });
        assertTrue(retained.stream().anyMatch(event -> com.skillforge.runtime.session.AgentExecutionProgram.COMPLETED_EVENT.equals(event.eventType())));
        TenantContext.clear();
        var scope = new SessionObservationScope(SessionObservationScope.Kind.PLATFORM_ADMIN, null, "99");
        var resource = new TrajectoryQuery.Resource(201, "trajectory-session", root);
        var summary = summary(scope, resource);
        var detail = transactions.execute(status -> service.detail(scope, resource));
        assertEquals(summary, detail.summary());
        assertEquals("fixture_graph_filter", detail.termination().reasonCode());
        var page = transactions.execute(status -> service.nodes(scope, new TrajectoryQuery.Nodes(201, "trajectory-session", root, null, null, 1, null, null)));
        assertEquals(1, page.items().size()); assertTrue(page.nextCursor() != null);
    }

    @Test
    void readsActualAttemptsAndFiveFieldUsageWithoutCrossTenantRootJoins() {
        var scope = new SessionObservationScope(SessionObservationScope.Kind.PLATFORM_ADMIN, null, "99");
        var summary = summary(scope, new TrajectoryQuery.Resource(201, "trajectory-session", "root-a"));
        assertEquals(3, summary.modelCalls());
        assertEquals("9007199254741001", summary.totals().promptTokens());
        assertEquals("15", summary.totals().completionTokens());
        assertEquals("question root-a", summary.input());
        assertEquals("answer root-a", summary.output());
        assertEquals("COMPLETED", summary.status());
        assertEquals(List.of("trace-root-a"), summary.traceIds());
        var other = summary(scope, new TrajectoryQuery.Resource(202, "other-session", "root-a"));
        assertEquals("208", other.totals().promptTokens());
        assertFalse(summary.sessionId().equals(other.sessionId()));
    }

    @Test
    void refusesRuntimeOnlyOrForeignProductSessionAsAnAuthorizedParent() {
        var scope = new SessionObservationScope(SessionObservationScope.Kind.PLATFORM_ADMIN, null, "99");
        assertThrows(ApiException.class, () -> summary(scope, new TrajectoryQuery.Resource(202, "trajectory-session", "root-a")));
        assertThrows(ApiException.class, () -> summary(scope, new TrajectoryQuery.Resource(201, "trajectory-session", "root-missing")));
    }

    @Test
    void institutionScopeReadsItsActualTenantAndRlsFiltersTheForeignTenant() {
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "201", "99"));
        TenantContext.setTenantId("201");
        try {
            var scope = new SessionObservationScope(SessionObservationScope.Kind.TENANT, 201L, "99");
            var own = summary(scope, new TrajectoryQuery.Resource(201, "trajectory-session", "root-a"));
            assertEquals(3, own.modelCalls());
            assertEquals("9007199254741001", own.totals().promptTokens());
            transactions.execute(status -> {
                assertTrue(trajectoryMapper.attempts("202", List.of("run-root-a"), com.skillforge.runtime.session.AgentExecutionCodec.MODEL_REQUEST_SCHEMA, com.skillforge.runtime.session.AgentExecutionCodec.MODEL_MANIFEST_SCHEMA).isEmpty());
                return null;
            });
            assertThrows(ApiException.class, () -> summary(scope, new TrajectoryQuery.Resource(202, "other-session", "root-a")));
        } finally {
            when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "99"));
            TenantContext.clear();
        }
    }

    @Test
    void auxiliaryCallsUseTheRealBudgetParentAndDoNotAcquireCoreMaterialsOrForeignRows() {
        String root = "auxiliary-" + java.util.UUID.randomUUID();
        var run = auxiliaryParent(201, "trajectory-session", root);
        String known = auxiliaryAttempt(run, "aux-call:" + root, false);
        String unknown = auxiliaryAttempt(run, "unknown-call:" + root, true);
        String mismatch = auxiliaryAttempt(run, "mismatch-call:" + root, false, root + "-conflicting");
        var scope = new SessionObservationScope(SessionObservationScope.Kind.PLATFORM_ADMIN, null, "99");
        var summary = summary(scope, new TrajectoryQuery.Resource(201, "trajectory-session", root));
        assertEquals(3, summary.modelCalls()); assertEquals(0, summary.toolCalls());
        assertEquals("7", summary.totals().promptTokens()); assertEquals("3", summary.totals().completionTokens());
        assertEquals(2, summary.coverage().unknownAttempts()); assertEquals("PARTIAL", summary.coverage().status());
        var page = transactions.execute(status -> service.nodes(scope,
                new TrajectoryQuery.Nodes(201, "trajectory-session", root, null, null, 100, null, null)));
        var model = page.items().stream().filter(node -> node.attempts().stream().anyMatch(attempt -> known.equals(attempt.attemptId()))).findFirst().orElseThrow();
        assertEquals("agent:" + run.runId(), model.parentNodeId());
        assertEquals(null, model.actionId()); assertEquals(null, model.stepId());
        assertEquals("NOT_RECORDED", model.input().availability());
        assertFalse(model.attempts().getFirst().diagnosticAvailable());
        assertTrue(page.items().stream().flatMap(node -> node.attempts().stream())
                .anyMatch(attempt -> unknown.equals(attempt.attemptId()) && "OUTCOME_UNKNOWN".equals(attempt.status())));
        assertEquals("PARTIAL", page.coverage());
        assertTrue(page.items().stream().flatMap(node -> node.attempts().stream())
                .anyMatch(attempt -> mismatch.equals(attempt.attemptId()) && attempt.totals().promptTokens() == null));
        TenantContext.setTenantId("202");
        transactions.execute(status -> { assertTrue(trajectoryMapper.auxiliaryAttempts("201", List.of(run.runId())).isEmpty()); return null; });
    }

    @Test
    void readsRecordedAuxiliaryMaterialsThroughTheActualBudgetClosureAndEnforcesRlsAndImmutability() throws Exception {
        String root = "recorded-auxiliary-" + java.util.UUID.randomUUID();
        var run = auxiliaryParent(201, "trajectory-session", root);
        String invocation = "recorded-call:" + root;
        String attempt = auxiliaryAttempt(run, invocation, false);
        var codec = new ModelCallRecordCodec();
        var request = codec.request("provider", new ModelProvider.InvocationRequest("https://fixture.invalid", "not-recorded-credential", "model",
                new ModelProvider.InvocationProfile("service", "profile", ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                        ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY, "dialect"),
                List.of(new ModelProvider.Message(ModelProvider.Role.USER, "完整请求原文", null, List.of())),
                new ModelPort.ResponseContract(new ModelPort.ResponseFormat.JsonSchema(new TypedPayload("application/json", "{\"type\":\"object\"}")),
                        ModelPort.EnforcementLevel.STRICT, "supported"),
                new ModelPort.GenerationOptions(java.util.OptionalInt.of(128), java.util.OptionalDouble.empty()), ModelPort.ReasoningPolicy.DEFAULT,
                List.of(), ModelProvider.ToolChoice.NONE, java.time.Duration.ofSeconds(5), () -> false, () -> { }));
        var outcome = codec.outcome(new ModelProvider.InvocationResult.Completed("完整返回原文", List.of(), "model",
                new ModelPort.ModelUsage(java.util.OptionalLong.of(7), java.util.OptionalLong.of(3)), "stop", "已返回的推理原文"));
        TenantContext.setTenantId("201");
        recordedModels.recordRequest(201, invocation, attempt, request);
        recordedModels.recordOutcome(201, invocation, attempt, outcome);
        recordedModels.recordRequest(201, invocation, attempt, request);
        assertThrows(IllegalStateException.class, () -> recordedModels.recordOutcome(201, invocation, attempt,
                codec.outcome(new ModelProvider.InvocationResult.OutcomeUnknown("different-outcome", new ModelPort.ModelUsage(java.util.OptionalLong.empty(), java.util.OptionalLong.empty())))));
        TenantContext.clear();
        var scope = new SessionObservationScope(SessionObservationScope.Kind.PLATFORM_ADMIN, null, "99");
        var page = transactions.execute(status -> service.nodes(scope,
                new TrajectoryQuery.Nodes(201, "trajectory-session", root, null, null, 100, null, null)));
        var node = page.items().stream().filter(value -> value.attempts().stream().anyMatch(valueAttempt -> attempt.equals(valueAttempt.attemptId())))
                .findFirst().orElseThrow();
        assertEquals("AVAILABLE", node.attempts().getFirst().input().availability());
        assertEquals("AVAILABLE", node.attempts().getFirst().output().availability());
        var input = transactions.execute(status -> service.content(scope,
                new TrajectoryQuery.Content(201, "trajectory-session", root, node.attempts().getFirst().input().ref())));
        var output = transactions.execute(status -> service.content(scope,
                new TrajectoryQuery.Content(201, "trajectory-session", root, node.attempts().getFirst().output().ref())));
        assertEquals("FULL", input.availability()); assertEquals("FULL", output.availability());
        assertEquals(request.canonicalJson(), input.value());
        assertFalse(((String) input.value()).contains("not-recorded-credential"));
        assertEquals(outcome.canonicalJson(), output.value());
        assertEquals("完整返回原文", new ObjectMapper().readTree((String) output.value()).get("content").asText());
        TenantContext.setTenantId("202");
        transactions.execute(status -> { assertEquals(null, modelRecordMapper.find(201, invocation, attempt)); return null; });
    }

    @Test
    void batchesOnlyExactAuxiliaryMaterialStatusPairsWithinTheOriginalTenantReadScope() {
        String suffix = java.util.UUID.randomUUID().toString();
        var run = auxiliaryParent(201, "trajectory-session", "status-batch:" + suffix);
        var foreignRun = auxiliaryParent(202, "other-session", "foreign-status:" + suffix);
        String invocationA = "status-a:" + suffix, invocationB = "status-b:" + suffix, foreignInvocation = "status-foreign:" + suffix;
        String attemptA = auxiliaryAttempt(run, invocationA, false);
        String attemptB = auxiliaryAttempt(run, invocationB, false);
        String foreignAttempt = auxiliaryAttempt(foreignRun, foreignInvocation, false);
        var writerJdbc = new JdbcTemplate(writer);
        for (var pair : List.of(List.of("201", invocationA, attemptA), List.of("201", invocationB, attemptB), List.of("202", foreignInvocation, foreignAttempt)))
            writerJdbc.update("INSERT INTO nw_model_call_records(tenant_id,invocation_id,attempt_id,request_schema,request_json) VALUES(?::bigint,?,?,?,?)",
                    pair.get(0), pair.get(1), pair.get(2), ModelCallRecordCodec.REQUEST_SCHEMA, "{\"private_body\":\"status-must-not-load\"}");
        var first = new com.skillforge.core.model.invocation.ModelCallRecordReader.Key(invocationA, attemptA);
        var second = new com.skillforge.core.model.invocation.ModelCallRecordReader.Key(invocationB, attemptB);
        var crossed = new com.skillforge.core.model.invocation.ModelCallRecordReader.Key(invocationA, attemptB);
        var foreign = new com.skillforge.core.model.invocation.ModelCallRecordReader.Key(foreignInvocation, foreignAttempt);
        var reader = new ModelCallRecords(modelRecordMapper);
        TenantContext.setTenantId("201");
        var expected = Map.of(first, new com.skillforge.core.model.invocation.ModelCallRecordStatus(true, false),
                second, new com.skillforge.core.model.invocation.ModelCallRecordStatus(true, false));
        assertEquals(expected, transactions.execute(status -> reader.statuses(201, List.of(first, second, crossed, foreign))));
        assertTrue(transactions.execute(status -> reader.statuses(201, List.of())).isEmpty());
        assertThrows(SecurityException.class, () -> reader.statuses(202, List.of(foreign)));
        var unsupported = transactions.execute(status -> modelRecordMapper.statuses(201, List.of(first), "unsupported-request-schema", ModelCallRecordCodec.OUTCOME_SCHEMA));
        assertEquals(1, unsupported.size()); assertFalse(unsupported.getFirst().requestRecorded()); assertFalse(unsupported.getFirst().outcomeRecorded());
        TenantContext.setTenantId("202");
        assertTrue(transactions.execute(status -> modelRecordMapper.statuses(201, List.of(first, second), ModelCallRecordCodec.REQUEST_SCHEMA, ModelCallRecordCodec.OUTCOME_SCHEMA)).isEmpty());
        TenantContext.clear();
        var reads = new PlatformObservationAccess(foundation, mock(PlatformObservationTargetMapper.class));
        assertEquals(expected, transactions.execute(status -> reads.read(201, () -> reader.statuses(201, List.of(first, second, crossed, foreign)))));
    }

    /** 实际Store受理父Run，实际预算组件登记辅助尝试；不伪造Core Action或请求材料。 */
    private static com.skillforge.runtime.api.RunRef auxiliaryParent(long tenantId, String sessionId, String root) {
        var tenant = new com.skillforge.runtime.api.TenantId(Long.toString(tenantId));
        var run = new com.skillforge.runtime.api.RunRef(tenant, "run:" + root);
        var owner = com.skillforge.runtime.api.port.SessionOwner.user(new com.skillforge.runtime.api.SessionRef(tenant, sessionId),
                new com.skillforge.runtime.api.AgentId(Long.toString(910_000L + tenantId)), new com.skillforge.runtime.api.EndUserId(920_000L + tenantId));
        var verified = new com.skillforge.runtime.api.port.VerifiedIdentity(new com.skillforge.runtime.api.PrincipalRef.SystemPrincipal(tenant, "observation-fixture"),
                owner, java.util.Set.of("sessions.write"), 1, new com.skillforge.runtime.api.ResultRef("verification:" + root));
        var identity = new com.skillforge.runtime.api.port.RunIdentity(owner, owner.target().requireAgent(), new com.skillforge.runtime.api.InputId(root), run,
                new com.skillforge.runtime.api.TraceEnvelope("trace:" + root, "span:" + root, new com.skillforge.runtime.api.InputId(root), java.util.Optional.empty()),
                verified.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()),
                java.util.Optional.of(new com.skillforge.runtime.api.SourceMessageRef(owner.sessionRef(), root)), new com.skillforge.runtime.api.RunOriginRef(tenant, root));
        var store = new com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore(writer,
                new com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort(writer, Map.of()),
                (session, actual, actor, prepared) -> actual.identity().runRef().tenantId().equals(tenant));
        var capacity = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Resources(5, 5, 5, 5, 100_000, 100_000, 200_000);
        var limits = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Limits(com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.STANDARD,
                capacity, capacity, capacity, 3600, 3600, "trajectory-auxiliary:v1");
        var input = new com.skillforge.runtime.session.spi.ExecutionStore.Material(new com.skillforge.runtime.api.ResultRef("input:" + root),
                new com.skillforge.runtime.api.TypedPayload("application/json", "{}"));
        var context = new com.skillforge.runtime.session.spi.ExecutionStore.Material(new com.skillforge.runtime.api.ResultRef("context:" + root),
                new com.skillforge.runtime.api.TypedPayload("application/json", "{}"));
        assertTrue(store.admit(new com.skillforge.runtime.session.spi.ExecutionStore.Admission(verified, identity, "admission:" + root,
                new com.skillforge.runtime.api.ResultRef("config:" + root), input, context,
                new com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration(run, run, com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY,
                        new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(limits)),
                List.of(com.skillforge.runtime.session.AgentExecutionProgram.admissionEvent(identity)), List.of())).created());
        return run;
    }

    /** 预算/发送/结果走正式生产者；供应商审计与报告是显式受控返回，未发送网络请求。 */
    private static String auxiliaryAttempt(com.skillforge.runtime.api.RunRef run, String invocation, boolean unknown) {
        return auxiliaryAttempt(run, invocation, unknown, null);
    }

    private static String auxiliaryAttempt(com.skillforge.runtime.api.RunRef run, String invocation, boolean unknown, String conflictingSourceRoot) {
        String attemptId = invocation + ":attempt";
        var budgets = new com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets(writer);
        var helper = new com.skillforge.core.billing.budget.AuxiliaryModelBudgets(budgets,
                mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class));
        try (var binding = com.skillforge.runtime.api.port.ExecutionBudgetScope.open(new com.skillforge.runtime.api.port.ExecutionBudgetScope.Binding(budgets, run));
             var attempt = helper.reserve(run.tenantId().value(), invocation, attemptId,
                     com.skillforge.core.billing.budget.AuxiliaryModelBudgets.Request.freeze(100, 100, "model.chat", "fixture:1", List.of("fixture")))) {
            assertTrue(attempt.sent());
            if (!unknown) {
                com.skillforge.core.billing.budget.AuxiliaryModelBudgets.captureUsage(new com.skillforge.core.billing.metering.LlmUsage(7L, 3L, 10L));
                attempt.resultSaved();
            }
        }
        var jdbc = new JdbcTemplate(writer);
        var own = jdbc.queryForMap("SELECT session_id,root_input_id FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", run.tenantId().value(), run.runId());
        String root = own.get("root_input_id").toString(); long tenant = Long.parseLong(run.tenantId().value());
        String sourceRoot = conflictingSourceRoot == null ? root : conflictingSourceRoot;
        new TransactionTemplate(new DataSourceTransactionManager(writer)).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, run.tenantId().value());
            jdbc.update("""
                    INSERT INTO nw_external_invocations(id,tenant_id,invocation_id,attempt_id,operation_type,provider,service,status,
                        input_id,root_input_id,trace_id,span_id,source_event_id,started_at,metadata)
                    VALUES(?,?,?,?,'model.chat','aux-provider','aux-model',?,?,?,?,?,?,now(),'{}'::jsonb)
                    """, com.skillforge.core.shared.config.SnowflakeIds.nextId(), tenant, invocation, attemptId,
                    unknown ? "UNRESOLVED" : "SUCCEEDED", root, sourceRoot, "trace:" + root, "span:" + root, "event:" + invocation);
            if (!unknown) jdbc.update("""
                    INSERT INTO nw_usage_events(id,tenant_id,event_type,provider,model,occurred_at,ingested_at,event_id,
                        session_id,root_input_id,run_id,attempt_id,invocation_id,prompt_tokens,completion_tokens,usage_source,
                        usage_event_kind,report_revision,origin_kind,actor_ref,responsibility_ref,call_point)
                    VALUES(?,?,'llm.chat','aux-provider','aux-model',now(),now(),?,?,?,?,?,?,7,3,'PROVIDER_REPORTED',
                        'usage_report',1,'system','system:trajectory-fixture',?,'system-agent.output-requirements')
                    """, com.skillforge.core.shared.config.SnowflakeIds.nextId(), tenant, "usage:" + attemptId,
                    own.get("session_id"), sourceRoot, run.runId(), attemptId, invocation, "tenant:" + tenant);
        });
        return attemptId;
    }

    private static TrajectoryView.Summary summary(SessionObservationScope scope, TrajectoryQuery.Resource resource) {
        return transactions.execute(status -> service.summary(scope, resource));
    }
}
