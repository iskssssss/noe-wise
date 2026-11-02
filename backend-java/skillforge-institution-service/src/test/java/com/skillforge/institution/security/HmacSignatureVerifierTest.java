package com.skillforge.institution.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.core.shared.security.TokenHash;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * OpenAPI HMAC-SHA256 签名校验单元测试。
 *
 * <p>覆盖 canonical 构造（空 body / 拼接格式）与验签路径
 * （正确通过、篡改任一输入失败、空签名拒绝、密钥不符拒绝）。
 */
class HmacSignatureVerifierTest {

    /** 固定测试密钥。 */
    private static final String SECRET = "test-api-secret-0123456789abcdef";

    /**
     * canonical 应按契约拼接：ts + "\n" + method + "\n" + path + "\n" + sha256(body)。
     */
    @Test
    void canonicalFollowsContractFormat() {
        String body = "{\"message\":\"hi\"}";
        String expected = "1700000000\nPOST\n/api/v1/chat/sync\n" + TokenHash.sha256Hex(body);
        assertEquals(expected,
                HmacSignatureVerifier.canonical("1700000000", "POST", "/api/v1/chat/sync", body));
    }

    /**
     * 空 body 时取 sha256("")，null 按空串处理。
     */
    @Test
    void canonicalWithNullBodyUsesEmptySha() {
        String expected = "1700000000\nGET\n/api/v1/chat/history\n" + TokenHash.sha256Hex("");
        assertEquals(expected,
                HmacSignatureVerifier.canonical("1700000000", "GET", "/api/v1/chat/history", null));
    }

    /**
     * 相同输入签验往返应通过。
     */
    @Test
    void signAndVerifyRoundTripPasses() {
        String canonical = HmacSignatureVerifier.canonical(
                "1700000000", "POST", "/api/v1/chat/sync", "{\"message\":\"hi\"}");
        String signature = HmacSignatureVerifier.sign(SECRET, canonical);
        assertTrue(HmacSignatureVerifier.verify(SECRET, canonical, signature));
    }

    /**
     * 篡改请求体后验签应失败。
     */
    @Test
    void verifyRejectsTamperedBody() {
        String canonical = HmacSignatureVerifier.canonical(
                "1700000000", "POST", "/api/v1/chat/sync", "{\"message\":\"hi\"}");
        String signature = HmacSignatureVerifier.sign(SECRET, canonical);
        String tampered = HmacSignatureVerifier.canonical(
                "1700000000", "POST", "/api/v1/chat/sync", "{\"message\":\"hacked\"}");
        assertFalse(HmacSignatureVerifier.verify(SECRET, tampered, signature));
    }

    /**
     * 篡改时间戳后验签应失败。
     */
    @Test
    void verifyRejectsTamperedTimestamp() {
        String canonical = HmacSignatureVerifier.canonical(
                "1700000000", "POST", "/api/v1/chat/sync", "{\"message\":\"hi\"}");
        String signature = HmacSignatureVerifier.sign(SECRET, canonical);
        String tampered = HmacSignatureVerifier.canonical(
                "1700000001", "POST", "/api/v1/chat/sync", "{\"message\":\"hi\"}");
        assertFalse(HmacSignatureVerifier.verify(SECRET, tampered, signature));
    }

    /**
     * 空签名应直接拒绝（不抛异常）。
     */
    @Test
    void verifyRejectsBlankSignature() {
        String canonical = HmacSignatureVerifier.canonical(
                "1700000000", "POST", "/api/v1/chat/sync", "");
        assertFalse(HmacSignatureVerifier.verify(SECRET, canonical, null));
        assertFalse(HmacSignatureVerifier.verify(SECRET, canonical, "  "));
    }

    /**
     * 使用错误密钥验签应失败。
     */
    @Test
    void verifyRejectsWrongSecret() {
        String canonical = HmacSignatureVerifier.canonical(
                "1700000000", "POST", "/api/v1/chat/sync", "");
        String signature = HmacSignatureVerifier.sign(SECRET, canonical);
        assertFalse(HmacSignatureVerifier.verify("another-secret", canonical, signature));
    }

    /** 包含非法UTF-8与NUL的原始字节签名不经过文本替换；任何一个字节变化均失效。 */
    @Test
    void canonicalBytesPreservesArbitraryBinaryAndRejectsTextReencoding() {
        byte[] body = {0, (byte) 0xff, (byte) 0xfe, (byte) 0xc3, 0x28, 10};
        String canonical = HmacSignatureVerifier.canonicalBytes("1700000000", "POST", "/upload?filename=fixture.bin", body);
        String signature = HmacSignatureVerifier.sign(SECRET, canonical);
        assertTrue(HmacSignatureVerifier.verify(SECRET, canonical, signature));
        assertNotEquals(canonical, HmacSignatureVerifier.canonical("1700000000", "POST", "/upload?filename=fixture.bin", new String(body, StandardCharsets.UTF_8)));
        body[2] ^= 1;
        assertFalse(HmacSignatureVerifier.verify(SECRET,
                HmacSignatureVerifier.canonicalBytes("1700000000", "POST", "/upload?filename=fixture.bin", body), signature));
        assertFalse(HmacSignatureVerifier.verify(SECRET, canonical, ""));
        assertThrows(NullPointerException.class, () -> HmacSignatureVerifier.canonicalBytes("1700000000", "POST", "/upload", null));
    }

    /** 原JSON、中文与空请求的UTF-8签名保持原契约字节，不引入另一套JSON规范化。 */
    @Test
    void textAndRawCanonicalAgreeForActualUtf8IncludingWhitespace() {
        for (String body : java.util.List.of("", "{}", "{\n  \"内容\":\"原始空白\"\n}")) {
            assertEquals(HmacSignatureVerifier.canonical("1700000000", "POST", "/api/v1/chat", body),
                    HmacSignatureVerifier.canonicalBytes("1700000000", "POST", "/api/v1/chat", body.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
