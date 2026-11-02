package com.skillforge.core.shared.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

/** SSRF, scheme and domain policy coverage for connector URLs. */
class SandboxGuardTest {

    @Test
    void rejectsMissingInvalidAndNonHttpsTargets() {
        SkillForgeProperties properties = new SkillForgeProperties();
        properties.setDebug(false);
        SandboxGuard guard = new SandboxGuard(properties);

        assertThatThrownBy(() -> guard.assertSafe(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.assertSafe("   ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.assertSafe("http://example.com/api"))
                .isInstanceOf(ApiException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> guard.assertSafe("https:///missing-host"))
                .isInstanceOf(ApiException.class).hasMessageContaining("主机");
    }

    @Test
    void allowsLocalHttpOnlyInDebugAndBlocksItOtherwise() {
        SkillForgeProperties debug = new SkillForgeProperties();
        debug.setDebug(true);
        assertThat(new SandboxGuard(debug).assertSafe("http://localhost:8080/health"))
                .isEqualTo("http://localhost:8080/health");

        SkillForgeProperties production = new SkillForgeProperties();
        production.setDebug(false);
        assertThatThrownBy(() -> new SandboxGuard(production).assertSafe("http://127.0.0.1:8080"))
                .isInstanceOf(ApiException.class).hasMessageContaining("HTTPS");
    }

    @Test
    void appliesDomainAllowlistAndPrivateAddressProtection() {
        SkillForgeProperties properties = new SkillForgeProperties();
        properties.setSandboxAllowedDomains("allowed.example");
        SandboxGuard guard = new SandboxGuard(properties);

        assertThatThrownBy(() -> guard.assertSafe("https://evil.example/path"))
                .isInstanceOf(ApiException.class).hasMessageContaining("白名单");

        SkillForgeProperties open = new SkillForgeProperties();
        assertThatThrownBy(() -> new SandboxGuard(open).assertSafe("https://192.168.1.1"))
                .isInstanceOf(ApiException.class).hasMessageContaining("内网");
    }
}
