package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** 四类 Capability 的稳定身份记录。 */
@Data
@TableName("nw_capability_definitions")
public class CapabilityDefinitionDO {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String capabilityType;
    private String capabilityKey;
    private String name;
    private String description;
    private String trustLevel;
    private String status;
    private Long defaultVersionId;
    private Long revision;
    private Instant createdAt;
    private Instant updatedAt;
}
