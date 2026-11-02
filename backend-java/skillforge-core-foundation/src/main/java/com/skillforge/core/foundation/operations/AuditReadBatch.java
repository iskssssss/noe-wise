package com.skillforge.core.foundation.operations;

import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.operations.OperationsContracts.AuditQuery;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 同主体、当前授权版本和精确过滤范围的一分钟读取批次；分页与刷新属于同一授权检索。
 * 首次访问留下不可变安全事实和类型化范围，后续读取仍实时鉴权；不聚合导出或权限拒绝事实。
 *
 * @param auditId 首次成功受理对应的审计事实身份
 * @param scope 可信租户或 platform 作用域
 * @param actorId 已认证操作者身份
 * @param authorizationVersion 本次实时授权投影版本
 * @param queryDigest 版本化、无歧义的精确过滤摘要，不含分页
 * @param windowStart 包含的 UTC 分钟开始时刻
 * @param windowEnd 不包含的 UTC 分钟结束时刻
 * @param query 固定列持久保存的过滤条件；分页不承担审计范围
 */
public record AuditReadBatch(long auditId, String scope, String actorId, long authorizationVersion,
        String queryDigest, Instant windowStart, Instant windowEnd, AuditQuery query) {
    /** 从已经校验的主体、实时授权与过滤条件建立有界读取身份。 */
    static AuditReadBatch create(FoundationAccess.Subject subject, long authorizationVersion,
            AuditQuery query, Instant observedAt) {
        Instant start = Instant.ofEpochSecond(Math.floorDiv(observedAt.getEpochSecond(), 60) * 60);
        return new AuditReadBatch(SnowflakeIds.nextId(), subject.scope(), subject.memberId(), authorizationVersion,
                digest(query), start, start.plusSeconds(60), query);
    }

    /** 用长度前缀及 NULL 标记保护空值、字段分界和格式版本，避免不同授权查询共享摘要。 */
    private static String digest(AuditQuery query) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                field(output, query.action()); field(output, query.actorId()); field(output, query.category());
                field(output, query.outcome()); field(output, query.resourceType()); field(output, query.traceId());
                field(output, query.from() == null ? null : query.from().toString());
                field(output, query.to() == null ? null : query.to().toString());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("audit_read_batch_identity_failed", failure);
        }
    }

    /** 明确区分未提供字段与实际字符串，保留 UTF-8 精确值。 */
    private static void field(DataOutputStream output, String value) throws IOException {
        if (value == null) { output.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }
}
