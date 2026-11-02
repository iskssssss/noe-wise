package com.skillforge.institution.service;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConnectorUpdateReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("request") String request,
              @Param("actor") String actor, @Param("instance") String instance,
              @Param("fingerprint") String fingerprint);
    Row find(@Param("tenant") long tenant, @Param("request") String request);
    record Row(String actorRef, String instanceId, String fingerprint) { }
}
