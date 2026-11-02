package com.skillforge.core.foundation.institution.usage;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 当前明确用量许可及其直属关系版本；不从目录或 Agent 来源授权推导目标。 */
@Mapper
public interface HeadquartersUsageMapper {
    List<TargetGrant> currentTargets(@Param("actorTenant") long actorTenant, @Param("action") String action);

    /** 许可和关系身份均进入游标授权摘要，迁出再迁入不复活原 cursor。 */
    record TargetGrant(long tenantId, long grantId, long grantRevision, long relationRef, long relationRevision) { }
}
