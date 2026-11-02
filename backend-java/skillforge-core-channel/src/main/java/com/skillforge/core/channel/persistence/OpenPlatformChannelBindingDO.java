package com.skillforge.core.channel.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** ChannelBinding；只允许指向已部署 Agent。 */
@Data
@TableName("nw_open_platform_channel_bindings")
public class OpenPlatformChannelBindingDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long channelId;
    private String deploymentId;
    private String resourceId;
    private Long resourceRevision;
    private Long bindingRevision;
    private String status;
    private String etag;
    private Instant createdAt;
    private Instant updatedAt;
}
