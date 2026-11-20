package com.skillforge.institution.runtime;

import com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.session.SqlSession;

/** 新用户输入的记忆来源；与受理共用事务，公开事件仅定位，正文取原冻结输入。 */
final class AgentRuntimeMemoryInputs {
    private final JdbcExecutionStore store;
    private final JdbcSessionLogPort log;
    private final AgentExecutionCodec codec;
    private final WorkflowTestRegistry tests;

    AgentRuntimeMemoryInputs(JdbcExecutionStore store, JdbcSessionLogPort log,
            AgentExecutionCodec codec, WorkflowTestRegistry tests) {
        this.store = store; this.log = log; this.codec = codec; this.tests = tests;
    }

    /** 不扫描历史；Goal、内部子执行与工作流隔离测试不产生个人记忆来源。 */
    void appendBorrowed(SqlSession sql, SessionRef session, List<ExecutionStore.SavedEvent> events) {
        for (var saved : events) {
            if (!AgentRuntimeExecutionResponses.USER_MESSAGE_EVENT.equals(saved.event().type())) continue;
            store.participate(sql, session.tenantId(), () -> {
                var run = store.load(saved.run()).orElseThrow();
                var identity = run.identity();
                // 用户对等待的回答有自己的 response 材料，不将原初始消息再次提取。
                if (!saved.event().id().value().equals("execution-user-message:" + saved.run().runId()
                        + ":" + identity.causationInputId().value())) return null;
                if (tests.session(sql, Long.parseLong(session.tenantId().value()), session.sessionId()) != null) return null;
                var input = store.originalInput(saved.run()).orElseThrow();
                var source = codec.frozenInput(input.content());
                var target = new SessionProjectionTarget(identity.owner(), identity.agentId(), identity.causationInputId(),
                        Optional.of(saved.run()), Optional.empty(), Optional.empty(), Optional.empty());
                final String fingerprint;
                try {
                    fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(input.content().canonicalJson().getBytes(StandardCharsets.UTF_8)));
                } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("sha256_unavailable", unavailable); }
                var event = new SessionEventDraft(new EventId("memory-input:" + saved.run().runId()),
                        "inbound.message", "v1", fingerprint, identity.trace(), source, saved.occurredAt());
                var result = log.participate(sql, session.tenantId(),
                        () -> log.append(new SessionLogPort.AppendSessionEvent(target, event)));
                if (result instanceof SessionLogPort.AppendResult.Conflict)
                    throw new IllegalStateException("memory_input_source_conflict");
                return null;
            });
        }
    }
}
