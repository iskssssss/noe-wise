package com.skillforge.institution.execution.media;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.model.media.ModelMediaJob;
import com.skillforge.core.model.media.ModelMediaJobService;
import com.skillforge.institution.artifact.ArtifactMetadata;
import com.skillforge.institution.artifact.ArtifactPublicInspection;
import com.skillforge.institution.artifact.ArtifactStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 将授权可读的媒体资产收集成真实Run候选，临时ZIP只位于显式配置的staging目录。 */
public final class ModelMediaArtifactPublisher {
    private final ModelMediaJobService jobs;
    private final ArtifactStorage storage;
    private final Path staging;

    public ModelMediaArtifactPublisher(ModelMediaJobService jobs, ArtifactStorage storage, String staging) {
        this.jobs = jobs; this.storage = storage; this.staging = Path.of(staging).toAbsolutePath().normalize();
    }

    public ArtifactMetadata publish(ModelMediaJob.Owner owner, ModelMediaJob job,
            PlatformToolExecutionContext context) throws IOException {
        List<ModelMediaJob.Asset> available = job.assets().stream().filter(ModelMediaJob.Asset::available).toList();
        if (available.isEmpty()) return null;
        checkControl(context);
        Files.createDirectories(staging);
        Path archive = Files.createTempFile(staging, "media-", ".zip");
        var manifest = new ArrayList<ArtifactMetadata.File>();
        long total = 0;
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                for (var asset : available) {
                    checkControl(context);
                    var download = jobs.download(owner, job.id(), asset.id());
                    try (var input = download.content()) {
                        String path = ArtifactStorage.safePath(asset.id() + "-" + download.name());
                        if (path.contains("/")) throw new IOException("media_asset_name_invalid");
                        MessageDigest digest = sha256();
                        long size = 0;
                        zip.putNextEntry(new ZipEntry(path));
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            checkControl(context);
                            size = Math.addExact(size, read); total = Math.addExact(total, read);
                            if (size > download.sizeBytes() || total > ArtifactPublicInspection.MAX_BATCH_BYTES)
                                throw new IOException("media_asset_size_invalid");
                            zip.write(buffer, 0, read); digest.update(buffer, 0, read);
                        }
                        zip.closeEntry();
                        if (size != download.sizeBytes()) throw new IOException("media_asset_size_invalid");
                        manifest.add(new ArtifactMetadata.File(path, size, HexFormat.of().formatHex(digest.digest())));
                    }
                }
            }
            checkControl(context);
            try (var input = Files.newInputStream(archive)) {
                return storage.publish(context, manifest, input);
            }
        } finally { Files.deleteIfExists(archive); }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void checkControl(PlatformToolExecutionContext context) throws IOException {
        if (Thread.currentThread().isInterrupted() || context.cancellationSignal().isCancellationRequested()
                || !Instant.now().isBefore(context.deadlineAt())) throw new IOException("media_collection_interrupted");
    }
}
