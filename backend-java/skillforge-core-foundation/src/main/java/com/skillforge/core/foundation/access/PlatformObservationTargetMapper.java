package com.skillforge.core.foundation.access;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台观测只枚举机构身份，不从此查询读取机构的会话正文或Runtime数据。 */
@Mapper
public interface PlatformObservationTargetMapper {
    List<Long> targets(@Param("requested") Long requestedTenantId, @Param("after") long afterTenantId,
                       @Param("limit") int limit);
}
