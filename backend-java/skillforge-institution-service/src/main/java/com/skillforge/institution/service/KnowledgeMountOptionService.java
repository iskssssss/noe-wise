package com.skillforge.institution.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.knowledge.KnowledgeReferenceService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.common.TenantIds;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 精确关联当前知识修订与已发布能力，不返回修订正文。 */
@Service
public class KnowledgeMountOptionService {
    private final FoundationAccess access;
    private final KnowledgeManagementService management;
    private final KnowledgeReferenceService knowledge;
    private final CapabilityCatalogService catalog;

    public KnowledgeMountOptionService(FoundationAccess access, KnowledgeManagementService management,
            KnowledgeReferenceService knowledge, CapabilityCatalogService catalog) {
        this.access = access; this.management = management; this.knowledge = knowledge; this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public Option option(Long kbId) {
        access.require("foundation.knowledge.read");
        String tenant = TenantIds.requireTenantId();
        management.requireBase(tenant, kbId, "read");
        long tenantId = Long.parseLong(tenant);
        var base = knowledge.base(tenantId, kbId);
        if (base == null) throw new ApiException(HttpStatus.NOT_FOUND, "知识库不存在");
        Long activeId = base.activeRevisionId();
        if (activeId == null) return new Option(kbId, null, null, null, null, null);
        var revision = knowledge.publishedRevision(tenantId, activeId);
        if (revision == null || !Objects.equals(revision.kbId(), kbId) || revision.capabilityVersionId() == null)
            return new Option(kbId, activeId, null, null, null, null);
        var version = catalog.publishedVersions(CapabilityCatalogScope.tenant(tenantId), List.of(revision.capabilityVersionId()))
                .stream().filter(item -> item.capabilityType() == CapabilityType.KNOWLEDGE_SOURCE).findFirst().orElse(null);
        if (version == null) return new Option(kbId, activeId, null, null, null, null);
        var definition = catalog.getDefinition(CapabilityCatalogScope.tenant(tenantId), version.definitionId());
        if (!Objects.equals(definition.defaultVersionId(), version.id()))
            return new Option(kbId, activeId, null, null, null, null);
        return new Option(kbId, activeId, version.definitionId(), version.id(), version.versionKey(), version.name());
    }

    public record Option(
            @JsonProperty("kb_id") @JsonSerialize(using = ToStringSerializer.class) Long kbId,
            @JsonProperty("active_revision_id") @JsonSerialize(using = ToStringSerializer.class) Long activeRevisionId,
            @JsonProperty("definition_id") @JsonSerialize(using = ToStringSerializer.class) Long definitionId,
            @JsonProperty("version_id") @JsonSerialize(using = ToStringSerializer.class) Long versionId,
            @JsonProperty("version_key") String versionKey,
            @JsonProperty("version_name") String versionName) { }
}
