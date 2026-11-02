package com.skillforge.institution.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * OpenAPI 集成方 HMAC-SHA256 签名校验工具（纯函数）。
 *
 * <p>对齐接口契约 §2.5：
 * {@code canonical = timestamp + "\n" + method + "\n" + path + "\n" + sha256(body)}，
 * {@code signature = hex(hmac_sha256(api_secret, canonical))}。
 * 验签使用 {@link MessageDigest#isEqual} 常量时间比较，防时序侧信道。
 */
public final class HmacSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private HmacSignatureVerifier() {
    }

    /**
     * 构造待签名的 canonical 字符串。
     *
     * @param timestamp 请求时间戳（Unix 秒字符串）
     * @param method    HTTP 方法（大写，如 GET/POST）
     * @param path      请求目标：固定路径及原始查询串（有查询串时拼接 ?，保留顺序与编码）
     * @param body      请求体原文；GET 无请求体时传空串
     * @return canonical 字符串
     */
    public static String canonical(String timestamp, String method, String path, String body) {
        return canonicalBytes(timestamp, method, path, (body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
    }

    /** 原始正文按实际字节取摘要；二进制不得先解码为UTF-8，缺少缓存也不能当成空正文。 */
    public static String canonicalBytes(String timestamp, String method, String path, byte[] body) {
        Objects.requireNonNull(body, "签名必须提供实际请求体字节");
        try {
            String bodySha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            return timestamp + "\n" + method + "\n" + path + "\n" + bodySha;
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 摘要不可用", unavailable);
        }
    }

    /** 查询参数参与认证，防止固定路径下替换资源标识而复用签名。 */
    public static String requestTarget(String path, String rawQuery) {
        return rawQuery == null || rawQuery.isEmpty() ? path : path + "?" + rawQuery;
    }

    /**
     * 计算签名。
     *
     * @param apiSecret API secret 明文
     * @param canonical canonical 字符串（见 {@link #canonical}）
     * @return 64 位十六进制签名
     */
    public static String sign(String apiSecret, String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名计算失败", e);
        }
    }

    /**
     * 验签（常量时间比较，签名缺失/长度不等不抛异常，直接返回 false）。
     *
     * @param apiSecret API secret 明文
     * @param canonical canonical 字符串（见 {@link #canonical}）
     * @param signature 请求携带的签名 hex
     * @return 签名一致返回 true
     */
    public static boolean verify(String apiSecret, String canonical, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expected = sign(apiSecret, canonical);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
