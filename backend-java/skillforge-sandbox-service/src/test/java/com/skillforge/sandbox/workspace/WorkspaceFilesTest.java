package com.skillforge.sandbox.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.WorkspaceCommand;
import com.skillforge.sandbox.api.SandboxConflictException;
import com.skillforge.sandbox.api.SandboxRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用真实临时文件覆盖工具语义和路径隔离，不依赖容器或云端。 */
class WorkspaceFilesTest {
    @TempDir Path root;
    private final WorkspaceFiles files = new WorkspaceFiles(new ObjectMapper());

    /** 创建、读取、精确修改、移动和删除组成一条真实文件链路。 */
    @Test
    void performsFileLifecycleWithExplicitBaseline() throws IOException {
        files.execute(root, command("mkdir", "demo", null, null, null, null, null, null));
        Map<String, Object> written = files.execute(root, command("write", "demo/a.txt", null, "你好\nTODO\n", null, null, null, null));
        String sha256 = (String) written.get("sha256");
        assertThat(files.execute(root, command("read", "demo/a.txt", null, null, null, null, 0, 3)))
                .containsEntry("content", "你好\n").containsEntry("truncated", true);
        Map<String, Object> patched = files.execute(root, command("patch", "demo/a.txt", null,
                "{\"old_text\":\"TODO\",\"new_text\":\"完成\"}", null, sha256, null, null));
        assertThat(Files.readString(root.resolve("demo/a.txt"))).isEqualTo("你好\n完成\n");
        files.execute(root, command("move", "demo/a.txt", "demo/b.txt", null, null, (String) patched.get("sha256"), null, null));
        assertThat(Files.exists(root.resolve("demo/a.txt"))).isFalse();
        files.execute(root, command("delete", "demo/b.txt", null, null, 1L, null, null, null));
        files.execute(root, command("delete", "demo", null, null, 1L, null, null, null));
        try (var entries = Files.list(root)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    /** 基准缺失、过期或重复替换都不能修改原文件。 */
    @Test
    void rejectsStaleMissingAndAmbiguousBaselineWithoutChanges() throws IOException {
        Files.writeString(root.resolve("a.txt"), "aaa");
        assertThatThrownBy(() -> files.execute(root, command("write", "a.txt", null, "new", null, null, null, null)))
                .isInstanceOf(SandboxConflictException.class).hasMessageContaining("基准");
        assertThatThrownBy(() -> files.execute(root, command("delete", "a.txt", null, null, null, "0".repeat(64), null, null)))
                .isInstanceOf(SandboxConflictException.class).hasMessageContaining("摘要");
        assertThatThrownBy(() -> files.execute(root, command("patch", "a.txt", null,
                "{\"old_text\":\"aa\",\"new_text\":\"x\"}", 1L, null, null, null)))
                .isInstanceOf(SandboxConflictException.class).hasMessageContaining("不止一次");
        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("aaa");
        try (var entries = Files.list(root)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList()).containsExactly("a.txt");
        }
    }

    /** 列表分页、根只读、目录非递归删除与无效 UTF-8 都有明确拒绝。 */
    @Test
    void boundsPagesAndRejectsUnsafeOperations() throws IOException {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.write(root.resolve("b.bin"), new byte[]{(byte) 0xc3, 0x28});
        Map<String, Object> page = files.execute(root, command("list", ".", null, null, null, null, 0, 1));
        assertThat((List<?>) page.get("entries")).hasSize(1);
        assertThat(page).containsEntry("truncated", true).containsEntry("next_offset", 1);
        assertThatThrownBy(() -> files.execute(root, command("read", "b.bin", null, null, null, null, null, null)))
                .isInstanceOf(SandboxRequestException.class).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> files.execute(root, command("delete", ".", null, null, 1L, null, null, null)))
                .isInstanceOf(SandboxRequestException.class).hasMessageContaining("根目录");
        assertThatThrownBy(() -> files.execute(root, command("read", "../a.txt", null, null, null, null, null, null)))
                .isInstanceOf(SandboxRequestException.class).hasMessageContaining("父目录");
        assertThatThrownBy(() -> files.execute(root, command("read", "a.txt", null, null, null, null, 0, 32769)))
                .isInstanceOf(SandboxRequestException.class).hasMessageContaining("分页");
        Files.createDirectory(root.resolve("nonempty"));
        Files.writeString(root.resolve("nonempty/file.txt"), "keep");
        assertThatThrownBy(() -> files.execute(root, command("delete", "nonempty", null, null, 1L, null, null, null)))
                .isInstanceOf(SandboxRequestException.class);
        assertThat(Files.readString(root.resolve("nonempty/file.txt"))).isEqualTo("keep");
    }

    /** 命中可跨读取缓冲边界；编码失败的原子写入必须保留原文件。 */
    @Test
    void patchesAcrossStreamBoundaryAndKeepsFileOnEncodingFailure() throws IOException {
        String prefix = "x".repeat(4095);
        Files.writeString(root.resolve("a.txt"), prefix + "待处理\n结束");
        files.execute(root, command("patch", "a.txt", null,
                "{\"old_text\":\"待处理\",\"new_text\":\"已完成\"}", 1L, null, null, null));
        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo(prefix + "已完成\n结束");
        assertThatThrownBy(() -> files.execute(root, command("write", "a.txt", null, "\ud800", 1L, null, null, null)))
                .isInstanceOf(SandboxRequestException.class).hasMessageContaining("UTF-8");
        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo(prefix + "已完成\n结束");
        try (var entries = Files.list(root)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList()).containsExactly("a.txt");
        }
    }

    /** 符号链接测试仅在当前宿主允许创建链接时执行，拒绝祖先链接。 */
    @Test
    void rejectsSymbolicLinkAncestors() throws IOException {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (IOException | UnsupportedOperationException denied) {
            assumeTrue(false, "当前宿主不允许创建符号链接");
        }
        assertThatThrownBy(() -> files.execute(root, command("read", "link/secret.txt", null, null, null, null, null, null)))
                .isInstanceOf(SandboxRequestException.class).hasMessageContaining("普通文件");
        assertThat(Files.readString(outside.resolve("secret.txt"))).isEqualTo("secret");
    }

    /** 固定合法期限，避免测试依赖后台服务时间与身份。 */
    private WorkspaceCommand command(String operation, String path, String targetPath, String content,
                                     Long revision, String sha256, Integer offset, Integer limit) {
        return new WorkspaceCommand("test-operation", null, operation, path, targetPath, content,
                revision, sha256, offset, limit, null, Instant.now().plusSeconds(60));
    }
}
