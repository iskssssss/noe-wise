package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionOutboxSequenceMapper;
import com.skillforge.core.chat.persistence.SessionOutboxEntryDO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 每批只分配新增身份；重放返回原行，普通单条也走同一写入路径。 */
class SessionMailboxBatchTest {
    private final SessionOutboxMapper outputs = mock(SessionOutboxMapper.class);
    private final SessionOutboxSequenceMapper sequences = mock(SessionOutboxSequenceMapper.class);
    private final SessionWebhookDeliveryLedger webhooks = mock(SessionWebhookDeliveryLedger.class);
    private final SessionMailboxService mailbox = new SessionMailboxService(outputs, sequences, webhooks);
    private final SessionOutboxProjectionSource source = new SessionOutboxProjectionSource(1, "source", "policy");

    @BeforeEach void lockedProjectionStartsWithoutReceiptOrCheckpoint() {
        when(sequences.lockProjection(7L, "session")).thenReturn(state(null, null));
    }

    @Test void batchReusesOldRowsAndReservesOnlyNewIdentitiesInFirstAppearanceOrder() {
        var old = new SessionOutboxEntryDO();
        old.setId(10L);
        old.setTenantId(7L);
        old.setSessionId("session");
        old.setEventId("old");
        old.setSessionSequence(1L);
        old.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
        when(outputs.findEvents(eq(7L), any(String[].class))).thenReturn(List.of(old));
        when(sequences.reserve(7L, "session", 2)).thenReturn(3L);
        when(outputs.insertOutputs(any())).thenAnswer(call -> call.<List<?>>getArgument(0).size());
        when(sequences.recordProjectionCompletion(eq(7L), eq("session"), eq(source), any())).thenReturn(1);

        var rows = mailbox.projectOutputsIncrementally("7", "session", source, checkpoint ->
                new SessionOutboxProjectionBatch("{}", List.of(command("new-1"), command("old"),
                        command("new-1"), command("new-2"))));

        assertEquals(List.of(2L, 1L, 2L, 3L), rows.stream().map(SessionOutboxEntry::getSessionSequence).toList());
        assertEquals(SessionMailboxService.view(old), rows.get(1));
        assertEquals(rows.getFirst(), rows.get(2));
        ArgumentCaptor<List<SessionOutboxEntryDO>> capture = ArgumentCaptor.forClass(List.class);
        verify(outputs).insertOutputs(capture.capture());
        assertEquals(List.of("new-1", "new-2"), capture.getValue().stream().map(SessionOutboxEntryDO::getEventId).toList());
        verify(outputs).findEvents(eq(7L), any(String[].class));
        verify(sequences).ensure(7L, "session");
        verify(sequences).lockProjection(7L, "session");
        verify(sequences, never()).lock(anyLong(), anyString());
        verify(sequences, never()).projectionRecord(anyLong(), anyString());
        verify(sequences, never()).projectionCheckpoint(anyLong(), anyString());
        verify(sequences).reserve(7L, "session", 2);
        verify(sequences).recordProjectionCompletion(7L, "session", source, "{}");
        verify(webhooks).enqueueBatch("7", capture.getValue());
    }

    @Test void emptyProjectionAdvancesCheckpointWithoutOutputSequenceOrSubscriptionWork() {
        when(sequences.recordProjectionCompletion(7L, "session", source, "{}")).thenReturn(1);
        assertEquals(List.of(), mailbox.projectOutputsIncrementally("7", "session", source,
                checkpoint -> new SessionOutboxProjectionBatch("{}", List.of())));
        verifyNoInteractions(outputs, webhooks);
        verify(sequences, never()).reserve(anyLong(), anyString(), anyInt());
        verify(sequences).recordProjectionCompletion(7L, "session", source, "{}");
    }

    @Test void coveredSourceUsesLockedCheckpointWithoutProjectingOrWriting() {
        var checkpoint = new SessionOutboxProjectionCheckpoint(1, "source", "policy", "{\"accepted\":true}");
        when(sequences.lockProjection(7L, "session")).thenReturn(state(null, checkpoint));
        assertEquals(List.of(), mailbox.projectOutputsIncrementally("7", "session", source, ignored -> {
            fail("covered source must not be projected again"); return null;
        }));
        verify(sequences).ensure(7L, "session");
        verify(sequences).lockProjection(7L, "session");
        verifyNoMoreInteractions(sequences);
        verifyNoInteractions(outputs, webhooks);
    }

    @Test void matchingPermanentRejectionStillWinsBeforeCoveredCheckpoint() {
        var rejection = new SessionOutboxProjectionRecord(1L, "source", "policy", "invalid");
        var checkpoint = new SessionOutboxProjectionCheckpoint(1, "source", "policy", "{}");
        when(sequences.lockProjection(7L, "session")).thenReturn(state(rejection, checkpoint));
        assertThrows(SessionOutboxProjectionRejectedException.class,
                () -> mailbox.projectOutputsIncrementally("7", "session", source, ignored -> {
                    fail("rejected source must not execute projection"); return null;
                }));
        verify(sequences, never()).recordProjectionCompletion(anyLong(), anyString(), any(), anyString());
        verifyNoInteractions(outputs, webhooks);
    }

    @Test void policyChangeResetsLockedCheckpointBeforeCompletingTheBatch() {
        var checkpoint = new SessionOutboxProjectionCheckpoint(9, "old-source", "old-policy", "{\"old\":true}");
        when(sequences.lockProjection(7L, "session")).thenReturn(state(null, checkpoint));
        when(sequences.recordProjectionCompletion(7L, "session", source, "{}")).thenReturn(1);
        mailbox.projectOutputsIncrementally("7", "session", source, current -> {
            assertEquals(SessionOutboxProjectionCheckpoint.empty("policy"), current);
            return new SessionOutboxProjectionBatch("{}", List.of());
        });
        verify(sequences).recordProjectionCompletion(7L, "session", source, "{}");
        verifyNoInteractions(outputs, webhooks);
    }

    @Test void missingSequenceLockFailsBeforeExecutingProjection() {
        when(sequences.lockProjection(7L, "session")).thenReturn(null);
        var error = assertThrows(IllegalStateException.class,
                () -> mailbox.projectOutputsIncrementally("7", "session", source, ignored -> {
                    fail("missing locked row must not execute projection"); return null;
                }));
        assertEquals("projection_sequence_missing", error.getMessage());
        verify(sequences, never()).recordProjectionCompletion(anyLong(), anyString(), any(), anyString());
        verifyNoInteractions(outputs, webhooks);
    }

    @Test void missingCompletionUpdateCannotReturnAnUnrecordedCheckpoint() {
        var error = assertThrows(IllegalStateException.class,
                () -> mailbox.projectOutputsIncrementally("7", "session", source,
                        checkpoint -> new SessionOutboxProjectionBatch("{}", List.of())));
        assertEquals("projection_checkpoint_not_recorded", error.getMessage());
    }

    @Test void ordinaryAppendUsesTheSameBatchLookupReservationAndInsert() {
        when(outputs.findEvents(eq(7L), any(String[].class))).thenReturn(List.of());
        when(sequences.reserve(7L, "session", 1)).thenReturn(1L);
        when(outputs.insertOutputs(any())).thenReturn(1);
        var row = mailbox.appendOutput("7", "session", "single", "message.delta", Map.of("content", "hello"));
        assertEquals(1L, row.getSessionSequence());
        verify(outputs).findEvents(eq(7L), any(String[].class));
        verify(sequences).reserve(7L, "session", 1);
        ArgumentCaptor<List<SessionOutboxEntryDO>> capture = ArgumentCaptor.forClass(List.class);
        verify(outputs).insertOutputs(capture.capture());
        assertEquals(row, SessionMailboxService.view(capture.getValue().getFirst()));
        verify(webhooks).enqueueBatch("7", capture.getValue());
    }

    @Test void foreignStoredScopeIsRejectedBeforeRangeReservationOrWrites() {
        for (boolean foreignTenant : List.of(false, true)) {
            var old = new SessionOutboxEntryDO();
            old.setTenantId(foreignTenant ? 8L : 7L);
            old.setSessionId(foreignTenant ? "session" : "foreign");
            old.setEventId("old");
            when(outputs.findEvents(eq(7L), any(String[].class))).thenReturn(List.of(old));
            assertThrows(IllegalStateException.class, () -> mailbox.appendOutput("7", "session", "old", "message.delta", Map.of()));
        }
        verify(sequences, never()).reserve(anyLong(), anyString(), anyInt());
        verify(outputs, never()).insertOutputs(any());
        verifyNoInteractions(webhooks);
    }

    @Test void outputNotificationWaitsForCommitAndRollbackPublishesNothing() throws Exception {
        when(outputs.findEvents(eq(7L), any(String[].class))).thenReturn(List.of());
        when(sequences.reserve(7L, "session", 1)).thenReturn(1L);
        when(outputs.insertOutputs(any())).thenReturn(1);
        try (var subscription = mailbox.observeOutputs("7", "session")) {
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                mailbox.appendOutput("7", "session", "first", "message.delta", Map.of("content", "hello"));
                assertEquals(0, subscription.version());
                var callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
                callbacks.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
                assertTrue(subscription.awaitChange(0, java.time.Duration.ofNanos(1)));
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
            long committed = subscription.version();
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                mailbox.appendOutput("7", "session", "rolled-back", "message.delta", Map.of("content", "private"));
                org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().forEach(callback ->
                        callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
                assertEquals(committed, subscription.version());
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    private static SessionOutboxOutput command(String id) {
        return new SessionOutboxOutput(id, "message.delta", Map.of("content", id), null, null);
    }

    private static SessionOutboxProjectionState state(SessionOutboxProjectionRecord record,
            SessionOutboxProjectionCheckpoint checkpoint) {
        return new SessionOutboxProjectionState(0, record == null ? null : record.sourceSequence(),
                record == null ? null : record.sourceEventId(), record == null ? null : record.policyVersion(),
                record == null ? null : record.errorCode(), checkpoint == null ? null : checkpoint.sequence(),
                checkpoint == null ? null : checkpoint.eventId(), checkpoint == null ? null : checkpoint.policyVersion(),
                checkpoint == null ? null : checkpoint.stateJson());
    }
}
