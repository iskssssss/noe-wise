package com.skillforge.institution.service;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HandoffActionMapper {
    int insert(@Param("tenant") long tenant, @Param("command") String command,
            @Param("ticket") String ticket, @Param("operator") long operator,
            @Param("action") String action, @Param("revision") long revision,
            @Param("fingerprint") String fingerprint);
    Row find(@Param("tenant") long tenant, @Param("command") String command);
    record Row(String ticketId, long operatorId, String action, long expectedRevision, String fingerprint) { }
}
