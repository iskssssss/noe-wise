package com.skillforge.core.channel;

import java.time.Instant;
import com.skillforge.core.channel.persistence.OpenPlatformChannelBindingDO;

/** 已提交渠道绑定的公开快照；不提供持久化写入能力。 */
public record ChannelBindingView(Long id, Long tenantId, Long channelId, String deploymentId,
        String resourceId, Long resourceRevision, Long bindingRevision, String status,
        String etag, Instant createdAt, Instant updatedAt) {
    static ChannelBindingView from(OpenPlatformChannelBindingDO row) {
        return new ChannelBindingView(row.getId(), row.getTenantId(), row.getChannelId(), row.getDeploymentId(),
                row.getResourceId(), row.getResourceRevision(), row.getBindingRevision(), row.getStatus(),
                row.getEtag(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
