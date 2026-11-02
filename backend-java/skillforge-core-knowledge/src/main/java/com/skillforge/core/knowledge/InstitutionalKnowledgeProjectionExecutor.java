package com.skillforge.core.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.knowledge.mapper.KnowledgeGovernanceProjectionMapper;
import com.skillforge.core.knowledge.model.KnowledgeGovernanceProjectionDO;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalProjectionExecutor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 审批后的机构知识提案，只能经 Knowledge Base revision 发布路径形成事实。 */
@Component
public class InstitutionalKnowledgeProjectionExecutor
        implements KnowledgeWriteProposalProjectionExecutor {

    private static final String INSTITUTIONAL_KNOWLEDGE = "institutional_knowledge";
    private static final String PROCESSING = "processing";
    private static final String APPLIED = "applied";
    private static final Set<String> POLICY_FIELDS = Set.of(
            "model_invocable", "user_invocable", "allowed_operators",
            "freshness_policy", "citation_required");

    private final KnowledgeGovernanceProjectionMapper projectionMapper;
    private final KnowledgeService knowledgeService;
    private final KnowledgeBaseRevisionService revisionService;
    private final TransactionTemplate writer;
    private final TransactionTemplate preparation;

    public InstitutionalKnowledgeProjectionExecutor(
            KnowledgeGovernanceProjectionMapper projectionMapper,
            KnowledgeService knowledgeService,
            KnowledgeBaseRevisionService revisionService, PlatformTransactionManager transactionManager) {
        this.projectionMapper = projectionMapper;
        this.knowledgeService = knowledgeService;
        this.revisionService = revisionService;
        writer = new TransactionTemplate(transactionManager);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        preparation = new TransactionTemplate(transactionManager);
        preparation.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Override
    public boolean supports(KnowledgeWriteProposal proposal) {
        return proposal != null
                && proposal.target() == KnowledgeWriteProposal.Target.INSTITUTIONAL_KNOWLEDGE;
    }

    @Override
    public ProjectionResult project(String tenantId, KnowledgeWriteProposal proposal) {
        requireProjectionType(proposal.payload());
        long tenant = parseTenant(tenantId);
        return preparation.execute(ignored -> {
            // 已完成/正在处理的重放不消耗模型；短写事务中的 claim 再次裁决竞争。
            KnowledgeGovernanceProjectionDO existing = find(tenant, proposal);
            if (existing != null && APPLIED.equals(existing.getStatus())) {
                return ProjectionResult.applied(existing.getId(), existing.getDetails());
            }
            if (existing != null && PROCESSING.equals(existing.getStatus())) {
                return ProjectionResult.failed("机构知识投影正在由其他请求处理");
            }
            Map<String, Object> body = nested(proposal.payload(), INSTITUTIONAL_KNOWLEDGE);
            long kbId = positiveLong(body, "kb_id");
            var capabilityPolicy = policy(body);
            var prepared = knowledgeService.prepareProjectionDocument(tenantId, kbId,
                    requiredText(body, "title"), requiredText(body, "content"));
            return writer.execute(status -> {
                KnowledgeGovernanceProjectionDO row = claim(tenant, proposal);
                if (row == null) return ProjectionResult.failed("机构知识投影正在由其他请求处理");
                if (APPLIED.equals(row.getStatus())) {
                    return ProjectionResult.applied(row.getId(), row.getDetails());
                }
                Map<String, Object> details = projectKnowledgeBase(tenantId, kbId, prepared, capabilityPolicy);
                row.setStatus(APPLIED);
                row.setDetails(details);
                row.setError(null);
                row.setUpdatedAt(Instant.now());
                projectionMapper.updateById(row);
                return ProjectionResult.applied(row.getId(), details);
            });
        });
    }

    private KnowledgeGovernanceProjectionDO find(long tenant, KnowledgeWriteProposal proposal) {
        return projectionMapper.selectOne(new LambdaQueryWrapper<KnowledgeGovernanceProjectionDO>()
                .eq(KnowledgeGovernanceProjectionDO::getTenantId, tenant)
                .eq(KnowledgeGovernanceProjectionDO::getProposalId, proposal.proposalId()));
    }

    private KnowledgeGovernanceProjectionDO claim(long tenant, KnowledgeWriteProposal proposal) {
        KnowledgeGovernanceProjectionDO created = new KnowledgeGovernanceProjectionDO();
        created.setId(SnowflakeIds.nextId());
        created.setTenantId(tenant);
        created.setProposalId(proposal.proposalId());
        created.setProjectionType(INSTITUTIONAL_KNOWLEDGE);
        created.setStatus(PROCESSING);
        created.setDetails(Map.of());
        created.setCreatedAt(Instant.now());
        created.setUpdatedAt(Instant.now());
        if (projectionMapper.insertClaim(created) == 1) return created;
        // PostgreSQL 冲突不抛异常，避免事务进入 aborted；行锁串行化重放/失败重试。
        KnowledgeGovernanceProjectionDO existing = projectionMapper.lockByProposal(tenant, proposal.proposalId());
        if (existing == null || PROCESSING.equals(existing.getStatus())) return null;
        if (APPLIED.equals(existing.getStatus())) return existing;
        existing.setStatus(PROCESSING);
        existing.setError(null);
        existing.setUpdatedAt(Instant.now());
        projectionMapper.updateById(existing);
        return existing;
    }

    private Map<String, Object> projectKnowledgeBase(String tenantId, long kbId,
            KnowledgeService.PreparedDocument prepared, KnowledgeCapabilityPolicyRequest capabilityPolicy) {
        var document = knowledgeService.persistPreparedDocument(tenantId, prepared);
        String sourceVersion = document.sourceVersion();
        var draft = revisionService.create(tenantId, kbId);
        var ready = revisionService.markReady(tenantId, kbId, draft.revision(),
                List.of(sourceVersion), capabilityPolicy);
        var published = revisionService.publish(tenantId, kbId, ready.revision());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projection_type", INSTITUTIONAL_KNOWLEDGE);
        details.put("kb_id", kbId);
        details.put("document_id", document.documentId());
        details.put("source_version", sourceVersion);
        details.put("revision_id", published.id());
        details.put("revision", published.revision());
        details.put("status", published.status());
        return details;
    }

    private KnowledgeCapabilityPolicyRequest policy(Map<String, Object> body) {
        Map<String, Object> value = nested(body, "capability_policy");
        if (!value.keySet().equals(POLICY_FIELDS)) {
            throw invalid("capability_policy 必须恰好包含五个显式治理字段");
        }
        Object model = value.get("model_invocable");
        Object user = value.get("user_invocable");
        Object citation = value.get("citation_required");
        Object operators = value.get("allowed_operators");
        Object freshness = value.get("freshness_policy");
        if (!(model instanceof Boolean) || !(user instanceof Boolean)
                || !(citation instanceof Boolean) || !(freshness instanceof String)
                || !(operators instanceof List<?> list) || list.isEmpty()
                || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw invalid("capability_policy 字段类型或 allowed_operators 无效");
        }
        Set<String> operatorSet = new java.util.LinkedHashSet<>();
        for (Object item : list) {
            String operator = ((String) item).trim();
            if (operator.isEmpty()) throw invalid("allowed_operators 不能为空");
            operatorSet.add(operator);
        }
        if (operatorSet.isEmpty() || ((String) freshness).isBlank()) {
            throw invalid("capability_policy 不能包含空治理值");
        }
        return new KnowledgeCapabilityPolicyRequest(
                (Boolean) model, (Boolean) user, operatorSet,
                (String) freshness, (Boolean) citation);
    }

    private void requireProjectionType(Map<String, Object> payload) {
        if (payload == null
                || !INSTITUTIONAL_KNOWLEDGE.equals(payload.get("projection_type"))) {
            throw invalid("projection_type 仅支持 institutional_knowledge");
        }
    }

    private Map<String, Object> nested(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (!(value instanceof Map<?, ?> map)) throw invalid("payload 缺少 " + key + " 对象");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((name, item) -> result.put(String.valueOf(name), item));
        return result;
    }

    private long positiveLong(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed > 0 && value != null) return parsed;
        } catch (RuntimeException ignored) {
            // 转为稳定的 422。
        }
        throw invalid(key + " 必须为正整数");
    }

    private String requiredText(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw invalid(key + " 不能为空");
        return String.valueOf(value);
    }

    private long parseTenant(String tenantId) {
        try {
            long tenant = Long.parseLong(tenantId);
            if (tenant <= 0) throw new NumberFormatException("non-positive");
            return tenant;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "租户 ID 无效", exception);
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
