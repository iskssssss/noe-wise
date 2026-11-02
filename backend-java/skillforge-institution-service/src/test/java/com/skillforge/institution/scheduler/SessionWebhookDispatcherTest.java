package com.skillforge.institution.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.chat.session.SessionWebhookDeliveryLedger;
import com.skillforge.core.chat.session.SessionWebhookPublicationResult;
import com.skillforge.core.chat.session.SessionWebhookPublicationService;
import com.skillforge.core.chat.session.SessionWebhookTransport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 调度器只发现租户与订阅，顺序领取仍交给 Webhook 发布服务。 */
class SessionWebhookDispatcherTest {

    @AfterEach
    void cleanTenant() {
        TenantContext.clear();
    }

    @Test
    void drainsDeliveredSubscriptionUntilNoReadyDeliveryAndClearsTenant() {
        TenantMapper tenants = mock(TenantMapper.class);
        SessionWebhookDeliveryLedger ledger = mock(SessionWebhookDeliveryLedger.class);
        SessionWebhookPublicationService publication = mock(SessionWebhookPublicationService.class);
        SessionWebhookTransport transport = mock(SessionWebhookTransport.class);
        TenantDO tenant = new TenantDO();
        tenant.setId(7L);
        when(tenants.selectList(any())).thenReturn(List.of(tenant));
        when(ledger.listActiveSubscriptionIds("7", 100)).thenReturn(List.of(12L));
        when(ledger.listSessionsWithPendingDelivery("7", 12L, 100)).thenReturn(List.of("session-1"));
        when(publication.publishNext(anyString(), anyLong(), anyString(), any(), any())).thenReturn(
                new SessionWebhookPublicationResult(9L, SessionWebhookPublicationResult.Status.DELIVERED),
                SessionWebhookPublicationResult.noReadyDelivery());

        new SessionWebhookDispatcher(tenants, ledger, publication, transport).dispatchReadyDeliveries();

        verify(publication, times(2)).publishNext(anyString(), anyLong(), anyString(), any(), any());
        org.junit.jupiter.api.Assertions.assertNull(TenantContext.getTenantId());
    }

    @Test
    void invalidTenantAndNonDeliveredResultDoNotStartAnotherAttempt() {
        TenantMapper tenants = mock(TenantMapper.class);
        SessionWebhookDeliveryLedger ledger = mock(SessionWebhookDeliveryLedger.class);
        SessionWebhookPublicationService publication = mock(SessionWebhookPublicationService.class);
        SessionWebhookTransport transport = mock(SessionWebhookTransport.class);
        TenantDO valid = new TenantDO();
        valid.setId(7L);
        when(tenants.selectList(any())).thenReturn(java.util.Arrays.asList(null, new TenantDO(), valid));
        when(ledger.listActiveSubscriptionIds("7", 100)).thenReturn(List.of(12L));
        when(ledger.listSessionsWithPendingDelivery("7", 12L, 100)).thenReturn(List.of("session-1"));
        when(publication.publishNext(anyString(), anyLong(), anyString(), any(), any())).thenReturn(
                new SessionWebhookPublicationResult(9L, SessionWebhookPublicationResult.Status.RETRY_SCHEDULED));

        new SessionWebhookDispatcher(tenants, ledger, publication, transport).dispatchReadyDeliveries();

        verify(publication).publishNext(eq("7"), eq(12L), eq("session-1"), any(), any());
    }

    @Test
    void stalledDeliveryInOneSessionDoesNotBlockAnotherSessionInTheSameSubscription() {
        TenantMapper tenants = mock(TenantMapper.class);
        SessionWebhookDeliveryLedger ledger = mock(SessionWebhookDeliveryLedger.class);
        SessionWebhookPublicationService publication = mock(SessionWebhookPublicationService.class);
        SessionWebhookTransport transport = mock(SessionWebhookTransport.class);
        TenantDO tenant = new TenantDO();
        tenant.setId(7L);
        when(tenants.selectList(any())).thenReturn(List.of(tenant));
        when(ledger.listActiveSubscriptionIds("7", 100)).thenReturn(List.of(12L));
        when(ledger.listSessionsWithPendingDelivery("7", 12L, 100))
                .thenReturn(List.of("session-stalled", "session-ready"));
        when(publication.publishNext(anyString(), anyLong(), anyString(), any(), any())).thenReturn(
                new SessionWebhookPublicationResult(9L, SessionWebhookPublicationResult.Status.RETRY_SCHEDULED),
                SessionWebhookPublicationResult.noReadyDelivery());

        new SessionWebhookDispatcher(tenants, ledger, publication, transport).dispatchReadyDeliveries();

        verify(publication).publishNext(eq("7"), eq(12L), eq("session-stalled"), any(), any());
        verify(publication).publishNext(eq("7"), eq(12L), eq("session-ready"), any(), any());
    }
}
