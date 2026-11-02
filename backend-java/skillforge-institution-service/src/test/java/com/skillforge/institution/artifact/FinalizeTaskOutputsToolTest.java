package com.skillforge.institution.artifact;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.SessionGoal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FinalizeTaskOutputsToolTest {
    @ParameterizedTest
    @EnumSource(value = SessionGoal.Reason.class, names = {"NOT_FOUND", "REVISION_CONFLICT", "STATE_CONFLICT"})
    void goalCompletionRejectionsKeepTheExistingPublicCode(SessionGoal.Reason reason) throws Exception {
        var service = mock(DeliverableService.class);
        when(service.finalizeOutputs(any(), any())).thenThrow(new SessionGoal.Rejected(reason));

        var result = new FinalizeTaskOutputsTool(service, new ObjectMapper()).execute(
                Map.of("deliverables", List.of(), "goal_completion", Map.of("expected_revision", 1)), context());

        assertEquals(Map.of("success", false, "error", reason.code()), result);
    }

    @Test void completeManifestReachesFinalizationWithoutAPrecedingPlanCall() throws Exception {
        var service = mock(DeliverableService.class);
        when(service.finalizeOutputs(any(), any())).thenReturn(Map.of("success", true));
        var tool = new FinalizeTaskOutputsTool(service, new ObjectMapper());
        var item = new LinkedHashMap<String, Object>();
        item.put("key", "report"); item.put("title", "核验报告"); item.put("kind", "document");
        item.put("base_revision_id", "actual-revision");
        item.put("representations", List.of(Map.of("role", "source", "format", "md", "primary_preview", true,
                "files", List.of(Map.of("artifact_id", "candidate", "path", "report.md")))));
        assertEquals(true, tool.execute(Map.of("deliverables", List.of(item)), context()).get("success"));
        var captured = org.mockito.ArgumentCaptor.forClass(DeliverableContract.Finalize.class);
        verify(service).finalizeOutputs(captured.capture(), any());
        var selected = captured.getValue().deliverables().getFirst();
        assertEquals("report", selected.key()); assertEquals("核验报告", selected.title());
        assertEquals("document", selected.kind()); assertEquals("actual-revision", selected.baseRevisionId());
        assertEquals("candidate", selected.representations().getFirst().files().getFirst().artifactId());
        verifyNoMoreInteractions(service);
    }

    @Test void modelCannotSubmitFormatProvenanceAsAuthority() throws Exception {
        var service = mock(DeliverableService.class);
        var tool = new FinalizeTaskOutputsTool(service, new ObjectMapper());
        var result = tool.execute(Map.of("deliverables", List.of(Map.of("key", "report", "title", "报告",
                "kind", "document", "formats", List.of(Map.of("format", "pdf", "required", true,
                        "decision_source", "user_explicit")), "representations", List.of()))), context());
        assertEquals("INVALID_FINALIZE_ARGUMENTS", result.get("error"));
        verifyNoInteractions(service);
    }

    @Test void missingActualFileReferencesReachModelAsExactStructuredCorrections() throws Exception {
        var service=mock(DeliverableService.class);
        var rejection=new OutputContractRejection("OUTPUT_MANIFEST_INVALID",List.of(
                new OutputContractRejection.Correction("*","representations[].files[].path",
                        List.of(),false)),"提供真实文件引用后提交完整清单");
        when(service.finalizeOutputs(any(),any())).thenThrow(rejection);
        var result=new FinalizeTaskOutputsTool(service,new ObjectMapper()).execute(Map.of("deliverables",List.of()),context());
        assertEquals(rejection.feedback(),result);
    }

    @Test void representationFailuresReachModelTogetherWithPreciseFieldsAndNoUntrustedEcho() throws Exception {
        var service = mock(DeliverableService.class);
        when(service.finalizeOutputs(any(), any())).thenAnswer(call -> {
            DeliverableContract.Finalize request = call.getArgument(0);
            DeliverableRequirements.validateSelectionShape(request.deliverables());
            return Map.of("success", true);
        });
        var item = Map.of("key", "untrusted-key", "title", "untrusted-title", "kind", "website",
                "representations", List.of(Map.of("role", "bundle", "format", "html", "primary_preview", true,
                        "entry_path", "untrusted-entry", "files", List.of(Map.of("artifact_id", "candidate", "path", "index.html")))));
        var feedback = new FinalizeTaskOutputsTool(service, new ObjectMapper()).execute(Map.of("deliverables", List.of(item)), context());
        assertEquals(false, feedback.get("success"));
        assertEquals("OUTPUT_MANIFEST_INVALID", feedback.get("error"));
        var fields = new ObjectMapper().valueToTree(feedback).path("corrections");
        assertEquals(2, fields.size());
        assertEquals("deliverables[0].representations[0].role", fields.get(0).path("field").asText());
        assertEquals("deliverables[0].representations[0].entry_path", fields.get(1).path("field").asText());
        assertTrue(feedback.get("instruction").toString().contains("尚未冻结"));
        assertFalse(feedback.toString().contains("untrusted-"));
    }

    private static PlatformToolExecutionContext context() {
        var tenant = new TenantId("1"); var input = new InputId("input");
        var owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("7"), new EndUserId(9));
        var run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("verified"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("verified"));
        return new PlatformToolExecutionContext(new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(300), () -> false)), "invocation", "attempt");
    }
}
