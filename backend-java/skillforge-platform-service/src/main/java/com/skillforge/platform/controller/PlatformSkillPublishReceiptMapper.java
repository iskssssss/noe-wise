package com.skillforge.platform.controller;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlatformSkillPublishReceiptMapper {
    int claim(@Param("requestId") String requestId, @Param("actor") String actor, @Param("fingerprint") String fingerprint);

    int complete(@Param("requestId") String requestId, @Param("status") String status, @Param("response") String response);

    Row find(@Param("requestId") String requestId);

    record Row(String actorRef, String fingerprint, String status, String responseJson) { }
}
