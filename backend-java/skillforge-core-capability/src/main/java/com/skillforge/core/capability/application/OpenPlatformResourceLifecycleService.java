package com.skillforge.core.capability.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.contract.ResourceType;
import com.skillforge.core.capability.persistence.OpenPlatformDeploymentMapper;
import com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionMapper;
import com.skillforge.core.capability.persistence.OpenPlatformDeploymentDO;
import com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO;
import com.skillforge.core.shared.security.TokenHash;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open Platform 资源正文和 Deployment 的生命周期入口。
 *
 * <p>同一 resourceId 的工作草稿可反复保存；发布历史只读。Deployment 与 ChannelBinding
 * 绑定稳定资源身份，发布后原子推进当前修订；执行方受理时冻结版本，恢复读取原历史。
 * 部署和渠道的 revision + ETag 仅用于路由 CAS，不产生新资源身份。
 */
@Service
public class OpenPlatformResourceLifecycleService {

    private static final String DRAFT = "draft";
    private static final String READY = "ready";
    private static final String PUBLISHED = "published";
    private static final String ACTIVE = "active";

    private final OpenPlatformResourceRevisionMapper revisions;
    private final OpenPlatformDeploymentMapper deployments;
    private final List<OpenPlatformPublicationListener> publicationListeners;
    private final ObjectMapper objectMapper;

    public OpenPlatformResourceLifecycleService(
            OpenPlatformResourceRevisionMapper revisions,
            OpenPlatformDeploymentMapper deployments,
            List<OpenPlatformPublicationListener> publicationListeners,
            ObjectMapper objectMapper) {
        this.revisions = revisions;
        this.deployments = deployments;
        this.publicationListeners = List.copyOf(publicationListeners);
        this.objectMapper = objectMapper;
    }

    /** 创建资源首个 draft，或在已有 published revision 后创建新的 draft。 */
    @Transactional(rollbackFor = Exception.class)
    public OpenPlatformResourceRevisionDO createDraft(
            String tenantId, ResourceType resourceType, String resourceId, Map<String, Object> content) {
        long tenant = tenant(tenantId);
        String type = type(resourceType);
        String resource = required(resourceId, "resourceId");
        OpenPlatformResourceRevisionDO current = latest(tenant, type, resource);
        if (current != null && (DRAFT.equals(current.getStatus()) || READY.equals(current.getStatus()))) {
            throw new IllegalStateException("资源已有未发布 draft revision");
        }
        long nextRevision = current == null ? 1L : current.getRevision() + 1L;
        return insertRevision(tenant, type, resource, nextRevision, content, DRAFT);
    }

    /** 仅允许对 draft 执行带 revision/ETag 的 CAS 更新。 */
    @Transactional(rollbackFor = Exception.class)
    public OpenPlatformResourceRevisionDO updateDraft(
            String tenantId, ResourceType resourceType, String resourceId,
            long expectedRevision, String expectedEtag, Map<String, Object> content) {
        long tenant = tenant(tenantId);
        OpenPlatformResourceRevisionDO current = requireLatest(tenant, type(resourceType), resourceId);
        requireCas(current, expectedRevision, expectedEtag);
        if (!DRAFT.equals(current.getStatus())) {
            throw new IllegalStateException("只有 draft revision 可修改");
        }
        Map<String, Object> normalized = content == null ? Map.of() : new LinkedHashMap<>(content);
        String hash = contentHash(normalized);
        String etag = revisionEtag(tenant, current.getResourceType(), current.getResourceId(),
                current.getRevision(), hash);
        int updated = revisions.update(null, new UpdateWrapper<OpenPlatformResourceRevisionDO>()
                .eq("id", current.getId())
                .eq("revision", expectedRevision)
                .eq("etag", current.getEtag())
                .eq("status", DRAFT)
                .set("content", normalized)
                .set("content_hash", hash)
                .set("etag", etag)
                .set("updated_at", Instant.now()));
        if (updated == 0) {
            throw conflict(latest(tenant, current.getResourceType(), current.getResourceId()));
        }
        current.setContent(normalized);
        current.setContentHash(hash);
        current.setEtag(etag);
        current.setUpdatedAt(Instant.now());
        return current;
    }

    /** draft → ready；校验结果由更具体的资源适配器在此边界前完成。 */
    @Transactional(rollbackFor = Exception.class)
    public OpenPlatformResourceRevisionDO markReady(
            String tenantId, ResourceType resourceType, String resourceId,
            long expectedRevision, String expectedEtag) {
        return transitionRevision(tenantId, resourceType, resourceId,
                expectedRevision, expectedEtag, DRAFT, READY);
    }

    /** ready → published，并把同一资源旧 published revision 标记为 archived。 */
    @Transactional(rollbackFor = Exception.class)
    public OpenPlatformResourceRevisionDO publish(
            String tenantId, ResourceType resourceType, String resourceId,
            long expectedRevision, String expectedEtag) {
        long tenant = tenant(tenantId);
        String type = type(resourceType);
        String resource = required(resourceId, "resourceId");
        OpenPlatformResourceRevisionDO current = requireLatest(tenant, type, resource);
        requireCas(current, expectedRevision, expectedEtag);
        if (!READY.equals(current.getStatus())) {
            throw new IllegalStateException("只有 ready revision 可发布");
        }
        revisions.update(null, new UpdateWrapper<OpenPlatformResourceRevisionDO>()
                .eq("tenant_id", tenant)
                .eq("resource_type", type)
                .eq("resource_id", resource)
                .eq("status", PUBLISHED)
                .set("status", "archived")
                .set("updated_at", Instant.now()));
        Instant now = Instant.now();
        int updated = revisions.update(null, new UpdateWrapper<OpenPlatformResourceRevisionDO>()
                .eq("id", current.getId())
                .eq("revision", expectedRevision)
                .eq("etag", current.getEtag())
                .eq("status", READY)
                .set("status", PUBLISHED)
                .set("published_at", now)
                .set("updated_at", now));
        if (updated == 0) {
            throw conflict(latest(tenant, type, resource));
        }
        current.setStatus(PUBLISHED);
        current.setPublishedAt(now);
        current.setUpdatedAt(now);
        advanceBindings(current);
        return current;
    }

    /** 资源发布与既有稳定路由切换共享事务；锁顺序固定为部署再渠道。 */
    private void advanceBindings(OpenPlatformResourceRevisionDO current) {
        List<OpenPlatformDeploymentDO> targets = deployments.selectList(new QueryWrapper<OpenPlatformDeploymentDO>()
                .eq("tenant_id", current.getTenantId()).eq("resource_type", current.getResourceType())
                .eq("resource_id", current.getResourceId()).eq("status", ACTIVE).orderByAsc("id").last("FOR UPDATE"));
        if (targets == null || targets.isEmpty()) return;
        for (OpenPlatformDeploymentDO target : targets) {
            target.setResourceRevision(current.getRevision());
            target.setDeploymentRevision(Math.addExact(target.getDeploymentRevision(), 1));
            target.setEtag(deploymentEtag(target.getTenantId(), target.getDeploymentId(), target.getResourceType(),
                    target.getResourceId(), target.getResourceRevision(), target.getEnvironment(), target.getDeploymentRevision()));
        }
        if (deployments.advancePublication(targets) != targets.size()) throw new IllegalStateException("部署发布路由未完整推进");
        if (!ResourceType.AGENT.value().equals(current.getResourceType())) return;
        List<String> deploymentIds = targets.stream().map(OpenPlatformDeploymentDO::getDeploymentId).toList();
        for (OpenPlatformPublicationListener listener : publicationListeners) {
            listener.advanceBindings(current.getTenantId(), current.getResourceId(), current.getRevision(), deploymentIds);
        }
    }

    @Transactional(readOnly = true)
    public OpenPlatformResourceRevisionDO latest(String tenantId, ResourceType resourceType, String resourceId) {
        return latest(tenant(tenantId), type(resourceType), resourceId);
    }

    /** 读取租户内每个资源的最新 revision，用于 Open Platform 统一资源目录。 */
    @Transactional(readOnly = true)
    public List<OpenPlatformResourceRevisionDO> listLatest(
            String tenantId, ResourceType resourceType, String status, int limit) {
        long tenant = tenant(tenantId);
        int boundedLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 200));
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        if (normalizedStatus != null && !isRevisionStatus(normalizedStatus)) {
            throw new IllegalArgumentException("unsupported resource revision status");
        }
        return revisions.listLatestSummaries(tenant,resourceType==null?null:type(resourceType),normalizedStatus,boundedLimit);
    }

    /** 读取指定资源 revision；租户、类型、资源和 revision 必须同时匹配。 */
    @Transactional(readOnly = true)
    public OpenPlatformResourceRevisionDO revision(
            String tenantId, ResourceType resourceType, String resourceId, long revision) {
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return revisions.selectOne(new QueryWrapper<OpenPlatformResourceRevisionDO>()
                .eq("tenant_id", tenant(tenantId))
                .eq("resource_type", type(resourceType))
                .eq("resource_id", required(resourceId, "resourceId"))
                .eq("revision", revision));
    }

    /** 将已发布 revision 部署到环境；更新部署必须使用部署自身的 CAS。 */
    @Transactional(rollbackFor = Exception.class)
    public OpenPlatformDeploymentDO deploy(
            String tenantId, String deploymentId, ResourceType resourceType, String resourceId,
            long resourceRevision, String environment, Long expectedDeploymentRevision,
            String expectedEtag) {
        long tenant = tenant(tenantId);
        String deployment = required(deploymentId, "deploymentId");
        String type = type(resourceType);
        String resource = required(resourceId, "resourceId");
        String env = required(environment, "environment");
        OpenPlatformResourceRevisionDO target = revisions.selectOne(new QueryWrapper<OpenPlatformResourceRevisionDO>()
                .eq("tenant_id", tenant).eq("resource_type", type).eq("resource_id", resource)
                .eq("revision", resourceRevision).eq("status", PUBLISHED).last("FOR SHARE"));
        if (target == null) {
            throw new IllegalStateException("Deployment 只能引用已发布 resource revision");
        }
        OpenPlatformDeploymentDO current = deployments.selectOne(new QueryWrapper<OpenPlatformDeploymentDO>()
                .eq("tenant_id", tenant).eq("deployment_id", deployment).last("FOR UPDATE"));
        Instant now = Instant.now();
        if (current == null) {
            if (expectedDeploymentRevision != null || (expectedEtag != null && !expectedEtag.isBlank())) {
                throw new ResourceRevisionConflictException(0, null);
            }
            OpenPlatformDeploymentDO created = new OpenPlatformDeploymentDO();
            created.setId(SnowflakeIds.nextId());
            created.setTenantId(tenant);
            created.setDeploymentId(deployment);
            created.setResourceType(type);
            created.setResourceId(resource);
            created.setResourceRevision(resourceRevision);
            created.setEnvironment(env);
            created.setStatus(ACTIVE);
            created.setDeploymentRevision(1L);
            created.setEtag(deploymentEtag(tenant, deployment, type, resource, resourceRevision, env, 1L));
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            deployments.insert(created);
            return created;
        }
        if (expectedDeploymentRevision == null || expectedDeploymentRevision <= 0
                || !Objects.equals(current.getDeploymentRevision(), expectedDeploymentRevision)
                || !Objects.equals(current.getEtag(), unquote(expectedEtag))) {
            throw conflict(current.getDeploymentRevision(), current.getEtag());
        }
        long next = current.getDeploymentRevision() + 1L;
        String etag = deploymentEtag(tenant, deployment, type, resource, resourceRevision, env, next);
        int updated = deployments.update(null, new UpdateWrapper<OpenPlatformDeploymentDO>()
                .eq("tenant_id", tenant).eq("deployment_id", deployment)
                .eq("deployment_revision", expectedDeploymentRevision).eq("etag", current.getEtag())
                .set("resource_type", type).set("resource_id", resource)
                .set("resource_revision", resourceRevision).set("environment", env)
                .set("status", ACTIVE).set("deployment_revision", next)
                .set("etag", etag).set("updated_at", now));
        if (updated == 0) {
            throw conflict(deployments.selectOne(new QueryWrapper<OpenPlatformDeploymentDO>()
                    .eq("tenant_id", tenant).eq("deployment_id", deployment)));
        }
        current.setResourceType(type);
        current.setResourceId(resource);
        current.setResourceRevision(resourceRevision);
        current.setEnvironment(env);
        current.setStatus(ACTIVE);
        current.setDeploymentRevision(next);
        current.setEtag(etag);
        current.setUpdatedAt(now);
        return current;
    }

    /** 必须由调用方开启事务；共享锁一直保留到渠道绑定提交。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ActiveAgentDeployment requireActiveAgentDeployment(long tenantId, String deploymentId) {
        OpenPlatformDeploymentDO target = deployments.selectOne(new QueryWrapper<OpenPlatformDeploymentDO>()
                .eq("tenant_id", tenantId).eq("deployment_id", required(deploymentId, "deploymentId"))
                .eq("status", ACTIVE).last("FOR SHARE"));
        if (target == null || !ResourceType.AGENT.value().equals(target.getResourceType())) {
            throw new IllegalStateException("ChannelBinding 只能引用 active Agent Deployment");
        }
        return new ActiveAgentDeployment(target.getResourceId(), target.getResourceRevision());
    }

    public record ActiveAgentDeployment(String resourceId, long resourceRevision) { }

    private OpenPlatformResourceRevisionDO transitionRevision(String tenantId, ResourceType resourceType,
            String resourceId, long expectedRevision, String expectedEtag, String from, String to) {
        long tenant = tenant(tenantId);
        OpenPlatformResourceRevisionDO current = requireLatest(tenant, type(resourceType), resourceId);
        requireCas(current, expectedRevision, expectedEtag);
        if (!from.equals(current.getStatus())) {
            throw new IllegalStateException("revision 状态不允许转换：" + current.getStatus() + " -> " + to);
        }
        Instant now = Instant.now();
        int updated = revisions.update(null, new UpdateWrapper<OpenPlatformResourceRevisionDO>()
                .eq("id", current.getId()).eq("revision", expectedRevision)
                .eq("etag", current.getEtag()).eq("status", from)
                .set("status", to).set("updated_at", now));
        if (updated == 0) {
            throw conflict(latest(tenant, current.getResourceType(), current.getResourceId()));
        }
        current.setStatus(to);
        current.setUpdatedAt(now);
        return current;
    }

    private OpenPlatformResourceRevisionDO insertRevision(long tenant, String type, String resource,
            long revision, Map<String, Object> content, String status) {
        Map<String, Object> normalized = content == null ? Map.of() : new LinkedHashMap<>(content);
        Instant now = Instant.now();
        OpenPlatformResourceRevisionDO row = new OpenPlatformResourceRevisionDO();
        row.setId(SnowflakeIds.nextId());
        row.setTenantId(tenant);
        row.setResourceType(type);
        row.setResourceId(resource);
        row.setRevision(revision);
        row.setStatus(status);
        row.setContent(normalized);
        row.setContentHash(contentHash(normalized));
        row.setEtag(revisionEtag(tenant, type, resource, revision, row.getContentHash()));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        revisions.insert(row);
        return row;
    }

    private OpenPlatformResourceRevisionDO requireLatest(long tenant, String type, String resourceId) {
        OpenPlatformResourceRevisionDO row = latest(tenant, type, required(resourceId, "resourceId"));
        if (row == null) {
            throw new IllegalStateException("资源 revision 不存在");
        }
        return row;
    }

    private OpenPlatformResourceRevisionDO latest(long tenant, String type, String resource) {
        return revisions.selectOne(new QueryWrapper<OpenPlatformResourceRevisionDO>()
                .eq("tenant_id", tenant).eq("resource_type", type).eq("resource_id", resource)
                .orderByDesc("revision").last("LIMIT 1"));
    }

    private void requireCas(OpenPlatformResourceRevisionDO current, long expectedRevision, String expectedEtag) {
        if (expectedRevision <= 0 || !Objects.equals(current.getRevision(), expectedRevision)
                || !Objects.equals(current.getEtag(), unquote(expectedEtag))) {
            throw conflict(current);
        }
    }

    private ResourceRevisionConflictException conflict(OpenPlatformResourceRevisionDO current) {
        return conflict(current == null ? 0 : current.getRevision(), current == null ? null : current.getEtag());
    }

    private ResourceRevisionConflictException conflict(OpenPlatformDeploymentDO current) {
        return conflict(current == null ? 0 : current.getDeploymentRevision(), current == null ? null : current.getEtag());
    }

    private ResourceRevisionConflictException conflict(long revision, String etag) {
        return new ResourceRevisionConflictException(revision, etag);
    }

    private String revisionEtag(long tenant, String type, String resource, long revision, String hash) {
        return TokenHash.sha256Hex(tenant + "|resource|" + type + "|" + resource + "|" + revision + "|" + hash);
    }

    private String deploymentEtag(long tenant, String deployment, String type, String resource,
            long resourceRevision, String environment, long deploymentRevision) {
        return TokenHash.sha256Hex(tenant + "|deployment|" + deployment + "|" + type + "|"
                + resource + "|" + resourceRevision + "|" + environment + "|" + deploymentRevision);
    }

    private String contentHash(Map<String, Object> content) {
        try {
            String json = objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(content);
            return TokenHash.sha256Hex(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("resource content 不能序列化", exception);
        }
    }

    private long tenant(String tenantId) {
        return Long.parseLong(required(tenantId, "tenantId"));
    }

    private String type(ResourceType resourceType) {
        return Objects.requireNonNull(resourceType, "resourceType").value();
    }

    private boolean isRevisionStatus(String value) {
        for (com.skillforge.core.capability.contract.ResourceRevisionStatus status
                : com.skillforge.core.capability.contract.ResourceRevisionStatus.values()) {
            if (status.value().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private String unquote(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")
                ? normalized.substring(1, normalized.length() - 1) : normalized;
    }
}
