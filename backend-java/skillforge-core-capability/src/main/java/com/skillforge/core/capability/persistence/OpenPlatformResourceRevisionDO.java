package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** Open Platform 资源正文的不可变 revision 记录。 */
@Data
@TableName(value = "nw_open_platform_resource_revisions", autoResultMap = true)
public class OpenPlatformResourceRevisionDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String resourceType;
    private String resourceId;
    private Long revision;
    private String status;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> content;
    private String contentHash;
    private String etag;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;
}
