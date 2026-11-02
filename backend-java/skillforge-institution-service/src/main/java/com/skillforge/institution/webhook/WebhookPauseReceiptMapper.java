package com.skillforge.institution.webhook;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WebhookPauseReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("request") String request,
              @Param("actor") String actor, @Param("subscription") long subscription);
    int complete(@Param("tenant") long tenant, @Param("request") String request, @Param("changed") boolean changed);
    Row find(@Param("tenant") long tenant, @Param("request") String request);
    record Row(String actorRef, long subscriptionId, Boolean changed) { }
}
