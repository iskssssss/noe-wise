package com.skillforge.core.billing.reconciliation;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.TenantBillingProfileService;
import com.skillforge.core.foundation.application.TenantBillingProfileService.ReconciliationProfile;
import com.skillforge.core.foundation.application.CryptoService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户账单 Provider 配置读写与 schema 白名单校验。 */
@Service
public class TenantUsageReconciliationConfigService {

    private static final Set<String> SCHEMA_KEYS = Set.of(
            "facts_path", "next_cursor", "event_id", "original_event_id", "amount_status",
            "input_amount", "output_amount", "total_amount", "exchange_rate_to_cny",
            "pricing_snapshot_version", "currency", "metadata");
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("http", "dashscope");
    private static final Pattern JSON_PATH = Pattern.compile("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*");

    private final TenantBillingProfileService profiles;
    private final CryptoService cryptoService;

    public TenantUsageReconciliationConfigService(TenantBillingProfileService profiles, CryptoService cryptoService) {
        this.profiles = profiles;
        this.cryptoService = cryptoService;
    }

    /** 返回当前租户的脱敏配置。 */
    public TenantUsageReconciliationConfig get(String tenantId) {
        ReconciliationProfile tenant = requireTenant(tenantId);
        return projection(tenant);
    }

    /** 写入当前租户 Provider 配置；null API key 表示保留，clearApiKey 才清除。 */
    @Transactional(rollbackFor = Exception.class)
    public TenantUsageReconciliationConfig save(String tenantId, TenantUsageReconciliationConfigRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "账单 Provider 配置不能为空");
        }
        ReconciliationProfile tenant = requireTenant(tenantId);
        String provider = normalize(request.provider());
        String endpoint = normalize(request.endpoint());
        String apiKeyHeader = normalize(request.apiKeyHeader());
        if (provider == null) {
            if (endpoint != null || request.apiKey() != null || request.clearApiKey()
                    || request.responseSchema() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "清除账单 Provider 时不能同时提交其他配置");
            }
            tenant = new ReconciliationProfile(null, null, null, null, null);
        } else {
            if (!provider.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "账单 Provider ID 格式无效");
            }
            if (!SUPPORTED_PROVIDERS.contains(provider)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "RECONCILIATION_PROVIDER_UNSUPPORTED", "不支持的对账提供方，请选择通用 HTTP 或阿里云百炼", false);
            }
            if (endpoint == null || !endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "账单 endpoint 必须为 HTTP(S) URL");
            }
            if (apiKeyHeader == null) {
                apiKeyHeader = "Authorization";
            }
            validateHeader(apiKeyHeader);
            String encrypted = tenant.apiKeyEncrypted();
            if (request.clearApiKey()) {
                encrypted = null;
            } else if (request.apiKey() != null) {
                if (request.apiKey().isBlank()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "apiKey 不能是空白");
                }
                encrypted = cryptoService.encrypt(request.apiKey());
            }
            tenant = new ReconciliationProfile(provider, endpoint, encrypted, apiKeyHeader,
                    normalizeSchema(request.responseSchema()));
        }
        profiles.saveReconciliationProfile(Long.parseLong(tenantId), tenant);
        return projection(tenant);
    }

    /** Provider 内部使用的租户配置读取，返回已解密 API key；不对 API 层暴露。 */
    public ResolvedTenantConfig resolveForProvider(String tenantId) {
        ReconciliationProfile tenant = requireTenant(tenantId);
        String encrypted = tenant.apiKeyEncrypted();
        String apiKey = encrypted == null || encrypted.isBlank() ? null : cryptoService.decrypt(encrypted);
        return new ResolvedTenantConfig(tenant.provider(),
                tenant.endpoint(), apiKey,
                tenant.apiKeyHeader(),
                normalizeSchema(tenant.responseSchema()));
    }

    private ReconciliationProfile requireTenant(String tenantId) {
        long id;
        try {
            id = Long.parseLong(tenantId);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "租户 ID 无效", exception);
        }
        ReconciliationProfile tenant = profiles.reconciliationProfile(id);
        if (tenant == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        return tenant;
    }

    private TenantUsageReconciliationConfig projection(ReconciliationProfile tenant) {
        return new TenantUsageReconciliationConfig(tenant.provider(),
                tenant.endpoint(),
                tenant.apiKeyEncrypted() != null
                        && !tenant.apiKeyEncrypted().isBlank(),
                tenant.apiKeyHeader(),
                normalizeSchema(tenant.responseSchema()));
    }

    private Map<String, Object> normalizeSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            if (!SCHEMA_KEYS.contains(entry.getKey()) || !(entry.getValue() instanceof String value)
                    || !JSON_PATH.matcher(value).matches()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "responseSchema 只允许受限 JSON path 字段映射");
            }
            normalized.put(entry.getKey(), value);
        }
        return Map.copyOf(normalized);
    }

    private void validateHeader(String header) {
        if (!header.matches("[A-Za-z0-9-]{1,128}")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "apiKeyHeader 格式无效");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ResolvedTenantConfig(
            String provider, String endpoint, String apiKey, String apiKeyHeader,
            Map<String, Object> responseSchema) {
        public ResolvedTenantConfig {
            responseSchema = responseSchema == null ? Map.of() : Map.copyOf(responseSchema);
        }
    }
}
