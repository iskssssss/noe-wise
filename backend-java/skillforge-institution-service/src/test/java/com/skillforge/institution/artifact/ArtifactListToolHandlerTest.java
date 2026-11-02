package com.skillforge.institution.artifact;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtifactListToolHandlerTest {
    private final ArtifactStorage storage = mock(ArtifactStorage.class);
    private final DeliverableService deliverables=mock(DeliverableService.class);
    private final ArtifactListToolHandler handler = new ArtifactListToolHandler(storage,deliverables);

    @AfterEach void clearContext() { TenantContext.clear(); }

    @Test void consumesOnlyTrustedSessionToolPageAndRestoresContext() {
        Instant future = Instant.now().plusSeconds(600);
        TenantContext.setTenantId("other-tenant");
        when(storage.listForTool("1", "session",0,30)).thenAnswer(call -> {
            assertEquals("1", TenantContext.getTenantId());
            return new ArtifactStorage.ToolPage(1,List.of(artifact("a","session","ready",future)));
        });
        var result = handler.execute(Map.of(), context("1", "session"));
        assertEquals(true, result.get("success"));
        assertEquals(1L, result.get("total"));
        assertEquals(0, result.get("offset"));
        assertEquals(false, result.get("has_more"));
        var items = (List<?>) result.get("artifacts");
        var reference = (Map<?,?>) items.getFirst();
        assertEquals(Set.of("id", "name", "type", "run_id", "expires_at", "files", "artifact_inputs"), reference.keySet());
        assertEquals("a", reference.get("id"));
        assertEquals(List.of(Map.of("file_id", "a:records.json", "path", "records.json", "size", 2L, "sha256", "0".repeat(64))), reference.get("files"));
        assertEquals(List.of(), reference.get("artifact_inputs"));
        assertEquals("other-tenant", TenantContext.getTenantId());
        verify(storage).listForTool("1", "session",0,30);
        verifyNoMoreInteractions(storage);
    }

    @Test void passesPagingToStorageAndKeepsCurrentDeliverablesIndependent() {
        Instant later = Instant.now().plusSeconds(600);
        when(storage.listForTool("2","second",1,1)).thenReturn(new ArtifactStorage.ToolPage(3,
                List.of(artifact("b","second","ready",later.minusSeconds(60)))));
        when(storage.listForTool("2","second",3,1)).thenReturn(new ArtifactStorage.ToolPage(3,List.of()));
        var current=List.<Map<String,Object>>of(Map.of("id","formal-output"));
        when(deliverables.currentOutputs(any())).thenReturn(current);
        var result = handler.execute(Map.of("offset", 1, "limit", 1), context("2", "second"));
        assertEquals(3L, result.get("total"));assertEquals(true, result.get("has_more"));
        assertEquals("b", ((Map<?,?>)((List<?>)result.get("artifacts")).getFirst()).get("id"));
        var end = handler.execute(Map.of("offset", 3, "limit", 1), context("2", "second"));
        assertEquals(List.of(), end.get("artifacts"));assertEquals(false, end.get("has_more"));
        assertNull(TenantContext.getTenantId());
        assertEquals(current,result.get("current_deliverables"));assertEquals(current,end.get("current_deliverables"));
        verify(deliverables,times(2)).currentOutputs(any());
        verify(storage).listForTool("2","second",1,1);verify(storage).listForTool("2","second",3,1);verifyNoMoreInteractions(storage);
    }

    @Test void restoresContextWhenStorageFails() {
        TenantContext.setTenantId("previous");
        when(storage.listForTool("1", "session",0,30)).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(IllegalStateException.class, () -> handler.execute(Map.of(), context("1", "session")));
        assertEquals("previous", TenantContext.getTenantId());
    }

    @Test void exposesExplicitParentGraphWithoutFileBodies() {
        Instant future=Instant.now().plusSeconds(600);
        var child=new ArtifactMetadata("child","session","run","invocation","file","report.txt",2,
                "0".repeat(64),future,"ready",List.of(new ArtifactMetadata.File("report.txt",2,"0".repeat(64))),
                List.of(new ArtifactProvenance("12345678-1234-1234-1234-123456789abc",
                        "source.txt",3,"1".repeat(64))));
        when(storage.listForTool("1","session",0,30)).thenReturn(new ArtifactStorage.ToolPage(1,List.of(child)));

        var reference=(Map<?,?>)((List<?>)handler.execute(Map.of(),context("1","session")).get("artifacts")).getFirst();

        assertEquals(List.of(Map.of("artifact_id","12345678-1234-1234-1234-123456789abc",
                "path","source.txt","size",3L,"sha256","1".repeat(64))),reference.get("artifact_inputs"));
    }

    @Test void rejectsUnknownIdentityAndInvalidPagingArgumentsBeforeStorageAccess() {
        List<Map<String,Object>> invalid = List.of(Map.of("tenant_id", "2"), Map.of("session_id", "foreign"),
                Map.of("offset", -1), Map.of("offset", 10001), Map.of("limit", 0), Map.of("limit", 101),
                Map.of("limit", 1.5), Map.of("offset", "0"), Map.of("limit", true), Map.of("limit", Double.NaN));
        for(var args : invalid)
            assertThrows(IllegalArgumentException.class, () -> handler.execute(args, context("1", "session")));
        var explicitNull = new HashMap<String,Object>();explicitNull.put("limit", null);
        assertThrows(IllegalArgumentException.class, () -> handler.execute(explicitNull, context("1", "session")));
        verifyNoInteractions(storage);
    }

    private ArtifactMetadata artifact(String id, String session, String status, Instant expires) {
        return new ArtifactMetadata(id, session, "run", "invocation", "file", "records.json", 2,
                "0".repeat(64), expires, status, List.of(new ArtifactMetadata.File("records.json", 2, "0".repeat(64))),List.of());
    }

    private PlatformToolExecutionContext context(String tenantId, String session) {
        var tenant = new TenantId(tenantId);var input = new InputId("input");
        var owner = SessionOwner.user(new SessionRef(tenant, session), new AgentId("7"), new EndUserId(9));
        var run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("result"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("verification"));
        return new PlatformToolExecutionContext(
                new PortCallContext(run, identity, new InvocationControl(Instant.now().plusSeconds(60), () -> false)),
                "invocation", "attempt");
    }
}
