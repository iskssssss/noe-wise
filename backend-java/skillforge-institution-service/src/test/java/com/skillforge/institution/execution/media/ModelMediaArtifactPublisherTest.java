package com.skillforge.institution.execution.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.model.media.ModelMediaJob;
import com.skillforge.core.model.media.ModelMediaJobService;
import com.skillforge.institution.artifact.ArtifactMetadata;
import com.skillforge.institution.artifact.ArtifactStorage;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.AssetKind;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelMediaArtifactPublisherTest {
    @TempDir Path staging;
    private final ModelMediaJobService jobs = mock(ModelMediaJobService.class);
    private final ArtifactStorage storage = mock(ArtifactStorage.class);
    private final ModelMediaJob.Owner owner = new ModelMediaJob.Owner("1", "9", "session");
    private final byte[] bytes = "actual downloaded media".getBytes(StandardCharsets.UTF_8);

    @Test void publishesTheActualDownloadedBytesAndVerifiedManifestThenReleasesStaging() throws Exception {
        var context = context();
        var input = download(bytes.length);
        var file = new ArtifactMetadata.File("102-image.png", bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var candidate = new ArtifactMetadata("artifact", "session", "run", "invocation", "file", "图片",
                bytes.length, "manifest-digest", Instant.now().plusSeconds(60), "ready", List.of(file), List.of());
        when(storage.publish(same(context), anyList(), any(InputStream.class))).thenAnswer(call -> {
            assertEquals(List.of(file), call.getArgument(1));
            try (var zip = new ZipInputStream(call.getArgument(2, InputStream.class))) {
                assertEquals(file.path(), zip.getNextEntry().getName());
                assertArrayEquals(bytes, zip.readAllBytes());
                assertNull(zip.getNextEntry());
            }
            return candidate;
        });

        assertSame(candidate, publisher().publish(owner, job(), context));

        verify(jobs).download(owner, "101", "102");
        assertTrue(input.closed);
        assertStagingEmpty();
    }

    @Test void aTruncatedDownloadCannotPublishAndStillClosesTheSourceAndDeletesTheZip() throws Exception {
        var input = download(bytes.length + 1);

        var failure = assertThrows(IOException.class, () -> publisher().publish(owner, job(), context()));

        assertEquals("media_asset_size_invalid", failure.getMessage());
        verifyNoInteractions(storage);
        assertTrue(input.closed);
        assertStagingEmpty();
    }

    @Test void publicationFailurePropagatesAndReleasesBothSourceAndStaging() throws Exception {
        var input = download(bytes.length);
        var failure = new IOException("fixture-publication-failed");
        when(storage.publish(any(), anyList(), any(InputStream.class))).thenThrow(failure);

        assertSame(failure, assertThrows(IOException.class, () -> publisher().publish(owner, job(), context())));

        assertTrue(input.closed);
        assertStagingEmpty();
    }

    private ModelMediaArtifactPublisher publisher() {
        return new ModelMediaArtifactPublisher(jobs, storage, staging.toString());
    }

    private CloseTrackingInput download(long size) throws IOException {
        var input = new CloseTrackingInput(bytes);
        when(jobs.download(owner, "101", "102")).thenReturn(
                new ModelMediaJobService.Download("image/png", "image.png", size, input));
        return input;
    }

    private static PlatformToolExecutionContext context() {
        var context = mock(PlatformToolExecutionContext.class);
        when(context.deadlineAt()).thenReturn(Instant.now().plusSeconds(60));
        when(context.cancellationSignal()).thenReturn(() -> false);
        return context;
    }

    private ModelMediaJob job() {
        return new ModelMediaJob("101", "operation", "7001", "qwen-image", 3, "IMAGE_GENERATION", Status.SUCCEEDED,
                false, null, null, Instant.now(), Instant.now(), null,
                List.of(new ModelMediaJob.Asset("102", AssetKind.IMAGE, "image/png", (long) bytes.length, "image.png", true)));
    }

    private void assertStagingEmpty() throws IOException {
        try (var remaining = Files.list(staging)) { assertEquals(0, remaining.count()); }
    }

    private static final class CloseTrackingInput extends ByteArrayInputStream {
        private boolean closed;
        private CloseTrackingInput(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
