package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** ToolVersion 到 Provider 的租户部署持久化记录。 */
@Data
@TableName("nw_tool_deployments")
public class ToolDeploymentRecordDO {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String deploymentKey;
    private Long toolVersionId;
    private String providerType;
    private String providerRef;
    private String environment;
    private String status;
    private Long revision;
    private Instant createdAt;
    private Instant updatedAt;
}
