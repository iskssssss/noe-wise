package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** Capability 不可变类型化 Descriptor 版本。 */
@Data
@TableName(value = "nw_capability_versions", autoResultMap = true)
public class CapabilityVersionDO {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long definitionId;
    private Long tenantId;
    private String capabilityType;
    private String versionKey;
    private String name;
    private String description;
    private String trustLevel;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> descriptor;
    private String contentHash;
    private String status;
    private Instant createdAt;
    private Instant publishedAt;
}
