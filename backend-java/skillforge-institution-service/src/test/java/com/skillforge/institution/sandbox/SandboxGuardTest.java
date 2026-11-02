package com.skillforge.institution.sandbox;
import com.skillforge.core.shared.sandbox.SandboxGuard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 沙箱安全守卫单元测试。
 *
 * <p>覆盖 HTTPS 强制、内网/链路本地地址拦截与 localhost 调试放行。
 */
class SandboxGuardTest {

    /** 应用配置。 */
    private SkillForgeProperties properties;

    /** 被测守卫。 */
    private SandboxGuard guard;

    /**
     * 初始化配置与守卫。
     */
    @BeforeEach
    void setUp() {
        properties = new SkillForgeProperties();
        properties.setDebug(true);
        guard = new SandboxGuard(properties);
    }

    /**
     * 调试环境允许 localhost HTTP。
     */
    @Test
    void localhostHttpAllowedInDebug() {
        assertDoesNotThrow(() -> guard.assertSafe("http://localhost:8000/api"));
    }

    /**
     * 生产模式拒绝明文 HTTP。
     */
    @Test
    void httpRejectedInProduction() {
        properties.setDebug(false);
        assertThrows(ApiException.class, () -> guard.assertSafe("http://example.com/api"));
    }

    /**
     * 内网地址应被拦截。
     */
    @Test
    void privateIpRejected() {
        assertThrows(ApiException.class, () -> guard.assertSafe("https://10.0.0.1/api"));
        assertThrows(ApiException.class, () -> guard.assertSafe("https://169.254.169.254/latest/meta-data"));
    }

    /**
     * 未配置白名单时 HTTPS 公网域名可通过（不触发 DNS 校验以外的拦截）。
     */
    @Test
    void publicHttpsAllowed() {
        assertDoesNotThrow(() -> guard.assertSafe("https://127.0.0.1:8443/api"));
    }
}
