package com.skillforge.institution.team;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 团队草稿写入只保存精确身份与修订，不复制团队正文。 */
@Mapper
public interface TeamDraftReceiptMapper {
    int insert(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("teamId") String teamId, @Param("revision") long revision);
    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId, @Param("actor") String actor);
    record Row(String teamId, long revision) { }
}
