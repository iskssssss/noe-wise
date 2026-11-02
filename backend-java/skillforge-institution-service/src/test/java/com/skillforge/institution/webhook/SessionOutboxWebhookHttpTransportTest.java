package com.skillforge.institution.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.chat.session.SessionWebhookTarget;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.session.SessionOutboxEventProjector;
import com.skillforge.core.chat.dto.chat.SessionOutboxCursorResponse;
import com.skillforge.core.shared.config.BusinessTimeWebConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.shared.security.WebhookSignature;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.chat.session.SessionWebhookTransportException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** HTTP 适配器对冻结事件签名，并只泄露稳定的 HTTP 失败分类。 */
class SessionOutboxWebhookHttpTransportTest {

    private final WebhookHttpClient client = mock(WebhookHttpClient.class);
    private final CryptoService crypto = crypto();
    private final SessionOutboxWebhookHttpTransport transport =
            new SessionOutboxWebhookHttpTransport(client, crypto);

    @Test
    void postsProjectedEventWithVerifiableSignatureAndIdempotencyKey() throws Exception {
        when(client.post(anyString(), anyString(), any())).thenReturn(202);
        SessionWebhookTarget subscription = subscription();

        transport.deliver(subscription, output());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(client).post(org.mockito.Mockito.eq("https://events.example.test/hook"),
                body.capture(), headers.capture());
        assertEquals("event-1", headers.getValue().get("Idempotency-Key"));
        String timestamp = headers.getValue().get("X-SkillForge-Timestamp");
        assertEquals("sha256=" + WebhookSignature.sign("signing-secret", timestamp, body.getValue()),
                headers.getValue().get("X-SkillForge-Signature"));
        org.junit.jupiter.api.Assertions.assertTrue(body.getValue().contains("\"event_id\":\"event-1\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.getValue().contains("\"occurred_at\":\"2026-10-03T08:00:00+08:00\""));
        var json = BusinessTimeWebConfiguration.httpMapper(new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
        var delivered = json.readTree(body.getValue());
        var projected = SessionOutboxEventProjector.project(output());
        var cursor = new SessionOutboxCursorResponse.Event(projected.event(), projected.eventId(), projected.sessionId(),
                projected.sessionSequence(), projected.inputId(), projected.rootInputId(), projected.causedByEventId(),
                projected.eventVersion(), projected.occurredAt(), projected.data());
        assertEquals(json.readTree(json.writeValueAsString(cursor)), delivered);
        assertEquals(json.readTree(json.writeValueAsString(projected.data())), delivered.path("data"));
        assertEquals("input-1", delivered.path("input_id").asText());
        assertEquals("root-1", delivered.path("root_input_id").asText());
        assertEquals("cause-1", delivered.path("caused_by_event_id").asText());
    }

    @Test
    void classifiesTransientAndPermanentHttpStatusesWithoutResponseBody() throws Exception {
        when(client.post(anyString(), anyString(), any())).thenReturn(503, 401);

        SessionWebhookTransportException transientFailure = assertThrows(SessionWebhookTransportException.class,
                () -> transport.deliver(subscription(), output()));
        assertEquals("WEBHOOK_HTTP_503", transientFailure.errorCode());
        org.junit.jupiter.api.Assertions.assertTrue(transientFailure.retryable());

        SessionWebhookTransportException permanentFailure = assertThrows(SessionWebhookTransportException.class,
                () -> transport.deliver(subscription(), output()));
        assertEquals("WEBHOOK_HTTP_401", permanentFailure.errorCode());
        org.junit.jupiter.api.Assertions.assertFalse(permanentFailure.retryable());
    }

    @Test
    void rejectsMissingSubscriptionSecretsBeforeNetwork() {
        SessionWebhookTarget invalid = new SessionWebhookTarget("https://events.example.test/hook", null);

        SessionWebhookTransportException failure = assertThrows(SessionWebhookTransportException.class,
                () -> transport.deliver(invalid, output()));

        assertEquals("WEBHOOK_SUBSCRIPTION_INVALID", failure.errorCode());
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    private SessionWebhookTarget subscription() {
        return new SessionWebhookTarget("https://events.example.test/hook", crypto.encrypt("signing-secret"));
    }

    private SessionOutboxEntry output() {
        var outputBuilder = SessionOutboxEntry.builder();
        outputBuilder.sessionId("session-1");
        outputBuilder.eventId("event-1");
        outputBuilder.sessionSequence(3L);
        outputBuilder.inputId("input-1");
        outputBuilder.rootInputId("root-1");
        outputBuilder.causedByEventId("cause-1");
        outputBuilder.createdAt(java.time.Instant.parse("2026-10-03T00:00:00Z"));
        outputBuilder.payload(Map.of("event", "message.completed", "data", Map.of("answer", "ok")));
        var output = outputBuilder.build();
        return output;
    }

    private CryptoService crypto() {
        SkillForgeProperties properties = new SkillForgeProperties();
        properties.setSecretKey("synthetic-webhook-test-master-key-32");
        return new CryptoService(properties);
    }
}
