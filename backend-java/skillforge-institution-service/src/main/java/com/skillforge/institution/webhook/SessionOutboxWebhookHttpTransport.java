package com.skillforge.institution.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.session.SessionWebhookTarget;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.shared.security.WebhookSignature;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.chat.session.SessionOutboxEventProjector;
import com.skillforge.core.chat.session.SessionWebhookTransport;
import com.skillforge.core.chat.session.SessionWebhookTransportException;
import com.skillforge.core.shared.config.BusinessTimeWebConfiguration;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 将冻结事件按统一投影、HMAC 签名发送到经审批的第三方 Webhook。 */
@Component
public class SessionOutboxWebhookHttpTransport implements SessionWebhookTransport {

    private static final ObjectMapper JSON = BusinessTimeWebConfiguration.httpMapper(new ObjectMapper());

    private final WebhookHttpClient client;
    private final CryptoService crypto;

    public SessionOutboxWebhookHttpTransport(WebhookHttpClient client, CryptoService crypto) {
        this.client = client;
        this.crypto = crypto;
    }

    @Override
    public void deliver(SessionWebhookTarget subscription, SessionOutboxEntry output)
            throws SessionWebhookTransportException {
        if (subscription == null || subscription.endpointUrl() == null
                || subscription.endpointUrl().isBlank()
                || subscription.signingSecretEncrypted() == null
                || subscription.signingSecretEncrypted().isBlank()) {
            throw new SessionWebhookTransportException("WEBHOOK_SUBSCRIPTION_INVALID", false);
        }
        try {
            SessionOutboxEventProjector.Event event = SessionOutboxEventProjector.project(output);
            String body = JSON.writeValueAsString(event);
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String secret = crypto.decrypt(subscription.signingSecretEncrypted());
            Map<String, String> headers = Map.of(
                    "X-SkillForge-Event-Id", event.eventId(),
                    "X-SkillForge-Session-Id", event.sessionId(),
                    "X-SkillForge-Timestamp", timestamp,
                    "X-SkillForge-Signature", "sha256=" + WebhookSignature.sign(secret, timestamp, body),
                    "Idempotency-Key", event.eventId());
            int status = client.post(subscription.endpointUrl(), body, headers);
            if (status >= 200 && status < 300) {
                return;
            }
            throw new SessionWebhookTransportException("WEBHOOK_HTTP_" + status,
                    status == 408 || status == 429 || status >= 500);
        } catch (SessionWebhookTransportException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new SessionWebhookTransportException("WEBHOOK_TRANSPORT_FAILED", true, failure);
        } catch (Exception failure) {
            throw new SessionWebhookTransportException("WEBHOOK_SUBSCRIPTION_INVALID", false, failure);
        }
    }

}
