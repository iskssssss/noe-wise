package com.skillforge.core.knowledge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalReviewResult;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalProjectionExecutor;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalSourceVerifier;
import com.skillforge.core.knowledge.persistence.KnowledgeWriteProposalMapper;
import com.skillforge.core.knowledge.persistence.KnowledgeWriteProposalDO;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

/** Knowledge 写回提案治理队列；审批后执行事实源幂等投影。 */
@Service
public class KnowledgeWriteProposalService {

    private static final String PENDING = "pending_confirm";
    private static final String APPROVED = "approved";
    private static final String REJECTED = "rejected";
    private static final String PROJECTION_PENDING = "pending";
    private static final String PROJECTION_APPLIED = "applied";
    private static final String PROJECTION_FAILED = "failed";

    private final KnowledgeWriteProposalMapper mapper;
    private final List<KnowledgeWriteProposalProjectionExecutor> projectionExecutors;
    private final List<KnowledgeWriteProposalSourceVerifier> sourceVerifiers;

    @Autowired
    public KnowledgeWriteProposalService(KnowledgeWriteProposalMapper mapper,
                                         List<KnowledgeWriteProposalProjectionExecutor> projectionExecutors,
                                         List<KnowledgeWriteProposalSourceVerifier> sourceVerifiers) {
        this.mapper = mapper;
        this.projectionExecutors = projectionExecutors == null
                ? List.of() : List.copyOf(projectionExecutors);
        this.sourceVerifiers = sourceVerifiers == null ? List.of() : List.copyOf(sourceVerifiers);
    }

    /** 创建待治理提案；不会触碰 MemoryFact 或机构知识事实源。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeWriteProposal create(String tenantId, KnowledgeWriteProposal proposal) {
        requireTenant(tenantId);
        if (proposal == null || proposal.status() != KnowledgeWriteProposal.Status.PENDING_CONFIRM) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "写回提案必须从 pending_confirm 创建");
        }
        validatePayload(proposal.payload());
        if (sourceVerifiers.size() != 1) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "写回提案来源验证器必须且只能装配一个");
        }
        sourceVerifiers.getFirst().verify(tenantId, proposal);
        KnowledgeWriteProposalDO row = new KnowledgeWriteProposalDO();
        row.setId(SnowflakeIds.nextId());
        row.setTenantId(parseTenant(tenantId));
        row.setProposalId(proposal.proposalId());
        row.setTarget(proposal.target().name().toLowerCase(Locale.ROOT));
        row.setSourceEventId(proposal.sourceEventId());
        row.setBundleId(proposal.bundleId());
        row.setPayload(proposal.payload());
        row.setStatus(PENDING);
        row.setProjectionStatus(PROJECTION_PENDING);
        row.setCreatedAt(Instant.now());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "proposalId 已存在", exception);
        }
        return toContract(row);
    }

    /** 列出当前租户待审核提案。 */
    public List<KnowledgeWriteProposal> listPending(String tenantId) {
        long tenant = parseTenant(tenantId);
        return mapper.selectList(new LambdaQueryWrapper<KnowledgeWriteProposalDO>()
                        .eq(KnowledgeWriteProposalDO::getTenantId, tenant)
                        .eq(KnowledgeWriteProposalDO::getStatus, PENDING)
                        .orderByAsc(KnowledgeWriteProposalDO::getCreatedAt))
                .stream().map(this::toContract).toList();
    }

    /** 管理入口按目标知识库先过滤；记忆提案仅完整租户治理范围允许读取。 */
    public List<KnowledgeWriteProposal> listPending(String tenantId, List<Long> permittedBaseIds, boolean includeMemory) {
        if (permittedBaseIds == null && includeMemory) return listPending(tenantId);
        if (permittedBaseIds != null && permittedBaseIds.isEmpty() && !includeMemory) return List.of();
        var query = new LambdaQueryWrapper<KnowledgeWriteProposalDO>()
                .eq(KnowledgeWriteProposalDO::getTenantId, parseTenant(tenantId))
                .eq(KnowledgeWriteProposalDO::getStatus, PENDING);
        query.and(targets -> {
            if (includeMemory) targets.eq(KnowledgeWriteProposalDO::getTarget, "memory");
            if (permittedBaseIds == null || !permittedBaseIds.isEmpty()) {
                targets.or(knowledge -> {
                    knowledge.eq(KnowledgeWriteProposalDO::getTarget, "institutional_knowledge");
                    if (permittedBaseIds != null) knowledge.and(ids -> permittedBaseIds.forEach(id -> ids.or()
                            .apply("payload->'institutional_knowledge'->>'kb_id' = {0}", id.toString())));
                });
            }
        });
        return mapper.selectList(query.orderByAsc(KnowledgeWriteProposalDO::getCreatedAt)).stream().map(this::toContract).toList();
    }

    /** 返回同租户单条治理提案供管理入口检查对象范围，不执行投影。 */
    public KnowledgeWriteProposal get(String tenantId, String proposalId) {
        return toContract(requireRow(find(parseTenant(tenantId), proposalId)));
    }

    /**
     * pending → approved，并通过唯一匹配的事实源执行器投影。
     * 已审批提案可安全重放：applied 结果直接返回，failed 结果会重试投影。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeWriteProposalReviewResult approve(String tenantId, String proposalId,
                                                      String reviewerId, String note) {
        long tenant = parseTenant(tenantId);
        validateReview(proposalId, reviewerId);
        KnowledgeWriteProposalDO row = requireRow(find(tenant, proposalId));
        if (REJECTED.equals(row.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "提案已被驳回，不能再次批准");
        }
        if (PENDING.equals(row.getStatus())) {
            Instant now = Instant.now();
            int updated = mapper.update(null, new LambdaUpdateWrapper<KnowledgeWriteProposalDO>()
                    .eq(KnowledgeWriteProposalDO::getTenantId, tenant)
                    .eq(KnowledgeWriteProposalDO::getProposalId, proposalId)
                    .eq(KnowledgeWriteProposalDO::getStatus, PENDING)
                    .set(KnowledgeWriteProposalDO::getStatus, APPROVED)
                    .set(KnowledgeWriteProposalDO::getReviewerId, reviewerId)
                    .set(KnowledgeWriteProposalDO::getReviewNote, note == null ? "" : note)
                    .set(KnowledgeWriteProposalDO::getReviewedAt, now));
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "提案不存在、跨租户或已完成审核");
            }
            row = requireRow(find(tenant, proposalId));
        }
        if (!APPROVED.equals(row.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "提案状态不允许批准");
        }
        return projectApproved(tenant, row);
    }

    /** pending → rejected 的单次 CAS；拒绝保留原始提案供审计回放。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeWriteProposal reject(String tenantId, String proposalId,
                                         String reviewerId, String note) {
        return review(tenantId, proposalId, reviewerId, note, REJECTED);
    }

    private KnowledgeWriteProposal review(String tenantId, String proposalId,
                                          String reviewerId, String note, String status) {
        long tenant = parseTenant(tenantId);
        validateReview(proposalId, reviewerId);
        Instant now = Instant.now();
        int updated = mapper.update(null, new LambdaUpdateWrapper<KnowledgeWriteProposalDO>()
                .eq(KnowledgeWriteProposalDO::getTenantId, tenant)
                .eq(KnowledgeWriteProposalDO::getProposalId, proposalId)
                .eq(KnowledgeWriteProposalDO::getStatus, PENDING)
                .set(KnowledgeWriteProposalDO::getStatus, status)
                .set(KnowledgeWriteProposalDO::getReviewerId, reviewerId)
                .set(KnowledgeWriteProposalDO::getReviewNote, note == null ? "" : note)
                .set(KnowledgeWriteProposalDO::getReviewedAt, now));
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "提案不存在、跨租户或已完成审核");
        }
        KnowledgeWriteProposalDO row = mapper.selectOne(new LambdaQueryWrapper<KnowledgeWriteProposalDO>()
                .eq(KnowledgeWriteProposalDO::getTenantId, tenant)
                .eq(KnowledgeWriteProposalDO::getProposalId, proposalId));
        return toContract(row);
    }

    private KnowledgeWriteProposalDO find(long tenant, String proposalId) {
        return mapper.selectOne(new LambdaQueryWrapper<KnowledgeWriteProposalDO>()
                .eq(KnowledgeWriteProposalDO::getTenantId, tenant)
                .eq(KnowledgeWriteProposalDO::getProposalId, proposalId));
    }

    private KnowledgeWriteProposalReviewResult projectApproved(long tenant, KnowledgeWriteProposalDO row) {
        String projectionStatus = projectionStatus(row);
        if (PROJECTION_APPLIED.equals(projectionStatus)) {
            return toReviewResult(row);
        }
        return projectWithExecutor(tenant, row);
    }

    private KnowledgeWriteProposalReviewResult projectWithExecutor(
            long tenant, KnowledgeWriteProposalDO row) {
        KnowledgeWriteProposal proposal = toContract(row);
        List<KnowledgeWriteProposalProjectionExecutor> executors = projectionExecutors.stream()
                .filter(executor -> executor.supports(proposal))
                .toList();
        if (executors.isEmpty()) {
            return markProjectionFailed(tenant, row,
                    "没有匹配的写回投影执行器；禁止绕过事实源治理");
        }
        if (executors.size() > 1) {
            return markProjectionFailed(tenant, row, "多个写回投影执行器匹配同一提案");
        }
        try {
            KnowledgeWriteProposalProjectionExecutor.ProjectionResult result = executors.get(0)
                    .project(String.valueOf(tenant), proposal);
            if (result == null) {
                return markProjectionFailed(tenant, row, "写回投影未返回结果");
            }
            if ("applied".equals(result.status()) && result.reference() != null) {
                row.setProjectionStatus(PROJECTION_APPLIED);
                row.setProjectionRef(result.reference());
                row.setProjectionError(null);
                row.setProjectedAt(Instant.now());
                updateProjection(tenant, row);
                return toReviewResult(row);
            }
            return markProjectionFailed(tenant, row,
                    result.error() == null ? "写回投影失败" : result.error());
        } catch (RuntimeException exception) {
            return markProjectionFailed(tenant, row, safeMessage(exception));
        }
    }

    private KnowledgeWriteProposalReviewResult markProjectionFailed(long tenant, KnowledgeWriteProposalDO row,
                                                                     String error) {
        row.setProjectionStatus(PROJECTION_FAILED);
        row.setProjectionError(error);
        row.setProjectedAt(Instant.now());
        updateProjection(tenant, row);
        return toReviewResult(row);
    }

    private void updateProjection(long tenant, KnowledgeWriteProposalDO row) {
        mapper.update(null, new LambdaUpdateWrapper<KnowledgeWriteProposalDO>()
                .eq(KnowledgeWriteProposalDO::getTenantId, tenant)
                .eq(KnowledgeWriteProposalDO::getProposalId, row.getProposalId())
                .set(KnowledgeWriteProposalDO::getProjectionStatus, row.getProjectionStatus())
                .set(KnowledgeWriteProposalDO::getProjectionRef, row.getProjectionRef())
                .set(KnowledgeWriteProposalDO::getProjectionError, row.getProjectionError())
                .set(KnowledgeWriteProposalDO::getProjectedAt, row.getProjectedAt()));
    }

    private KnowledgeWriteProposalReviewResult toReviewResult(KnowledgeWriteProposalDO row) {
        return new KnowledgeWriteProposalReviewResult(toContract(row), projectionStatus(row),
                row.getProjectionRef(), row.getProjectionError());
    }

    private String projectionStatus(KnowledgeWriteProposalDO row) {
        return row.getProjectionStatus() == null || row.getProjectionStatus().isBlank()
                ? PROJECTION_PENDING : row.getProjectionStatus().toLowerCase(Locale.ROOT);
    }

    private void validateReview(String proposalId, String reviewerId) {
        if (proposalId == null || proposalId.isBlank() || reviewerId == null || reviewerId.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "proposalId 与 reviewerId 不能为空");
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private KnowledgeWriteProposalDO requireRow(KnowledgeWriteProposalDO row) {
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "写回提案不存在");
        }
        return row;
    }

    private KnowledgeWriteProposal toContract(KnowledgeWriteProposalDO row) {
        requireRow(row);
        KnowledgeWriteProposal.Target target;
        try {
            target = KnowledgeWriteProposal.Target.valueOf(row.getTarget().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "写回提案 target 数据无效", exception);
        }
        KnowledgeWriteProposal.Status status;
        try {
            status = KnowledgeWriteProposal.Status.valueOf(row.getStatus().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "写回提案 status 数据无效", exception);
        }
        return new KnowledgeWriteProposal(row.getProposalId(), target, row.getSourceEventId(),
                row.getBundleId(), row.getPayload(), status);
    }

    private void requireTenant(String tenantId) {
        parseTenant(tenantId);
    }

    private void validatePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "写回提案 payload 不能为空");
        }
        rejectUnsafe(payload);
        Object source = payload.get("source");
        if (!(source instanceof Map<?, ?> map)
                || !(map.get("kind") instanceof String kind)
                || !("evidence_ref".equals(kind) || "observation".equals(kind))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "payload.source.kind 必须为 evidence_ref 或 observation");
        }
    }

    private void rejectUnsafe(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT)
                        .replace("-", "_");
                if (Set.of("prompt", "variables", "internal_variables", "raw_payload",
                        "callback_payload", "raw_callback_payload").contains(key)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "写回提案禁止包含 Prompt、内部变量或原始回调载荷");
                }
                rejectUnsafe(entry.getValue());
            }
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(this::rejectUnsafe);
        }
    }

    private long parseTenant(String tenantId) {
        try {
            long tenant = Long.parseLong(tenantId);
            if (tenant <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return tenant;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "租户 ID 无效", exception);
        }
    }
}
