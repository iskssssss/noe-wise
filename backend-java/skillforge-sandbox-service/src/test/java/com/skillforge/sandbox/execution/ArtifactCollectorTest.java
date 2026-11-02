package com.skillforge.sandbox.execution;

import com.skillforge.execution.ArtifactFile;
import com.skillforge.sandbox.api.SandboxRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactCollectorTest {

    @TempDir
    Path temporary;

    @Test
    void publishesOnlyDeclaredDeliverablesAndTheirWebsiteResources() throws Exception {
        Path output = Files.createDirectories(temporary.resolve("workspaces/task/output"));
        Files.writeString(output.resolve("document.json"), "temporary input");
        Files.writeString(output.resolve("document-summary.json"), "debug summary");
        Files.writeString(output.resolve("document.docx"), "final document");
        Files.writeString(output.resolve("index.html"), "<link href='assets/main.css'>");
        Files.createDirectory(output.resolve("assets"));
        Files.writeString(output.resolve("assets/main.css"), "body{}");
        ArtifactCollector collector = new ArtifactCollector(new TaskWorkspace(TestSandboxProperties.create(temporary)));
        assertEquals(List.of(), collector.collect("task", 256, List.of()));
        assertEquals(List.of("document.docx"), collector.collect("task", 256, List.of("document.docx"))
                .stream().map(ArtifactFile::path).toList());
        assertEquals(List.of("assets/main.css", "index.html"),
                collector.collect("task", 256, List.of("index.html", "assets/"))
                        .stream().map(ArtifactFile::path).toList());
        assertThrows(SandboxRequestException.class, () -> collector.collect("task", 256, List.of("missing.docx")));
        assertThrows(SandboxRequestException.class, () -> collector.collect("task", 256, List.of("../outside")));
        assertThrows(SandboxRequestException.class, () -> collector.collect("task", 256, List.of("./")));
        assertThrows(SandboxRequestException.class, () -> collector.collect("task", 1, List.of("index.html", "assets/")));
    }

    @Test
    void reservedBusinessChannelCannotBeDeclaredOrStreamedAsArtifact() throws Exception {
        Path output=Files.createDirectories(temporary.resolve("workspaces/task/output"));
        Files.writeString(output.resolve("result.json"),"{\"results\":[]}");
        Files.createDirectories(output.resolve("nested"));
        Files.writeString(output.resolve("nested/result.json"),"ordinary downloadable JSON");
        var collector=new ArtifactCollector(new TaskWorkspace(TestSandboxProperties.create(temporary)));
        for(String reserved:List.of("result.json","result.json/","result.json/child.txt")) {
            var rejection=assertThrows(SandboxRequestException.class,()->collector.collect("task",256,List.of(reserved)));
            assertEquals("UNSAFE_ARTIFACT",rejection.code());
        }
        assertEquals(List.of("nested/result.json"),collector.collect("task",256,List.of("nested/"))
                .stream().map(ArtifactFile::path).toList());
        assertThrows(java.io.IOException.class,()->collector.streamZip("task",
                List.of(new ArtifactFile("result.json",14,"a".repeat(64))),new ByteArrayOutputStream()));
    }

    @Test
    void streamsManifestAsZipWithoutWholeFileBuffer() throws Exception {
        Path output = Files.createDirectories(temporary.resolve("workspaces/task/output/nested"));
        Files.writeString(output.resolve("report.txt"), "hello");
        ArtifactCollector collector = new ArtifactCollector(
                new TaskWorkspace(TestSandboxProperties.create(temporary)));

        List<ArtifactFile> files = collector.collect("task", 256, List.of("nested/report.txt"));
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        collector.streamZip("task", files, target);

        assertEquals(1, files.size());
        assertEquals("nested/report.txt", files.getFirst().path());
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(target.toByteArray()))) {
            assertEquals("nested/report.txt", zip.getNextEntry().getName());
            assertEquals("hello", new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsSymbolicLinksWhenPlatformSupportsThem() throws Exception {
        Path output = Files.createDirectories(temporary.resolve("workspaces/task/output"));
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "secret");
        try {
            Files.createSymbolicLink(output.resolve("leak.txt"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            return;
        }
        ArtifactCollector collector = new ArtifactCollector(
                new TaskWorkspace(TestSandboxProperties.create(temporary)));

        SandboxRequestException failure = assertThrows(SandboxRequestException.class,
                () -> collector.collect("task", 256, List.of("leak.txt")));
        assertEquals("UNSAFE_ARTIFACT", failure.code());
        assertTrue(failure.getMessage().contains("链接"));
    }
}
