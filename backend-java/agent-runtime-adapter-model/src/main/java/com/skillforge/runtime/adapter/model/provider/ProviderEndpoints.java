package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.EndpointMode;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderProfileRejectedException;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceProfile;
import java.net.URI;
import java.util.Locale;

/** 供应商实现共享的端点规范化原语，不包含任何供应商品牌或地域。 */
public final class ProviderEndpoints {

    private ProviderEndpoints() {
    }

    /** 本地自建接入不借由自定义协议转发到未经登记的境外服务。 */
    public static String local(String value) {
        String endpoint = normalized(value);
        String host = URI.create(endpoint).getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("[::1]")
                || host.equals("::1")) return endpoint;
        if (host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
            int[] parts = java.util.Arrays.stream(host.split("\\.")).mapToInt(Integer::parseInt).toArray();
            if (java.util.Arrays.stream(parts).allMatch(part -> part >= 0 && part <= 255)
                    && (parts[0] == 127 || parts[0] == 10 || parts[0] == 192 && parts[1] == 168
                        || parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31)) return endpoint;
        }
        throw rejected("model_local_endpoint_required");
    }

    public static String resolveDefault(ServiceProfile service, String requested) {
        return switch (service.endpointMode()) {
            case FIXED -> fixed(service.endpoint(), requested);
            case CUSTOM -> normalized(requested);
            case SCOPED_SUBDOMAIN -> throw rejected(
                    "model_service_endpoint_policy_not_implemented");
        };
    }

    static String fixed(String value, String requested) {
        String expected = normalized(value);
        if (requested != null && !requested.isBlank()
                && !expected.equals(normalized(requested))) {
            throw rejected("model_service_endpoint_mismatch");
        }
        return expected;
    }

    public static String scopedSubdomain(String requested, String domainSuffix,
            String requiredPath) {
        String normalized = normalized(requested);
        URI uri = URI.create(normalized);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String suffix = "." + domainSuffix;
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != -1
                || !requiredPath.equals(uri.getRawPath())
                || !host.endsWith(suffix)) {
            throw rejected("model_service_endpoint_mismatch");
        }
        String scope = host.substring(0, host.length() - suffix.length());
        if (!scope.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
            throw rejected("model_service_endpoint_scope_invalid");
        }
        return "https://" + scope + suffix + requiredPath;
    }

    static String normalized(String value) {
        if (value == null || value.isBlank()) {
            throw rejected("model_endpoint_missing");
        }
        if (value.chars().anyMatch(character ->
                Character.isISOControl((char) character))) {
            throw rejected("model_endpoint_invalid");
        }
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw rejected("model_endpoint_invalid");
            }
            String normalized = uri.toString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (ProviderProfileRejectedException rejected) {
            throw rejected;
        } catch (RuntimeException invalid) {
            throw rejected("model_endpoint_invalid");
        }
    }

    private static ProviderProfileRejectedException rejected(String reason) {
        return new ProviderProfileRejectedException(reason);
    }
}
