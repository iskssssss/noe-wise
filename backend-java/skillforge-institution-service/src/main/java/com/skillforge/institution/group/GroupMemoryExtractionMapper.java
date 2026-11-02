package com.skillforge.institution.group;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群来源交接与当前任务资格；提取生命周期继续归通用 MemoryExtractionMapper。 */
@Mapper
public interface GroupMemoryExtractionMapper {
    Pending next(@Param("tenant") String tenant);
    boolean eligible(@Param("tenant") String tenant, @Param("integration") String integration,
            @Param("group") String group, @Param("agent") String agent);
    List<Long> subjects(@Param("tenant") String tenant, @Param("integration") String integration,
            @Param("group") String group);
    int enqueue(@Param("source") Pending source, @Param("event") String event,
            @Param("extractor") String extractor, @Param("policy") String policy);
    boolean queued(@Param("source") Pending source, @Param("event") String event,
            @Param("extractor") String extractor, @Param("policy") String policy);
    int acknowledge(@Param("source") Pending source);
    Policy policy(@Param("tenant") String tenant,@Param("integration") String integration,@Param("group") String group);
    boolean agentEnabled(@Param("tenant") String tenant,@Param("agent") String agent);
    int savePolicy(@Param("row") Policy row,@Param("expected") long expected);

    record Policy(String tenantId,String integrationId,String groupBindingId,boolean enabled,
            String extractorAgentId,long revision,String updatedByPrincipal) { }

    /** 作者和服务 Agent 分离；source 不要求存在前台 trigger，也不授予作者当前操作权。 */
    record Pending(String tenantId, String integrationId, String groupBindingId, String sessionId,
            String sourceMessageId, long sourceSequence, long sourceRevision, long authorEndUserId,
            String agentId) { }
}
