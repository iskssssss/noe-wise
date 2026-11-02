package com.skillforge.sandbox.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** 独立沙箱服务的可信部署配置；缺少认证或隔离资源时服务拒绝启动。 */
@Validated
@ConfigurationProperties(prefix = "skillforge.sandbox")
public record SandboxProperties(
        @NotBlank String serviceToken,
        @NotNull Path dataRoot,
        @NotBlank String dockerBinary,
        @NotBlank String taskNetwork,
        @NotBlank String taskVolume,
        @NotBlank String caVolume,
        @NotBlank String proxyUrl,
        @NotBlank String proxyContainer,
        @NotBlank String pythonImage,
        @NotBlank String nodeImage,
        @NotBlank String pythonIndexUrl,
        @NotBlank String nodeRegistryUrl,
        @NotNull Duration dockerControlTimeout,
        @NotBlank String workflowPythonEnvironmentVersion,
        @NotBlank String workflowNodeEnvironmentVersion,
        @NotBlank String workflowPythonImage,
        @NotBlank String workflowNodeImage
) {

    public SandboxProperties {
        if (serviceToken != null && serviceToken.length() < 32) {
            throw new IllegalArgumentException("skillforge.sandbox.service-token 至少需要 32 个字符");
        }
        if (dockerControlTimeout != null && (dockerControlTimeout.isZero() || dockerControlTimeout.isNegative())) {
            throw new IllegalArgumentException("skillforge.sandbox.docker-control-timeout 必须为正数");
        }
        validatePublicRepository("python-index-url", pythonIndexUrl);
        validatePublicRepository("node-registry-url", nodeRegistryUrl);
    }

    private static void validatePublicRepository(String property, String value) {
        if (value == null) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("skillforge.sandbox." + property + " 必须是合法 HTTPS URL", invalid);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("skillforge.sandbox." + property
                    + " 必须是无凭据、query 与 fragment 的 HTTPS URL");
        }
    }
}
