package com.skillforge.institution.webhook;

import java.io.IOException;
import java.util.Map;

/** Webhook 的最小 HTTP 边界，便于在发布器单测中隔离真实网络。 */
@FunctionalInterface
public interface WebhookHttpClient {

    int post(String endpointUrl, String body, Map<String, String> headers) throws IOException;
}
