package com.skillforge.institution.artifact;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class DelegationDeliverableFactsTest {
    private final DeliverableMapper mapper=mock(DeliverableMapper.class);
    private final StudioArtifactProjection projection=mock(StudioArtifactProjection.class);
    private final ArtifactStorage storage=mock(ArtifactStorage.class);
    private final DelegationDeliverableFacts service=new DelegationDeliverableFacts(mapper,projection,storage);
    private final RunIdentity parent=run("1","parent-session","parent-run","7",9);
    private final RunIdentity child=run("1","delegation:child","child-run","8",9);
    private final StudioArtifactProjection.Route route=new StudioArtifactProjection.Route("parent-session","AGENT","7","8","parent-run");

    @Test void publishedEvidenceRequiresExactChildResultAndFilesAndDoesNotGiveFileAccess() {
        arrange();
        var result=service.read(parent,child).orElseThrow();
        assertEquals(List.of("deliverable-revision:revision-one"),result.evidence());
        assertTrue(result.summary().contains("方案（docx）"));
        assertFalse(result.summary().contains("artifact-one"));
        assertFalse(result.summary().contains("ticket"));
        verify(storage).requireFileReadable(any(),eq("document.docx"));
    }

    @Test void pendingPublicationWaitsAndFailedPublicationDoesNotBecomeDelivery() {
        when(projection.route("1","delegation:child","child-run")).thenReturn(Optional.of(route));
        when(mapper.publication("1","child-run")).thenReturn(Map.of("status","pending"));
        assertTrue(service.read(parent,child).isEmpty());
        when(mapper.publication("1","child-run")).thenReturn(Map.of("status","failed"));
        assertTrue(service.read(parent,child).orElseThrow().evidence().isEmpty());
        verifyNoInteractions(storage);
    }

    @Test void differentTenantUserParentOrRevocationCannotDiscloseMetadata() {
        assertThrows(SecurityException.class,()->service.read(parent,run("2","delegation:child","child-run","8",9)));
        assertThrows(SecurityException.class,()->service.read(parent,run("1","delegation:child","child-run","8",10)));
        when(projection.route("1","delegation:child","child-run")).thenReturn(Optional.of(
                new StudioArtifactProjection.Route("parent-session","AGENT","7","8","different-parent")));
        assertThrows(SecurityException.class,()->service.read(parent,child));
        when(projection.route("1","delegation:child","child-run")).thenThrow(new SecurityException("revoked"));
        assertThrows(SecurityException.class,()->service.read(parent,child));
        verifyNoInteractions(mapper,storage);
    }

    @Test void mismatchedFileOrExpiredAccessDoesNotBecomePublishedEvidence() {
        arrange();
        when(storage.metadata(any())).thenReturn(new ArtifactMetadata("artifact-one","delegation:child","child-run","inv",
                "file","document.docx",4,"sha",Instant.now().plusSeconds(60),"ready",
                List.of(new ArtifactMetadata.File("document.docx",4,"other")),List.of()));
        assertThrows(SecurityException.class,()->service.read(parent,child));
        doThrow(new SecurityException("expired")).when(storage).requireFileReadable(any(),anyString());
        assertThrows(SecurityException.class,()->service.read(parent,child));
    }

    @Test void anotherChildRevisionAndUnsafeTitleAreNotHandoffEvidence() {
        arrange();
        when(mapper.readRevisions(1,"delegation:child")).thenReturn(List.of(Map.of("id","revision-one","run_id","other-run","title","方案")));
        assertThrows(SecurityException.class,()->service.read(parent,child));
        when(mapper.readRevisions(1,"delegation:child")).thenReturn(List.of(Map.of("id","revision-one","run_id","child-run","title","private-title")));
        var guard=mock(PublicOutputGuard.Scope.class);when(guard.rejects("private-title")).thenReturn(true);
        when(storage.publicScope(child)).thenReturn(guard);
        assertThrows(SecurityException.class,()->service.read(parent,child));
    }

    private void arrange() {
        when(projection.route("1","delegation:child","child-run")).thenReturn(Optional.of(route));
        when(mapper.publication("1","child-run")).thenReturn(Map.of("status","published"));
        when(mapper.revisionIds("1","child-run")).thenReturn(List.of("revision-one"));
        when(mapper.readRevisions(1,"delegation:child")).thenReturn(List.of(Map.of("id","revision-one","run_id","child-run","title","方案")));
        when(mapper.readFiles(1,"delegation:child")).thenReturn(List.of(Map.of("revision_id","revision-one","artifact_id","artifact-one",
                "path","document.docx","sha256","sha","size",4L,"status","ready")));
        var row=new ArtifactRow("artifact-one","1","delegation:child","child-run","inv","file","document.docx",4,"sha","[]","sha",Instant.now(),Instant.now().plusSeconds(60),"ready");
        when(storage.require("1","artifact-one")).thenReturn(row);
        when(storage.metadata(row)).thenReturn(new ArtifactMetadata("artifact-one","delegation:child","child-run","inv",
                "file","document.docx",4,"sha",Instant.now().plusSeconds(60),"ready",List.of(new ArtifactMetadata.File("document.docx",4,"sha")),List.of()));
        when(storage.publicScope(child)).thenReturn(mock(PublicOutputGuard.Scope.class));
    }

    private static RunIdentity run(String tenant,String session,String run,String agent,long user) {
        var t=new TenantId(tenant);var input=new InputId("input-"+run);
        return new RunIdentity(SessionOwner.user(new SessionRef(t,session),new AgentId(agent),new EndUserId(user)), new AgentId((SessionOwner.user(new SessionRef(t,session),new AgentId(agent),new EndUserId(user))).target().id()), input, new RunRef(t,run), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("verification"), new RunRequester.EndUser((SessionOwner.user(new SessionRef(t,session),new AgentId(agent),new EndUserId(user))).requireUser()), java.util.Optional.empty(), new RunOriginRef((SessionOwner.user(new SessionRef(t,session),new AgentId(agent),new EndUserId(user))).sessionRef().tenantId(), "input:" + (input).value()));
    }
}
