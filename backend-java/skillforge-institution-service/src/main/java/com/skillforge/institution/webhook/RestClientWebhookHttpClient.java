package com.skillforge.institution.webhook;

import com.skillforge.core.shared.sandbox.SandboxGuard;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.springframework.stereotype.Component;

/** 使用 SSRF 守卫后的 RestClient 执行单次 Webhook POST。 */
@Component
public class RestClientWebhookHttpClient implements WebhookHttpClient {

    private final SandboxGuard sandboxGuard;

    public RestClientWebhookHttpClient(SandboxGuard sandboxGuard) {
        this.sandboxGuard = sandboxGuard;
    }

    @Override
    public int post(String endpointUrl, String body, Map<String, String> headers) throws IOException {
        try {
            return RestClient.create().post().uri(sandboxGuard.assertSafe(endpointUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(target -> headers.forEach(target::set))
                    .body(body)
                    .retrieve().toBodilessEntity().getStatusCode().value();
        } catch (RestClientResponseException response) {
            return response.getStatusCode().value();
        } catch (RestClientException response) {
            throw new IOException("Webhook HTTP transport failed", response);
        }
    }
}
