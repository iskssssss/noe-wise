package com.skillforge.institution.service;

import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.knowledge.dto.request.CreateKnowledgeBaseRequest;
import com.skillforge.core.knowledge.dto.response.CreateKnowledgeBaseResponse;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseListResponse;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceKind;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** HTTP 管理消费者的实时资源边界；核心检索和 Runtime 回放保持受信内部契约。 */
@Service
public class KnowledgeManagementService {
    private final KnowledgeService knowledge;
    private final InstitutionResourceAccess resources;
    private final FoundationOrganizationService organization;
    private final FeatureGate features;
    private final ResourceQuota quota;
    private final FoundationAccess access;

    public KnowledgeManagementService(KnowledgeService knowledge, InstitutionResourceAccess resources,
            FoundationOrganizationService organization, FeatureGate features, ResourceQuota quota, FoundationAccess access) {
        this.knowledge = knowledge; this.resources = resources; this.organization = organization;
        this.features = features; this.quota = quota;
        this.access = access;
    }

    /** 列表 SQL 只读取允许的真实知识库，再计算展示统计。 */
    public KnowledgeBaseListResponse list(String tenantId) {
        var filter = resources.filter("knowledge", "read");
        return knowledge.listBases(tenantId, filter.all() ? null : filter.resourceIds().stream().map(Long::valueOf).toList());
    }

    /** 真实配额锁、资源写入和所有权在同一个事务内提交。 */
    @Transactional(rollbackFor = Exception.class)
    public CreateKnowledgeBaseResponse create(String tenantId, String name, String description) {
        return create(tenantId, name, description, null, null, null);
    }

    /** 索引与检索配置随创建请求传递，核心在插入前完成模式和模型资格校验。 */
    @Transactional(rollbackFor = Exception.class)
    public CreateKnowledgeBaseResponse create(String tenantId, String name, String description,
            String indexingMode, String retrievalMode, Boolean rerankEnabled) {
        return create(tenantId, name, description, indexingMode, retrievalMode, rerankEnabled, null);
    }

    /** 高级设置随创建原子提交，仍检查更新权限及新对象的管理范围。 */
    public static KnowledgeService.KnowledgeBaseSettingsUpdate advancedSettings(CreateKnowledgeBaseRequest request) {
        return request.chunkSize() == null && request.chunkOverlap() == null && request.topK() == null
                && request.scoreThresholdEnabled() == null && request.scoreThreshold() == null && request.vectorWeight() == null
                ? null : new KnowledgeService.KnowledgeBaseSettingsUpdate(request.chunkSize(), request.chunkOverlap(),
                        null, null, request.topK(), request.scoreThresholdEnabled(), request.scoreThreshold(), request.vectorWeight(), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public CreateKnowledgeBaseResponse create(String tenantId, String name, String description,
            String indexingMode, String retrievalMode, Boolean rerankEnabled,
            KnowledgeService.KnowledgeBaseSettingsUpdate settings) {
        if (settings != null && !resources.external()) access.require("foundation.knowledge.update");
        features.require(tenantId, "knowledge");
        quota.requireCapacity(tenantId, ResourceKind.KNOWLEDGE_BASE, 1);
        var created = knowledge.createBase(tenantId, name, description, indexingMode, retrievalMode, rerankEnabled);
        if (!resources.external()) organization.registerResource("knowledge", created.id().toString());
        if (settings != null) {
            requireBase(tenantId, created.id(), "manage");
            knowledge.updateBase(tenantId, created.id(), null, null, null, settings);
        }
        return created;
    }

    /** 删除业务对象与遗留授权同事务；失败不会留下局部删除。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, long kbId) {
        requireBase(tenantId, kbId, "manage");
        knowledge.deleteBase(tenantId, kbId);
        if (resources.external()) organization.forgetResourceForTenant(tenantId, "knowledge", Long.toString(kbId));
        else organization.forgetResource("knowledge", Long.toString(kbId));
    }

    public void requireBase(String tenantId, long kbId, String action) {
        resources.require("knowledge", Long.toString(kbId), action);
        if (!"read".equals(action)) features.require(tenantId, "knowledge");
    }

    /** 字符串业务 ID 保持十进制精度，非法标识在管理入口明确拒绝。 */
    public void requireBase(String tenantId, String kbId, String action) {
        try { long id = Long.parseLong(kbId); if (id > 0) { requireBase(tenantId, id, action); return; } }
        catch (NumberFormatException ignored) { /* 统一为参数错误 */ }
        throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "知识库 ID 不合法");
    }

    public void requireDocument(String tenantId, long documentId, String action) {
        requireBase(tenantId, knowledge.documentBaseId(tenantId, documentId), action);
    }

    public void requireChunk(String tenantId, long chunkId, String action) {
        requireBase(tenantId, knowledge.chunkBaseId(tenantId, chunkId), action);
    }
}
