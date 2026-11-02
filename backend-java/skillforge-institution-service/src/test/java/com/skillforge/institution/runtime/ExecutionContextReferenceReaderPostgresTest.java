package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.identity.JdbcIdentityTransactions;
import com.skillforge.runtime.adapter.jdbc.schema.RuntimeJdbcSchema;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import com.skillforge.runtime.session.spi.SessionContextReferenceReader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 私有真实PG的Store到正式Reader/Handler组合；IAM固定允许夹具，不代表生产IAM或Provider验收。 */
@Testcontainers(disabledWithoutDocker = false)
class ExecutionContextReferenceReaderPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("context_source_lookup").withUsername("admin").withPassword("synthetic-context-admin");
    private static final TenantId TENANT = new TenantId("context-test");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "context-session"), new AgentId("context-agent"), new EndUserId(1));
    private static final ExecutionBudgetPort.Resources CAPACITY = new ExecutionBudgetPort.Resources(8, 2, 8, 2, 100_000, 100_000, 200_000);
    private static final AgentExecutionCodec CODEC = new AgentExecutionCodec(new CodecLimits(1_000_000, 100_000, 128));

    @Test void exactSourceMatchesFullScanUnicodeAndReferenceWithOneReturnedEventAndOneMaterial() throws Exception {
        var admin = new org.postgresql.ds.PGSimpleDataSource(); admin.setURL(POSTGRES.getJdbcUrl());
        admin.setUser(POSTGRES.getUsername()); admin.setPassword(POSTGRES.getPassword()); RuntimeJdbcSchema.install(admin);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var sql = connection.createStatement()) {
            sql.execute("CREATE ROLE context_reader_fixture LOGIN PASSWORD 'synthetic-context-reader' NOSUPERUSER NOBYPASSRLS");
            sql.execute("GRANT USAGE ON SCHEMA public TO context_reader_fixture");
            sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO context_reader_fixture");
            sql.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO context_reader_fixture");
        }
        var pool = new HikariConfig(); pool.setJdbcUrl(POSTGRES.getJdbcUrl()); pool.setUsername("context_reader_fixture");
        pool.setPassword("synthetic-context-reader"); pool.setMaximumPoolSize(3);
        try (var source = new HikariDataSource(pool)) {
            try (var connection = source.getConnection(); var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(rows.next()); assertFalse(rows.getBoolean(1));
            }
            var actors = new HashMap<ResultRef, VerifiedIdentity>();
            // 当前身份与事务授权由本用例显式固定；不冒充完整Foundation IAM链路。
            var transactions = mock(JdbcIdentityTransactions.class);
            doAnswer(call -> Optional.ofNullable(actors.get(call.getArgument(2, ResultRef.class))))
                    .when(transactions).originalVerification(any(org.apache.ibatis.session.SqlSession.class), eq(OWNER), any(ResultRef.class));
            when(transactions.matchesOriginalCaller(any(), eq(OWNER), any(), any())).thenReturn(true);
            when(transactions.authorize(any(org.apache.ibatis.session.SqlSession.class), any(IdentityPolicyPort.AuthorizationRequest.class)))
                    .thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(1, new ResultRef("fixture-transaction-allow")));
            var actual = new JdbcExecutionStore(source, transactions, (sql, run, actor, prepared) -> true);
            for (int i = 0; i < 120; i++) {
                var command = admission("history-" + i, "甲🙂乙𠮷丙-资料-" + i + "-" + "普通历史正文".repeat(64) + "尾部命中🙂", actors, true);
                actual.admit(command);
            }
            var current = admission("current", "当前读取请求", actors, false);
            var claimed = actual.claim(new Claim(actual.admit(current).run().identity().runRef(), "context-worker", "context-claim", Duration.ofMinutes(5))).orElseThrow();
            var request = material("context-tool-request", new TypedPayload("fixture-tool-request", "{}"));
            var prepared = new Prepared(new ActionRef(TENANT, "current-context-tool"), 1, Kind.TOOL, "read_context_reference", new ResultRef("fixture-capability"), request, "context-request-fingerprint");
            String attemptId = "current-context-attempt";
            var demand = new ExecutionBudgetPort.Demand(claimed.identity().runRef(), attemptId, prepared.fingerprint(), ExecutionBudgetPort.Category.TOOL,
                    ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false);
            var registered = assertInstanceOf(DispatchResult.Registered.class, actual.dispatch(new Dispatch(claimed.identity().runRef(), claimed.revision(), claimed.lease().orElseThrow(),
                    prepared, attemptId, demand, current.identity(), Optional.empty(), List.of())));
            var context = new PlatformToolExecutionContext(new PortCallContext(registered.run().identity(), current.identity(),
                    new InvocationControl(Instant.now().plusSeconds(120), () -> false)), prepared.action().actionId(), attemptId);
            var eventRows = new AtomicInteger(); var materialReads = new AtomicInteger(); var pagedCalls = new AtomicInteger(); var pointCalls = new AtomicInteger(); var rangeCalls = new AtomicInteger();
            var measured = spy(actual);
            doAnswer(call -> { var rows = (List<?>) call.callRealMethod(); pagedCalls.incrementAndGet(); eventRows.addAndGet(rows.size()); return rows; })
                    .when(measured).events(any(SessionRef.class), anyLong(), anyInt());
            doAnswer(call -> { var rows = (List<?>) call.callRealMethod(); rangeCalls.incrementAndGet(); eventRows.addAndGet(rows.size()); return rows; })
                    .when(measured).eventsInRange(any(SessionRef.class), anyLong(), anyLong(), anyInt());
            doAnswer(call -> { var row = (Optional<?>) call.callRealMethod(); pointCalls.incrementAndGet(); eventRows.addAndGet(row.isPresent() ? 1 : 0); return row; })
                    .when(measured).event(any(SessionRef.class), any(EventId.class));
            doAnswer(call -> { materialReads.incrementAndGet(); return call.callRealMethod(); }).when(measured).material(any(TenantId.class), any(ResultRef.class));
            var iam = mock(JdbcIdentityPolicyPort.class);
            when(iam.authorize(any(IdentityPolicyPort.AuthorizationRequest.class))).thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(1, new ResultRef("fixture-reader-allow")));
            var projection = mock(AgentRuntimeExecutionProjection.class);
            var readers = new ExecutionContextReferenceReader(measured, CODEC, iam, projection,
                    new PublicOutputGuard("./private-context-test-artifacts"), new ObjectMapper());
            var reader = readers.forCall(context); String sourceId = "accepted-history-119";
            var selected = reader.readSource(current.run(), sourceId, 0, 4096);
            assertEquals(1, pointCalls.get()); assertEquals(0, pagedCalls.get()); assertEquals(1, eventRows.get()); assertEquals(1, materialReads.get());
            assertTrue(selected.content().startsWith("甲🙂乙𠮷丙-资料-119-"));
            assertEquals("sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(selected.content().getBytes(java.nio.charset.StandardCharsets.UTF_8))), selected.reference().contentDigest().value());
            eventRows.set(0); materialReads.set(0); pagedCalls.set(0); pointCalls.set(0);
            // reference_id的既有documents路径与优化前source定位同样完整遍历，作真实同输入基线。
            var fullScan = reader.read(current.run(), selected.reference().referenceId(), 0, 4096);
            assertEquals(selected, fullScan); assertEquals(120, eventRows.get()); assertEquals(120, materialReads.get()); assertEquals(2, pagedCalls.get()); assertEquals(0, pointCalls.get());
            int oldRows = eventRows.get(); int oldMaterials = materialReads.get();
            eventRows.set(0); materialReads.set(0); pagedCalls.set(0); pointCalls.set(0);
            var unicode = reader.readSource(current.run(), sourceId, 1, 2);
            assertEquals("🙂乙", unicode.content()); assertEquals(3, unicode.nextOffset()); assertFalse(unicode.end()); assertEquals(selected.reference(), unicode.reference());
            var found = reader.findSource(current.run(), sourceId, "𠮷", 0, 2);
            assertTrue(found.matched()); assertEquals(3, found.offset()); assertEquals("𠮷丙", found.page().content()); assertEquals(selected.reference(), found.page().reference());
            var result = new ContextReferenceToolHandler(readers).execute(Map.of("source_event_id", sourceId, "offset", 1, "limit", 2), context);
            assertEquals(true, result.get("success")); assertEquals("🙂乙", result.get("content")); assertEquals(selected.reference().referenceId(), result.get("reference_id"));
            assertEquals(3, pointCalls.get()); assertEquals(0, pagedCalls.get()); assertEquals(3, eventRows.get()); assertEquals(3, materialReads.get());
            verify(iam, times(3)).authorize(any(IdentityPolicyPort.AuthorizationRequest.class));
            verifyNoInteractions(projection); // 本组accepted来源由原输入材料拥有，不走替代公开正文投影。
            assertEquals(registered.run(), actual.load(current.run().runRef()).orElseThrow());
            assertTrue(actual.attempt(current.run().runRef(), attemptId).orElseThrow().result().isEmpty());

            var handler = new ContextReferenceToolHandler(readers);
            eventRows.set(0); materialReads.set(0); pagedCalls.set(0);
            var missing = handler.execute(Map.of("mode", "history", "query", "not present", "limit", 2), context);
            assertEquals(List.of(), missing.get("records"));
            assertEquals(false, missing.get("end"));
            assertEquals(2L, missing.get("next_sequence"));
            assertEquals(3, eventRows.get()); // 两条扫描加一条不投影的末尾探测。
            assertEquals(2, materialReads.get());
            assertEquals(2, pagedCalls.get());
            var missingNext = handler.execute(Map.of("mode", "history", "query", "not present", "limit", 2,
                    "after_sequence", missing.get("next_sequence")), context);
            assertEquals(List.of(), missingNext.get("records"));
            assertEquals(4L, missingNext.get("next_sequence"));
            var rawHistory = reader.history(current.run(), new SessionContextReferenceReader.HistoryQuery(
                    Optional.empty(), 0, 2, Optional.of("尾部命中"), Optional.empty(), Optional.empty(), Optional.empty(), 20));
            var history = handler.execute(Map.of("mode", "history", "query", "尾部命中", "limit", 2), context);
            assertEquals(true, history.get("success"));
            assertEquals(rawHistory.nextSequence(), history.get("next_sequence"));
            assertEquals(rawHistory.end(), history.get("end"));
            assertFalse(rawHistory.end());
            var records = assertInstanceOf(List.class, history.get("records"));
            assertEquals(2, records.size());
            for (int i = 0; i < records.size(); i++) {
                var record = assertInstanceOf(Map.class, records.get(i));
                var original = rawHistory.records().get(i);
                assertEquals(original.sourceEventId(), record.get("source_event_id"));
                assertEquals(original.runId(), record.get("run_id"));
                assertEquals(original.sequence(), record.get("sequence"));
                assertEquals(original.occurredAt().toString(), record.get("occurred_at"));
                var preview = assertInstanceOf(String.class, record.get("content"));
                assertTrue(original.content().startsWith(preview));
                assertTrue(preview.codePointCount(0, preview.length()) < original.content().codePointCount(0, original.content().length()));
                assertEquals(true, record.get("content_truncated"));
                assertEquals(false, record.get("query_match_in_content"));
                var recovered = new StringBuilder();
                long offset = 0;
                String referenceId = null;
                boolean end;
                do {
                    var part = handler.execute(Map.of("source_event_id", original.sourceEventId(), "offset", offset, "limit", 100), context);
                    assertEquals(true, part.get("success"));
                    assertEquals(offset, part.get("offset"));
                    if (referenceId == null) referenceId = (String) part.get("reference_id");
                    assertEquals(referenceId, part.get("reference_id"));
                    var content = assertInstanceOf(String.class, part.get("content"));
                    recovered.append(content);
                    offset += content.codePointCount(0, content.length());
                    assertEquals(offset, part.get("next_offset"));
                    end = (Boolean) part.get("end");
                } while (!end);
                assertEquals(original.content(), recovered.toString());
            }
            eventRows.set(0); materialReads.set(0); pagedCalls.set(0); pointCalls.set(0);
            var windowPage = handler.execute(Map.of("mode", "history", "around_source_event_id", sourceId,
                    "window", 2), context);
            var windowRecords = assertInstanceOf(List.class, windowPage.get("records"));
            assertEquals(3, windowRecords.size());
            assertEquals(sourceId, assertInstanceOf(Map.class, windowRecords.getLast()).get("source_event_id"));
            assertEquals(true, windowPage.get("end"));
            assertEquals(0, pagedCalls.get());
            assertEquals(1, pointCalls.get());
            assertEquals(4, materialReads.get()); // 锚点点查一次，加窗口内三条。
            assertEquals(1, rangeCalls.get());
            assertThrows(IllegalStateException.class, () -> handler.execute(Map.of("mode", "history",
                    "around_source_event_id", sourceId, "history_run_id", "another-run", "window", 2), context));
            rangeCalls.set(0);
            var maxWindow = handler.execute(Map.of("mode", "history", "around_source_event_id", "accepted-history-60",
                    "window", 50), context);
            var maxRecords = assertInstanceOf(List.class, maxWindow.get("records"));
            assertEquals(101, maxRecords.size());
            assertEquals("accepted-history-60", assertInstanceOf(Map.class, maxRecords.get(50)).get("source_event_id"));
            assertEquals(2, rangeCalls.get());
            var nextHistory = handler.execute(Map.of("mode", "history", "query", "尾部命中", "limit", 2,
                    "after_sequence", history.get("next_sequence")), context);
            var rawNextHistory = reader.history(current.run(), new SessionContextReferenceReader.HistoryQuery(
                    Optional.empty(), rawHistory.nextSequence(), 2, Optional.of("尾部命中"), Optional.empty(), Optional.empty(), Optional.empty(), 20));
            assertEquals(rawNextHistory.nextSequence(), nextHistory.get("next_sequence"));
            assertEquals(rawNextHistory.end(), nextHistory.get("end"));
            var nextRecords = assertInstanceOf(List.class, nextHistory.get("records"));
            assertEquals(2, nextRecords.size());
            assertEquals(rawNextHistory.records().getFirst().sourceEventId(),
                    assertInstanceOf(Map.class, nextRecords.getFirst()).get("source_event_id"));
            assertTrue(((Number) assertInstanceOf(Map.class, nextRecords.getFirst()).get("sequence")).longValue()
                    > ((Number) history.get("next_sequence")).longValue());
            var tail = handler.execute(Map.of("mode", "history", "after_sequence", 118, "limit", 2), context);
            assertEquals(true, tail.get("end"));
            assertEquals(120L, tail.get("next_sequence"));
            when(iam.authorize(any(IdentityPolicyPort.AuthorizationRequest.class)))
                    .thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(1, new ResultRef("fixture-reader-allow")))
                    .thenReturn(new IdentityPolicyPort.AuthorizationResult.Denied("revoked", 2, new ResultRef("fixture-reader-deny")));
            assertThrows(IllegalStateException.class, () -> handler.execute(Map.of("mode", "history", "limit", 2), context));
            System.out.printf("context-source same-private-PG: historyRuns=120 oldReturnedEventRows=%d oldMaterialReads=%d exactReturnedEventRows=1 exactMaterialReads=1 exactPagedCalls=0 originalReferenceAndUnicodeEqual=true iam=CONTROLLED_ALLOW_NO_PROVIDER%n", oldRows, oldMaterials);
        }
    }

    private static Admission admission(String id, String text, Map<ResultRef, VerifiedIdentity> actors, boolean acceptedEvent) {
        var input = new InputId("input-" + id); var run = new RunRef(TENANT, id); var verification = new ResultRef("verified-" + id);
        var actor = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT, "context-fixture"), OWNER, Set.of("sessions.write"), 1, verification); actors.put(verification, actor);
        var identity = new RunIdentity(OWNER, new AgentId("context-agent"), input, run, new TraceEnvelope("trace-" + id, "span-" + id, input, Optional.empty()), verification,
                new RunRequester.EndUser(OWNER.requireUser()), Optional.empty(), new RunOriginRef(TENANT, "origin-" + id));
        var frozen = material("execution-input:" + id, CODEC.encodeInput(List.of(new ModelMessage("input:" + input.value(), ModelRole.USER, text)), new TypedPayload("input.v1", "{}")));
        var context = material("context-" + id, new TypedPayload("fixture-context", "{}"));
        var extra = material("unrelated-material-" + id, new TypedPayload("fixture-extra", "{\"text\":\"" + "无关冻结材料".repeat(256) + "\"}"));
        var budget = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, CAPACITY, CAPACITY, CAPACITY, 600, 600, "context-fixture-v1")));
        return new Admission(actor, identity, "fingerprint-" + id, new ResultRef("configuration-" + id), frozen, context, budget,
                acceptedEvent ? List.of(new Event(new EventId("accepted-" + id), AgentExecutionProgram.ACCEPTED_EVENT, new TypedPayload("accepted.v1", "{}"))) : List.of(), List.of(extra));
    }
    private static Material material(String id, TypedPayload value) { return new Material(new ResultRef(id), value); }
}
