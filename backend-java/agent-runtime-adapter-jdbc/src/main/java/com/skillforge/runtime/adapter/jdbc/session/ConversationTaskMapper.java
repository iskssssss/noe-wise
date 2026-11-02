package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.port.ConversationTask;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.port.SessionOwner;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 任务存储 SQL 仅维护在配套 XML。 */
interface ConversationTaskMapper {
    int ensureRoot(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId, @Param("taskId") String taskId);
    ConversationTaskRoot lockRoot(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId, @Param("taskId") String taskId);
    ConversationTaskRow find(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId, @Param("taskId") String taskId);
    List<ConversationTaskRow> search(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId, @Param("query") String query,
            @Param("includeClosed") boolean includeClosed, @Param("offset") int offset, @Param("limit") int limit);
    String current(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId);
    Instant databaseNow();
    ConversationTaskOperation operation(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId, @Param("eventId") String eventId);
    int save(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId, @Param("task") ConversationTask task,
            @Param("payload") TypedPayload payload);
    int record(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId, @Param("taskId") String taskId,
            @Param("eventId") String eventId, @Param("expectedRevision") long expectedRevision,
            @Param("selected") boolean selected);
    int select(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId, @Param("taskId") String taskId);
    int lease(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId, @Param("taskId") String taskId,
            @Param("runId") String runId, @Param("fence") long fence,
            @Param("untilSecond") long untilSecond, @Param("untilNano") int untilNano);
    int release(@Param("owner") SessionOwner owner, @Param("agentId") AgentId agentId, @Param("taskId") String taskId,
            @Param("runId") String runId, @Param("fence") long fence);
}

/** 完整快照保留纳秒与类型化来源，不依赖 PostgreSQL JSON 重排。 */
final class ConversationTaskRow {
    String taskId;
    long revision;
    String schemaId;
    String snapshot;
}

/** fence 不随释放清零，避免旧持有者重新获得写权。 */
final class ConversationTaskRoot {
    String runId;
    long fence;
    Long untilSecond;
    Integer untilNano;
    Instant until() { return untilSecond == null ? null : Instant.ofEpochSecond(untilSecond, untilNano); }
}

/** 冻结首次提交的附加命令语义，事件正文保存任务完整快照。 */
final class ConversationTaskOperation {
    String taskId;
    long expectedRevision;
    boolean selected;
}
