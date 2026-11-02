package com.skillforge.institution.execution;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.core.capability.connector.ProgramToolInvoker;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** run_code 仅负责将受限程序输入交给既有执行管线，不能改变执行结果语义。 */
class ProgramCodeToolHandlerTest {
    @Test
    void forcesInlineNodeAndPreservesProgramSummaryAndArtifacts() throws Exception {
        var execution = mock(CodeExecutionToolHandler.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProgramToolInvoker> tools = mock(ObjectProvider.class);
        ProgramToolInvoker invoker = mock(ProgramToolInvoker.class);
        when(tools.getObject()).thenReturn(invoker);
        var result = Map.<String, Object>of("success", true, "summary", "查询完成",
                "content", List.of(Map.of("type", "text", "text", "已核验数量：26000")),
                "artifacts", List.of(Map.of("id", "artifact-1", "name", "report.csv")),
                "delivery_status", "ready");
        when(execution.executeProgram(anyMap(), any(), same(invoker))).thenReturn(result);
        var handler = new ProgramCodeToolHandler(execution, tools);
        var context = context();

        assertSame(result, handler.execute(Map.of("code", "await tools.call('tool:lookup', {});",
                "dependencies", List.of("csv"), "artifact_inputs", List.of()), context));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(execution).executeProgram(arguments.capture(), same(context), same(invoker));
        assertEquals("inline", arguments.getValue().get("mode"));
        assertEquals("node", arguments.getValue().get("language"));
        assertEquals("查询完成", result.get("summary"));
        assertEquals(List.of(Map.of("id", "artifact-1", "name", "report.csv")), result.get("artifacts"));
        assertEquals("ready", result.get("delivery_status"));
        assertInstanceOf(com.skillforge.core.capability.connector.PlatformMediaToolHandler.class, handler);
        assertEquals(List.of(Map.of("type", "text", "text", "已核验数量：26000")), result.get("content"));
    }

    @Test
    void rejectsUnknownOrIncompleteProgramArgumentsBeforeLoadingTheInvoker() throws Exception {
        var execution = mock(CodeExecutionToolHandler.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProgramToolInvoker> tools = mock(ObjectProvider.class);
        var handler = new ProgramCodeToolHandler(execution, tools);

        assertEquals(Map.of("success", false, "error", "invalid_arguments"),
                handler.execute(Map.of("code", "console.log('x')", "language", "node", "mode", "inline"), context()));
        assertEquals(Map.of("success", false, "error", "invalid_arguments"),
                handler.execute(Map.of("dependencies", List.of()), context()));
        verifyNoInteractions(execution, tools);
    }

    private static PlatformToolExecutionContext context() {
        TenantId tenant = new TenantId("tenant");
        InputId input = new InputId("input");
        SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("agent"), new EndUserId(1));
        RunIdentity run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("verification"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("identity"));
        return new PlatformToolExecutionContext(new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(30), () -> false)), "invocation", "attempt");
    }
}
