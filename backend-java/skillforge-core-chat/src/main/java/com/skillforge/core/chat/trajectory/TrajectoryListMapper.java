package com.skillforge.core.chat.trajectory;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 先按真实机构、会话与时间收窄根输入；公开正文匹配由统一投影完成。 */
@Mapper
public interface TrajectoryListMapper {
    Instant readAt();
    List<Candidate> candidates(Candidates query);

    record Candidate(long tenantId, String sessionId, String rootInputId,
                     long startedSecond, int startedNano) { }

    record Candidates(Long scopeTenantId, Long tenantId, String sessionId, Long agentId,
                      List<String> visibleSessionIds, long fromSecond, int fromNano,
                      long toSecond, int toNano, long offset, int limit) { }
}
