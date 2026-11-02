package com.skillforge.core.capability;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkillCreateReceiptMapper {
    int claim(@Param("requestId") String requestId, @Param("tenant") Long tenant,
            @Param("actor") String actor, @Param("fingerprint") String fingerprint);
    int complete(@Param("requestId") String requestId, @Param("definition") Long definition,
            @Param("draft") Long draft, @Param("revision") long revision);
    Row find(@Param("requestId") String requestId);
    record Row(String actorRef, String fingerprint, String definitionId, String draftId, Long revision) { }
}
