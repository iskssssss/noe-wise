package com.skillforge.core.foundation.integration.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 平台身份与回调入口的部署配置；启动时绑定，变更后须重启。 */
@ConfigurationProperties("skillforge.agent-runtime")
public final class AgentRuntimePlatformProperties {
    private Duration trustedAuthenticationValidity = Duration.ofMinutes(5);
    private List<SystemGrant> systemGrants = new ArrayList<>();
    private Duration callbackSignatureWindow = Duration.ofMinutes(5);
    private Duration callbackRecoveryWindow = Duration.ofHours(24);
    private int callbackMaximumBodyBytes = 65_536;
    private Set<String> callbackPayloadSchemaIds = new LinkedHashSet<>();

    public Duration getTrustedAuthenticationValidity() { return trustedAuthenticationValidity; }
    public void setTrustedAuthenticationValidity(Duration value) { trustedAuthenticationValidity = value; }
    public List<SystemGrant> getSystemGrants() { return systemGrants; }
    public void setSystemGrants(List<SystemGrant> value) { systemGrants = value; }
    public Duration getCallbackSignatureWindow() { return callbackSignatureWindow; }
    public void setCallbackSignatureWindow(Duration value) { callbackSignatureWindow = value; }
    public Duration getCallbackRecoveryWindow() { return callbackRecoveryWindow; }
    public void setCallbackRecoveryWindow(Duration value) { callbackRecoveryWindow = value; }
    public int getCallbackMaximumBodyBytes() { return callbackMaximumBodyBytes; }
    public void setCallbackMaximumBodyBytes(int value) { callbackMaximumBodyBytes = value; }
    public Set<String> getCallbackPayloadSchemaIds() { return callbackPayloadSchemaIds; }
    public void setCallbackPayloadSchemaIds(Set<String> value) { callbackPayloadSchemaIds = value; }

    void validate() {
        requirePositive(trustedAuthenticationValidity, "trusted-authentication-validity");
        if (trustedAuthenticationValidity.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException(
                    "skillforge.agent-runtime.trusted-authentication-validity must be within one hour");
        }
        if (systemGrants == null || systemGrants.stream().anyMatch(grant -> grant == null || grant.invalid())) {
            throw new IllegalStateException("skillforge.agent-runtime.system-grants is invalid");
        }
        requirePositive(callbackSignatureWindow, "callback-signature-window");
        requirePositive(callbackRecoveryWindow, "callback-recovery-window");
        if (callbackMaximumBodyBytes <= 0) {
            throw new IllegalStateException("skillforge.agent-runtime.callback-maximum-body-bytes must be positive");
        }
        if (callbackPayloadSchemaIds == null || callbackPayloadSchemaIds.stream()
                .anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("skillforge.agent-runtime.callback-payload-schema-ids is invalid");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("skillforge.agent-runtime." + name + " must be positive");
        }
    }

    /** 可信内部主体的固定授权；空列表表示禁用系统入口。 */
    public static final class SystemGrant {
        private String tenantId;
        private String principalId;
        private Set<String> actions = new LinkedHashSet<>();

        public String getTenantId() { return tenantId; }
        public void setTenantId(String value) { tenantId = value; }
        public String getPrincipalId() { return principalId; }
        public void setPrincipalId(String value) { principalId = value; }
        public Set<String> getActions() { return actions; }
        public void setActions(Set<String> value) { actions = value; }

        private boolean invalid() {
            return tenantId == null || tenantId.isBlank() || principalId == null
                    || principalId.isBlank() || actions == null || actions.isEmpty()
                    || actions.stream().anyMatch(action -> action == null || action.isBlank());
        }
    }
}
