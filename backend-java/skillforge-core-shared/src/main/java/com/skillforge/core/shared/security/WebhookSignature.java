package com.skillforge.core.shared.security;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 出站 Webhook 请求体的 HMAC-SHA256 签名。 */
public final class WebhookSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    /** 签名内容固定为 {@code timestamp + "\n" + body}，避免路径重写影响验签。 */
    public static String sign(String secret, String timestamp, String body) {
        return signWithAlgorithm(secret, timestamp, body, ALGORITHM);
    }

    /** 包可见的算法边界，供兼容性测试确认 JCA 不可用时不降级为未签名请求。 */
    static String signWithAlgorithm(String secret, String timestamp, String body, String algorithm) {
        if (secret == null || secret.isBlank() || timestamp == null || timestamp.isBlank()) {
            throw new IllegalArgumentException("Webhook secret and timestamp must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "\n" +
                    (body == null ? "" : body)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Webhook signature calculation failed", failure);
        }
    }
}
