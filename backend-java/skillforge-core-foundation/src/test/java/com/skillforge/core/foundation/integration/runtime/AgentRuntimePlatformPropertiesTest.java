package com.skillforge.core.foundation.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AgentRuntimePlatformPropertiesTest {
    @Test
    void bindsPlatformAndSdkFieldsFromTheirOwnNamespaces() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "agent-runtime.worker-id=host-worker",
                        "skillforge.agent-runtime.trusted-authentication-validity=PT12M",
                        "skillforge.agent-runtime.system-grants[0].tenant-id=12",
                        "skillforge.agent-runtime.system-grants[0].principal-id=host-worker",
                        "skillforge.agent-runtime.system-grants[0].actions[0]=sessions.read",
                        "skillforge.agent-runtime.callback-signature-window=PT7M",
                        "skillforge.agent-runtime.callback-recovery-window=PT48H",
                        "skillforge.agent-runtime.callback-maximum-body-bytes=1024",
                        "skillforge.agent-runtime.callback-payload-schema-ids[0]=business.result.v1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var platform = context.getBean(AgentRuntimePlatformProperties.class);
                    platform.validate();
                    assertThat(platform.getTrustedAuthenticationValidity()).isEqualTo(Duration.ofMinutes(12));
                    assertThat(platform.getCallbackSignatureWindow()).isEqualTo(Duration.ofMinutes(7));
                    assertThat(platform.getCallbackRecoveryWindow()).isEqualTo(Duration.ofHours(48));
                    assertThat(platform.getCallbackMaximumBodyBytes()).isEqualTo(1024);
                    assertThat(platform.getCallbackPayloadSchemaIds()).containsExactly("business.result.v1");
                    assertThat(new AgentRuntimeSystemGrants(platform).tenantIds())
                            .containsExactly(new com.skillforge.runtime.api.TenantId("12"));
                    assertThat(context.getBean(AgentRuntimeProperties.class).getWorkerId()).isEqualTo("host-worker");
                });
    }

    @Test
    void platformNamespaceCannotOverrideSdkWorkerIdentity() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("skillforge.agent-runtime.worker-id=platform-field-does-not-own-sdk-worker")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AgentRuntimeProperties.class).getWorkerId())
                            .isEqualTo(new AgentRuntimeProperties().getWorkerId());
                });
    }

    @Test
    void rejectsInvalidAuthenticationAndCallbackBoundaries() {
        var properties = new AgentRuntimePlatformProperties();
        properties.setTrustedAuthenticationValidity(Duration.ofHours(2));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-authentication-validity");
        properties.setTrustedAuthenticationValidity(Duration.ofMinutes(5));
        properties.setCallbackRecoveryWindow(Duration.ZERO);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("callback-recovery-window");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AgentRuntimePlatformProperties.class, AgentRuntimeProperties.class})
    static class PropertiesConfiguration { }
}
