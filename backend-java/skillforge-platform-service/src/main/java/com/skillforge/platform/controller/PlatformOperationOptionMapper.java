package com.skillforge.platform.controller;

import com.skillforge.platform.operationoptions.PlatformOperationOption;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读操作候选；调用前由所属服务取得当前精确动作的目标闭包。 */
@Mapper
public interface PlatformOperationOptionMapper {
    List<PlatformOperationOption> tenants(@Param("allowed") Set<Long> allowed, @Param("query") String query,
            @Param("id") Long id, @Param("limit") int limit, @Param("offset") long offset);
    long tenantCount(@Param("allowed") Set<Long> allowed, @Param("query") String query, @Param("id") Long id);
    List<PlatformOperationOption> definitions(@Param("allowed") Set<Long> allowed, @Param("grant") boolean grant,
            @Param("query") String query, @Param("id") Long id, @Param("limit") int limit, @Param("offset") long offset);
    long definitionCount(@Param("allowed") Set<Long> allowed, @Param("grant") boolean grant,
            @Param("query") String query, @Param("id") Long id);
    List<PlatformOperationOption> integrations(@Param("tenant") long tenant, @Param("query") String query,
            @Param("id") String id, @Param("limit") int limit, @Param("offset") long offset);
    long integrationCount(@Param("tenant") long tenant, @Param("query") String query, @Param("id") String id);
}
