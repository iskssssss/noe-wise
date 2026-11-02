package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.dto.openplatform.CreateSessionWebhookSubscriptionRequest;
import com.skillforge.core.chat.dto.openplatform.SessionWebhookSubscriptionResponse;
import com.skillforge.core.chat.session.SessionWebhookSubscription;
import com.skillforge.core.chat.session.SessionWebhookSubscriptionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 管理 API 不得回显 Webhook 密钥或允许跨租户暂停订阅。 */
class SessionWebhookSubscriptionControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void createsListsAndPausesWithinCurrentTenantWithoutReturningSecret() {
        TenantContext.setTenantId("7");
        SessionWebhookSubscriptionService service = mock(SessionWebhookSubscriptionService.class);
        var row = new SessionWebhookSubscription(12L, "session-1", "https://events.example.test/hook",
                "active", Instant.parse("2026-08-24T12:00:00Z"));
        when(service.create("7", "session-1", "https://events.example.test/hook", "secret"))
                .thenReturn(row);
        when(service.list("7")).thenReturn(List.of(row));
        when(service.pause("7", 12L)).thenReturn(true);
        SessionWebhookSubscriptionController controller = new SessionWebhookSubscriptionController(service);

        SessionWebhookSubscriptionResponse created = controller.create(
                new CreateSessionWebhookSubscriptionRequest("session-1",
                        "https://events.example.test/hook", "secret")).data();

        assertEquals(12L, created.id());
        assertEquals("active", controller.list().data().getFirst().status());
        assertEquals(true, controller.pause(12L).get("paused"));
        verify(service).pause("7", 12L);
    }
}
