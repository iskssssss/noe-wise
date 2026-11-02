package com.skillforge.sandbox.workspace;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.nio.file.Path;

/** 工作区持久卷与云端接入的部署事实；云端凭据只供可信备份服务使用。 */
@Validated
@ConfigurationProperties(prefix = "skillforge.workspace-cloud")
public record WorkspaceCloudProperties(
        @NotNull @DefaultValue("/var/lib/skillforge-projects") Path projectRoot,
        @NotBlank @DefaultValue("skillforge-sandbox-projects") String projectVolume,
        @NotNull @DefaultValue("https://v0.api.upyun.com") URI endpoint,
        @DefaultValue("") String bucket,
        @DefaultValue("") String operator,
        @DefaultValue("") String password,
        @Min(1) @Max(1000) @DefaultValue("1") int requestsPerSecond
) {

    /** 拒绝含认证信息的 URL 与不完整配置，防止后台误向其他协议发送秘密。 */
    public WorkspaceCloudProperties {
        if (endpoint != null && (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || (endpoint.getPath() != null && !endpoint.getPath().isEmpty()
                && !"/".equals(endpoint.getPath())))) {
            throw new IllegalArgumentException("工作区云端接入地址必须是无凭据的 HTTPS 服务根地址");
        }
        bucket = bucket == null ? "" : bucket;
        operator = operator == null ? "" : operator;
        password = password == null ? "" : password;
        boolean anyCredential = !bucket.isBlank() || !operator.isBlank() || !password.isBlank();
        if (anyCredential && (bucket.isBlank() || operator.isBlank() || password.isBlank())) {
            throw new IllegalArgumentException("工作区云端服务名、操作员和密码必须一起配置");
        }
        if (!bucket.isBlank() && !bucket.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("工作区云端服务名格式不合法");
        }
        if (!operator.isBlank() && !operator.matches("[A-Za-z0-9_.@-]+")) {
            throw new IllegalArgumentException("工作区云端操作员格式不合法");
        }
    }

    /** 配置对象意外进入诊断日志时，仅展示部署位置，绝不展示秘密。 */
    @Override
    public String toString() {
        return "WorkspaceCloudProperties[projectRoot=" + projectRoot + ", projectVolume=" + projectVolume
                + ", endpoint=" + endpoint + ", credentials=redacted, requestsPerSecond=" + requestsPerSecond + "]";
    }
}
