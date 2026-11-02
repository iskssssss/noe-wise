package com.skillforge.core.foundation.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP：SHA-1、30 秒、六位数字；数据库保存已消费计数器阻止重放。 */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private Totp() { }

    /** 创建 160 位随机种子，以标准无填充 Base32 交付验证器。 */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 255); bits += 8;
            while (bits >= 5) { bits -= 5; out.append(ALPHABET.charAt((buffer >> bits) & 31)); }
        }
        return out.toString();
    }

    /** 返回匹配且未消费的计数器；允许前后一个时间窗，拒绝已用或非法值。 */
    public static long match(String secret, String code, Instant now, long consumed) {
        if (code == null || !code.matches("[0-9]{6}")) return -1;
        long current = now.getEpochSecond() / 30;
        for (long counter = current - 1; counter <= current + 1; counter++) {
            if (counter > consumed && MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII),
                    generate(secret, counter).getBytes(StandardCharsets.US_ASCII))) return counter;
        }
        return -1;
    }

    /** 标准动态截断；仅用于验证和 RFC 测试，不记录种子或验证码。 */
    public static String generate(String secret, long counter) {
        try {
            byte[] key = new byte[secret.length() * 5 / 8];
            int buffer = 0, bits = 0, cursor = 0;
            for (char value : secret.toCharArray()) {
                int digit = ALPHABET.indexOf(value);
                if (digit < 0) throw new IllegalArgumentException("非法 TOTP 种子");
                buffer = (buffer << 5) | digit; bits += 5;
                if (bits >= 8) { bits -= 8; key[cursor++] = (byte) (buffer >> bits); }
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 15;
            int binary = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                    | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
            return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (java.security.GeneralSecurityException invalid) {
            throw new IllegalStateException("TOTP 密码原语不可用", invalid);
        }
    }
}
