package com.skillforge.institution.memory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 治理受理的完整固定请求；请求键可以省略，其余身份和操作均不可变。 */
public record MemoryGovernanceRequest(String tenantId, String factId, long expectedRevision,
        Operation operation, String requestedKey, String fingerprint) {
    /** 操作名同时用于领域请求及持久回执，不与Runtime mutation状态混用。 */
    public enum Operation { TRANSITION, REVIEW, CORRECT }

    /** 拒绝缺失身份、操作、摘要或非正版本；请求键的省略不改变业务请求字段。 */
    public MemoryGovernanceRequest {
        requireText(tenantId, "tenantId");
        requireText(factId, "factId");
        Objects.requireNonNull(operation, "operation");
        requireText(fingerprint, "fingerprint");
        if (expectedRevision <= 0) throw new IllegalArgumentException("expectedRevision must be positive");
    }

    /** 缺省键只由完整业务摘要派生，不引入当前时间或随机身份。 */
    String key() {
        return requestedKey == null || requestedKey.isBlank() ? "memory-governance:auto:" + fingerprint : requestedKey;
    }

    /** UTF-8字段按有符号大端长度编码；null用-1、空串用0，字段总数也参与摘要。 */
    static String fingerprint(String... fields) {
        Objects.requireNonNull(fields, "fields");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(fields.length).array());
            for (String field : fields) {
                byte[] bytes = field == null ? null : field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("memory_governance_fingerprint_unavailable", unavailable);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
