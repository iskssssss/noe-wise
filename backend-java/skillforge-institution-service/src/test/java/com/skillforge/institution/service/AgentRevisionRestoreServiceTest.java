package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AgentRevisionRestoreServiceTest {
    private final AgentManagementService agents=mock(AgentManagementService.class);
    private final AgentRevisionRestoreReceiptMapper receipts=mock(AgentRevisionRestoreReceiptMapper.class);
    private final FoundationAccess access=mock(FoundationAccess.class);
    private final CapabilityCatalogDatabaseContext context=mock(CapabilityCatalogDatabaseContext.class);
    private final AgentRevisionRestoreService service=new AgentRevisionRestoreService(agents,receipts,context,access);
    private final UUID request=UUID.randomUUID();
    @BeforeEach void identity() {
        TenantContext.setTenantId("7");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","70"));
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void freshRequestRestoresOnceAndReplayReturnsOriginalTarget() {
        when(receipts.claim(7,request.toString(),"70","9",2)).thenReturn(1,0);
        when(receipts.find(7,request.toString())).thenReturn(new AgentRevisionRestoreReceiptMapper.Row("70","9",2));
        var first=service.restore("9",2,request);
        assertEquals(first,service.restore("9",2,request));
        assertEquals(new AgentRevisionRestoreService.Result(request,"9",2),first);
        verify(agents).restoreRevision("9",2);
        verify(access,times(2)).requireResource("agent","9","manage");
        verify(agents,times(2)).getAgentItem("9");
    }

    @Test void requestIdentityCannotChangeActorAgentOrRevision() {
        for(var row:java.util.List.of(new AgentRevisionRestoreReceiptMapper.Row("71","9",2),
                new AgentRevisionRestoreReceiptMapper.Row("70","10",2),
                new AgentRevisionRestoreReceiptMapper.Row("70","9",3))) {
            when(receipts.find(7,request.toString())).thenReturn(row);
            var failure=assertThrows(ApiException.class,()->service.restore("9",2,request));
            assertEquals("AGENT_RESTORE_IDENTITY_CONFLICT",failure.getCode());
        }
        verify(agents,never()).restoreRevision(anyString(),anyInt());
    }

    @Test void resultRequiresCurrentReadAndOriginalActorAndNeverRestores() {
        assertNull(service.result("9",request));
        when(receipts.find(7,request.toString())).thenReturn(new AgentRevisionRestoreReceiptMapper.Row("71","9",2));
        assertNull(service.result("9",request));
        when(receipts.find(7,request.toString())).thenReturn(new AgentRevisionRestoreReceiptMapper.Row("70","10",2));
        assertNull(service.result("9",request));
        when(receipts.find(7,request.toString())).thenReturn(new AgentRevisionRestoreReceiptMapper.Row("70","9",2));
        assertEquals(2,service.result("9",request).revision());
        verify(access,times(4)).require("foundation.agents.read");
        verify(agents,times(4)).getAgentItem("9");
        verify(receipts,never()).claim(anyLong(),anyString(),anyString(),anyString(),anyInt());
        verify(agents,never()).restoreRevision(anyString(),anyInt());
    }

    @Test void currentResourceDenialStopsReceiptReadAndClaim() {
        doThrow(new IllegalStateException("read_denied")).when(agents).getAgentItem("9");
        assertThrows(IllegalStateException.class,()->service.result("9",request));
        assertThrows(IllegalStateException.class,()->service.restore("9",2,request));
        verifyNoInteractions(receipts);
    }

    @Test void restoreFailureRollsBackTheReceiptClaimTransaction() {
        var transactions=mock(PlatformTransactionManager.class);
        var transaction=new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(transaction);
        var proxy=new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        var transactional=(AgentRevisionRestoreService)proxy.getProxy();
        when(receipts.claim(7,request.toString(),"70","9",2)).thenReturn(1);
        doThrow(new IllegalStateException("mount_restore_failed")).when(agents).restoreRevision("9",2);
        assertThrows(IllegalStateException.class,()->transactional.restore("9",2,request));
        verify(receipts).claim(7,request.toString(),"70","9",2);
        verify(transactions).rollback(transaction);
        verify(transactions,never()).commit(any());
    }
}
