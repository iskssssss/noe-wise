package com.skillforge.core.channel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.channel.persistence.OpenPlatformChannelBindingMapper;
import com.skillforge.core.channel.persistence.OpenPlatformChannelBindingDO;
import com.skillforge.core.capability.application.OpenPlatformResourceLifecycleService;
import com.skillforge.core.capability.application.ResourceRevisionConflictException;
import com.skillforge.core.shared.security.TokenHash;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** 渠道领域拥有绑定写入；部署资格由资源领域在同一事务内锁定验证。 */
public class OpenPlatformChannelBindingService {
    private static final String ACTIVE = "active";
    private final OpenPlatformChannelBindingMapper bindings;
    private final OpenPlatformResourceLifecycleService resources;

    public OpenPlatformChannelBindingService(OpenPlatformChannelBindingMapper bindings,
            OpenPlatformResourceLifecycleService resources) {
        this.bindings = bindings;
        this.resources = resources;
    }

    /** ChannelBinding 只允许指向 active Agent Deployment。 */
    @Transactional(rollbackFor = Exception.class)
    public ChannelBindingView bindChannel(
            String tenantId, long channelId, String deploymentId,
            Long expectedBindingRevision, String expectedEtag) {
        long tenant = tenant(tenantId);
        if (channelId <= 0) {
            throw new IllegalArgumentException("channelId must be positive");
        }
        String deployment = required(deploymentId, "deploymentId");
        OpenPlatformResourceLifecycleService.ActiveAgentDeployment target =
                resources.requireActiveAgentDeployment(tenant, deployment);
        OpenPlatformChannelBindingDO current = bindings.selectOne(new QueryWrapper<OpenPlatformChannelBindingDO>()
                .eq("tenant_id", tenant).eq("channel_id", channelId));
        Instant now = Instant.now();
        if (current == null) {
            if (expectedBindingRevision != null || (expectedEtag != null && !expectedEtag.isBlank())) {
                throw new ResourceRevisionConflictException(0, null);
            }
            OpenPlatformChannelBindingDO created = new OpenPlatformChannelBindingDO();
            created.setId(SnowflakeIds.nextId());
            created.setTenantId(tenant);
            created.setChannelId(channelId);
            created.setDeploymentId(deployment);
            created.setResourceId(target.resourceId());
            created.setResourceRevision(target.resourceRevision());
            created.setBindingRevision(1L);
            created.setStatus(ACTIVE);
            created.setEtag(bindingEtag(tenant, channelId, deployment, target.resourceRevision(), 1L));
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            bindings.insert(created);
            return ChannelBindingView.from(created);
        }
        if (expectedBindingRevision == null || expectedBindingRevision <= 0
                || !Objects.equals(current.getBindingRevision(), expectedBindingRevision)
                || !Objects.equals(current.getEtag(), unquote(expectedEtag))) {
            throw conflict(current.getBindingRevision(), current.getEtag());
        }
        long next = current.getBindingRevision() + 1L;
        String etag = bindingEtag(tenant, channelId, deployment, target.resourceRevision(), next);
        int updated = bindings.update(null, new UpdateWrapper<OpenPlatformChannelBindingDO>()
                .eq("tenant_id", tenant).eq("channel_id", channelId)
                .eq("binding_revision", expectedBindingRevision).eq("etag", current.getEtag())
                .set("deployment_id", deployment).set("resource_id", target.resourceId())
                .set("resource_revision", target.resourceRevision()).set("binding_revision", next)
                .set("status", ACTIVE).set("etag", etag).set("updated_at", now));
        if (updated == 0) {
            OpenPlatformChannelBindingDO raced = bindings.selectOne(new QueryWrapper<OpenPlatformChannelBindingDO>()
                    .eq("tenant_id", tenant).eq("channel_id", channelId));
            throw conflict(raced == null ? 0 : raced.getBindingRevision(), raced == null ? null : raced.getEtag());
        }
        current.setDeploymentId(deployment);
        current.setResourceId(target.resourceId());
        current.setResourceRevision(target.resourceRevision());
        current.setBindingRevision(next);
        current.setStatus(ACTIVE);
        current.setEtag(etag);
        current.setUpdatedAt(now);
        return ChannelBindingView.from(current);
    }

    static String bindingEtag(long tenant, long channelId, String deployment,
            long resourceRevision, long bindingRevision) {
        return TokenHash.sha256Hex(tenant + "|channel-binding|" + channelId + "|" + deployment
                + "|" + resourceRevision + "|" + bindingRevision);
    }

    private ResourceRevisionConflictException conflict(long revision, String etag) {
        return new ResourceRevisionConflictException(revision, etag);
    }

    private long tenant(String tenantId) {
        return Long.parseLong(required(tenantId, "tenantId"));
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
