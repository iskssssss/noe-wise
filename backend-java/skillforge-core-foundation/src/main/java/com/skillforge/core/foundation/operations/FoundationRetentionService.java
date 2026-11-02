package com.skillforge.core.foundation.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.operations.OperationsContracts.*;
import com.skillforge.core.foundation.operations.persistence.OperationsMapper;
import com.skillforge.core.foundation.operations.persistence.OperationsRows;
import com.skillforge.core.foundation.application.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 审计保留策略、冻结清理预览和执行回执；不接管Runtime事实或其他领域的清理写权。 */
@Service
public class FoundationRetentionService {
    /** 策略和回执读取。 */ public static final String READ = "foundation.operations.retention_read";
    /** 策略与调查引用管理。 */ public static final String MANAGE = "foundation.operations.retention_manage";
    /** 清理预览与执行。 */ public static final String EXECUTE = "foundation.operations.retention_execute";
    private final FoundationAccess access;
    private final OperationsMapper mapper;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final TransactionTemplate cleanupTransaction;

    /** 显式事务保证失败不会留下部分审计删除；失败回执在回滚后的独立事务登记。 */
    public FoundationRetentionService(FoundationAccess access, OperationsMapper mapper, AuditService audit,
            ObjectMapper json, PlatformTransactionManager manager) {
        this.access = access; this.mapper = mapper; this.audit = audit; this.json = json;
        this.transaction = new TransactionTemplate(manager);
        this.cleanupTransaction = new TransactionTemplate(manager);
        this.cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 初次读取会创建默认关闭的180天策略；开启清理必须经授权显式更新。 */
    public RetentionPolicy policy() {
        access.require(READ);
        return policy(currentPolicy(access.currentSubject().scope()));
    }

    /** 按明确版本完整替换策略，任何冲突拒绝覆盖。 */
    public RetentionPolicy update(int days, boolean enabled, long expectedVersion) {
        access.require(MANAGE);
        if (days < 30 || days > 3650 || expectedVersion < 0)
            throw FoundationAuditService.failure(HttpStatus.BAD_REQUEST, "operations.retention.policy_invalid", "保留天数须为30至3650，版本不可为负");
        FoundationAccess.Subject subject = access.currentSubject();
        return transaction.execute(status -> {
            currentPolicy(subject.scope());
            if (mapper.updatePolicy(subject.scope(), days, enabled, expectedVersion) != 1)
                throw FoundationAuditService.failure(HttpStatus.CONFLICT, "operations.retention.version_conflict", "保留策略已变更，请重新读取");
            audit.record(subject.tenantId(), subject.memberId(), "permission.retention.update", "retention_policy", "audit",
                    "更新审计保留策略", Map.of("retention_days", days, "enabled", enabled));
            return policy(currentPolicy(subject.scope()));
        });
    }

    /** 冻结最多1000个具体候选ID和策略版本；保护数量与预览同时展示。 */
    public CleanupRun preview() {
        access.require(EXECUTE);
        FoundationAccess.Subject subject = access.currentSubject();
        return transaction.execute(status -> {
            OperationsRows.Policy policy = currentPolicy(subject.scope());
            requireEnabled(policy);
            Instant now = Instant.now();
            Instant cutoff = now.minus(Duration.ofDays(policy.getRetentionDays()));
            List<Long> ids = mapper.candidates(subject.scope(), cutoff, 1000);
            OperationsRows.Run run = new OperationsRows.Run();
            run.setId(SnowflakeIds.nextId()); run.setScope(subject.scope()); run.setActorId(subject.memberId());
            run.setResource("audit"); run.setPolicyVersion(policy.getVersion()); run.setCutoff(cutoff);
            run.setCandidateIds(encode(ids)); run.setCandidates(ids.size());
            run.setProtectedCount(ids.isEmpty() ? 0 : mapper.protectedCount(subject.scope(), ids));
            run.setStatus("ready"); run.setExpiresAt(now.plus(Duration.ofMinutes(15))); run.setCreatedAt(now);
            mapper.insertRun(run);
            return run(run);
        });
    }

    /**
     * 仅执行冻结候选；重复执行读取原终态。事务内锁定审计行、重查当前保护和策略版本。
     * 权限撤销、策略变化或预览到期均在删除前拒绝；技术失败回滚全部删除并登记failed。
     */
    public CleanupRun execute(String previewId) {
        access.require(EXECUTE);
        long id = id(previewId);
        FoundationAccess.Subject subject = access.currentSubject();
        try {
            return cleanupTransaction.execute(status -> {
                OperationsRows.Run value = mapper.lockRun(subject.scope(), id);
                if (value == null) throw FoundationAuditService.failure(HttpStatus.NOT_FOUND, "operations.retention.preview_missing", "清理预览不存在");
                if (!"ready".equals(value.getStatus())) return run(value);
                if (!value.getExpiresAt().isAfter(Instant.now())) {
                    return finishRun(subject.scope(), id, "expired", 0, value.getProtectedCount());
                }
                OperationsRows.Policy policy = currentPolicy(subject.scope());
                requireEnabled(policy);
                if (policy.getVersion() != value.getPolicyVersion())
                    throw FoundationAuditService.failure(HttpStatus.CONFLICT, "operations.retention.policy_changed", "策略已变更，请生成新的清理预览");
                List<Long> ids = decode(value.getCandidateIds());
                // 确定顺序取得行锁，与保护登记使用同一事实行锁避免并发删除正在登记的引用。
                ids.stream().sorted().forEach(candidate -> mapper.lockAudit(subject.scope(), candidate));
                access.require(EXECUTE);
                long protectedCount = ids.isEmpty() ? 0 : mapper.protectedCount(subject.scope(), ids);
                int deleted = ids.isEmpty() ? 0 : mapper.deleteAudits(subject.scope(), ids, value.getCutoff());
                CleanupRun receipt = finishRun(subject.scope(), id, "succeeded", deleted, protectedCount);
                audit.record(subject.tenantId(), subject.memberId(), "operation.retention.execute", "cleanup_run", previewId,
                        "执行审计保留清理", Map.of("deleted_count", deleted, "protected_count", protectedCount));
                return receipt;
            });
        } catch (com.skillforge.core.shared.exception.ApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            cleanupTransaction.executeWithoutResult(status -> mapper.finishRun(subject.scope(), id, "failed", 0, -1, "cleanup_database_failure"));
            throw new com.skillforge.core.shared.exception.ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "清理执行失败，删除事务已回滚，可查询失败回执", failure);
        }
    }

    /** 首次响应与重放均投影同一完整持久回执，包括数据库生成的精确完成时间。 */
    private CleanupRun finishRun(String scope, long id, String status, long deleted, long protectedCount) {
        if (mapper.finishRun(scope, id, status, deleted, protectedCount, null) != 1)
            throw new IllegalStateException("cleanup_receipt_conflict");
        return run(mapper.lockRun(scope, id));
    }

    /** 读取当前作用域最近100条清理回执。 */
    public List<CleanupRun> runs() {
        access.require(READ);
        return mapper.runs(access.currentSubject().scope()).stream().map(FoundationRetentionService::run).toList();
    }

    /** 登记审计调查引用，非本作用域事实不能被读取或保护。 */
    public AuditHold hold(String auditId, String reason, Instant expiresAt) {
        access.require(MANAGE);
        long target = id(auditId);
        if (reason == null || reason.isBlank() || reason.length() > 500
                || (expiresAt != null && !expiresAt.isAfter(Instant.now())))
            throw FoundationAuditService.failure(HttpStatus.BAD_REQUEST, "operations.retention.hold_invalid", "保护原因或到期时间无效");
        FoundationAccess.Subject subject = access.currentSubject();
        return transaction.execute(status -> {
            if (mapper.lockAudit(subject.scope(), target) == null)
                throw FoundationAuditService.failure(HttpStatus.NOT_FOUND, "operations.audit.missing", "审计事实不存在");
            OperationsRows.Hold hold = new OperationsRows.Hold();
            hold.setId(SnowflakeIds.nextId()); hold.setScope(subject.scope()); hold.setAuditId(target);
            hold.setReason(reason); hold.setExpiresAt(expiresAt); mapper.insertHold(hold);
            audit.record(subject.tenantId(), subject.memberId(), "permission.audit.hold", "audit", auditId,
                    "登记审计调查保护", Map.of("hold_id", hold.getId().toString()));
            return hold(hold);
        });
    }

    /** 读取本作用域保护记录，包含已解除记录。 */
    public List<AuditHold> holds() {
        access.require(READ);
        return mapper.holds(access.currentSubject().scope()).stream().map(FoundationRetentionService::hold).toList();
    }

    /** 显式解除具体保护，保留解除事实，重复请求无新副作用。 */
    public void release(String holdId) {
        access.require(MANAGE);
        FoundationAccess.Subject subject = access.currentSubject();
        transaction.executeWithoutResult(status -> {
            if (mapper.releaseHold(subject.scope(), id(holdId)) == 1)
                audit.record(subject.tenantId(), subject.memberId(), "permission.audit.hold.release", "audit_hold", holdId,
                        "解除审计调查保护", Map.of());
        });
    }

    /** 按作用域创建策略，冲突时读取已提交权威行。 */
    private OperationsRows.Policy currentPolicy(String scope) {
        OperationsRows.Policy policy = mapper.policy(scope, "audit");
        if (policy != null) return policy;
        policy = new OperationsRows.Policy(); policy.setScope(scope); policy.setResource("audit");
        policy.setRetentionDays(180); policy.setEnabled(false); policy.setVersion(0);
        mapper.createPolicy(policy);
        return mapper.policy(scope, "audit");
    }

    /** 默认关闭的策略无法发生删除。 */
    private static void requireEnabled(OperationsRows.Policy policy) {
        if (!policy.isEnabled()) throw FoundationAuditService.failure(HttpStatus.CONFLICT, "operations.retention.disabled", "清理策略尚未启用");
    }

    /** 候选ID编码仅由受控查询产生。 */
    private String encode(List<Long> ids) {
        try { return json.writeValueAsString(ids); }
        catch (java.io.IOException failure) { throw new IllegalStateException("cleanup_preview_encode_failed", failure); }
    }

    /** 恢复原冻结候选，拒绝非法或超预算的持久内容。 */
    private List<Long> decode(String value) {
        try {
            List<Long> ids = json.readValue(value, new TypeReference<List<Long>>() { });
            if (ids.size() > 1000 || ids.stream().anyMatch(id -> id == null || id <= 0)) throw new IllegalStateException("cleanup_preview_invalid");
            return ids;
        } catch (java.io.IOException failure) { throw new IllegalStateException("cleanup_preview_decode_failed", failure); }
    }

    /** ID协议只接受正十进制数字。 */
    private static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException failure) { throw FoundationAuditService.failure(HttpStatus.BAD_REQUEST, "operations.id_invalid", "ID格式无效"); }
    }

    /** 显式投影策略，避免返回数据库行。 */
    private static RetentionPolicy policy(OperationsRows.Policy value) { return new RetentionPolicy(value.getResource(), value.getRetentionDays(), value.isEnabled(), value.getVersion()); }
    /** 显式投影清理回执。 */
    private static CleanupRun run(OperationsRows.Run value) { return new CleanupRun(value.getId().toString(), value.getResource(), value.getCutoff(), value.getCandidates(), value.getProtectedCount(), value.getDeletedCount(), value.getStatus(), value.getExpiresAt(), value.getCreatedAt(), value.getCompletedAt(), value.getFailureCode()); }
    /** 显式投影保护关系。 */
    private static AuditHold hold(OperationsRows.Hold value) { return new AuditHold(value.getId().toString(), value.getAuditId().toString(), value.getReason(), value.getExpiresAt(), value.getReleasedAt()); }
}
