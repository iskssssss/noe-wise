package com.skillforge.core.foundation.application;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.core.shared.config.SkillForgeProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 凭据加密主密钥必须显式可靠配置，错误响应不包含秘密。 */
class CryptoServiceTest {
    private static final String CURRENT = "synthetic-crypto-service-master-key-32";
    private static final String NEXT = "synthetic-next-generation-master-key-32";
    private static final String HISTORICAL = "change-me-synthetic-historical-key";

    /** 缺失、过短及公开占位密钥在构造边界拒绝。 */
    @Test void rejectsMissingShortAndPlaceholderMasterKeys() {
        for(String value:new String[]{"", "short", "change_me_synthetic_master_key_over_32_characters",
                "change-me-synthetic-master-key-over-32-characters"}) {
            var properties=new SkillForgeProperties(); properties.setSecretKey(value);
            var failure=assertThrows(IllegalStateException.class,()->new CryptoService(properties));
            if(!value.isEmpty()) assertFalse(failure.getMessage().contains(value));
        }
        assertThrows(IllegalStateException.class,()->new CryptoService(new SkillForgeProperties()));
        var missing = properties(CURRENT); missing.setSecretKey(null);
        assertThrows(IllegalStateException.class, () -> new CryptoService(missing));
    }
    /** 随机nonce使相同正文密文不同，解密恢复原文且认证标签拒绝损坏。 */
    @Test void encryptsWithAuthenticatedCiphertextAndRandomNonce() {
        var crypto = new CryptoService(properties(CURRENT)); String first=crypto.encrypt("one-time-test-token");
        assertTrue(first.matches("sfenc:1:[0-9a-f]{32}:[A-Za-z0-9+/=]+"));
        assertNotEquals(first,crypto.encrypt("one-time-test-token")); assertEquals("one-time-test-token",crypto.decrypt(first));
        String[] parts = first.split(":", 4);
        byte[] bytes = Base64.getDecoder().decode(parts[3]); bytes[bytes.length-1]^=1;
        String damaged = header(parts) + Base64.getEncoder().encodeToString(bytes);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(damaged));
        assertEquals("", crypto.decrypt(crypto.encrypt("")));
        assertEquals("中文一次性凭据", crypto.decrypt(crypto.encrypt("中文一次性凭据")));
    }

    /** 独立旧格式夹具沿用 SHA256(master) 与无 AAD 的 IV+密文，不由新实现生成旧数据。 */
    @Test void readsHistoricalLegacyCiphertextButWritesOnlyWithCurrentStrongKey() throws Exception {
        String legacy = legacyEncrypt(HISTORICAL, "historical-model-credential");
        assertThrows(IllegalStateException.class, () -> new CryptoService(properties(CURRENT)).decrypt(legacy));
        var rotated = new CryptoService(properties(CURRENT, HISTORICAL));
        assertEquals("historical-model-credential", rotated.decrypt(legacy));
        String rewritten = rotated.encrypt(rotated.decrypt(legacy));
        assertEquals("historical-model-credential", new CryptoService(properties(CURRENT)).decrypt(rewritten));
        assertNotEquals(legacy, rewritten);
        assertThrows(IllegalStateException.class,
                () -> new CryptoService(properties(NEXT, HISTORICAL)).decrypt(rewritten));
    }

    /** 增加 keyring 仍不能把公开旧 key 作为当前写入密钥启动。 */
    @Test void historicalKeyCannotBypassCurrentKeyValidation() {
        var invalid = properties(HISTORICAL, CURRENT);
        assertThrows(IllegalStateException.class, () -> new CryptoService(invalid));
    }

    /** 当前强 key 以前写入的无版本密文无需额外历史配置。 */
    @Test void readsLegacyCiphertextUsingCurrentKey() throws Exception {
        assertEquals("existing-strong-key-credential",
                new CryptoService(properties(CURRENT)).decrypt(legacyEncrypt(CURRENT, "existing-strong-key-credential")));
    }

    /** 二次轮换按封套 key id 定位，只把明确登记的旧写入 key 留作解密。 */
    @Test void readsVersionedCiphertextAcrossRotationAndKeepsNewWritesOnNextKey() {
        String original = new CryptoService(properties(CURRENT)).encrypt("versioned-credential");
        var next = new CryptoService(properties(NEXT, CURRENT));
        assertEquals("versioned-credential", next.decrypt(original));
        assertThrows(IllegalStateException.class, () -> new CryptoService(properties(NEXT)).decrypt(original));
        String newer = next.encrypt("new-generation-credential");
        assertEquals("new-generation-credential", new CryptoService(properties(NEXT)).decrypt(newer));
        assertThrows(IllegalStateException.class, () -> new CryptoService(properties(CURRENT)).decrypt(newer));
    }

    /** 版本、未知身份及缺损封套直接拒绝；绝不把 body 降级成旧格式再试。 */
    @Test void rejectsUnknownVersionsKeyIdsAndMalformedEnvelopesWithoutFallback() throws Exception {
        var crypto = new CryptoService(properties(CURRENT, HISTORICAL));
        String[] parts = crypto.encrypt("envelope-data").split(":", 4);
        String validLegacyBody = legacyEncrypt(HISTORICAL, "must-not-fall-back");
        for (String encoded : List.of("sfenc:2:" + parts[2] + ":" + validLegacyBody,
                "sfenc:1:" + "0".repeat(32) + ":" + validLegacyBody,
                "sfenc:1:" + parts[2] + ":" + validLegacyBody,
                "sfenc:1:" + parts[2], "sfenc:1::" + parts[3], "sfenc:1:" + parts[2] + ":")) {
            assertThrows(IllegalStateException.class, () -> crypto.decrypt(encoded));
        }
    }

    /** 替换成已知 key id 仍须通过头部 AAD；移除头部也不能把新密文降级读取。 */
    @Test void authenticatesEnvelopeHeaderAndRejectsDowngrade() {
        var crypto = new CryptoService(properties(CURRENT, NEXT));
        String[] original = crypto.encrypt("authenticated-header").split(":", 4);
        String nextId = new CryptoService(properties(NEXT)).encrypt("other").split(":", 4)[2];
        assertThrows(IllegalStateException.class, () -> crypto.decrypt("sfenc:1:" + nextId + ":" + original[3]));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(original[3]));
    }

    /** 独立 wire fixture 验证完整头部确实是 AAD，密钥定位成功也不能忽略它。 */
    @Test void envelopeHeaderIsGcmAdditionalAuthenticatedData() throws Exception {
        var crypto = new CryptoService(properties(CURRENT));
        String[] parts = crypto.encrypt("original").split(":", 4);
        assertEquals("independent-wire-value", crypto.decrypt(header(parts)
                + encryptBody(CURRENT, "independent-wire-value", header(parts))));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(header(parts)
                + encryptBody(CURRENT, "independent-wire-value", "different-authenticated-header")));
    }

    /** 配置有明确数量上限，重复、空值、非法 Base64/UTF-8 在启动时拒绝。 */
    @Test void rejectsUnboundedDuplicateAndMalformedHistoricalKeyConfiguration() {
        var excessive = properties(CURRENT, "old-one", "old-two", "old-three", "old-four", "old-five");
        assertThrows(IllegalStateException.class, () -> new CryptoService(excessive));
        for (SkillForgeProperties configured : List.of(properties(CURRENT, CURRENT),
                properties(CURRENT, "old-one", "old-one"), properties(CURRENT, "  "))) {
            assertThrows(IllegalStateException.class, () -> new CryptoService(configured));
        }
        var invalid = properties(CURRENT);
        invalid.setSecretKeyDecryptionKeys(List.of("not-base64!"));
        var failure = assertThrows(IllegalStateException.class, () -> new CryptoService(invalid));
        assertFalse(failure.getMessage().contains("not-base64!")); assertNull(failure.getCause());
        invalid.setSecretKeyDecryptionKeys(List.of(Base64.getEncoder().encodeToString(new byte[]{(byte) 0xff})));
        assertThrows(IllegalStateException.class, () -> new CryptoService(invalid));
        invalid.setSecretKeyDecryptionKeys(List.of(""));
        assertThrows(IllegalStateException.class, () -> new CryptoService(invalid));
        invalid.setSecretKeyDecryptionKeys(null);
        assertThrows(IllegalStateException.class, () -> new CryptoService(invalid));
    }

    /** Base64 配置保存旧 key 的空格、逗号与 Unicode 原始字节，且支持全部 4 个历史 key。 */
    @Test void preservesExactHistoricalKeyBytesAndSupportsBoundedKeyring() throws Exception {
        String last = "  历史,主密钥  ";
        var crypto = new CryptoService(properties(CURRENT, "old-one", "old-two", "old-three", last));
        assertEquals("exact-old-credential", crypto.decrypt(legacyEncrypt(last, "exact-old-credential")));
        assertThrows(IllegalStateException.class,
                () -> crypto.decrypt(legacyEncrypt(last.trim(), "different-old-key")));
    }

    /** 应用 YAML 的空环境值绑定为空列表；逗号配置只分割 Base64 项，不处理密钥原字节。 */
    @Test void bindsOptionalSharedHostKeyringConfiguration() throws Exception {
        for (String configured : List.of("", encoded(HISTORICAL) + "," + encoded(CURRENT))) {
            var source = new MapConfigurationPropertySource(Map.of("skillforge.secret-key", NEXT,
                    "skillforge.secret-key-decryption-keys", configured));
            var bound = new Binder(source).bind("skillforge", Bindable.of(SkillForgeProperties.class)).get();
            assertEquals(configured.isEmpty() ? 0 : 2, bound.getSecretKeyDecryptionKeys().size());
            var crypto = new CryptoService(bound);
            if (!configured.isEmpty()) assertEquals("configured-historical-value",
                    crypto.decrypt(legacyEncrypt(HISTORICAL, "configured-historical-value")));
        }
    }

    /** 指纹不能暴露真实 AES key，配置对象的诊断字符串也不包含当前或历史秘密。 */
    @Test void doesNotExposeEncryptionKeyInFingerprintOrConfigurationDiagnostics() throws Exception {
        var configured = properties(CURRENT, HISTORICAL);
        String keyId = new CryptoService(configured).encrypt("credential").split(":", 4)[2];
        String aesKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CURRENT.getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(aesKey.substring(0, 32), keyId);
        assertFalse(configured.toString().contains(CURRENT));
        assertFalse(configured.toString().contains(HISTORICAL));
        assertFalse(configured.toString().contains(encoded(HISTORICAL)));
    }

    /** 短密文、非法 Base64、未知 key 的旧密文及 nonce 损坏均失败关闭。 */
    @Test void rejectsMalformedLegacyBodiesWrongKeysAndTamperedNonces() throws Exception {
        var crypto = new CryptoService(properties(CURRENT, HISTORICAL));
        for (String encoded : List.of("", "not-base64!", Base64.getEncoder().encodeToString(new byte[27]),
                legacyEncrypt("unregistered-historical-key", "unknown"))) {
            assertThrows(IllegalStateException.class, () -> crypto.decrypt(encoded));
        }
        byte[] damaged = Base64.getDecoder().decode(legacyEncrypt(HISTORICAL, "nonce-protected")); damaged[0] ^= 1;
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(Base64.getEncoder().encodeToString(damaged)));
    }

    private static SkillForgeProperties properties(String current, String... historical) {
        var configured = new SkillForgeProperties(); configured.setSecretKey(current);
        configured.setSecretKeyDecryptionKeys(Arrays.stream(historical).map(CryptoServiceTest::encoded).toList());
        return configured;
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String header(String[] parts) { return parts[0] + ":" + parts[1] + ":" + parts[2] + ":"; }

    private static String legacyEncrypt(String master, String plaintext) throws Exception {
        return encryptBody(master, plaintext, null);
    }

    /** 测试夹具固定 IV，仅用于生成独立 wire 样本；生产 nonce 随机性单独断言。 */
    private static String encryptBody(String master, String plaintext, String aad) throws Exception {
        byte[] iv = new byte[12]; Arrays.fill(iv, (byte) 7);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(master.getBytes(StandardCharsets.UTF_8)), "AES"),
                new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad.getBytes(StandardCharsets.US_ASCII));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = Arrays.copyOf(iv, iv.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return Base64.getEncoder().encodeToString(combined);
    }
}
