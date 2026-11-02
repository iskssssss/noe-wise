package com.skillforge.institution.channel;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChannelKeyReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("request") String request,
              @Param("actor") String actor, @Param("channel") long channel);
    Row find(@Param("tenant") long tenant, @Param("request") String request);
    record Row(String actorRef, long channelId) { }
}
