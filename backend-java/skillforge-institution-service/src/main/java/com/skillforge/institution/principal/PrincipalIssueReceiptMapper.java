package com.skillforge.institution.principal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PrincipalIssueReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("request") String request,
              @Param("actor") String actor, @Param("fingerprint") String fingerprint);
    int complete(@Param("tenant") long tenant, @Param("request") String request,
                 @Param("principal") long principal);
    Row find(@Param("tenant") long tenant, @Param("request") String request);
    record Row(String actorRef, String fingerprint, Long principalId) { }
}
