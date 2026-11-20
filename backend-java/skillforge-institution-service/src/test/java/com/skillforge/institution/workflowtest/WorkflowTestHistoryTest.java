package com.skillforge.institution.workflowtest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.service.WorkflowManagementService;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Instant;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class WorkflowTestHistoryTest {
    private final WorkflowTestRegistry tests=mock(WorkflowTestRegistry.class);
    private final FoundationAccess access=mock(FoundationAccess.class);
    private final WorkflowManagementService management=mock(WorkflowManagementService.class);
    private final ExecutionStore store=mock(ExecutionStore.class);
    private final WorkflowTestSessionService service=new WorkflowTestSessionService(
            tests,null,access,management,null,null,null,null,null,null,null,null,null,store,null,
            new ObjectMapper(),new AgentRuntimeProperties(),mock(PlatformTransactionManager.class),null);

    @AfterEach void clear() {TenantContext.clear();}

    @Test void historyUsesTrustedOwnerAndBoundedCursorWithoutLoadingOrStartingRuns() {
        TenantContext.setTenantId("17");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"17","29"));
        var rows=LongStream.range(0,21).mapToObj(index->new WorkflowTestRegistry.HistoryItem(
                9007199254741100L-index,"draft",3L,null,"原测试 "+index,"closed",Instant.parse("2026-11-20T00:00:00Z"))).toList();
        when(tests.history(17,29,"flow",9007199254741200L,21)).thenReturn(rows);
        var page=service.history("flow",9007199254741200L);
        assertEquals(20,page.items().size());
        assertEquals("9007199254741081",page.nextBeforeId());
        assertEquals("9007199254741100",page.items().getFirst().testSessionId());
        verify(management).require(17,"flow","read");
        verify(tests).history(17,29,"flow",9007199254741200L,21);
        verifyNoMoreInteractions(tests);
        verifyNoInteractions(store);
    }

    @Test void mismatchedSubjectAndInvalidCursorNeverReachHistoryQuery() {
        TenantContext.setTenantId("17");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"18","29"));
        assertThrows(ApiException.class,()->service.history("flow",null));
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"17","29"));
        assertThrows(ApiException.class,()->service.history("flow",0L));
        verifyNoInteractions(tests,management,store);
    }
}
