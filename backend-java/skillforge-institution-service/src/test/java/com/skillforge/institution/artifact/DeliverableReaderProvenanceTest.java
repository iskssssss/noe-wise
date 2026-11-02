package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 来源投影必须来自已授权交付文件的持久元数据。 */
class DeliverableReaderProvenanceTest {
    final DeliverableMapper mapper=mock(DeliverableMapper.class);
    final ArtifactAccess access=mock(ArtifactAccess.class);
    final ArtifactStorage storage=mock(ArtifactStorage.class);

    @Test void returnsPersistedParentsOfPublishedFile() {
        fixture();
        var parent=new ArtifactProvenance("original","report.txt",17,"digest");
        when(storage.metadata(any())).thenReturn(new ArtifactMetadata("child","session","run","invocation","file","report",20,"digest",null,"ready",List.of(),List.of(parent)));
        var result=new DeliverableReader(mapper,access,storage).list("session",true);
        assertEquals(List.of(parent),result.getFirst().artifactInputs());
        verify(access).authorizeCurrent("1","session");
        verify(access).authorizeArtifactCurrent(eq("1"),any());
    }

    @Test void deniedChildDoesNotExposeItsSources() {
        fixture();
        doThrow(new SecurityException("denied")).when(access).authorizeArtifactCurrent(eq("1"),any());
        assertTrue(new DeliverableReader(mapper,access,storage).list("session",true).isEmpty());
        verify(access).authorizeArtifactCurrent(eq("1"),any());
        verify(storage,never()).metadata(any());
    }

    private void fixture() {
        when(access.tenant()).thenReturn("1");
        when(storage.require("1","child")).thenReturn(new ArtifactRow("child","1","session","run","invocation","file","report",20,"digest","{}","digest",null,null,"ready"));
        when(mapper.readRevisions(1L, "session")).thenReturn(List.of(Map.of("id","revision","current_revision_id","revision","deliverable_id","deliverable","revision_no",2,"run_id","run","kind","document","title","report","fingerprint","digest")));
        when(mapper.readFiles(1L, "session")).thenReturn(List.of(Map.of("revision_id","revision","artifact_id","child","id","file","relative_path","report.txt","download_name","report.txt","size",20,"sha256","digest","path","report.txt","representation_id","representation","primary_preview",true)));
        when(mapper.readResultLinks(1L, "session")).thenReturn(List.of(Map.of("revision_id","revision","run_id","run")));
    }
}
