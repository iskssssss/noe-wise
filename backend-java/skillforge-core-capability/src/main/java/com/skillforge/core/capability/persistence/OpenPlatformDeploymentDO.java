package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** Open Platform Deployment；只保存已发布资源 revision 的引用。 */
@Data
@TableName("nw_open_platform_deployments")
public class OpenPlatformDeploymentDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String deploymentId;
    private String resourceType;
    private String resourceId;
    private Long resourceRevision;
    private String environment;
    private String status;
    private Long deploymentRevision;
    private String etag;
    private Instant createdAt;
    private Instant updatedAt;
}
