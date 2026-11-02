package com.skillforge.core.billing.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.metering.AmountStatus;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 配置驱动的第三方账单 HTTP 适配器。
 *
 * <p>响应契约为 {@code {"facts":[...],"next_cursor":"..."}}。每条 fact 必须
 * 提供 {@code event_id}、{@code original_event_id} 和 {@code amount_status}；金额
 * 字段保持精确十进制，非法 fact 被拒绝而不会伪造成零金额。Provider 默认关闭，
 * 网络失败时返回不推进游标的空页，由上层保留重试机会。
 */
@Component
public class HttpUsageReconciliationProvider implements UsageReconciliationProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpUsageReconciliationProvider.class);

    private final ObjectMapper objectMapper;
    private final OperationalSettingsService settings;
    private final RestClient fixedClient;
    private final TenantUsageReconciliationConfigService tenantConfigService;
    private final String configuredProviderId;

    @Autowired
    public HttpUsageReconciliationProvider(ObjectMapper objectMapper,
                                           OperationalSettingsService settings,
                                           TenantUsageReconciliationConfigService tenantConfigService) {
        this(objectMapper, settings, null, tenantConfigService);
    }

    @Override
    public String providerId() {
        return configuredProviderId;
    }

    HttpUsageReconciliationProvider(ObjectMapper objectMapper, OperationalSettingsService settings,
                                    RestClient fixedClient) {
        this(objectMapper, settings, fixedClient, null);
    }

    HttpUsageReconciliationProvider(ObjectMapper objectMapper, OperationalSettingsService settings,
                                    RestClient fixedClient,
                                    TenantUsageReconciliationConfigService tenantConfigService) {
        this(objectMapper, settings, fixedClient, tenantConfigService, "http");
    }

    HttpUsageReconciliationProvider(ObjectMapper objectMapper, OperationalSettingsService settings,
                                    RestClient fixedClient,
                                    TenantUsageReconciliationConfigService tenantConfigService,
                                    String configuredProviderId) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.fixedClient = fixedClient;
        this.tenantConfigService = tenantConfigService;
        if (configuredProviderId == null || configuredProviderId.isBlank()) {
            throw new IllegalArgumentException("usage reconciliation providerId is required");
        }
        this.configuredProviderId = configuredProviderId;
    }

    @Override
    public Batch pull(String tenantId, Instant from, Instant to, String cursor) {
        String safeCursor = cursor == null ? "" : cursor;
        Snapshot billing = settings.read("billing");
        EffectiveConfig config = effectiveConfig(tenantId, billing);
        if (tenantId == null || tenantId.isBlank() || from == null || to == null
                || !config.enabled() || config.endpoint() == null
                || config.endpoint().isBlank()) {
            return new Batch(List.of(), safeCursor);
        }
        try {
            RestClient client = fixedClient == null ? buildClient(billing) : fixedClient;
            URI uri = UriComponentsBuilder.fromUriString(config.endpoint())
                    .buildAndExpand(Map.of(
                            "tenantId", tenantId,
                            "from", from.toString(),
                            "to", to.toString(),
                            "cursor", safeCursor))
                    .encode()
                    .toUri();
            String response = client.get()
                    .uri(uri)
                    .headers(headers -> {
                        if (config.apiKey() != null && !config.apiKey().isBlank()
                                && config.apiKeyHeader() != null
                                && !config.apiKeyHeader().isBlank()) {
                            headers.set(config.apiKeyHeader(), config.apiKey());
                        }
                    })
                    .retrieve()
                    .body(String.class);
            return parse(response, safeCursor, config.responseSchema());
        } catch (RuntimeException exception) {
            log.warn("第三方账单拉取失败 tenant={} reason=usage_pull_failed diagnostic={}", tenantId,
                    com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(exception));
            return new Batch(List.of(), safeCursor);
        }
    }

    private Batch parse(String response, String cursor, Map<String, Object> schema) {
        if (response == null || response.isBlank()) {
            return new Batch(List.of(), cursor);
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            List<Fact> facts = new ArrayList<>();
            boolean invalidFact = false;
            JsonNode factNodes = path(root, schemaField(schema, "facts_path", "facts"));
            if (factNodes != null && factNodes.isArray()) {
                for (JsonNode node : factNodes) {
                    try {
                        facts.add(parseFact(node, schema));
                    } catch (RuntimeException exception) {
                        invalidFact = true;
                        log.warn("第三方账单 fact 被拒绝 reason=usage_fact_invalid diagnostic={}",
                                com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(exception));
                    }
                }
            }
            String nextCursor = text(root, schemaField(schema, "next_cursor", "next_cursor"));
            return new Batch(facts, invalidFact || nextCursor == null ? cursor : nextCursor);
        } catch (Exception exception) {
            return new Batch(List.of(), cursor);
        }
    }

    private Fact parseFact(JsonNode node, Map<String, Object> schema) {
        String eventId = requiredText(node, schemaField(schema, "event_id", "event_id"));
        JsonNode original = path(node, schemaField(schema, "original_event_id", "original_event_id"));
        if (original == null || !original.canConvertToLong() || original.asLong() <= 0) {
            throw new IllegalArgumentException("original_event_id must be positive");
        }
        String statusText = requiredText(node, schemaField(schema, "amount_status", "amount_status"));
        AmountStatus status = parseStatus(statusText);
        return new Fact(
                eventId,
                original.asLong(),
                status,
                decimal(node, schemaField(schema, "input_amount", "input_amount")),
                decimal(node, schemaField(schema, "output_amount", "output_amount")),
                decimal(node, schemaField(schema, "total_amount", "total_amount")),
                decimal(node, schemaField(schema, "exchange_rate_to_cny", "exchange_rate_to_cny")),
                text(node, schemaField(schema, "pricing_snapshot_version", "pricing_snapshot_version")),
                text(node, schemaField(schema, "currency", "currency")),
                metadata(node, schemaField(schema, "metadata", "metadata")));
    }

    private AmountStatus parseStatus(String value) {
        for (AmountStatus status : AmountStatus.values()) {
            if (status.value().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown amount_status");
    }

    private BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value);
    }

    private Map<String, Object> metadata(JsonNode node, String field) {
        JsonNode value = path(node, field);
        if (value == null || !value.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(value, LinkedHashMap.class);
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = path(node, field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null : value.asText();
    }

    private EffectiveConfig effectiveConfig(String tenantId, Snapshot billing) {
        EffectiveConfig fallback = new EffectiveConfig(
                billing.bool("usage_reconciliation_enabled"),
                billing.text("usage_reconciliation_endpoint"),
                billing.text("usage_reconciliation_api_key"),
                billing.text("usage_reconciliation_api_key_header"), Map.of());
        if (tenantConfigService == null || tenantId == null || tenantId.isBlank()) {
            return "http".equals(configuredProviderId) ? fallback
                    : new EffectiveConfig(false, null, null, null, Map.of());
        }
        try {
            TenantUsageReconciliationConfigService.ResolvedTenantConfig tenant =
                    tenantConfigService.resolveForProvider(tenantId);
            if (tenant.provider() == null && tenant.endpoint() == null) {
                return "http".equals(configuredProviderId) ? fallback
                        : new EffectiveConfig(false, null, null, null, Map.of());
            }
            return new EffectiveConfig(configuredProviderId.equals(tenant.provider()), tenant.endpoint(),
                    tenant.apiKey(), tenant.apiKeyHeader(), tenant.responseSchema());
        } catch (RuntimeException exception) {
            log.warn("租户账单 Provider 配置读取失败 tenant={} reason=usage_config_failed diagnostic={}", tenantId,
                    com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(exception));
            return new EffectiveConfig(false, null, null, null, Map.of());
        }
    }

    private String schemaField(Map<String, Object> schema, String key, String fallback) {
        Object value = schema == null ? null : schema.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private JsonNode path(JsonNode root, String jsonPath) {
        if (root == null || jsonPath == null || jsonPath.isBlank()) {
            return null;
        }
        JsonNode current = root;
        for (String segment : jsonPath.split("\\.")) {
            current = current == null ? null : current.get(segment);
        }
        return current;
    }

    private record EffectiveConfig(boolean enabled, String endpoint, String apiKey,
                                   String apiKeyHeader, Map<String, Object> responseSchema) {
    }

    static RestClient buildClient(Snapshot billing) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int connectTimeout = billing.integer("usage_reconciliation_connect_timeout_seconds");
        int readTimeout = billing.integer("usage_reconciliation_read_timeout_seconds");
        factory.setConnectTimeout(connectTimeout * 1000);
        factory.setReadTimeout(readTimeout * 1000);
        return RestClient.builder().requestFactory(factory).build();
    }
}
