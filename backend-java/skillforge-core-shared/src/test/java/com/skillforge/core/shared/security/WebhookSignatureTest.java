package com.skillforge.core.shared.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

/** Webhook 签名必须覆盖时间戳与原始请求体。 */
class WebhookSignatureTest {

    @Test
    void producesStableHmacSha256ForTimestampAndBody() {
        assertEquals("27b4066e0938adad45d65d5cfe6ed1eb74626ae252912cdd94a6193dc009f6ed",
                WebhookSignature.sign("secret", "1724500800", "{\"event\":\"done\"}"));
    }

    @Test
    void requiresSigningSecretAndTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> WebhookSignature.sign("", "1", "{}"));
        assertThrows(IllegalArgumentException.class, () -> WebhookSignature.sign(null, "1", "{}"));
        assertThrows(IllegalArgumentException.class, () -> WebhookSignature.sign("secret", "", "{}"));
        assertEquals(WebhookSignature.sign("secret", "1", ""), WebhookSignature.sign("secret", "1", null));
        assertThrows(IllegalStateException.class,
                () -> WebhookSignature.signWithAlgorithm("secret", "1", "{}", "NoSuchMac"));
    }

    @Test
    void utilityConstructorIsNotPartOfThePublicApi() throws Exception {
        Constructor<WebhookSignature> constructor = WebhookSignature.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
