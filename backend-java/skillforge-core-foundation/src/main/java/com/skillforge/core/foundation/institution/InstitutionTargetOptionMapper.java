package com.skillforge.core.foundation.institution;

import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 权限范围和当前关系均先在 SQL 中过滤，再按名称/标识查询、分页。 */
@Mapper
public interface InstitutionTargetOptionMapper {
    List<InstitutionTargetOption> rows(@Param("actor") long actor, @Param("direct") boolean direct,
            @Param("targets") Set<Long> targets, @Param("query") String query, @Param("id") Long id,
            @Param("limit") int limit, @Param("offset") long offset);
    long count(@Param("actor") long actor, @Param("direct") boolean direct,
            @Param("targets") Set<Long> targets, @Param("query") String query, @Param("id") Long id);
}
