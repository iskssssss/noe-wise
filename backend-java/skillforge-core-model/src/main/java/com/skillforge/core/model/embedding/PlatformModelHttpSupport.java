package com.skillforge.core.model.embedding;

import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.foundation.application.CryptoService;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/** 平台模型的轻量 HTTP 支撑，凭据和单次超时均来自冻结的模型版本。 */
public final class PlatformModelHttpSupport {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    private final CryptoService cryptoService;
    private final com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog providers =
            com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.standard();

    public PlatformModelHttpSupport(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    public RestClient client(PlatformModelConfig config) {
        return client(config, configuredTimeout(config));
    }

    /** HTTP 各阶段都受模型配置和当前调用剩余时间中较小者限制。 */
    public RestClient client(PlatformModelConfig config, Duration remaining) {
        com.skillforge.core.model.ModelServiceAdmission.resolve(providers, config);
        if (remaining == null || remaining.isNegative() || remaining.isZero()) {
            throw new IllegalArgumentException("platform_model_timeout_invalid");
        }
        Duration configured = configuredTimeout(config);
        Duration readTimeout = configured.compareTo(remaining) < 0 ? configured : remaining;
        Duration connectTimeout = readTimeout.compareTo(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)) < 0
                ? readTimeout : Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 小于 1ms 的有效剩余时间不能转换成代表无限等待的 0ms。
        factory.setConnectTimeout(timeoutMillis(connectTimeout));
        factory.setReadTimeout(timeoutMillis(readTimeout));
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (config.apiKeyEncrypted() != null && !config.apiKeyEncrypted().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + decryptCredential(config));
        }
        return builder.build();
    }

    private static Duration configuredTimeout(PlatformModelConfig config) {
        int seconds = config.timeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : config.timeoutSeconds();
        if (seconds < 1) throw new IllegalArgumentException("platform_model_timeout_invalid");
        return Duration.ofSeconds(seconds);
    }

    private static int timeoutMillis(Duration timeout) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
    }

    private String decryptCredential(PlatformModelConfig config) {
        try {
            String credential = cryptoService.decrypt(config.apiKeyEncrypted());
            if (credential == null || credential.isBlank()) {
                throw new IllegalArgumentException("credential_empty");
            }
            return credential;
        } catch (RuntimeException invalidCredential) {
            throw new IllegalStateException("platform_model_credential_unavailable");
        }
    }

    public String endpoint(PlatformModelConfig config, String defaultPath) {
        String base = config.endpoint();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = config.path() == null || config.path().isBlank()
                ? defaultPath : config.path();
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    public String friendlyError(Exception exception) {
        if (exception instanceof HttpStatusCodeException http) {
            return "模型服务请求被拒绝（HTTP " + http.getStatusCode().value() + "）";
        }
        return "模型服务连接失败";
    }
}
