package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 子成果保持原版本与文件身份，只有精确且仍授权的父会话可展示及使用票据。 */
class DeliverableDelegationAccessTest {
    private static final String CHILD="delegation:child";
    private final DeliverableMapper mapper=mock(DeliverableMapper.class);
    private final ArtifactAccess access=mock(ArtifactAccess.class);
    private final ArtifactStorage storage=mock(ArtifactStorage.class);
    private final DeliverableReader reader=new DeliverableReader(mapper,access,storage);
    private final ArtifactRow artifact=new ArtifactRow("artifact","1",CHILD,"child-run","invocation","file",
            "report",20,"digest","{}","digest",null,null,"ready");

    @AfterEach void clear(){TenantContext.clear();ChannelContext.clear();}

    @Test void parentListPreservesChildVersionAndAddsOnlyItsExactParentRun() {
        fixture();
        var result=reader.list("parent",true);
        assertEquals(1,result.size());var item=result.getFirst();
        assertEquals("parent",item.sessionId());assertEquals("child-run",item.runId());
        assertEquals("revision",item.id());assertEquals(2,item.revision());
        assertEquals(List.of("child-run","parent-run"),item.resultRunIds());
        assertEquals("artifact",item.files().getFirst().artifactId());
        verify(access).authorizeCurrent("1","parent");
        verify(access).authorizeArtifactCurrent("1",artifact);
        verify(mapper,never()).readRevisions(1L,"other-session");
        assertEquals(item,reader.revisionCurrent("revision"));
    }

    @Test void foreignMissingOrRevokedRouteCannotExposeChildRevision() {
        fixture();
        when(access.deliverableRoute("1",CHILD,"child-run")).thenReturn(Optional.of(
                new StudioArtifactProjection.Route("other-session","AGENT","7","8","other-run")));
        assertTrue(reader.list("parent",true).isEmpty());
        when(access.deliverableRoute("1",CHILD,"child-run")).thenReturn(Optional.empty());
        assertTrue(reader.list("parent",true).isEmpty());
        assertThrows(RuntimeException.class,()->reader.revisionCurrent("revision"));
        when(access.deliverableRoute("1",CHILD,"child-run")).thenThrow(new SecurityException("source_revoked"));
        assertTrue(reader.list("parent",true).isEmpty());
        assertThrows(SecurityException.class,()->reader.file("1","revision","file"));
    }

    @Test void ticketAndHtmlResourceRecheckParentRequesterAndOriginalArtifact() {
        fixture();var tickets=tickets();
        var url=tickets.ticket("revision","file",true).get("url");
        String token=url.substring(url.indexOf("/d/")+3,url.lastIndexOf('/'));
        var grant=tickets.verify(token,"index.html","127.0.0.1");
        assertEquals("parent",grant.file().sessionId());assertEquals(artifact,grant.file().artifact());
        verify(access).authorizeRequester("1","parent","member",null);
        verify(access).authorizeArtifactRequester("1",artifact,"member",null);
        verify(access,never()).authorizeCurrent("1",CHILD);
        verify(access,never()).authorizeRequester("1",CHILD,"member",null);
        doThrow(new SecurityException("file_revoked")).when(access).authorizeArtifactRequester("1",artifact,"member",null);
        assertThrows(RuntimeException.class,()->tickets.verify(token,"index.html","127.0.0.1"));
        assertNull(TenantContext.getTenantId());
    }

    @Test void oldTicketCannotBypassRemovedDelegationRoute() {
        fixture();var tickets=tickets();
        String url=tickets.ticket("revision","file",false).get("url");
        String token=url.substring(url.indexOf("ticket=")+7);
        when(access.deliverableRoute("1",CHILD,"child-run")).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class,()->tickets.verify(token,null,"127.0.0.1"));
        assertNull(TenantContext.getTenantId());
    }

    @Test void channelTicketKeepsItsOriginalRequesterAndRechecksParentAccess() {
        fixture();ChannelContext.setChannelId("9");var tickets=tickets();
        String url=tickets.ticket("revision","file",false).get("url");
        String token=url.substring(url.indexOf("ticket=")+7);
        ChannelContext.clear();
        assertEquals("parent",tickets.verify(token,null,"127.0.0.1").file().sessionId());
        verify(access).authorizeRequester("1","parent",null,"9");
        verify(access).authorizeArtifactRequester("1",artifact,null,"9");
        doThrow(new SecurityException("parent_channel_revoked")).when(access).authorizeRequester("1","parent",null,"9");
        assertThrows(RuntimeException.class,()->tickets.verify(token,null,"127.0.0.1"));
    }

    private DeliverableAccess tickets(){return new DeliverableAccess(access,reader,new ObjectMapper(),
            "0123456789abcdef0123456789abcdef","http://localhost:8080","http://preview.localhost:8091",300,3600);}

    private void fixture() {
        when(access.tenant()).thenReturn("1");when(access.member()).thenReturn("member");
        when(access.deliverableSessions("1","parent")).thenReturn(List.of("parent",CHILD));
        when(access.deliverableRoute("1",CHILD,"child-run")).thenReturn(Optional.of(
                new StudioArtifactProjection.Route("parent","AGENT","7","8","parent-run")));
        when(mapper.revisionSessions(1L,"revision")).thenReturn(List.of(Map.of("session_id",CHILD)));
        when(mapper.readRevisions(1L,CHILD)).thenReturn(List.of(Map.of("id","revision","current_revision_id","revision",
                "deliverable_id","deliverable","revision_no",2,"run_id","child-run","kind","website","title","report","fingerprint","digest")));
        when(mapper.readResultLinks(1L,CHILD)).thenReturn(List.of(Map.of("revision_id","revision","run_id","child-run")));
        var file=Map.<String,Object>ofEntries(Map.entry("revision_id","revision"),Map.entry("artifact_id","artifact"),
                Map.entry("id","file"),Map.entry("relative_path","index.html"),Map.entry("download_name","index.html"),
                Map.entry("size",20),Map.entry("sha256","digest"),Map.entry("path","index.html"),
                Map.entry("representation_id","representation"),Map.entry("primary_preview",true),Map.entry("entry_path","index.html"),
                Map.entry("session_id",CHILD),Map.entry("current_revision_id","revision"),Map.entry("revision_no",2));
        when(mapper.readFiles(1L,CHILD)).thenReturn(List.of(file));
        when(mapper.revisionFile(1L,"revision","file")).thenReturn(List.of(file));
        when(mapper.representationPath(1L,"representation","index.html")).thenReturn(List.of(Map.of("file_id","file")));
        when(storage.require("1","artifact")).thenReturn(artifact);
        when(storage.metadata(artifact)).thenReturn(new ArtifactMetadata("artifact",CHILD,"child-run","invocation",
                "file","report",20,"digest",null,"ready",List.of(),List.of()));
    }
}
