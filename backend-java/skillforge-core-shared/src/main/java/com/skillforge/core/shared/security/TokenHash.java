package com.skillforge.core.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 令牌哈希工具。
 *
 * <p>使用 SHA-256 对渠道令牌与刷新令牌取哈希后落库，
 * 避免明文令牌入库。
 */
public final class TokenHash {

    private TokenHash() {
    }

    /**
     * 计算 SHA-256 十六进制摘要。
     *
     * @param value 原始令牌
     * @return 64 位十六进制摘要
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("令牌哈希计算失败", e);
        }
    }
}
