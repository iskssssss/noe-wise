package com.skillforge.core.billing.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpExchangeRateProviderTest {

    private HttpServer server;

    /** 上游非成功响应即使回显凭据，业务降级日志也不得保存响应体或异常消息。 */
    @Test
    void httpFailureDoesNotLogSensitiveResponseBody() {
        String marker = "synthetic-rate-secret-3f812";
        server.createContext("/sensitive-failure", exchange -> respond(exchange, 503, marker));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(HttpExchangeRateProvider.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            var provider = new HttpExchangeRateProvider(new ObjectMapper(),
                    settings(true, endpoint("/sensitive-failure"), marker, "Authorization"),
                    RestClient.builder().build());
            assertTrue(provider.toCny("USD", Instant.now()).isEmpty());
            assertTrue(!logs.list.isEmpty(), "失败应有安全诊断");
            for (var event : logs.list) {
                assertTrue(!event.getFormattedMessage().contains(marker));
                if (event.getThrowableProxy() != null) {
                    assertTrue(!ch.qos.logback.classic.spi.ThrowableProxyUtil
                            .asString(event.getThrowableProxy()).contains(marker));
                }
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsAuditableSnapshotAndSendsConfiguredQueryAndApiKey() {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        server.createContext("/rates", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            apiKey.set(exchange.getRequestHeaders().getFirst("X-Rate-Key"));
            respond(exchange, 200, "{\"rate\":\"7.123456789012\","
                    + "\"observed_at\":\"2026-08-25T01:02:03Z\","
                    + "\"snapshot_version\":\"provider-v42\"}");
        });

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/rates?base={currency}&at={occurredAt}";

        HttpExchangeRateProvider provider = new HttpExchangeRateProvider(
                new ObjectMapper(), settings(true, endpoint, "secret", "X-Rate-Key"),
                RestClient.builder().build());
        var rate = provider.toCny("usd", Instant.parse("2026-08-25T00:00:00Z"));

        assertTrue(rate.isPresent());
        assertEquals("7.123456789012", rate.get().toCny().toPlainString());
        assertEquals("provider-v42", rate.get().snapshotVersion());
        assertTrue(query.get().contains("base=USD"));
        assertTrue(query.get().contains("at=2026-08-25T00:00:00Z"));
        assertEquals("secret", apiKey.get());
    }

    @Test
    void missingAuditFieldsAndHttpFailuresReturnEmpty() {
        server.createContext("/incomplete", exchange -> respond(exchange, 200,
                "{\"rate\":\"7.1\",\"observed_at\":\"2026-08-25T01:02:03Z\"}"));
        server.createContext("/failed", exchange -> respond(exchange, 503, "unavailable"));

        HttpExchangeRateProvider incomplete = new HttpExchangeRateProvider(
                new ObjectMapper(), settings(true, endpoint("/incomplete"), "", "Authorization"),
                RestClient.builder().build());
        HttpExchangeRateProvider failed = new HttpExchangeRateProvider(
                new ObjectMapper(), settings(true, endpoint("/failed"), "", "Authorization"),
                RestClient.builder().build());

        assertTrue(incomplete.toCny("USD", Instant.now()).isEmpty());
        assertTrue(failed.toCny("USD", Instant.now()).isEmpty());
    }

    @Test
    void disabledProviderDoesNotMakeARequest() {
        HttpExchangeRateProvider provider = new HttpExchangeRateProvider(
                new ObjectMapper(), settings(false, "http://127.0.0.1:1/unreachable", "", "Authorization"),
                RestClient.builder().build());

        assertTrue(provider.toCny("USD", Instant.now()).isEmpty());
    }

    private OperationalSettingsService settings(
            boolean enabled, String endpoint, String apiKey, String header) {
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        when(settings.read("billing")).thenReturn(new Snapshot(1, java.util.Map.of(
                "exchange_rate_enabled", enabled,
                "exchange_rate_endpoint", endpoint,
                "exchange_rate_api_key", apiKey,
                "exchange_rate_api_key_header", header)));
        return settings;
    }

    private String endpoint(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
