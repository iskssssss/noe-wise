package com.skillforge.sandbox.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.sandbox.api.SandboxRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 ZIP 验证完整性、版本无关内容摘要和隔离恢复。 */
class WorkspaceArchiveTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final WorkspaceArchive archive = new WorkspaceArchive(json);

    /** 含同名清单文件、Unicode、二进制和空目录的工作区能够完整恢复。 */
    @Test
    void roundTripsContentAndEmptyDirectories() throws IOException {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Files.createDirectories(project.resolve("nested/empty"));
        Files.writeString(project.resolve("manifest.json"), "用户文件");
        Files.write(project.resolve("nested/data.bin"), new byte[]{0, (byte) 0xff, 7});
        Path zip = temporary.resolve("snapshot.zip");
        WorkspaceArchive.Manifest frozen = archive.freeze(project, zip, 1);
        assertThat(frozen.files()).hasSize(2);
        assertThat(frozen.size()).isEqualTo(Files.size(zip));
        Path staging = temporary.resolve("staging");
        WorkspaceArchive.Manifest restored = archive.restore(zip, staging);
        assertThat(restored).isEqualTo(frozen);
        assertThat(Files.readString(staging.resolve("manifest.json"))).isEqualTo("用户文件");
        assertThat(Files.readAllBytes(staging.resolve("nested/data.bin"))).containsExactly(new byte[]{0, (byte) 0xff, 7});
        assertThat(Files.isDirectory(staging.resolve("nested/empty"))).isTrue();
        WorkspaceArchive.Manifest next = archive.freeze(project, temporary.resolve("next.zip"), 2);
        assertThat(next.contentDigest()).isEqualTo(frozen.contentDigest());
        assertThat(next.sha256()).isNotEqualTo(frozen.sha256());
    }

    /** 空项目是合法快照，不创建虚假文件。 */
    @Test
    void roundTripsEmptyWorkspace() throws IOException {
        Path project = Files.createDirectory(temporary.resolve("empty"));
        WorkspaceArchive.Manifest frozen = archive.freeze(project, temporary.resolve("empty.zip"), 0);
        Path staging = temporary.resolve("restored");
        WorkspaceArchive.Manifest restored = archive.restore(temporary.resolve("empty.zip"), staging);
        assertThat(restored.files()).isEmpty();
        assertThat(restored.contentDigest()).isEqualTo(frozen.contentDigest());
        try (var entries = Files.list(staging)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    /** 改写条目字节并保持 ZIP 本身合法，必须由清单摘要识别损坏。 */
    @Test
    void rejectsContentCorruptionBeforePublishingStaging() throws IOException {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Files.writeString(project.resolve("a.txt"), "good");
        archive.freeze(project, temporary.resolve("good.zip"), 1);
        Path corrupt = temporary.resolve("corrupt.zip");
        try (var input = new ZipInputStream(Files.newInputStream(temporary.resolve("good.zip")));
             var output = new ZipOutputStream(Files.newOutputStream(corrupt))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                output.write(entry.getName().equals("files/a.txt") ? "evil".getBytes(StandardCharsets.UTF_8) : input.readAllBytes());
                output.closeEntry();
            }
        }
        assertThatThrownBy(() -> archive.restore(corrupt, temporary.resolve("staging")))
                .isInstanceOf(SandboxRequestException.class).hasMessageContaining("摘要");
        assertThat(Files.readString(project.resolve("a.txt"))).isEqualTo("good");
    }

    /** 不可信清单不能通过 ../、重复路径或缺失父目录写出恢复目录。 */
    @Test
    void rejectsEscapingManifestPaths() throws IOException {
        Path zip = temporary.resolve("unsafe.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("manifest.json"));
            output.write(json.writeValueAsBytes(Map.of("format", 1, "revision", 1, "contentDigest", "0".repeat(64),
                    "directories", List.of(), "files", List.of(Map.of("path", "../escape.txt", "size", 0, "sha256", "0".repeat(64))))));
            output.closeEntry();
        }
        assertThatThrownBy(() -> archive.restore(zip, temporary.resolve("staging")))
                .isInstanceOf(SandboxRequestException.class).hasMessageContaining("父目录");
        assertThat(Files.exists(temporary.resolve("escape.txt"))).isFalse();
    }

    /** 空文件洪泛仍受清单条目上限约束，失败不留下半快照。 */
    @Test
    void rejectsExcessiveEntries() throws IOException {
        Path project = Files.createDirectory(temporary.resolve("many"));
        for (int index = 0; index <= ExecutionLimits.MAX_FILES; index++) {
            Files.createFile(project.resolve("file-" + index));
        }
        Path zip = temporary.resolve("many.zip");
        assertThatThrownBy(() -> archive.freeze(project, zip, 1))
                .isInstanceOf(SandboxRequestException.class).hasMessageContaining("条目");
        assertThat(Files.exists(zip)).isFalse();
    }

    /** 解包已进入文件流时推进调用时钟到期限；不得返回可发布的完整暂存结果。 */
    @Test
    void refusesToPublishRestoreWhenCallerDeadlineElapsesDuringExtraction() throws IOException {
        Path project = Files.createDirectory(temporary.resolve("project"));
        byte[] content = new byte[ExecutionLimits.STREAM_BUFFER_BYTES * 2];
        Files.write(project.resolve("data.bin"), content);
        Path zip = temporary.resolve("snapshot.zip");
        archive.freeze(project, zip, 1);
        Path staging = temporary.resolve("staging");
        Instant deadline = Instant.parse("2030-01-01T00:00:01Z");
        AtomicReference<Instant> now = new AtomicReference<>(deadline.minusSeconds(1));
        assertThatThrownBy(() -> archive.restore(zip, staging, () -> {
            if (Files.exists(staging.resolve("data.bin"))) now.set(deadline);
            return !now.get().isBefore(deadline);
        })).isInstanceOf(SandboxRequestException.class).hasMessageContaining("截止时间");
        assertThat(now.get()).isEqualTo(deadline);
        assertThat(Files.size(staging.resolve("data.bin"))).isLessThan(content.length);
        assertThat(Files.readAllBytes(project.resolve("data.bin"))).isEqualTo(content);
    }
}
