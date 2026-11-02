package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionOutboxSequenceMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionMailboxCursorTest {
    private final SessionOutboxMapper outputs = mock(SessionOutboxMapper.class);
    private final SessionMailboxService mailbox = new SessionMailboxService(outputs,
            mock(SessionOutboxSequenceMapper.class), mock(SessionWebhookDeliveryLedger.class));

    @Test void validatedCursorIsResolvedOnceAndSequenceReadsKeepTenantAndSessionScope() {
        when(outputs.outputSequence(7L, "session-1", "opaque-event-id")).thenReturn(17L);
        when(outputs.selectList(any())).thenAnswer(invocation -> {
            QueryWrapper<?> query = invocation.getArgument(0);
            query.getSqlSegment();
            assertTrue(query.getParamNameValuePairs().containsValue(7L));
            assertTrue(query.getParamNameValuePairs().containsValue("session-1"));
            assertTrue(query.getParamNameValuePairs().containsValue(17L));
            return List.of();
        });
        long sequence = mailbox.resolveOutputSequence("7", "session-1", "opaque-event-id");
        mailbox.listOutputsAfterSequence("7", "session-1", sequence, 100);
        mailbox.listOutputsAfterSequence("7", "session-1", sequence, 100);
        verify(outputs, times(1)).outputSequence(7L, "session-1", "opaque-event-id");
        verify(outputs, never()).selectOne(any());
        verify(outputs, times(2)).selectList(any());
    }

    @Test void unknownOrForeignCursorIsRejectedBeforeObservingAndEmptyCursorStartsAtZero() {
        assertEquals(0, mailbox.resolveOutputSequence("7", "session-1", null));
        assertEquals(0, mailbox.resolveOutputSequence("7", "session-1", "  "));
        verifyNoInteractions(outputs);
        when(outputs.outputSequence(7L, "session-1", "foreign-cursor")).thenReturn(null);
        var missing = assertThrows(ApiException.class, () -> mailbox.resolveOutputSequence("7", "session-1", "foreign-cursor"));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, missing.getStatus());
        assertEquals("EVENT_CURSOR_EXPIRED", missing.getCode());
        verify(outputs).outputSequence(7L, "session-1", "foreign-cursor");
        when(outputs.outputSequence(7L, "session-1", "null-sequence")).thenReturn(null);
        var nullSequence = assertThrows(ApiException.class, () -> mailbox.resolveOutputSequence("7", "session-1", "null-sequence"));
        assertEquals("EVENT_CURSOR_EXPIRED", nullSequence.getCode());
        verify(outputs, never()).selectOne(any());
        verify(outputs, never()).selectList(any());
        assertThrows(IllegalArgumentException.class, () -> mailbox.listOutputsAfterSequence("7", "session-1", -1, 100));
    }

    @Test void historyCursorReadsCommittedOutputWithinExactSessionScopeWithoutClaimingDelivery() {
        when(outputs.committedCursor(7L,"session-1")).thenReturn("event-17");
        assertEquals("event-17",mailbox.committedCursor("7","session-1"));
        verify(outputs).committedCursor(7L,"session-1");
        verifyNoMoreInteractions(outputs);
    }

    @Test void recentMessagesUsesOneExactScopedQueryAndRejectsUnboundedLimits() {
        when(outputs.recentMessages(7L, "session-1", 100)).thenReturn(List.of());
        assertTrue(mailbox.recentMessages("7", "session-1", 100).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> mailbox.recentMessages("7", "session-1", 101));
        assertThrows(IllegalArgumentException.class, () -> mailbox.recentMessages("7", "session-1", 0));
        verify(outputs).recentMessages(7L, "session-1", 100);
        verifyNoMoreInteractions(outputs);
    }
}
