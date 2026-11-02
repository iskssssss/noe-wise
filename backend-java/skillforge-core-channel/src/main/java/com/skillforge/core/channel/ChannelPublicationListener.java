package com.skillforge.core.channel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.channel.persistence.OpenPlatformChannelBindingMapper;
import com.skillforge.core.channel.persistence.OpenPlatformChannelBindingDO;
import com.skillforge.core.capability.application.OpenPlatformPublicationListener;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** 发布成功与渠道推进共享资源领域开启的事务，禁止异步更新生效路由。 */
public class ChannelPublicationListener implements OpenPlatformPublicationListener {
    private static final String ACTIVE = "active";
    private final OpenPlatformChannelBindingMapper bindings;
    public ChannelPublicationListener(OpenPlatformChannelBindingMapper bindings) { this.bindings = bindings; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void advanceBindings(long tenantId, String resourceId, long resourceRevision, List<String> deploymentIds) {
        List<OpenPlatformChannelBindingDO> channels = bindings.selectList(new QueryWrapper<OpenPlatformChannelBindingDO>()
                .eq("tenant_id", tenantId).eq("resource_id", resourceId).eq("status", ACTIVE)
                .in("deployment_id", deploymentIds)
                .orderByAsc("id").last("FOR UPDATE"));
        if (channels == null || channels.isEmpty()) return;
        for (OpenPlatformChannelBindingDO channel : channels) {
            channel.setResourceRevision(resourceRevision);
            channel.setBindingRevision(Math.addExact(channel.getBindingRevision(), 1));
            channel.setEtag(OpenPlatformChannelBindingService.bindingEtag(channel.getTenantId(), channel.getChannelId(), channel.getDeploymentId(),
                    channel.getResourceRevision(), channel.getBindingRevision()));
        }
        if (bindings.advancePublication(channels) != channels.size()) throw new IllegalStateException("渠道发布路由未完整推进");
    }
}
