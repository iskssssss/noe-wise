package com.skillforge.institution.channel;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChannelCreateReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("fingerprint") String fingerprint);
    int complete(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("channelId") long channelId);
    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId);
    record Row(String actorRef, String fingerprint, Long channelId) { }
}
