package com.skillforge.institution.webhook;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WebhookCreateReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("request") String request, @Param("actor") String actor);
    int complete(@Param("tenant") long tenant, @Param("request") String request, @Param("subscription") long subscription);
    Row find(@Param("tenant") long tenant, @Param("request") String request);
    record Row(String actorRef, Long subscriptionId) { }
}
