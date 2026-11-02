package com.skillforge.core.capability.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.contract.ResourceType;
import com.skillforge.core.capability.persistence.ResourceMappingMapper;
import com.skillforge.core.capability.persistence.ResourceMappingDO;
import com.skillforge.core.shared.security.TokenHash;
import java.time.Instant;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Open Platform externalRef 映射的唯一 CAS 入口。 */
@Service
public class ResourceMappingService {

    private final ResourceMappingMapper mappings;

    public ResourceMappingService(ResourceMappingMapper mappings) {
        this.mappings = mappings;
    }

    @Transactional(readOnly = true)
    public ResourceMappingDO find(String tenantId, String integrationId,
            ResourceType resourceType, String externalRef) {
        return mappings.selectOne(new QueryWrapper<ResourceMappingDO>()
                .eq("tenant_id", tenant(tenantId))
                .eq("integration_id", required(integrationId, "integrationId"))
                .eq("resource_type", type(resourceType))
                .eq("external_ref", required(externalRef, "externalRef")));
    }

    /**
     * 创建映射或以 revision + ETag 执行 CAS 更新。
     * 新建不得携带 If-Match；更新必须同时携带当前 revision 与 ETag。
     */
    @Transactional(rollbackFor = Exception.class)
    public ResourceMappingDO upsert(String tenantId, String integrationId, ResourceType resourceType,
            String externalRef, String resourceId, String contentHash,
            Long expectedRevision, String expectedEtag) {
        long tenant = tenant(tenantId);
        String integration = required(integrationId, "integrationId");
        String type = type(resourceType);
        String external = required(externalRef, "externalRef");
        String resource = required(resourceId, "resourceId");
        String hash = required(contentHash, "contentHash");
        ResourceMappingDO current = find(tenantId, integration, resourceType, external);
        if (current == null) {
            if (expectedRevision != null || (expectedEtag != null && !expectedEtag.isBlank())) {
                throw new ResourceRevisionConflictException(0, null);
            }
            ResourceMappingDO created = row(tenant, integration, type, external, resource, 1L, hash);
            try {
                mappings.insert(created);
                return created;
            } catch (DuplicateKeyException duplicate) {
                ResourceMappingDO raced = find(tenantId, integration, resourceType, external);
                throw conflict(raced);
            }
        }
        if (expectedRevision == null || expectedRevision <= 0
                || expectedEtag == null || expectedEtag.isBlank()
                || !Objects.equals(current.getRevision(), expectedRevision)
                || !Objects.equals(current.getEtag(), unquote(expectedEtag))) {
            throw conflict(current);
        }
        long nextRevision = current.getRevision() + 1;
        String nextEtag = etag(tenant, integration, type, external, resource, nextRevision, hash);
        int updated = mappings.update(null, new UpdateWrapper<ResourceMappingDO>()
                .eq("tenant_id", tenant)
                .eq("integration_id", integration)
                .eq("resource_type", type)
                .eq("external_ref", external)
                .eq("revision", expectedRevision)
                .eq("etag", current.getEtag())
                .set("resource_id", resource)
                .set("revision", nextRevision)
                .set("content_hash", hash)
                .set("etag", nextEtag)
                .set("updated_at", Instant.now()));
        if (updated == 0) {
            throw conflict(find(tenantId, integration, resourceType, external));
        }
        current.setResourceId(resource);
        current.setRevision(nextRevision);
        current.setContentHash(hash);
        current.setEtag(nextEtag);
        current.setUpdatedAt(Instant.now());
        return current;
    }

    private ResourceMappingDO row(long tenant, String integration, String type, String external,
            String resource, long revision, String hash) {
        ResourceMappingDO row = new ResourceMappingDO();
        row.setId(SnowflakeIds.nextId());
        row.setTenantId(tenant);
        row.setIntegrationId(integration);
        row.setResourceType(type);
        row.setExternalRef(external);
        row.setResourceId(resource);
        row.setRevision(revision);
        row.setContentHash(hash);
        row.setEtag(etag(tenant, integration, type, external, resource, revision, hash));
        Instant now = Instant.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private String etag(long tenant, String integration, String type, String external,
            String resource, long revision, String hash) {
        return TokenHash.sha256Hex(tenant + "|" + integration + "|" + type + "|"
                + external + "|" + resource + "|" + revision + "|" + hash);
    }

    private ResourceRevisionConflictException conflict(ResourceMappingDO current) {
        return current == null
                ? new ResourceRevisionConflictException(0, null)
                : new ResourceRevisionConflictException(current.getRevision(), current.getEtag());
    }

    private long tenant(String tenantId) {
        return Long.parseLong(required(tenantId, "tenantId"));
    }

    private String type(ResourceType resourceType) {
        return Objects.requireNonNull(resourceType, "resourceType").value();
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private String unquote(String value) {
        String normalized = value.trim();
        return normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")
                ? normalized.substring(1, normalized.length() - 1) : normalized;
    }
}
