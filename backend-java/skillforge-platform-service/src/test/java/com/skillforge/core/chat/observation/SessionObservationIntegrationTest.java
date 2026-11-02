package com.skillforge.core.chat.observation;

import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationMapper;
import com.skillforge.core.chat.observation.SessionObservationQuery;
import com.skillforge.core.chat.observation.SessionObservationRepository;
import com.skillforge.core.chat.observation.SessionObservationScope;
import com.skillforge.core.chat.observation.SessionObservationService;
import com.skillforge.core.chat.observation.SessionObservationView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.integration.publicread.RuntimeCompletedAnswerReader;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.chat.session.SessionTraceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实迁移与 Runtime 事实共同证明平台会话观测查询，不通过关闭约束伪造夹具。 */
@Testcontainers
class SessionObservationIntegrationTest {
    private static final long PLAN_ID = 910_000L;

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("session_observation")
            .withUsername("skillforge")
            .withPassword("test-only");

    static JdbcTemplate jdbc;
    static SessionObservationService service;

    @BeforeAll
    static void initialize() {
        var source = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO nw_billing_plans(id, plan_code, display_name, slo_availability_target)
                VALUES (?, 'session-observation', '会话观测测试套餐', 0.9900)
                """, PLAN_ID);
        var factory = new org.mybatis.spring.SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/SessionObservationMapper.xml"));
        org.apache.ibatis.session.SqlSessionFactory sessions;
        try { sessions = factory.getObject(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        var reads = mock(PlatformObservationAccess.class);
        org.mockito.Mockito.when(reads.read(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        service = new SessionObservationService(new SessionObservationRepository(
                new org.mybatis.spring.SqlSessionTemplate(sessions).getMapper(SessionObservationMapper.class), reads), mock(SessionTraceService.class),
                mock(ObservationReadAudit.class), new ObjectMapper(), new RuntimeCompletedAnswerReader(new ObjectMapper()),
                new PublicOutputGuard("./build/session-observation-private-artifacts"), reads);
    }

    @Test
    void filtersSessionsByAbsoluteTimeWithInclusiveStartAndExclusiveEnd() {
        fixture(106L, "session-date", "root-date-a", "root-date-b", 1L, 1L, 1L);
        jdbc.update("UPDATE nw_chat_sessions SET created_at = '2026-10-07T00:00:00.123456Z' WHERE session_id = ?",
                "session-date");
        Instant start = Instant.parse("2026-10-07T08:00:00.123456+08:00");
        var included = service.sessions(platform(), new SessionObservationQuery.Sessions(
                106L, null, null, null, null, null, null, start, start.plusSeconds(1), null, 1, 20));
        assertEquals(1L, included.total());
        var excluded = service.sessions(platform(), new SessionObservationQuery.Sessions(
                106L, null, null, null, null, null, null, start.minusSeconds(1), start, null, 1, 20));
        assertEquals(0L, excluded.total());
    }

    @Test
    void readsSessionRootsMessagesTraceAndUsageFromCommittedFacts() {
        fixture(101L, "session-main", "root-a", "root-b", 9007199254740993L, 5L, 3L);

        var sessions = service.sessions(platform(), new SessionObservationQuery.Sessions(
                101L, "session-main", null, null, null, null, null, null, null, null, 1, 20));
        assertEquals(1L, sessions.total());
        assertEquals("session-main", sessions.items().getFirst().sessionId());
        assertEquals(2, sessions.items().getFirst().rootInputCount());
        assertEquals("COMPLETED", sessions.items().getFirst().answerStatus());
        assertEquals("COMPLETED", sessions.items().getFirst().executionStatus());
        assertEquals(4, sessions.items().getFirst().messageCount());

        var detail = service.detail(platform(), new SessionObservationQuery.Resource(101L, "session-main"));
        assertEquals("9007199254741001", detail.usageSnapshot().promptTokens());
        assertEquals(sessions.items().getFirst(), detail.session());
        assertEquals(SessionObservationFixture.MESSAGE_TIME, detail.firstMessageAt());
        assertTrue(detail.lastMessageAt().isAfter(SessionObservationFixture.MESSAGE_TIME));

        var rounds = service.rounds(platform(), new SessionObservationQuery.Rounds(
                101L, "session-main", "root-a", null, 10));
        assertEquals(1, rounds.items().size());
        assertEquals("root-a", rounds.items().getFirst().rootInputId());
        assertEquals("1", rounds.items().getFirst().roundNumber());
        assertEquals("COMPLETED", rounds.items().getFirst().answerStatus());
        assertEquals("COMPLETED", rounds.items().getFirst().executionStatus());
        assertEquals(List.of("run-root-a"), rounds.items().getFirst().runs().stream()
                .map(SessionObservationView.Run::runId).toList());

        var messages = service.messages(platform(), new SessionObservationQuery.Messages(
                101L, "session-main", "root-a", null, 10));
        assertEquals(Set.of("root-a", SessionObservationFixture.answerMessageId(101L, "root-a")),
                messages.items().stream().map(SessionObservationView.Message::messageId).collect(java.util.stream.Collectors.toSet()));
        var answer = messages.items().stream().filter(item -> "assistant".equals(item.role())).findFirst().orElseThrow();
        assertEquals("answer root-a", answer.content());
        assertEquals("run-root-a", answer.runId());
        assertEquals("final", answer.phase());
        assertEquals("completed", answer.state());
        assertEquals("1", answer.messageVersion());

        var trace = service.trace(platform(), new SessionObservationQuery.Trace(
                101L, "session-main", "root-a", null, 10, null));
        assertEquals(List.of("execution.accepted", "execution.model-result", "execution.model-result", "execution.model-result", "execution.completed"),
                trace.items().stream().map(SessionObservationView.TraceEvent::eventType).toList());

        var usage = service.usage(platform(), new SessionObservationQuery.Usage(
                101L, "session-main", "root-a", null, 10));
        assertEquals("COMPLETE", usage.coverage().status());
        assertEquals(3, usage.coverage().totalAttempts());
        assertEquals("9007199254741001", usage.totals().promptTokens());
        assertEquals("15", usage.totals().completionTokens());
        assertEquals("9007199254740993", usage.attempts().getFirst().totals().promptTokens());
    }

    @Test
    void filtersByDerivedAnswerStatusWithoutChangingTotalsOrSummaries() {
        fixture(107L, "session-status", "root-status-a", "root-status-b", 1L, 1L, 1L);
        var completed = service.sessions(platform(), sessionsQuery(107L, "session-status", "COMPLETED"));
        var failed = service.sessions(platform(), sessionsQuery(107L, "session-status", "FAILED"));
        var detail = service.detail(platform(), new SessionObservationQuery.Resource(107L, "session-status"));
        assertEquals(1L, completed.total());
        assertEquals(List.of(detail.session()), completed.items());
        var emptyPage = service.sessions(platform(), new SessionObservationQuery.Sessions(107L, "session-status",
                null, null, null, null, "COMPLETED", null, null, null, 2, 20));
        assertEquals(1L, emptyPage.total());
        assertTrue(emptyPage.items().isEmpty());
        assertEquals(0L, failed.total());
        assertTrue(failed.items().isEmpty());
        var roots = service.rounds(platform(), new SessionObservationQuery.Rounds(107L, "session-status", null, null, 10));
        assertEquals(2, roots.items().size());
        assertTrue(roots.items().stream().allMatch(root -> "COMPLETED".equals(root.answerStatus())));
        assertEquals(4, service.messages(platform(), new SessionObservationQuery.Messages(107L, "session-status", null, null, 10)).items().size());
    }

    /** 无 phase/state 的主动消息也必须有非空正文；空 Run 报告不能替它证明回答交付。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankProactiveAssistantAndEmptyRuntimeCompletionDoNotCompleteTheLatestAnswer(String content) {
        long tenantId = content.isEmpty() ? 112L : 113L;
        String session = "session-blank-proactive-" + tenantId;
        String root = "root-blank-proactive-" + tenantId;
        new SessionObservationFixture(jdbc).populateAnswerCase(
                tenantId, session, root, SessionObservationFixture.AnswerCase.EMPTY_COMPLETION);
        jdbc.update("""
                INSERT INTO nw_chat_messages(id, tenant_id, session_id, end_user_id, message_id, role, content,
                                          metadata, created_at)
                VALUES (?, ?, ?, ?, ?, 'assistant', ?, CAST(? AS jsonb), ?)
                """, 970_000L + tenantId, tenantId, session, 920_000L + tenantId,
                "proactive-" + root, content, "{\"root_input_id\":\"" + root + "\"}",
                java.sql.Timestamp.from(SessionObservationFixture.MESSAGE_TIME));
        var detail = service.detail(platform(), new SessionObservationQuery.Resource(tenantId, session));
        var rounds = service.rounds(platform(), new SessionObservationQuery.Rounds(tenantId, session, root, null, 10));
        var sessions = service.sessions(platform(), sessionsQuery(tenantId, session, "NONE"));
        assertEquals("NONE", detail.session().answerStatus());
        assertEquals("COMPLETED", detail.session().executionStatus());
        assertEquals("NONE", rounds.items().getFirst().answerStatus());
        assertEquals(1L, sessions.total());
        assertEquals(List.of(detail.session()), sessions.items());
        // status 同时筛选 answer/execution；执行完成过滤中的回答状态仍必须保持 NONE。
        assertEquals("NONE", service.sessions(platform(), sessionsQuery(tenantId, session, "COMPLETED"))
                .items().getFirst().answerStatus());
    }

    /** 非成功 Run 不发布最终回答；Run 完成但无正文也不能宣称回答完成。 */
    @ParameterizedTest
    @CsvSource({"EMPTY_COMPLETION,NONE,COMPLETED,none,1",
            "FAILED,FAILED,FAILED,failed,1", "CANCELLED,NONE,CANCELLED,cancelled,1",
            "UNKNOWN,FAILED,OUTCOME_UNKNOWN,failed,1", "UNSAFE,FAILED,COMPLETED,failed,2"})
    void doesNotInferSuccessfulAnswersFromUnsuccessfulOrEmptyRuns(
            SessionObservationFixture.AnswerCase answerCase, String answerStatus,
            String executionStatus, String messageState, int expectedCount) {
        long tenantId = 120L + answerCase.ordinal();
        String session = "session-negative-" + answerCase;
        String root = "root-negative-" + answerCase;
        new SessionObservationFixture(jdbc).populateAnswerCase(tenantId, session, root, answerCase);
        var detail = service.detail(platform(), new SessionObservationQuery.Resource(tenantId, session));
        var filtered = service.sessions(platform(), sessionsQuery(tenantId, session, answerStatus));
        var rounds = service.rounds(platform(), new SessionObservationQuery.Rounds(tenantId, session, root, null, 10));
        var messages = service.messages(platform(), new SessionObservationQuery.Messages(tenantId, session, root, null, 10));
        assertEquals(answerStatus, detail.session().answerStatus());
        assertEquals(executionStatus, detail.session().executionStatus());
        assertEquals(List.of(detail.session()), filtered.items());
        assertEquals(1L, filtered.total());
        assertEquals(answerStatus, rounds.items().getFirst().answerStatus());
        assertEquals(executionStatus, rounds.items().getFirst().executionStatus());
        assertEquals(expectedCount, detail.session().messageCount());
        assertEquals(expectedCount, messages.items().size());
        assertTrue(messages.items().stream().noneMatch(message -> "completed".equals(message.state())));
        if (expectedCount == 2) {
            var answer = messages.items().stream().filter(message -> "assistant".equals(message.role())).findFirst().orElseThrow();
            assertEquals(messageState, answer.state());
            if (answerCase == SessionObservationFixture.AnswerCase.UNSAFE) {
                assertEquals(PublicOutputGuard.SAFE_MESSAGE, answer.content());
                assertFalse(messages.toString().contains("not-a-real-test-secret"));
                assertFalse(detail.toString().contains("not-a-real-test-secret"));
            }
        }
        if (answerCase == SessionObservationFixture.AnswerCase.UNKNOWN) {
            var usage = service.usage(platform(), new SessionObservationQuery.Usage(tenantId, session, root, null, 10));
            assertEquals("UNKNOWN", usage.coverage().status());
            assertEquals(1, usage.coverage().totalAttempts());
            assertEquals(1, usage.coverage().unknownAttempts());
            assertEquals(0, usage.coverage().zeroUsageAttempts());
            assertNull(usage.totals().promptTokens());
            assertNull(usage.totals().completionTokens());
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=? AND session_id=?",
                    Long.class, tenantId, session));
            assertEquals("UNCERTAIN", jdbc.queryForObject("SELECT phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?",
                    String.class, Long.toString(tenantId), "run-" + root));
        }
    }

    /** 同时间的用户消息与数据库真实提交答案稳定分页；后续输入与答案不能污染原cursor。 */
    @Test
    void freezesBothMessageSourcesAndUsesStableCompositeCursorAtTimestampTies() {
        var fixture = new SessionObservationFixture(jdbc);
        fixture.populate(108L, "session-message-pages", "root-page-a", "root-page-b", 1L, 1L, 1L);
        var initial = service.messages(platform(), new SessionObservationQuery.Messages(108L, "session-message-pages", null, null, 10));
        assertEquals(4, initial.items().size());
        assertTrue(initial.items().stream().filter(message -> "user".equals(message.role()))
                .allMatch(message -> SessionObservationFixture.MESSAGE_TIME.equals(message.createdAt())));
        assertTrue(initial.items().stream().filter(message -> "assistant".equals(message.role()))
                .allMatch(message -> message.createdAt().isAfter(SessionObservationFixture.MESSAGE_TIME)));
        var first = service.messages(platform(), new SessionObservationQuery.Messages(108L, "session-message-pages", null, null, 1));
        assertNotNull(first.nextCursor());
        fixture.appendCompletedRound(108L, "session-message-pages", "root-page-late");
        var observed = new ArrayList<>(first.items());
        String cursor = first.nextCursor();
        for (int remaining = 0; cursor != null && remaining < 10; remaining++) {
            var next = service.messages(platform(), new SessionObservationQuery.Messages(108L, "session-message-pages", null, cursor, 1));
            observed.addAll(next.items());
            cursor = next.nextCursor();
        }
        assertNull(cursor, "复合 cursor 必须在冻结消息集合后结束");
        assertEquals(initial.items(), observed);
        assertEquals(4L, observed.stream().map(SessionObservationView.Message::messageId).distinct().count());
        assertTrue(observed.stream().noneMatch(message -> "root-page-late".equals(message.rootInputId())));
        assertEquals(6, service.messages(platform(), new SessionObservationQuery.Messages(108L, "session-message-pages", null, null, 10)).items().size());
        assertEquals(6, service.detail(platform(), new SessionObservationQuery.Resource(108L, "session-message-pages")).session().messageCount());
    }

    @Test
    void bindsMessageCursorToTenantSessionRootAndPageKind() {
        fixture(109L, "session-message-scope", "root-scope-a", "root-scope-b", 1L, 1L, 1L);
        fixture(110L, "session-message-other", "root-other-a", "root-other-b", 1L, 1L, 1L);
        var first = service.messages(platform(), new SessionObservationQuery.Messages(109L, "session-message-scope", "root-scope-a", null, 1));
        assertNotNull(first.nextCursor());
        assertBadCursor(new SessionObservationQuery.Messages(109L, "session-message-scope", "root-scope-b", first.nextCursor(), 1));
        assertBadCursor(new SessionObservationQuery.Messages(109L, "session-message-scope", null, first.nextCursor(), 1));
        assertBadCursor(new SessionObservationQuery.Messages(110L, "session-message-other", "root-other-a", first.nextCursor(), 1));
        var trace = service.trace(platform(), new SessionObservationQuery.Trace(109L, "session-message-scope", "root-scope-a", null, 1, null));
        assertBadCursor(new SessionObservationQuery.Messages(109L, "session-message-scope", "root-scope-a", trace.nextCursor(), 1));
        var missing = assertThrows(ApiException.class, () -> service.messages(platform(),
                new SessionObservationQuery.Messages(109L, "session-message-other", "root-other-a", null, 1)));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
    }

    @Test
    void keepsZeroRuntimeHighWatermarkWhenAnswersAppearAfterTheFirstMessagePage() {
        var fixture = new SessionObservationFixture(jdbc);
        fixture.populateInputOnly(111L, "session-zero-runtime", "root-zero-a", "root-zero-b");
        var baseline = service.messages(platform(), new SessionObservationQuery.Messages(111L, "session-zero-runtime", null, null, 10));
        var first = service.messages(platform(), new SessionObservationQuery.Messages(111L, "session-zero-runtime", null, null, 1));
        assertEquals(2, baseline.items().size());
        assertNotNull(first.nextCursor());
        fixture.appendCompletedRound(111L, "session-zero-runtime", "root-zero-late");
        var second = service.messages(platform(), new SessionObservationQuery.Messages(111L, "session-zero-runtime", null, first.nextCursor(), 10));
        var observed = new ArrayList<>(first.items());
        observed.addAll(second.items());
        assertEquals(baseline.items(), observed);
        assertNull(second.nextCursor());
        assertEquals(4, service.messages(platform(), new SessionObservationQuery.Messages(111L, "session-zero-runtime", null, null, 10)).items().size());
    }

    @Test
    void rejectsTenantScopeAndRootEscapes() {
        fixture(102L, "session-tenant-one", "root-one", "root-two", 10L, 2L, 1L);
        fixture(103L, "session-tenant-two", "root-three", "root-four", 10L, 2L, 1L);

        assertThrows(IllegalArgumentException.class, () -> service.detail(tenant(102L),
                new SessionObservationQuery.Resource(103L, "session-tenant-two")));
        ApiException missingRoot = assertThrows(ApiException.class, () -> service.messages(platform(),
                new SessionObservationQuery.Messages(102L, "session-tenant-one", "root-three", null, 10)));
        assertEquals(HttpStatus.NOT_FOUND, missingRoot.getStatus());

        var rootOne = service.usage(platform(), new SessionObservationQuery.Usage(
                102L, "session-tenant-one", "root-one", null, 10));
        assertEquals(3, rootOne.coverage().totalAttempts());
        assertEquals("13", rootOne.totals().promptTokens());
    }

    @Test
    void bindsTraceAndUsageCursorsToOriginalScopeAndFreezesUsageSummary() {
        fixture(104L, "session-cursors", "root-cursor-a", "root-cursor-b", 7L, 11L, 13L);

        // target root 只作为首定位锚点；续页合法返回更早的 root，不要求重复包含原锚点。
        var roundFirst = service.rounds(platform(), new SessionObservationQuery.Rounds(
                104L, "session-cursors", "root-cursor-b", null, 1));
        assertEquals("root-cursor-b", roundFirst.items().getFirst().rootInputId());
        assertNotNull(roundFirst.nextCursor());
        var roundSecond = service.rounds(platform(), new SessionObservationQuery.Rounds(
                104L, "session-cursors", "root-cursor-b", roundFirst.nextCursor(), 1));
        assertEquals(List.of("root-cursor-a"), roundSecond.items().stream()
                .map(SessionObservationView.Round::rootInputId).toList());
        assertNull(roundSecond.nextCursor());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> service.rounds(platform(),
                new SessionObservationQuery.Rounds(104L, "session-cursors", "root-cursor-a",
                        roundFirst.nextCursor(), 1))).getStatus());

        var traceFirst = service.trace(platform(), new SessionObservationQuery.Trace(
                104L, "session-cursors", "root-cursor-a", null, 1, null));
        assertNotNull(traceFirst.nextCursor());
        ApiException invalidTraceCursor = assertThrows(ApiException.class, () -> service.trace(platform(),
                new SessionObservationQuery.Trace(104L, "session-cursors", "root-cursor-b",
                        traceFirst.nextCursor(), 1, null)));
        assertEquals(HttpStatus.BAD_REQUEST, invalidTraceCursor.getStatus());

        var first = service.usage(platform(), new SessionObservationQuery.Usage(
                104L, "session-cursors", "root-cursor-a", null, 1));
        assertNotNull(first.nextCursor());
        var second = service.usage(platform(), new SessionObservationQuery.Usage(
                104L, "session-cursors", "root-cursor-a", first.nextCursor(), 1));
        assertEquals(first.coverage(), second.coverage());
        assertEquals(first.totals(), second.totals());
        assertEquals(first.cache(), second.cache());
        assertEquals(first.breakdowns(), second.breakdowns());
        assertEquals(1, first.attempts().size());
        assertEquals(1, second.attempts().size());
        assertEquals(false, first.attempts().getFirst().attemptId()
                .equals(second.attempts().getFirst().attemptId()));
    }

    @Test
    void keepsChronologicalRoundNumbersAcrossPagingAnchorsAndNewInputs() {
        var fixture = new SessionObservationFixture(jdbc);
        fixture.populate(701L, "session-round-order", "root-order-a", "root-order-b", 1L, 1L, 1L);
        var first = service.rounds(platform(), new SessionObservationQuery.Rounds(
                701L, "session-round-order", null, null, 1));
        assertEquals("root-order-b", first.items().getFirst().rootInputId());
        assertEquals("2", first.items().getFirst().roundNumber());
        assertNotNull(first.nextCursor());

        fixture.appendCompletedRound(701L, "session-round-order", "root-order-c");
        var older = service.rounds(platform(), new SessionObservationQuery.Rounds(
                701L, "session-round-order", null, first.nextCursor(), 1));
        assertEquals(List.of("root-order-a"), older.items().stream()
                .map(SessionObservationView.Round::rootInputId).toList());
        assertEquals("1", older.items().getFirst().roundNumber());
        assertNull(older.nextCursor());

        var refreshed = service.rounds(platform(), new SessionObservationQuery.Rounds(
                701L, "session-round-order", null, null, 10));
        assertEquals(List.of("root-order-c", "root-order-b", "root-order-a"), refreshed.items().stream()
                .map(SessionObservationView.Round::rootInputId).toList());
        assertEquals(List.of("3", "2", "1"), refreshed.items().stream()
                .map(SessionObservationView.Round::roundNumber).toList());
        assertTrue(Long.parseLong(refreshed.items().get(0).firstSequence())
                > Long.parseLong(refreshed.items().get(1).firstSequence()));

        var anchored = service.rounds(platform(), new SessionObservationQuery.Rounds(
                701L, "session-round-order", "root-order-b", null, 1));
        assertEquals("root-order-b", anchored.items().getFirst().rootInputId());
        assertEquals("2", anchored.items().getFirst().roundNumber());
        var anchoredOlder = service.rounds(platform(), new SessionObservationQuery.Rounds(
                701L, "session-round-order", "root-order-b", anchored.nextCursor(), 1));
        assertEquals("root-order-a", anchoredOlder.items().getFirst().rootInputId());
        assertEquals("1", anchoredOlder.items().getFirst().roundNumber());
    }

    @Test
    void servesTheActualHttpContractWithLosslessTokenStrings() throws Exception {
        fixture(105L, "session-http", "root-http-a", "root-http-b", 9007199254740993L, 0L, 0L);
        var authorization = mock(SessionObservationAuthorizer.class);
        org.mockito.Mockito.when(authorization.platformAdmin()).thenReturn(platform());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.skillforge.platform.controller.PlatformSessionController(authorization, service)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/platform/sessions/rounds")
                .param("tenant_id", "105").param("session_id", "session-http").param("page_size", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].root_input_id").value("root-http-b"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].round_number").value("2"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/platform/sessions/usage")
                .param("tenant_id", "105").param("session_id", "session-http")
                .param("root_input_id", "root-http-a").param("page_size", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.totals.prompt_tokens").value("9007199254740993"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.attempts.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.next_cursor").isString());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/platform/sessions/detail")
                .param("tenant_id", "105").param("session_id", "session-http"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.session_id").value("session-http"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.answer_status").value("COMPLETED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.message_count").value(4));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/platform/sessions/messages")
                .param("tenant_id", "105").param("session_id", "session-http")
                .param("root_input_id", "root-http-a").param("page_size", "10"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items.length()").value(2))
                // matcher 检查数组内容；value(List.of(...)) 会要求 JSONPath 转成 JDK 私有不可变 List 类型。
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[?(@.role=='assistant')].state")
                        .value(org.hamcrest.Matchers.contains("completed")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[?(@.role=='assistant')].message_version")
                        .value(org.hamcrest.Matchers.contains("1")));
    }

    private static SessionObservationQuery.Sessions sessionsQuery(long tenantId, String sessionId, String status) {
        return new SessionObservationQuery.Sessions(tenantId, sessionId, null, null, null, null, status,
                null, null, null, 1, 20);
    }

    private static void assertBadCursor(SessionObservationQuery.Messages query) {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> service.messages(platform(), query)).getStatus());
    }

    private static SessionObservationScope platform() {
        return new SessionObservationScope(SessionObservationScope.Kind.PLATFORM_ADMIN, null, "platform");
    }

    private static SessionObservationScope tenant(long tenantId) {
        return new SessionObservationScope(SessionObservationScope.Kind.TENANT, tenantId, "tenant-operator");
    }

    private static void fixture(
            long tenantId, String sessionId, String rootA, String rootB,
            long firstPrompt, long secondPrompt, long thirdPrompt) {
        new SessionObservationFixture(jdbc).populate(
                tenantId, sessionId, rootA, rootB, firstPrompt, secondPrompt, thirdPrompt);
    }
}
