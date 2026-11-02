package com.skillforge.sandbox.execution;

import com.skillforge.execution.ArtifactFile;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.sandbox.api.SandboxRequestException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 停止后收集普通文件清单，并以固定内存缓冲流式生成 ZIP。 */
@Component
public class ArtifactCollector {

    private final TaskWorkspace workspace;

    public ArtifactCollector(TaskWorkspace workspace) {
        this.workspace = workspace;
    }

    /** 只收集显式声明的最终文件或资源目录，未声明文件不进入清单与下载包。 */
    public List<ArtifactFile> collect(String taskId, int maxFiles, List<String> declared) {
        if (maxFiles < 1) throw new IllegalArgumentException("artifact_limit_invalid");
        if (declared.isEmpty()) return List.of();
        Path output = workspace.output(taskId);
        var selected = new java.util.TreeSet<Path>();
        try {
            for (String name : declared) {
                boolean directory = name.endsWith("/");
                String value = directory ? name.substring(0, name.length() - 1) : name;
                if (value.equals(ExecutionLimits.PUBLIC_RESULT_FILE) || value.startsWith(ExecutionLimits.PUBLIC_RESULT_FILE + "/"))
                    throw new UnsafeArtifactException("result.json 是保留业务结果通道，不能作为交付成果");
                if (value.isBlank() || value.startsWith("/") || value.contains("\\") || value.contains(":")
                        || value.chars().anyMatch(c -> c < 32 || c == 127)
                        || java.util.Arrays.stream(value.split("/", -1))
                            .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals("..")))
                    throw new UnsafeArtifactException("非法交付路径");
                Path target = output.resolve(value).normalize();
                requireNoLinks(output, target);
                if (directory) {
                    if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
                        throw new UnsafeArtifactException("声明的交付目录不存在: " + value);
                    int[] count = {0};
                    Files.walkFileTree(target, new SimpleFileVisitor<>() {
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            if (!attributes.isRegularFile() || Files.isSymbolicLink(file))
                                throw new UnsafeArtifactException("交付目录包含链接或特殊文件");
                            selected.add(file);
                            count[0]++;
                            if (selected.size() > maxFiles) throw new UnsafeArtifactException("交付文件数超过限制");
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    if (count[0] == 0)
                        throw new UnsafeArtifactException("声明的交付目录为空: " + value);
                } else {
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                        throw new UnsafeArtifactException("声明的交付文件不存在或不是普通文件: " + value);
                    selected.add(target);
                }
                if (selected.size() > maxFiles) throw new UnsafeArtifactException("交付文件数超过限制");
            }
            List<ArtifactFile> manifest = new ArrayList<>(selected.size());
            long totalBytes=0;
            for (Path file : selected) {
                var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                totalBytes=Math.addExact(totalBytes,attributes.size());
                if(totalBytes>ExecutionLimits.TRANSFER_BYTES)throw new UnsafeArtifactException("交付文件总量超过传输限制");
                manifest.add(new ArtifactFile(relative(output, file), attributes.size(), sha256(file)));
            }
            return List.copyOf(manifest);
        } catch (UnsafeArtifactException unsafe) {
            throw new SandboxRequestException("UNSAFE_ARTIFACT", unsafe.getMessage());
        } catch (IOException failure) {
            throw new IllegalStateException("无法收集任务交付文件: " + taskId, failure);
        }
    }

    private static void requireNoLinks(Path root, Path target) {
        if (!target.startsWith(root)) throw new UnsafeArtifactException("非法交付路径");
        for (Path current = target; current != null && current.startsWith(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new UnsafeArtifactException("交付路径包含符号链接");
        }
    }

    public void streamZip(String taskId, List<ArtifactFile> manifest, OutputStream target) throws IOException {
        Path output = workspace.output(taskId);
        ZipOutputStream zip = new ZipOutputStream(target);
        byte[] buffer = new byte[64 * 1024];
        for (ArtifactFile expected : manifest) {
            Path file = output.resolve(expected.path()).normalize();
            if (file.startsWith(output.resolve(ExecutionLimits.PUBLIC_RESULT_FILE)))
                throw new IOException("保留业务结果通道不能进入下载包");
            requireNoLinks(output, file);
            if (!file.startsWith(output) || Files.isSymbolicLink(file)) {
                throw new IOException("产物路径在传输前失效: " + expected.path());
            }
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() != expected.size()) {
                throw new IOException("产物内容在传输前发生变化: " + expected.path());
            }
            MessageDigest digest = newDigest();
            zip.putNextEntry(new ZipEntry(expected.path()));
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    zip.write(buffer, 0, read);
                }
            }
            zip.closeEntry();
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
                throw new IOException("产物摘要在传输时发生变化: " + expected.path());
            }
        }
        zip.finish();
        zip.flush();
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            while (input.read(buffer) >= 0) {
                // 摘要在 DigestInputStream 中增量计算。
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    private static String relative(Path root, Path path) {
        String value = root.relativize(path).toString().replace('\\', '/');
        if (value.isBlank() || value.startsWith("/") || value.contains("../") || value.equals("..")) {
            throw new UnsafeArtifactException("非法输出路径");
        }
        return value;
    }

    private static final class UnsafeArtifactException extends RuntimeException {
        private UnsafeArtifactException(String message) {
            super(message);
        }
    }
}
