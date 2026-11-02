package com.skillforge.core.chat.session;

import com.skillforge.core.foundation.application.CryptoService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import com.skillforge.core.shared.sandbox.SandboxGuard;
import java.util.List;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;

/** 订阅配置必须加密密钥、限定租户并可安全暂停。 */
class SessionWebhookSubscriptionServiceTest {

    private final SessionEventSubscriptionMapper mapper = mock(SessionEventSubscriptionMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final SandboxGuard sandbox = mock(SandboxGuard.class);
    private final SessionWebhookSubscriptionService service = new SessionWebhookSubscriptionService(mapper, crypto, sandbox);

    @Test
    void createsActiveSubscriptionWithEncryptedSecret() {
        when(crypto.encrypt("secret")).thenReturn("ciphertext");
        when(sandbox.assertSafe("https://events.example.test/hook"))
                .thenReturn("https://events.example.test/hook");
        var persisted = new AtomicReference<SessionEventSubscriptionDO>();
        Instant storedTime = Instant.parse("2026-10-13T00:00:00.234567Z");
        when(mapper.insert(any(SessionEventSubscriptionDO.class))).thenAnswer(invocation -> {
            var stored = new SessionEventSubscriptionDO();
            BeanUtils.copyProperties(invocation.getArgument(0), stored);
            stored.setCreatedAt(storedTime); stored.setUpdatedAt(storedTime);
            persisted.set(stored);
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.selectList(any())).thenAnswer(invocation -> List.of(persisted.get()));

        SessionWebhookSubscription created = service.create("7", "session-1",
                "https://events.example.test/hook", "secret");

        ArgumentCaptor<SessionEventSubscriptionDO> capture = ArgumentCaptor.forClass(SessionEventSubscriptionDO.class);
        verify(mapper).insert(capture.capture());
        assertEquals(7L, capture.getValue().getTenantId());
        assertEquals(capture.getValue().getId(), created.subscriptionId());
        assertEquals("session-1", capture.getValue().getSessionId());
        assertEquals("ciphertext", capture.getValue().getSigningSecretEncrypted());
        assertEquals(SessionWebhookDeliveryLedger.ACTIVE, capture.getValue().getStatus());
        assertEquals(storedTime, created.createdAt());
        assertEquals(service.list("7").getFirst(), created);
        assertTrue(java.util.Arrays.stream(SessionWebhookSubscription.class.getRecordComponents())
                .noneMatch(component -> component.getName().toLowerCase(java.util.Locale.ROOT).contains("secret")));
        verify(crypto).encrypt("secret");
        var lookup = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectOne(lookup.capture());
        assertTrue(lookup.getValue().getSqlSegment().contains("tenant_id"));
        assertEquals(Set.of(7L, capture.getValue().getId()), Set.copyOf(lookup.getValue().getParamNameValuePairs().values()));
    }

    @Test
    void rejectsMalformedEndpointAndBlankSecretBeforeWriting() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("7", null, "ftp://events.example.test/hook", "secret"));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("7", null, "https://events.example.test/hook", ""));
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void listsAndPausesOnlyCurrentTenantSubscriptions() {
        SessionEventSubscriptionDO row = new SessionEventSubscriptionDO();
        row.setId(12L);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.update(any(), any())).thenReturn(1);

        assertEquals(List.of(new SessionWebhookSubscription(12L, null, null, null, null)), service.list("7"));
        assertTrue(service.pause("7", 12L));
        verify(mapper).update(any(), any());
    }
}
