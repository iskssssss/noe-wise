package com.skillforge.institution.attachment;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 会话附件和图片共用的不可变正文所有者。元数据创建方拥有外层事务，正文注册、容量核算、
 * 引用创建及回收在同一租户主行锁下串行；此组件不另行提交，也不授予会话访问权。
 */
@Service
public class SessionAttachmentBodyStore {
    public static final String FORMAT = "bytea.v1";
    private final SessionAttachmentBodyMapper mapper;
    private final long maxSessionBytes;
    private final long maxTenantBytes;

    /** 0 明确表示尚未配置累积预算；单文件与单消息的既有限制继续由原入口执行。 */
    public SessionAttachmentBodyStore(SessionAttachmentBodyMapper mapper,
            @Value("${skillforge.chat-storage.max-session-bytes:0}") long maxSessionBytes,
            @Value("${skillforge.chat-storage.max-tenant-bytes:0}") long maxTenantBytes) {
        if (maxSessionBytes < 0 || maxTenantBytes < 0) throw new IllegalArgumentException("attachment_storage_budget_invalid");
        this.mapper = mapper;
        this.maxSessionBytes = maxSessionBytes;
        this.maxTenantBytes = maxTenantBytes;
    }

    /** 先锁租户主行，后续 SQL 在 READ COMMITTED 下看见此前持锁事务的完整提交。 */
    public void lockTenant(String tenant) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("attachment_storage_transaction_required");
        if (!tenant.equals(TenantContext.getTenantId())) throw new SecurityException("attachment_storage_tenant_mismatch");
        if (!tenant.equals(mapper.lockTenant(tenant))) throw new SecurityException("attachment_storage_tenant_missing");
    }

    /** 相同正文先核对原始字节并复用；只有不存在的正文才 INSERT，不写无变化 UPDATE。 */
    public void register(String tenant, String productSession, String sha256, byte[] bytes) {
        lockTenant(tenant);
        if (bytes == null || bytes.length == 0 || !digest(bytes).equals(sha256))
            throw new IllegalArgumentException("attachment_body_identity_invalid");
        var existing = mapper.find(tenant, sha256, bytes.length);
        if (existing != null && !Arrays.equals(bytes, existing.content()))
            throw new IllegalStateException("attachment_body_digest_collision");
        if (maxSessionBytes > 0 || maxTenantBytes > 0) {
            var capacity = mapper.capacity(tenant, productSession, sha256, bytes.length);
            if (capacity == null) throw new IllegalStateException("attachment_storage_capacity_missing");
            long sessionGrowth = capacity.inSession() ? 0 : bytes.length;
            long tenantGrowth = existing == null ? bytes.length : 0;
            requireBudget(capacity.sessionBytes(), sessionGrowth, maxSessionBytes, "attachment_session_capacity_exceeded", "当前会话存储已达到配置容量上限");
            requireBudget(capacity.tenantBytes(), tenantGrowth, maxTenantBytes, "attachment_tenant_capacity_exceeded", "机构会话存储已达到配置容量上限");
        }
        if (existing == null && mapper.insert(tenant, sha256, bytes.length, bytes) != 1)
            throw new IllegalStateException("attachment_body_registration_conflict");
    }

    /** 授权后的会话容量观察；不读取或返回其它会话的租户累计数据。 */
    public SessionStorageBudget inspectSession(String tenant, String productSession) {
        if (!tenant.equals(TenantContext.getTenantId())) throw new SecurityException("attachment_storage_tenant_mismatch");
        return new SessionStorageBudget(Long.toString(mapper.sessionBytes(tenant, productSession)),
                Long.toString(maxSessionBytes), maxSessionBytes > 0);
    }

    /** 仅由已核对机构管理权限及全数据范围的 CapacityService 调用，普通会话入口不使用。 */
    public TenantStorageBudget inspectTenant(String tenant) {
        if (!tenant.equals(TenantContext.getTenantId())) throw new SecurityException("attachment_storage_tenant_mismatch");
        return new TenantStorageBudget(Long.toString(mapper.tenantBytes(tenant)), Long.toString(maxTenantBytes), maxTenantBytes > 0);
    }

    private static void requireBudget(long used, long growth, long limit, String reason, String message) {
        if (growth > 0 && limit > 0 && (used > limit || growth > limit - used))
            throw new ApiException(HttpStatus.CONFLICT, reason, message, false);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SessionStorageBudget(String sessionBytes, String sessionLimitBytes, boolean sessionConfigured) { }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TenantStorageBudget(String tenantBytes, String tenantLimitBytes, boolean tenantConfigured) { }
}
