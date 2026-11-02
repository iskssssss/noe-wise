package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionOutboxEntryDO;
import com.skillforge.core.chat.persistence.SessionOutboxSequenceMapper;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryMapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryDO;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 借用入口沿同一SqlSession写输出与投递责任，提交权与通知权保留给外层事务。 */
class SessionMailboxBorrowedTest {
    private final SessionOutboxMapper springOutputs = mock(SessionOutboxMapper.class);
    private final SessionOutboxSequenceMapper springSequences = mock(SessionOutboxSequenceMapper.class);
    private final SessionEventSubscriptionMapper springSubscriptions = mock(SessionEventSubscriptionMapper.class);
    private final SessionOutboxWebhookDeliveryMapper springDeliveries = mock(SessionOutboxWebhookDeliveryMapper.class);
    private final SessionOutboxMapper outputs = mock(SessionOutboxMapper.class);
    private final SessionOutboxSequenceMapper sequences = mock(SessionOutboxSequenceMapper.class);
    private final SessionEventSubscriptionMapper subscriptions = mock(SessionEventSubscriptionMapper.class);
    private final SessionOutboxWebhookDeliveryMapper deliveries = mock(SessionOutboxWebhookDeliveryMapper.class);
    private final SqlSession sql = mock(SqlSession.class);
    private final SessionMailboxService mailbox = new SessionMailboxService(springOutputs, springSequences,
            new SessionWebhookDeliveryLedger(springSubscriptions, springDeliveries));
    private final SessionOutboxProjectionSource source = new SessionOutboxProjectionSource(1, "source-1", "policy");

    /** 仅配置测试持有的代理，不触碰连接、租户或任何事务生命周期。 */
    private void borrowedMappers() {
        when(sql.getMapper(SessionOutboxMapper.class)).thenReturn(outputs);
        when(sql.getMapper(SessionOutboxSequenceMapper.class)).thenReturn(sequences);
        when(sql.getMapper(SessionEventSubscriptionMapper.class)).thenReturn(subscriptions);
        when(sql.getMapper(SessionOutboxWebhookDeliveryMapper.class)).thenReturn(deliveries);
        when(sequences.lockProjection(7L, "session")).thenReturn(emptyState());
        when(outputs.findEvents(eq(7L), any(String[].class))).thenReturn(List.of());
        when(outputs.insertOutputs(any())).thenAnswer(call -> call.<List<?>>getArgument(0).size());
        when(sequences.recordProjectionCompletion(eq(7L), eq("session"), any(), anyString())).thenReturn(1);
    }

    @Test void plainMyBatisRegistersEveryBorrowedStatementWithoutPlusInjectionAndIsIdempotent() {
        var configuration = new Configuration();
        SessionMailboxService.configureBorrowed(configuration);
        int count = configuration.getMappedStatementNames().size();
        SessionMailboxService.configureBorrowed(configuration);
        assertEquals(count, configuration.getMappedStatementNames().size());
        for (Class<?> mapper : List.of(SessionOutboxMapper.class, SessionOutboxSequenceMapper.class,
                SessionEventSubscriptionMapper.class, SessionOutboxWebhookDeliveryMapper.class)) {
            assertTrue(configuration.hasMapper(mapper));
        }
        assertTrue(configuration.hasStatement(SessionOutboxMapper.class.getName() + ".insertOutputs"));
        assertTrue(configuration.hasStatement(SessionOutboxSequenceMapper.class.getName() + ".lockProjection"));
        assertTrue(configuration.hasStatement(SessionOutboxWebhookDeliveryMapper.class.getName() + ".insertDeliveries"));
        assertFalse(configuration.hasStatement(SessionEventSubscriptionMapper.class.getName() + ".selectList"));
        var statement = configuration.getMappedStatement(SessionEventSubscriptionMapper.class.getName() + ".activeForSession");
        var bound = statement.getBoundSql(Map.of("tenantId", 7L, "sessionId", "session"));
        String query = bound.getSql().replaceAll("\\s+", " ").trim();
        assertEquals("SELECT id FROM nw_chat_session_event_subscriptions WHERE tenant_id=? AND status='active' AND (session_id IS NULL OR session_id=?) ORDER BY id", query);
        assertEquals(List.of("tenantId", "sessionId"), bound.getParameterMappings().stream()
                .map(org.apache.ibatis.mapping.ParameterMapping::getProperty).toList());
    }

    @Test void borrowedProjectionWritesWebhookOnSameSessionAndOnlyExplicitCommitNotifies() {
        borrowedMappers();
        when(sequences.reserve(7L, "session", 1)).thenReturn(1L);
        var subscription = new SessionEventSubscriptionDO(); subscription.setId(12L);
        when(subscriptions.activeForSession(7L, "session")).thenReturn(List.of(subscription));
        try (var observer = mailbox.observeOutputs("7", "session")) {
            TransactionSynchronizationManager.initSynchronization();
            try {
                var rows = mailbox.projectOutputsBorrowed(sql, "7", "session", source,
                        checkpoint -> new SessionOutboxProjectionBatch("{}", List.of(command("output-1"))));
                assertEquals(1L, rows.getFirst().getSessionSequence());
                ArgumentCaptor<List<SessionOutboxWebhookDeliveryDO>> captured = ArgumentCaptor.forClass(List.class);
                var order = inOrder(sequences, outputs, subscriptions, deliveries);
                order.verify(sequences).ensure(7L, "session");
                order.verify(sequences).lockProjection(7L, "session");
                order.verify(outputs).findEvents(eq(7L), any(String[].class));
                order.verify(sequences).reserve(7L, "session", 1);
                ArgumentCaptor<List<SessionOutboxEntryDO>> inserted = ArgumentCaptor.forClass(List.class);
                order.verify(outputs).insertOutputs(inserted.capture());
                order.verify(subscriptions).activeForSession(7L, "session");
                order.verify(deliveries).insertDeliveries(captured.capture());
                order.verify(sequences).recordProjectionCompletion(7L, "session", source, "{}");
                assertEquals(rows.getFirst(), SessionMailboxService.view(inserted.getValue().getFirst()));
                assertEquals(inserted.getValue().getFirst().getId(), captured.getValue().getFirst().getOutboxEventId());
                assertEquals(12L, captured.getValue().getFirst().getSubscriptionId());
                assertEquals(0, observer.version());
                assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
                verify(sql).getMapper(SessionOutboxMapper.class);
                verify(sql).getMapper(SessionOutboxSequenceMapper.class);
                verify(sql).getMapper(SessionEventSubscriptionMapper.class);
                verify(sql).getMapper(SessionOutboxWebhookDeliveryMapper.class);
                verifyNoMoreInteractions(sql);
                verifyNoInteractions(springOutputs, springSequences, springSubscriptions, springDeliveries);
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
            assertEquals(0, observer.version());
            mailbox.outputsCommitted("7", "session");
            assertEquals(1, observer.version());
        }
    }

    @Test void coveredBorrowedSourceDoesNotReserveWriteOrReenqueue() {
        borrowedMappers();
        when(sequences.lockProjection(7L, "session")).thenReturn(new SessionOutboxProjectionState(
                1, 1L, "source-1", "policy", null, 1L, "source-1", "policy", "{}"));
        assertEquals(List.of(), mailbox.projectOutputsBorrowed(sql, "7", "session", source, checkpoint -> {
            fail("covered source must not project again"); return null;
        }));
        verify(sequences).ensure(7L, "session"); verify(sequences).lockProjection(7L, "session");
        verifyNoMoreInteractions(sequences);
        verifyNoInteractions(outputs, subscriptions, deliveries, springOutputs, springSequences, springSubscriptions, springDeliveries);
    }

    @Test void missingCompletionDoesNotCommitRollbackCloseOrNotifyTheBorrowedTransaction() {
        borrowedMappers();
        when(sequences.reserve(7L, "session", 1)).thenReturn(1L);
        when(sequences.recordProjectionCompletion(7L, "session", source, "{}")).thenReturn(0);
        try (var observer = mailbox.observeOutputs("7", "session")) {
            var error = assertThrows(IllegalStateException.class, () -> mailbox.projectOutputsBorrowed(
                    sql, "7", "session", source, checkpoint -> new SessionOutboxProjectionBatch("{}", List.of(command("output-1")))));
            assertEquals("projection_checkpoint_not_recorded", error.getMessage());
            assertEquals(0, observer.version());
            verify(sql, never()).commit(); verify(sql, never()).rollback(); verify(sql, never()).close();
            verify(sql, never()).getConnection();
        }
    }

    @Test void borrowedGapPagesMayReenterTheSameSessionBeforeCompletingCurrentSource() {
        borrowedMappers();
        when(sequences.reserve(7L, "session", 1)).thenReturn(1L, 2L);
        var current = new SessionOutboxProjectionSource(2, "source-2", "policy");
        try (var observer = mailbox.observeOutputs("7", "session")) {
            var rows = mailbox.projectOutputsBorrowed(sql, "7", "session", current, checkpoint -> {
                var gap = mailbox.projectOutputsBorrowed(sql, "7", "session", source,
                        ignored -> new SessionOutboxProjectionBatch("{}", List.of(command("gap-output"))));
                assertEquals(1L, gap.getFirst().getSessionSequence());
                return new SessionOutboxProjectionBatch("{}", List.of(command("current-output")));
            });
            assertEquals(2L, rows.getFirst().getSessionSequence());
            var order = inOrder(sequences);
            order.verify(sequences, times(2)).ensure(7L, "session");
            order.verify(sequences).reserve(7L, "session", 1);
            order.verify(sequences).recordProjectionCompletion(7L, "session", source, "{}");
            order.verify(sequences).reserve(7L, "session", 1);
            order.verify(sequences).recordProjectionCompletion(7L, "session", current, "{}");
            assertEquals(0, observer.version());
        }
    }

    @Test void commitNotificationIsScopedAndDoesNotAccessPersistence() {
        try (var same = mailbox.observeOutputs("7", "session");
                var foreignTenant = mailbox.observeOutputs("8", "session");
                var otherSession = mailbox.observeOutputs("7", "other")) {
            mailbox.outputsCommitted("7", "session");
            assertEquals(1, same.version()); assertEquals(0, foreignTenant.version()); assertEquals(0, otherSession.version());
            verifyNoInteractions(sql, outputs, sequences, subscriptions, deliveries,
                    springOutputs, springSequences, springSubscriptions, springDeliveries);
        }
    }

    /** 合法输出使用稳定事件身份；payload不参与事务身份或幂等判定。 */
    private static SessionOutboxOutput command(String eventId) {
        return new SessionOutboxOutput(eventId, "message.delta", Map.of("content", eventId), null, null);
    }

    /** 新会话没有处理回执或成功水位。 */
    private static SessionOutboxProjectionState emptyState() {
        return new SessionOutboxProjectionState(0, null, null, null, null, null, null, null, null);
    }
}
