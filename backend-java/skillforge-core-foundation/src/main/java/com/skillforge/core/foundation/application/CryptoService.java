package com.skillforge.core.foundation.application;

import com.skillforge.core.shared.config.SkillForgeProperties;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * 应用层加密服务。
 *
 * <p>使用 AES-GCM 对称加密保护敏感字段（如连接器凭证）。密钥由应用配置中的
 * SECRET_KEY 经 SHA-256 派生。新密文携带认证后的版本和密钥身份；显式历史密钥只用于
 * 解密，使不可变 Runtime 快照保持原字节。该 keyring 不改变历史弱密钥的泄漏风险，
 * 完整 KEK/DEK 信封加密将在后续阶段接入。
 */
@Service
public class CryptoService {

    /** GCM 初始化向量长度（字节）。 */
    private static final int IV_LENGTH = 12;

    /** GCM 认证标签长度（位）。 */
    private static final int TAG_LENGTH_BITS = 128;

    /** 有界历史解密配置；无版本旧密文最多尝试当前密钥及 4 个显式历史密钥。 */
    private static final int MAX_DECRYPTION_KEYS = 4;

    /** 封套头整体作为 AAD；版本或密钥身份漂移必须令 GCM 认证失败。 */
    private static final String ENVELOPE_PREFIX = "sfenc:";
    private static final String ENVELOPE_VERSION = "1";

    /** 密钥身份使用域分离摘要，绝不暴露 SHA256(master) 这一实际 AES 密钥。 */
    private static final byte[] KEY_ID_DOMAIN = "skillforge:aes-gcm:key-id:v1\0".getBytes(StandardCharsets.US_ASCII);

    /** 派生的 AES 密钥。 */
    private final SecretKeySpec key;

    /** 当前密钥的公开身份与认证封套头，不包含主密钥或派生 AES 密钥。 */
    private final String keyId;
    private final String envelopeHeader;

    /** 按身份精确定位版本化密文；仅无版本密文允许遍历该有界列表。 */
    private final Map<String, SecretKeySpec> decryptionKeys;
    private final List<SecretKeySpec> legacyDecryptionKeys;

    /** 安全随机数生成器，用于生成初始化向量。 */
    private final SecureRandom random = new SecureRandom();

    /**
     * 构造加密服务。
     *
     * @param properties 应用配置，提供 SECRET_KEY
     */
    public CryptoService(SkillForgeProperties properties) {
        String masterKey = properties.getSecretKey();
        // 凭据和安全通知正文不能以公开默认密钥保护；启动时拒绝占位符而非延迟到派发。
        if (masterKey == null || masterKey.isBlank() || masterKey.length() < 32
                || masterKey.toLowerCase(java.util.Locale.ROOT).contains("change-me")
                || masterKey.toLowerCase(java.util.Locale.ROOT).contains("change_me")) {
            throw new IllegalStateException("SECRET_KEY 必须配置为至少 32 字符的独立随机主密钥");
        }
        try {
            this.key = derive(masterKey.getBytes(StandardCharsets.UTF_8));
            this.keyId = identify(key);
            this.envelopeHeader = ENVELOPE_PREFIX + ENVELOPE_VERSION + ":" + keyId + ":";
            var configured = properties.getSecretKeyDecryptionKeys();
            if (configured == null || configured.size() > MAX_DECRYPTION_KEYS) {
                throw new IllegalArgumentException("historical_decryption_keys_invalid");
            }
            var keys = new LinkedHashMap<String, SecretKeySpec>();
            keys.put(keyId, key);
            for (String encoded : configured) {
                if (encoded == null || encoded.isBlank()) {
                    throw new IllegalArgumentException("historical_decryption_key_invalid");
                }
                byte[] bytes = Base64.getDecoder().decode(encoded);
                try {
                    // 不 trim、不归一化历史主密钥；UTF-8/Base64 只是精确传递原字节。
                    String historical = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString();
                    if (historical.isBlank()) throw new IllegalArgumentException("historical_decryption_key_invalid");
                    var historicalKey = derive(bytes);
                    if (keys.putIfAbsent(identify(historicalKey), historicalKey) != null) {
                        throw new IllegalArgumentException("historical_decryption_key_duplicate");
                    }
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
            this.decryptionKeys = Map.copyOf(keys);
            this.legacyDecryptionKeys = List.copyOf(keys.values());
        } catch (Exception e) {
            // 配置异常不携带原值或解析器异常，避免框架启动诊断回显历史密钥。
            throw new IllegalStateException("业务加密密钥配置无效");
        }
    }

    /**
     * 加密明文。
     *
     * <p>输出格式为 sfenc:1:keyId:Base64(nonce + ciphertext + tag)，完整头部作为 GCM AAD。
     *
     * @param plaintext 明文
     * @return 带认证版本和密钥身份的密文，始终使用当前主密钥
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(envelopeHeader.getBytes(StandardCharsets.US_ASCII));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return envelopeHeader + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解密密文。
     *
     * <p>版本化密文按认证头中的 keyId 单键定位；未知版本或身份禁止退回历史解密。
     * 无前缀旧格式保留原 SHA-256/AES-GCM 语义，只尝试当前及显式有界历史密钥。
     *
     * @param encoded 版本化密文或历史 Base64 密文
     * @return 解密后的明文
     */
    public String decrypt(String encoded) {
        try {
            if (encoded.startsWith(ENVELOPE_PREFIX)) {
                String[] parts = encoded.split(":", 4);
                if (parts.length != 4 || !ENVELOPE_VERSION.equals(parts[1])
                        || !parts[2].matches("[0-9a-f]{32}")) {
                    throw new IllegalArgumentException("ciphertext_envelope_invalid");
                }
                SecretKeySpec selected = decryptionKeys.get(parts[2]);
                if (selected == null) throw new IllegalArgumentException("ciphertext_key_unknown");
                String header = ENVELOPE_PREFIX + parts[1] + ":" + parts[2] + ":";
                return decryptBody(parts[3], selected, header);
            }
            byte[] combined = Base64.getDecoder().decode(encoded);
            for (SecretKeySpec selected : legacyDecryptionKeys) {
                try {
                    return decryptBody(combined, selected, null);
                } catch (javax.crypto.AEADBadTagException wrongKey) {
                    // 仅已校验长度的无版本旧格式可尝试下一个显式密钥；其他损坏直接拒绝。
                }
            }
            throw new IllegalArgumentException("ciphertext_authentication_failed");
        } catch (Exception e) {
            throw new IllegalStateException("解密失败", e);
        }
    }

    /** 保持旧格式的派生算法；原始主密钥字节只在派生期间使用。 */
    private static SecretKeySpec derive(byte[] masterBytes) throws java.security.NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(masterBytes);
        try { return new SecretKeySpec(digest, "AES"); }
        finally { Arrays.fill(digest, (byte) 0); Arrays.fill(masterBytes, (byte) 0); }
    }

    /** 公开身份与 AES 密钥使用不同摘要域，截取 128 位用于有界 keyring 定位。 */
    private static String identify(SecretKeySpec selected) throws java.security.NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(KEY_ID_DOMAIN);
        byte[] derivedBytes = selected.getEncoded();
        try { return HexFormat.of().formatHex(digest.digest(derivedBytes), 0, 16); }
        finally { Arrays.fill(derivedBytes, (byte) 0); }
    }

    private static String decryptBody(String encoded, SecretKeySpec selected, String header) throws Exception {
        return decryptBody(Base64.getDecoder().decode(encoded), selected, header);
    }

    /** 最短密文也须包含 12 字节 IV 与 16 字节标签；不存在零填充/短密文容错。 */
    private static String decryptBody(byte[] combined, SecretKeySpec selected, String header) throws Exception {
        if (combined.length < IV_LENGTH + TAG_LENGTH_BITS / 8) {
            throw new IllegalArgumentException("ciphertext_body_invalid");
        }
        byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, selected, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        if (header != null) cipher.updateAAD(header.getBytes(StandardCharsets.US_ASCII));
        byte[] plaintext = cipher.doFinal(ciphertext);
        try { return new String(plaintext, StandardCharsets.UTF_8); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    /**
     * 对敏感值进行脱敏展示。
     *
     * @param value 原始值
     * @return 前 4 位 + *** 的脱敏结果；null 时返回 null
     */
    public String mask(String value) {
        if (value == null) {
            return null;
        }
        int keep = Math.min(4, value.length());
        return value.substring(0, keep) + "***";
    }
}
