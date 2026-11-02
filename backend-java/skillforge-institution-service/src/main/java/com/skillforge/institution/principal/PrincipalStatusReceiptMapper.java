package com.skillforge.institution.principal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PrincipalStatusReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("request") String request,
              @Param("actor") String actor, @Param("principal") long principal, @Param("action") String action);
    int complete(@Param("tenant") long tenant, @Param("request") String request, @Param("changed") boolean changed);
    Row find(@Param("tenant") long tenant, @Param("request") String request);
    record Row(String actorRef, long principalId, String action, Boolean changed) { }
}
