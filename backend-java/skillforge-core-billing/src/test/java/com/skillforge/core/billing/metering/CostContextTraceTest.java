package com.skillforge.core.billing.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.skillforge.core.shared.tracing.TraceContext;
import org.junit.jupiter.api.Test;

/** CostContext 与持久 TraceContext 的继承契约测试。 */
class CostContextTraceTest {
    @org.junit.jupiter.api.Test void changingActualRunDoesNotInheritAnotherRunsAttribution() {
        var parent=CostContext.builder().runId("parent").attributionRunId("source").build();
        org.junit.jupiter.api.Assertions.assertNull(CostContext.merge(parent,CostContext.builder().runId("child").build()).attributionRunId());
        org.junit.jupiter.api.Assertions.assertEquals("source",CostContext.merge(parent,CostContext.builder().callPoint("nested").build()).attributionRunId());
        org.junit.jupiter.api.Assertions.assertEquals("frozen",CostContext.merge(parent,CostContext.builder().runId("child").attributionRunId("frozen").build()).attributionRunId());
    }

    @Test
    void childOverridesOnlyProvidedFieldsAndKeepsTrace() {
        TraceContext trace = new TraceContext("input", "root", "trace", "span", "event", null);
        CostContext parent = CostContext.builder()
                .requestId("input")
                .sessionId("session")
                .callPoint("chat.request")
                .traceContext(trace)
                .build();
        CostContext child = CostContext.builder().callPoint("llm.chat").build();

        CostContext merged = CostContext.merge(parent, child);

        assertEquals("input", merged.requestId());
        assertEquals("session", merged.sessionId());
        assertEquals("llm.chat", merged.callPoint());
        assertSame(trace, merged.traceContext());
    }

    @Test
    void builderCarriesAllBusinessDimensions() {
        TraceContext trace = new TraceContext("i", "r", "t", "s", "e", "parent");
        CostContext context = CostContext.builder()
                .eventId("event")
                .requestId("i")
                .sessionId("session")
                .endUserId(11L)
                .channelId("channel")
                .agentId(12L)
                .runId("run-13")
                .workflowInstanceId(14L)
                .executionId("execution-16")
                .scopeId("scope")
                .scopeType("case")
                .callPoint("tool.call")
                .traceContext(trace)
                .build();

        assertEquals("event", context.eventId());
        assertEquals(11L, context.endUserId());
        assertEquals("r", context.rootInputId());
        assertEquals("run-13", context.runId());
        assertEquals("execution-16", context.executionId());
        assertSame(trace, context.traceContext());
    }
}
