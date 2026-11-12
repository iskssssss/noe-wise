package com.skillforge.core.agent.systemagent;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台配置及按执行机构隔离的运行账本持久化；SQL 集中在对应 XML。 */
@Mapper
public interface SystemAgentMapper {
    List<String> list();
    String find(@Param("id") String id);
    String findPurpose(@Param("purpose") String purpose);
    int create(@Param("id") String id, @Param("key") String key, @Param("purpose") String purpose,
               @Param("mode") String mode, @Param("config") String config);
    int save(@Param("id") String id, @Param("expected") long expected, @Param("config") String config);
    int publish(@Param("id") String id, @Param("expected") long expected,
                @Param("published") long published, @Param("config") String config);
    int state(@Param("id") String id, @Param("expected") long expected,
              @Param("status") String status, @Param("published") Long published);
    int append(@Param("id") String id, @Param("version") long version,
               @Param("config") String config, @Param("actor") String actor);
    List<String> versions(@Param("id") String id);
    int delete(@Param("id") String id, @Param("expected") long expected);
    int audit(@Param("id") String id, @Param("revision") long revision,
              @Param("operation") String operation, @Param("actor") String actor);
    String bindTenant(@Param("tenant") String tenant);
    String existingRun(@Param("tenant") String tenant, @Param("agent") String agent,
                       @Param("key") String key);
    int insertRun(@Param("id") String id, @Param("tenant") String tenant, @Param("agent") String agent,
                  @Param("key") String key, @Param("fingerprint") String fingerprint,
                  @Param("version") Long version, @Param("debug") boolean debug,
                  @Param("config") String config, @Param("modelRevision") long modelRevision,
                  @Param("input") String input, @Param("mounts") String mounts);
    String run(@Param("id") String id, @Param("tenant") String tenant);
    List<String> runs(@Param("tenant") String tenant, @Param("agent") String agent);
    String debugRun(@Param("tenant") String tenant, @Param("agent") String agent, @Param("key") String key);
    List<String> pendingDebug(@Param("tenant") String tenant);
    int expireModelRun(@Param("id") String id, @Param("tenant") String tenant);
    int claim(@Param("id") String id, @Param("tenant") String tenant);
    int finish(@Param("id") String id, @Param("tenant") String tenant, @Param("status") String status,
               @Param("output") String output, @Param("error") String error,
               @Param("inputTokens") Long inputTokens, @Param("outputTokens") Long outputTokens,
               @Param("duration") long duration);
}
