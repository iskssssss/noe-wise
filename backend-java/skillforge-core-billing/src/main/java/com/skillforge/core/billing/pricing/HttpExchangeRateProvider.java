package com.skillforge.core.billing.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 配置驱动的外部汇率 HTTP Provider。
 *
 * <p>端点响应必须包含 {@code rate}、{@code observed_at} 和
 * {@code snapshot_version} 字段；Provider 不接受缺少审计快照的信息。端点可用
 * {@code {currency}} 与 {@code {occurredAt}} 接收查询币种和事件时间。Provider
 * 默认关闭，网络失败或响应不完整时返回 empty，由计费层将金额标记为 unresolved。
 */
@Component
public class HttpExchangeRateProvider implements ExchangeRateProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpExchangeRateProvider.class);

    private final ObjectMapper objectMapper;
    private final OperationalSettingsService settings;
    private final RestClient fixedClient;

    @Autowired
    public HttpExchangeRateProvider(
            ObjectMapper objectMapper, OperationalSettingsService settings) {
        this(objectMapper, settings, null);
    }

    HttpExchangeRateProvider(ObjectMapper objectMapper, OperationalSettingsService settings,
                             RestClient fixedClient) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.fixedClient = fixedClient;
    }

    @Override
    public Optional<Rate> toCny(String currency, Instant occurredAt) {
        if (currency == null || currency.isBlank() || occurredAt == null) {
            return Optional.empty();
        }
        Snapshot config = settings.read("billing");
        if (!config.bool("exchange_rate_enabled")
                || config.text("exchange_rate_endpoint").isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = UriComponentsBuilder.fromUriString(config.text("exchange_rate_endpoint"))
                    .buildAndExpand(Map.of(
                            "currency", currency.trim().toUpperCase(java.util.Locale.ROOT),
                            "occurredAt", occurredAt.toString()))
                    .encode()
                    .toUri();
            RestClient client = fixedClient == null ? buildClient(config) : fixedClient;
            String response = client.get()
                    .uri(uri)
                    .headers(headers -> {
                        String apiKey = config.text("exchange_rate_api_key");
                        String header = config.text("exchange_rate_api_key_header");
                        if (!apiKey.isBlank() && !header.isBlank()) {
                            headers.set(header, apiKey);
                        }
                    })
                    .retrieve()
                    .body(String.class);
            return parse(response);
        } catch (RuntimeException exception) {
            log.warn("外部汇率快照获取失败 reason=exchange_rate_fetch_failed diagnostic={}",
                    com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(exception));
            return Optional.empty();
        }
    }

    private Optional<Rate> parse(String response) {
        if (response == null || response.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            String rateText = text(root, "rate");
            String observedText = text(root, "observed_at");
            String snapshotVersion = text(root, "snapshot_version");
            if (rateText == null || observedText == null || snapshotVersion == null) {
                return Optional.empty();
            }
            return Optional.of(new Rate(
                    new BigDecimal(rateText),
                    Instant.parse(observedText),
                    snapshotVersion));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null : value.asText();
    }

    private static RestClient buildClient(Snapshot config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int connectTimeout = config.integer("exchange_rate_connect_timeout_seconds");
        int readTimeout = config.integer("exchange_rate_read_timeout_seconds");
        factory.setConnectTimeout(connectTimeout * 1000);
        factory.setReadTimeout(readTimeout * 1000);
        return RestClient.builder().requestFactory(factory).build();
    }
}
