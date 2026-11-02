package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** Open Platform externalRef 到内部资源 revision 的租户隔离映射。 */
@Data
@TableName("nw_integration_resource_mappings")
public class ResourceMappingDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String integrationId;
    private String resourceType;
    private String externalRef;
    private String resourceId;
    private Long revision;
    private String contentHash;
    private String etag;
    private Instant createdAt;
    private Instant updatedAt;
}
