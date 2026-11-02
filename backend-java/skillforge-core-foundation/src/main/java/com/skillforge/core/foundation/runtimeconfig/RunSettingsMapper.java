package com.skillforge.core.foundation.runtimeconfig;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RunSettingsMapper {
    String bindTenant(@Param("tenant") String tenant);
    String find(@Param("tenant") String tenant,@Param("run") String run);
    int insert(@Param("tenant") String tenant,@Param("run") String run,@Param("snapshot") String snapshot);
    String findOperation(@Param("tenant") String tenant, @Param("run") String run, @Param("operation") String operation);
    int insertOperation(@Param("tenant") String tenant, @Param("run") String run,
            @Param("operation") String operation, @Param("snapshot") String snapshot);
}
