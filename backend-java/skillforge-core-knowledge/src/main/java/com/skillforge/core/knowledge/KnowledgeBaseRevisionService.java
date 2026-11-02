package com.skillforge.core.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseRevisionListResponse;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseRevisionResponse;
import com.skillforge.core.knowledge.dto.response.KnowledgeCapabilityPolicyResponse;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.skillforge.core.knowledge.model.KnowledgeBaseDO;
import com.skillforge.core.knowledge.model.KnowledgeBaseRevisionDO;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.capability.CapabilityProducerPublicationRequest;
import com.skillforge.core.capability.CapabilityProducerPublicationResult;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.knowledge.constant.KnowledgeConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.security.TokenHash;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Knowledge Base draft → ready → published → archived 生命周期与 Capability Source 发布。 */
@Service
public class KnowledgeBaseRevisionService {

    private static final Set<String> POLICY_FIELDS = Set.of(
            "model_invocable", "user_invocable", "allowed_operators",
            "freshness_policy", "citation_required");

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseRevisionMapper revisionMapper;
    private final KnowledgeDocumentVersionMapper documentVersionMapper;
    private final CapabilityProducerPublicationBridge publicationBridge;
    private final ObjectMapper objectMapper;

    /** Spring production constructor; publication bridge is mandatory. */
    @Autowired
    public KnowledgeBaseRevisionService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseRevisionMapper revisionMapper,
            KnowledgeDocumentVersionMapper documentVersionMapper,
            CapabilityProducerPublicationBridge publicationBridge,
            ObjectMapper objectMapper) {
        this.knowledgeBaseMapper = Objects.requireNonNull(knowledgeBaseMapper, "knowledgeBaseMapper");
        this.revisionMapper = Objects.requireNonNull(revisionMapper, "revisionMapper");
        this.documentVersionMapper = Objects.requireNonNull(
                documentVersionMapper, "documentVersionMapper");
        this.publicationBridge = Objects.requireNonNull(publicationBridge, "publicationBridge");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** 列出当前租户知识库的全部 revision，最新 revision 在前。 */
    public KnowledgeBaseRevisionListResponse list(String tenantId, Long kbId) {
        long tenant = parseTenant(tenantId);
        requireBase(tenant, kbId);
        List<KnowledgeBaseRevisionResponse> items = revisionMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeBaseRevisionDO>()
                                .eq(KnowledgeBaseRevisionDO::getTenantId, tenant)
                                .eq(KnowledgeBaseRevisionDO::getKbId, kbId)
                                .orderByDesc(KnowledgeBaseRevisionDO::getRevision))
                .stream().map(this::toResponse).toList();
        return new KnowledgeBaseRevisionListResponse(items.size(), items);
    }

    /** 在知识库身份锁内复用工作草稿；反复进入编辑不新增修订。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseRevisionResponse create(String tenantId, Long kbId) {
        long tenant = parseTenant(tenantId);
        KnowledgeBaseDO base = revisionMapper.lockBase(tenant, kbId);
        if (base == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "知识库不存在");
        }
        if (base.getRebuildJobId() != null) throw conflict("knowledge_rebuild_write_locked");
        if (!KnowledgeConstants.BASE_STATUS_ACTIVE.equals(base.getStatus())) {
            throw conflict("归档知识库不能创建 revision");
        }
        KnowledgeBaseRevisionDO latest = revisionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseRevisionDO>()
                        .eq(KnowledgeBaseRevisionDO::getTenantId, tenant)
                        .eq(KnowledgeBaseRevisionDO::getKbId, kbId)
                        .orderByDesc(KnowledgeBaseRevisionDO::getRevision)
                        .last("LIMIT 1"));
        if (latest != null && KnowledgeConstants.REVISION_STATUS_DRAFT.equals(latest.getStatus())) {
            return toResponse(latest);
        }
        KnowledgeBaseRevisionDO revision = new KnowledgeBaseRevisionDO();
        revision.setId(SnowflakeIds.nextId());
        revision.setTenantId(tenant);
        revision.setKbId(kbId);
        revision.setRevision(latest == null ? 1L : Math.addExact(latest.getRevision(), 1L));
        revision.setStatus(KnowledgeConstants.REVISION_STATUS_DRAFT);
        revision.setSourceVersions(List.of());
        revision.setCapabilityPolicy(Map.of());
        revision.setCreatedAt(Instant.now());
        revisionMapper.insert(revision);
        return toResponse(revision);
    }

    /** 创建或复用工作草稿并固定显式来源与策略；准备失败不会留下新草稿。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseRevisionResponse prepare(
            String tenantId, Long kbId, List<String> sourceVersions,
            KnowledgeCapabilityPolicyRequest policyRequest) {
        var draft = create(tenantId, kbId);
        return markReady(tenantId, kbId, draft.revision(), sourceVersions, policyRequest);
    }

    /**
     * 校验 source-version manifest 与显式治理策略，并把 draft 单向推进为 ready。
     * ready 后 source manifest 与 policy snapshot 只能由数据库守卫保持不变。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseRevisionResponse markReady(
            String tenantId,
            Long kbId,
            Long revisionNumber,
            List<String> sourceVersions,
            KnowledgeCapabilityPolicyRequest policyRequest) {
        long tenant = parseTenant(tenantId);
        KnowledgeBaseDO writable = requireBase(tenant, kbId);
        if (writable.getRebuildJobId() != null) throw conflict("knowledge_rebuild_write_locked");
        KnowledgeBaseRevisionDO revision = requireRevision(tenant, kbId, revisionNumber);
        if (!KnowledgeConstants.REVISION_STATUS_DRAFT.equals(revision.getStatus())) {
            throw conflict("只有 draft revision 可以标记为 ready");
        }
        List<String> normalized = normalizeSourceVersions(sourceVersions);
        Map<String, Object> policy = normalizePolicy(policyRequest);
        assertSourceVersionsExist(tenant, kbId, normalized);
        List<com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO> sources = documentVersionMapper.selectList(
                new LambdaQueryWrapper<com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO>()
                        .eq(com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO::getTenantId, tenant)
                        .eq(com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO::getKbId, kbId)
                        .in(com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO::getSourceVersion, normalized));
        if (sources.size()!=normalized.size()) throw conflict("knowledge_source_model_missing");
        var model=sources.getFirst();
        boolean noEmbedding = model.getEmbeddingModelConfigId() == null && model.getEmbeddingModelRevision() == null
                && model.getEmbeddingDimensions() == null;
        boolean completeEmbedding = model.getEmbeddingModelConfigId() != null && model.getEmbeddingModelRevision() != null
                && model.getEmbeddingDimensions() != null;
        if ((!completeEmbedding && !(noEmbedding && "economical".equals(writable.getIndexingMode())))
                ||sources.stream().anyMatch(v -> !Objects.equals(model.getEmbeddingModelConfigId(),v.getEmbeddingModelConfigId())
                ||!Objects.equals(model.getEmbeddingModelRevision(),v.getEmbeddingModelRevision())
                ||!Objects.equals(model.getEmbeddingDimensions(),v.getEmbeddingDimensions()))) throw conflict("knowledge_source_model_mixed");
        revision.setEmbeddingModelConfigId(model.getEmbeddingModelConfigId());
        revision.setEmbeddingModelRevision(model.getEmbeddingModelRevision());
        revision.setEmbeddingDimensions(model.getEmbeddingDimensions());
        // Persist the policy while the row is still DRAFT. The existing mapper
        // then performs the draft -> ready CAS and writes the manifest; this
        // ordering keeps the database immutability trigger atomic without a
        // second ready-state policy mutation.
        revision.setCapabilityPolicy(policy);
        if (revisionMapper.updateById(revision) != 1) {
            throw conflict("revision 治理快照写入冲突，请刷新后重试");
        }
        if (revisionMapper.markReady(tenant, kbId, revisionNumber, normalized) != 1) {
            throw conflict("revision 状态已被其他请求推进，请刷新后重试");
        }
        revision.setStatus(KnowledgeConstants.REVISION_STATUS_READY);
        revision.setSourceVersions(normalized);
        revision.setReadyAt(Instant.now());
        return toResponse(revision);
    }

    /**
     * 以 ready revision 做 CAS，发布严格 KnowledgeSource descriptor，并原子建立 FK。
     * Producer bridge 与本方法共享 Spring 事务；任一 Catalog/KB 步骤失败全部回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseRevisionResponse publish(String tenantId, Long kbId, Long revisionNumber) {
        long tenant = parseTenant(tenantId);
        KnowledgeBaseDO base = revisionMapper.lockBase(tenant, kbId);
        if (base == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "知识库不存在");
        }
        if (base.getRebuildJobId() != null) throw conflict("knowledge_rebuild_write_locked");
        if (!KnowledgeConstants.BASE_STATUS_ACTIVE.equals(base.getStatus())) {
            throw conflict("归档知识库不能发布 revision");
        }
        KnowledgeBaseRevisionDO revision = requireRevision(tenant, kbId, revisionNumber);
        if (!KnowledgeConstants.REVISION_STATUS_READY.equals(revision.getStatus())) {
            throw conflict("只有 ready revision 可以发布");
        }
        if (revision.getCapabilityVersionId() != null) {
            throw conflict("ready revision 已存在 capability 关联");
        }
        if(!Objects.equals(base.getEmbeddingModelConfigId(),revision.getEmbeddingModelConfigId())
                ||!Objects.equals(base.getEmbeddingModelRevision(),revision.getEmbeddingModelRevision())
                ||!Objects.equals(base.getEmbeddingDimensions(),revision.getEmbeddingDimensions())) throw conflict("knowledge_revision_model_mismatch");
        List<String> sourceVersions = normalizeSourceVersions(revision.getSourceVersions());
        assertSourceVersionsReady(tenant, kbId, sourceVersions);
        Map<String, Object> descriptor = descriptorWithSourceIdentity(
                revision.getKbId(), sourceVersions,
                normalizePolicySnapshot(revision.getCapabilityPolicy()));
        String contentHash = contentHash(descriptor);
        CapabilityProducerPublicationResult publication = publicationBridge.publish(
                new CapabilityProducerPublicationRequest(
                        CapabilityCatalogScope.tenant(tenant),
                        CapabilityType.KNOWLEDGE_SOURCE,
                        "knowledge-base:" + kbId,
                        "revision:" + revisionNumber,
                        base.getName(),
                        base.getDescription() == null ? "" : base.getDescription(),
                        contentHash,
                        descriptor));

        // Associate while the KB row is still READY. The database trigger permits this
        // one-way attachment, then publishReady performs the existing status CAS.
        revision.setCapabilityVersionId(publication.version().id());
        if (revisionMapper.updateById(revision) != 1) {
            throw conflict("revision capability 关联写入冲突，请刷新当前知识库状态");
        }
        revisionMapper.archivePublished(tenant, kbId, revisionNumber);
        if (revisionMapper.publishReady(tenant, kbId, revisionNumber) != 1
                || revisionMapper.activateBase(tenant, kbId, revision.getId()) != 1) {
            throw conflict("revision 发布冲突，请刷新当前知识库状态");
        }
        revision.setStatus(KnowledgeConstants.REVISION_STATUS_PUBLISHED);
        revision.setPublishedAt(Instant.now());
        return toResponse(revision);
    }

    private void assertSourceVersionsExist(long tenant, Long kbId, List<String> sourceVersions) {
        assertSourceVersionsReady(tenant, kbId, sourceVersions);
    }

    /** ready/publish 都要求来源版本已 ready，且声明的完整分块集合仍与实际一致。 */
    private void assertSourceVersionsReady(long tenant, Long kbId, List<String> sourceVersions) {
        int complete = documentVersionMapper.countCompleteReadyVersions(tenant, kbId, sourceVersions);
        if (complete != sourceVersions.size()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "source_version 不存在、未 ready、分块不完整或不属于当前知识库");
        }
    }

    private static List<String> normalizeSourceVersions(List<String> sourceVersions) {
        if (sourceVersions == null || sourceVersions.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "source_versions 不能为空");
        }
        if (sourceVersions.stream().anyMatch(Objects::isNull)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "source_versions 不能包含 null");
        }
        List<String> normalized = sourceVersions.stream()
                .map(String::trim)
                .peek(value -> {
                    if (value.isEmpty() || value.length() > 128) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "source_version 必须为 1-128 个非空字符");
                    }
                })
                .distinct().sorted(Comparator.naturalOrder()).toList();
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "source_versions 不能为空");
        }
        return List.copyOf(normalized);
    }

    private static Map<String, Object> normalizePolicy(KnowledgeCapabilityPolicyRequest request) {
        if (request == null || request.modelInvocable() == null || request.userInvocable() == null
                || request.citationRequired() == null || request.allowedOperators() == null
                || request.allowedOperators().isEmpty()
                || request.freshnessPolicy() == null || request.freshnessPolicy().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Knowledge Source policy 必须显式包含 modelInvocable、userInvocable、"
                            + "allowedOperators、freshnessPolicy、citationRequired");
        }
        if (request.allowedOperators().stream().anyMatch(Objects::isNull)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "allowedOperators 不能包含 null");
        }
        List<String> operators = request.allowedOperators().stream()
                .map(String::trim)
                .peek(value -> {
                    if (value.isEmpty() || value.length() > 64) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "allowedOperators 只能包含 1-64 个非空字符");
                    }
                })
                .distinct().sorted().toList();
        if (operators.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "allowedOperators 不能为空");
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        // Persist and publish the descriptor in the canonical wire form. Java DTO
        // components stay camelCase, while all JSONB/catalog descriptor keys are
        // snake_case under the shared SNAKE_CASE ObjectMapper contract.
        policy.put("model_invocable", request.modelInvocable());
        policy.put("user_invocable", request.userInvocable());
        policy.put("allowed_operators", List.copyOf(operators));
        policy.put("freshness_policy", request.freshnessPolicy().trim());
        policy.put("citation_required", request.citationRequired());
        return Map.copyOf(policy);
    }

    private static Map<String, Object> normalizePolicySnapshot(Map<String, Object> raw) {
        if (raw == null || !raw.keySet().equals(POLICY_FIELDS)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Knowledge Source policy snapshot 缺少必填字段或包含未知字段");
        }
        Object model = raw.get("model_invocable");
        Object user = raw.get("user_invocable");
        Object citation = raw.get("citation_required");
        Object freshness = raw.get("freshness_policy");
        Object operators = raw.get("allowed_operators");
        if (!(model instanceof Boolean) || !(user instanceof Boolean) || !(citation instanceof Boolean)
                || !(freshness instanceof String) || ((String) freshness).isBlank()
                || ((String) freshness).trim().length() > 128) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Knowledge Source policy snapshot 类型无效");
        }
        if (!(operators instanceof List<?> list) || list.isEmpty()
                || list.stream().anyMatch(value -> !(value instanceof String)
                        || ((String) value).isBlank()
                        || ((String) value).trim().length() > 64)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Knowledge Source policy snapshot allowedOperators 无效");
        }
        List<String> normalizedOperators = list.stream().map(String.class::cast)
                .map(String::trim).distinct().sorted().toList();
        if (normalizedOperators.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "allowedOperators 不能为空");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("model_invocable", model);
        normalized.put("user_invocable", user);
        normalized.put("allowed_operators", List.copyOf(normalizedOperators));
        normalized.put("freshness_policy", ((String) freshness).trim());
        normalized.put("citation_required", citation);
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> descriptorWithSourceIdentity(
            long kbId, List<String> sourceVersions, Map<String, Object> policy) {
        Map<String, Object> descriptor = new LinkedHashMap<>(policy);
        descriptor.put("source_id", Long.toString(kbId));
        descriptor.put("source_versions", List.copyOf(sourceVersions));
        return Map.copyOf(descriptor);
    }

    private String contentHash(Map<String, Object> descriptor) {
        try {
            String canonical = objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(descriptor);
            return TokenHash.sha256Hex(canonical);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Knowledge Source content hash serialization failed", exception);
        }
    }

    private KnowledgeBaseDO requireBase(long tenant, Long kbId) {
        if (kbId == null || kbId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "kb_id 必须为正整数");
        }
        KnowledgeBaseDO base = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseDO>()
                .eq(KnowledgeBaseDO::getTenantId, tenant)
                .eq(KnowledgeBaseDO::getId, kbId));
        if (base == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "知识库不存在");
        }
        return base;
    }

    private KnowledgeBaseRevisionDO requireRevision(long tenant, Long kbId, Long revisionNumber) {
        if (revisionNumber == null || revisionNumber <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "revision 必须为正整数");
        }
        KnowledgeBaseRevisionDO revision = revisionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseRevisionDO>()
                        .eq(KnowledgeBaseRevisionDO::getTenantId, tenant)
                        .eq(KnowledgeBaseRevisionDO::getKbId, kbId)
                        .eq(KnowledgeBaseRevisionDO::getRevision, revisionNumber));
        if (revision == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Knowledge Base revision 不存在");
        }
        return revision;
    }

    private static long parseTenant(String tenantId) {
        try {
            return Long.parseLong(tenantId);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "租户 ID 无效", exception);
        }
    }

    private static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    @SuppressWarnings("unchecked")
    private KnowledgeBaseRevisionResponse toResponse(KnowledgeBaseRevisionDO revision) {
        List<String> sourceVersions = revision.getSourceVersions() == null
                ? List.of() : List.copyOf(new ArrayList<>(revision.getSourceVersions()));
        KnowledgeCapabilityPolicyResponse policy = null;
        String hash = null;
        if (revision.getCapabilityPolicy() != null && !revision.getCapabilityPolicy().isEmpty()) {
            Map<String, Object> normalized = normalizePolicySnapshot(revision.getCapabilityPolicy());
            policy = new KnowledgeCapabilityPolicyResponse(
                    (Boolean) normalized.get("model_invocable"),
                    (Boolean) normalized.get("user_invocable"),
                    List.copyOf((List<String>) normalized.get("allowed_operators")),
                    (String) normalized.get("freshness_policy"),
                    (Boolean) normalized.get("citation_required"));
            hash = contentHash(descriptorWithSourceIdentity(
                    revision.getKbId(), sourceVersions, normalized));
        }
        return new KnowledgeBaseRevisionResponse(revision.getId(), revision.getKbId(), revision.getRevision(),
                revision.getStatus(), sourceVersions, revision.getCreatedAt(), revision.getReadyAt(),
                revision.getPublishedAt(), policy, revision.getCapabilityVersionId(), hash);
    }
}
