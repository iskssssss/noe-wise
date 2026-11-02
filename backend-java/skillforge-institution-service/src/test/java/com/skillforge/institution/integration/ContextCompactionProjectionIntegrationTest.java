package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.persistence.SessionOutboxSequenceMapper;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.session.AgentExecutionContextViews;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 与Runtime appendEvents共用真实借用事务入口，内部事件只推进持久水位，不产生公开正文。 */
class ContextCompactionProjectionIntegrationTest extends AbstractIntegrationTest {
    /** 本类先于基类容器启动入口登记；E盘初始化较慢时最多等待三分钟，不改变数据库耐久参数。 */
    @DynamicPropertySource
    static void contextProjectionStartup(DynamicPropertyRegistry registry) {
        POSTGRES.withStartupTimeout(Duration.ofMinutes(3));
    }

    @Autowired SqlSessionFactory sessions;
    @Autowired SessionMailboxService mailbox;
    @Autowired AgentRuntimeExecutionProjection projection;
    @Autowired ObjectMapper json;

    @Test void internalContextBatchCommitsCursorAndNextPublicFactExactlyOnceThroughBorrowedJdbcTransaction() throws Exception {
        String tenant=Long.toString(SnowflakeIds.nextId());
        var session=new SessionRef(new TenantId(tenant),"context-projection-"+UUID.randomUUID());
        var run=new RunRef(session.tenantId(),"context-run-"+UUID.randomUUID());
        var material=saved(run,1,"context-material",AgentExecutionContextViews.EVENT,AgentExecutionContextViews.SCHEMA,
                "{\"sourceContext\":\"private-source\",\"messages\":{}}");
        var attempt=saved(run,2,"context-attempt","agent.context-compaction-attempt",
                "skillforge.runtime.execution.context-compaction-attempt.v1","{\"summaryStatus\":\"UNAVAILABLE\",\"summaryReason\":\"SUMMARY_DISABLED\"}");
        var accepted=saved(run,3,"accepted",AgentExecutionProgram.ACCEPTED_EVENT,AgentExecutionProgram.ACCEPTED_SCHEMA,
                json.writeValueAsString(Map.of("inputId","input","rootInputId","root","traceId","trace","spanId","span","agentId","7")));
        TenantContext.setTenantId(tenant);
        try {
            try(var sql=sessions.openSession(false)) {
                try(var statement=sql.getConnection().prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    statement.setString(1,tenant);statement.execute();
                }
                projection.appendBorrowed(sql,session,List.of(material,attempt),after->List.of());
                var cursor=sql.getMapper(SessionOutboxSequenceMapper.class).projectionRecord(Long.valueOf(tenant),session.sessionId());
                assertEquals(2L,cursor.sourceSequence()); assertEquals("context-attempt",cursor.sourceEventId());
                sql.commit();
            }
            assertTrue(mailbox.listOutputsAfterSequence(tenant,session.sessionId(),0,100).isEmpty());
            try(var sql=sessions.openSession(false)) {
                try(var statement=sql.getConnection().prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    statement.setString(1,tenant);statement.execute();
                }
                projection.appendBorrowed(sql,session,List.of(accepted),after->{throw new AssertionError("已提交内部水位不应重读");});
                sql.commit();
            }
            var outputs=mailbox.listOutputsAfterSequence(tenant,session.sessionId(),0,100);
            assertEquals(1,outputs.size());assertEquals("execution.accepted",outputs.getFirst().getPayload().get("event"));
            assertFalse(json.writeValueAsString(outputs).contains("private-source"));
            try(var sql=sessions.openSession(false)) {
                try(var statement=sql.getConnection().prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    statement.setString(1,tenant);statement.execute();
                }
                projection.appendBorrowed(sql,session,List.of(material,attempt,accepted),after->{throw new AssertionError("已提交事实不能重放");});
                sql.commit();
            }
            assertEquals(1,mailbox.listOutputsAfterSequence(tenant,session.sessionId(),0,100).size());
        } finally { TenantContext.clear(); }
    }

    private static ExecutionStore.SavedEvent saved(RunRef run,long sequence,String id,String type,String schema,String body) {
        return new ExecutionStore.SavedEvent(run,sequence,2,Instant.parse("2026-10-27T14:00:00Z"),
                new ExecutionStore.Event(new EventId(id),type,new TypedPayload(schema,body)));
    }
}
