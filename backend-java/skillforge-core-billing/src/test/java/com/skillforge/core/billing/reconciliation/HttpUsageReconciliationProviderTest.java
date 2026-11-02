package com.skillforge.core.billing.reconciliation;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpUsageReconciliationProviderTest {

    private static final Instant FROM = Instant.parse("2026-08-25T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-25T01:00:00Z");

    private HttpServer server;

    /** 上游响应体回显合成凭据时仍保留失败游标，且诊断不记录敏感文本。 */
    @Test
    void httpFailureKeepsCursorWithoutLoggingSensitiveResponseBody() {
        String marker = "synthetic-reconciliation-secret-5bd13";
        server.createContext("/sensitive-failure", exchange -> respond(exchange, 503, marker));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(HttpUsageReconciliationProvider.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            var provider = new HttpUsageReconciliationProvider(new ObjectMapper(),
                    settings(true, endpoint("/sensitive-failure"), marker, "Authorization"),
                    RestClient.builder().build());
            var batch = provider.pull("7", FROM, TO, "cursor-1");
            assertTrue(batch.facts().isEmpty());
            assertEquals("cursor-1", batch.nextCursor());
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
    void parsesPreciseFactsAndKeepsCursorAndTenantQuery() {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        server.createContext("/billing", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            apiKey.set(exchange.getRequestHeaders().getFirst("X-Billing-Key"));
            respond(exchange, 200, "{\"facts\":[{"
                    + "\"event_id\":\"bill-1\",\"original_event_id\":101,"
                    + "\"amount_status\":\"priced\",\"total_amount\":\"1.230000000001\","
                    + "\"exchange_rate_to_cny\":\"7.2\",\"pricing_snapshot_version\":\"vendor-v3\","
                    + "\"currency\":\"USD\",\"metadata\":{\"invoice\":\"i-1\"}}],"
                    + "\"next_cursor\":\"cursor-2\"}");
        });

        OperationalSettingsService settings = settings(true,
                endpoint("/billing?tenant={tenantId}&from={from}&to={to}&cursor={cursor}"),
                "secret", "X-Billing-Key");
        HttpUsageReconciliationProvider provider = new HttpUsageReconciliationProvider(
                new ObjectMapper(), settings, RestClient.builder().build());

        UsageReconciliationProvider.Batch batch = provider.pull("tenant-7", FROM, TO, "cursor-1");

        assertEquals("cursor-2", batch.nextCursor());
        assertEquals(1, batch.facts().size());
        assertEquals("bill-1", batch.facts().getFirst().eventId());
        assertEquals(new java.math.BigDecimal("1.230000000001"), batch.facts().getFirst().totalAmount());
        assertEquals("i-1", batch.facts().getFirst().metadata().get("invoice"));
        assertTrue(query.get().contains("tenant=tenant-7"));
        assertTrue(query.get().contains("cursor=cursor-1"));
        assertEquals("secret", apiKey.get());
    }

    @Test
    void rejectsMalformedFactsWithoutAdvancingCursorAndHandlesHttpFailure() {
        server.createContext("/bad", exchange -> respond(exchange, 200,
                "{\"facts\":[{\"event_id\":\"bad\",\"original_event_id\":0,"
                        + "\"amount_status\":\"priced\"}],\"next_cursor\":\"vendor-next\"}"));
        server.createContext("/failed", exchange -> respond(exchange, 503, "unavailable"));

        HttpUsageReconciliationProvider bad = new HttpUsageReconciliationProvider(
                new ObjectMapper(), settings(true, endpoint("/bad"), "", "Authorization"),
                RestClient.builder().build());
        HttpUsageReconciliationProvider failed = new HttpUsageReconciliationProvider(
                new ObjectMapper(), settings(true, endpoint("/failed"), "", "Authorization"),
                RestClient.builder().build());

        UsageReconciliationProvider.Batch rejected = bad.pull("7", FROM, TO, "cursor-1");
        UsageReconciliationProvider.Batch unavailable = failed.pull("7", FROM, TO, "cursor-1");

        assertTrue(rejected.facts().isEmpty());
        assertEquals("cursor-1", rejected.nextCursor());
        assertTrue(unavailable.facts().isEmpty());
        assertEquals("cursor-1", unavailable.nextCursor());
    }

    @Test
    void disabledProviderIsAnEmptyPageWithoutNetworkAccess() {
        HttpUsageReconciliationProvider provider = new HttpUsageReconciliationProvider(
                new ObjectMapper(), settings(false, "http://127.0.0.1:1/unreachable", "", "Authorization"),
                RestClient.builder().build());

        UsageReconciliationProvider.Batch batch = provider.pull("7", FROM, TO, "cursor-1");

        assertTrue(batch.facts().isEmpty());
        assertEquals("cursor-1", batch.nextCursor());
    }

    @Test
    void tenantProviderOverridesGlobalEndpointAndMapsRestrictedResponseSchema() {
        AtomicReference<String> apiKey = new AtomicReference<>();
        server.createContext("/tenant-billing", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("X-Tenant-Key"));
            respond(exchange, 200, "{\"data\":{\"records\":[{"
                    + "\"id\":\"vendor-1\",\"source_event\":101,"
                    + "\"state\":\"priced\",\"amount\":\"2.50\","
                    + "\"meta\":{\"invoice\":\"inv-1\"}}]},"
                    + "\"page\":{\"cursor\":\"tenant-next\"}}");
        });

        OperationalSettingsService settings = settings(true, endpoint("/global-not-used"),
                "", "Authorization");
        TenantUsageReconciliationConfigService tenantConfig = mock(
                TenantUsageReconciliationConfigService.class);
        when(tenantConfig.resolveForProvider("7"))
                .thenReturn(new TenantUsageReconciliationConfigService.ResolvedTenantConfig(
                        "http", "http://127.0.0.1:" + server.getAddress().getPort()
                                + "/tenant-billing?tenant={tenantId}&cursor={cursor}",
                        "tenant-secret", "X-Tenant-Key", Map.of(
                                "facts_path", "data.records",
                                "next_cursor", "page.cursor",
                                "event_id", "id",
                                "original_event_id", "source_event",
                                "amount_status", "state",
                                "total_amount", "amount",
                                "metadata", "meta")));
        HttpUsageReconciliationProvider provider = new HttpUsageReconciliationProvider(
                new ObjectMapper(), settings, RestClient.builder().build(), tenantConfig);

        UsageReconciliationProvider.Batch batch = provider.pull("7", FROM, TO, "cursor-1");

        assertEquals("tenant-next", batch.nextCursor());
        assertEquals(1, batch.facts().size());
        assertEquals("vendor-1", batch.facts().getFirst().eventId());
        assertEquals(101L, batch.facts().getFirst().originalEventId());
        assertEquals(new java.math.BigDecimal("2.50"), batch.facts().getFirst().totalAmount());
        assertEquals("inv-1", batch.facts().getFirst().metadata().get("invoice"));
        assertEquals("tenant-secret", apiKey.get());
    }

    private OperationalSettingsService settings(
            boolean enabled, String endpoint, String apiKey, String header) {
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        when(settings.read("billing")).thenReturn(new Snapshot(1, Map.of(
                "usage_reconciliation_enabled", enabled,
                "usage_reconciliation_endpoint", endpoint,
                "usage_reconciliation_api_key", apiKey,
                "usage_reconciliation_api_key_header", header)));
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
