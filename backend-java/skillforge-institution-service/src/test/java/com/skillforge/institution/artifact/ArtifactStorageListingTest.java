package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 仓储目录批量读取与单项即时查询分离；不替代真实PG谓词和查询计数验收。 */
class ArtifactStorageListingTest {
    @TempDir Path root;
    private final ObjectMapper json = spy(new ObjectMapper().findAndRegisterModules());
    private final ArtifactMapper mapper = mock(ArtifactMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private static final String POLICY = ArtifactPublicInspection.POLICY_VERSION;

    private ArtifactStorage storage() {
        when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        return new ArtifactStorage(mapper, json, transactions, root.toString(), mock(RunSettingsService.class),
                mock(StudioArtifactProjection.class), mock(ArtifactPublicInspection.class));
    }

    @Test void sessionListReadsExactBatchOnceAndKeepsManifestInputsAndProjectedStatus() throws Exception {
        var source = new ArtifactProvenance(new UUID(0, 100).toString(), "input.txt", 2, "a".repeat(64));
        var rows = new ArrayList<ArtifactRow>();
        for (int index = 0; index < 20; index++) rows.add(row(index, "session", index == 2 ? "expired" : "ready",
                index == 0 ? List.of(source) : List.of()));
        var ids = rows.stream().map(ArtifactRow::id).toList();
        when(mapper.sessionInputs("7", "session")).thenReturn(List.of(new ArtifactInputRow(
                rows.getFirst().id(), 0, source.artifactId(), source.path(), source.size(), source.sha256())));
        when(mapper.list("7", "session", POLICY)).thenReturn(rows);
        when(mapper.readableArtifactIds("7", ids, POLICY)).thenReturn(List.of(rows.get(0).id(), rows.get(2).id()));
        var storage = storage(); clearInvocations(json);
        var result = storage.list("7", "session");
        assertEquals(ids, result.stream().map(ArtifactMetadata::id).toList());
        assertEquals("ready", result.get(0).status(), "引用保留可读文件时不能只按artifact过期时间降级");
        assertEquals("expired", result.get(1).status());
        assertEquals("expired", result.get(2).status(), "可读仍须保留原row.status");
        assertEquals(List.of(source), result.getFirst().artifactInputs());
        assertEquals(List.of(new ArtifactMetadata.File("file.txt", 2, "b".repeat(64))), result.getFirst().files());
        verify(mapper).sessionInputs("7", "session"); verify(mapper).list("7", "session", POLICY);
        verify(mapper).readableArtifactIds("7", ids, POLICY);
        verify(mapper, never()).anyFileReadable(any(), any(), any());
        verify(json, times(20)).readValue(anyString(), any(TypeReference.class));
        verify(transactions).getTransaction(any());
    }

    @Test void multiSessionListDeduplicatesSessionsAndReadsOneExactArtifactBatch() throws Exception {
        var first = row(1, "session-a", "ready", List.of());
        var second = row(2, "session-b", "ready", List.of());
        var sessions = List.of("session-b", "session-a");
        when(mapper.sessionInputsForSessions("7", sessions)).thenReturn(List.of());
        when(mapper.listSessions("7", sessions, POLICY)).thenReturn(List.of(first, second));
        when(mapper.readableArtifactIds("7", List.of(first.id(), second.id()), POLICY)).thenReturn(List.of(second.id()));
        var result = storage().listSessions("7", List.of("session-b", "session-a", "session-b"));
        assertEquals(List.of("expired", "ready"), result.stream().map(ArtifactMetadata::status).toList());
        assertEquals(List.of("session-a", "session-b"), result.stream().map(ArtifactMetadata::sessionId).toList());
        verify(mapper).sessionInputsForSessions("7", sessions); verify(mapper).listSessions("7", sessions, POLICY);
        verify(mapper).readableArtifactIds("7", List.of(first.id(), second.id()), POLICY);
        verify(mapper, never()).anyFileReadable(any(), any(), any());
    }

    @Test void emptyRowsReadOnlyRowsForBothListEntrypoints() {
        when(mapper.list("7", "empty", POLICY)).thenReturn(List.of());
        when(mapper.listSessions("7", List.of("empty"), POLICY)).thenReturn(List.of());
        var storage = storage();
        assertTrue(storage.list("7", "empty").isEmpty());
        assertTrue(storage.listSessions("7", List.of("empty")).isEmpty());
        verify(mapper).list("7", "empty", POLICY);
        verify(mapper).listSessions("7", List.of("empty"), POLICY);
        verify(mapper, never()).sessionInputs(any(), any());
        verify(mapper, never()).sessionInputsForSessions(any(), any());
        verify(mapper, never()).readableArtifactIds(any(), any(), any());
        verify(mapper, never()).anyFileReadable(any(), any(), any());
    }

    @Test void ordinaryMetadataStillChecksCurrentReadabilityOnEveryCall() throws Exception {
        var row = row(1, "session", "ready", List.of());
        when(mapper.inputs("7", row.id())).thenReturn(List.of());
        when(mapper.anyFileReadable("7", row.id(), POLICY)).thenReturn(true, false);
        var storage = storage();
        assertEquals("ready", storage.metadata(row).status());
        assertEquals("expired", storage.metadata(row).status());
        verify(mapper, times(2)).inputs("7", row.id());
        verify(mapper, times(2)).anyFileReadable("7", row.id(), POLICY);
        verify(mapper, never()).readableArtifactIds(any(), any(), any());
    }

    @Test void multiSessionListStillRejectsEmptyOrBlankScopeBeforeReadingRows() {
        var storage = storage();
        assertThrows(IllegalArgumentException.class, () -> storage.listSessions("7", List.of()));
        assertThrows(IllegalArgumentException.class, () -> storage.listSessions("7", List.of("session", " ")));
        verifyNoInteractions(mapper);
    }

    @Test void corruptInputDigestStillRejectsListedMetadata() throws Exception {
        var valid = row(1, "session", "ready", List.of());
        var corrupt = new ArtifactRow(valid.id(), valid.tenantId(), valid.sessionId(), valid.runId(), valid.invocationId(),
                valid.type(), valid.name(), valid.size(), valid.sha256(), valid.manifest(), "0".repeat(64),
                valid.createdAt(), valid.expiresAt(), valid.status());
        when(mapper.list("7", "session", POLICY)).thenReturn(List.of(corrupt));
        assertEquals("artifact_inputs_digest_mismatch", assertThrows(IllegalStateException.class,
                () -> storage().list("7", "session")).getMessage());
    }

    @Test void corruptManifestDigestStillRejectsMultiSessionMetadata() throws Exception {
        var valid = row(1, "session", "ready", List.of());
        var corrupt = new ArtifactRow(valid.id(), valid.tenantId(), valid.sessionId(), valid.runId(), valid.invocationId(),
                valid.type(), valid.name(), valid.size(), "0".repeat(64), valid.manifest(), valid.inputsSha256(),
                valid.createdAt(), valid.expiresAt(), valid.status());
        when(mapper.listSessions("7", List.of("session"), POLICY)).thenReturn(List.of(corrupt));
        assertEquals("artifact_manifest_digest_mismatch", assertThrows(IllegalStateException.class,
                () -> storage().listSessions("7", List.of("session"))).getMessage());
    }

    @Test void malformedManifestStillUsesExistingCorruptionError() throws Exception {
        var valid = row(1, "session", "ready", List.of());
        var corrupt = new ArtifactRow(valid.id(), valid.tenantId(), valid.sessionId(), valid.runId(), valid.invocationId(),
                valid.type(), valid.name(), valid.size(), valid.sha256(), "not-json", valid.inputsSha256(),
                valid.createdAt(), valid.expiresAt(), valid.status());
        when(mapper.list("7", "session", POLICY)).thenReturn(List.of(corrupt));
        assertEquals("artifact_manifest_corrupt", assertThrows(IllegalStateException.class,
                () -> storage().list("7", "session")).getMessage());
    }

    @Test void toolPageReadsAndDecodesOnlySelectedRowsAndTheirInputs() throws Exception {
        var source=new ArtifactProvenance("12345678-1234-1234-1234-123456789abc","source.txt",3,"c".repeat(64));
        var selected=row(4,"session","ready",List.of(source));
        when(mapper.countToolAvailable("7","session",POLICY)).thenReturn(20L);
        when(mapper.listToolPage("7","session",POLICY,0,1)).thenReturn(List.of(selected));
        when(mapper.inputsForArtifacts("7",List.of(selected.id()))).thenReturn(List.of(new ArtifactInputRow(selected.id(),0,source.artifactId(),source.path(),source.size(),source.sha256())));
        var storage=storage();clearInvocations(json);
        var page=storage.listForTool("7","session",0,1);
        assertEquals(20L,page.total());assertEquals(1,page.artifacts().size());assertEquals(List.of(source),page.artifacts().getFirst().artifactInputs());
        verify(json).readValue(anyString(),any(TypeReference.class));
        verify(mapper).inputsForArtifacts("7",List.of(selected.id()));
        verify(mapper,never()).list(any(),any(),any());verify(mapper,never()).sessionInputs(any(),any());
        verify(mapper,never()).readableArtifactIds(any(),any(),any());verify(mapper,never()).anyFileReadable(any(),any(),any());
    }
    @Test void emptyAndPastEndToolPagesReadOnlyTheCount() {
        var storage=storage();
        assertTrue(storage.listForTool("7","empty",0,1).artifacts().isEmpty());
        when(mapper.countToolAvailable("7","session",POLICY)).thenReturn(20L);
        var page=storage.listForTool("7","session",20,1);assertEquals(20L,page.total());assertTrue(page.artifacts().isEmpty());
        verify(mapper,never()).listToolPage(any(),any(),any(),anyInt(),anyInt());verify(mapper,never()).inputsForArtifacts(any(),any());
    }
    @Test void toolPageStillRejectsSelectedMaterialCorruption() throws Exception {
        var valid=row(1,"session","ready",List.of());
        var bad=new ArtifactRow(valid.id(),valid.tenantId(),valid.sessionId(),valid.runId(),valid.invocationId(),valid.type(),valid.name(),valid.size(),"0".repeat(64),valid.manifest(),valid.inputsSha256(),valid.createdAt(),valid.expiresAt(),valid.status());
        when(mapper.countToolAvailable("7","session",POLICY)).thenReturn(2L);
        when(mapper.listToolPage("7","session",POLICY,1,1)).thenReturn(List.of(bad));
        when(mapper.inputsForArtifacts("7",List.of(valid.id()))).thenReturn(List.of());
        assertEquals("artifact_manifest_digest_mismatch",assertThrows(IllegalStateException.class,()->storage().listForTool("7","session",1,1)).getMessage());
    }

    private ArtifactRow row(int index, String session, String status, List<ArtifactProvenance> inputs) throws Exception {
        var files = List.of(new ArtifactMetadata.File("file.txt", 2, "b".repeat(64)));
        String manifest = json.writeValueAsString(files);
        String inputsJson = json.writeValueAsString(inputs);
        return new ArtifactRow(new UUID(0, index + 1L).toString(), "7", session, "run", "invocation-" + index,
                "file", "file.txt", 2, digest(manifest), manifest, digest(inputsJson), Instant.EPOCH, Instant.EPOCH, status);
    }
    private static String digest(String value) {
        return ArtifactStorage.hex(ArtifactStorage.hash().digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
