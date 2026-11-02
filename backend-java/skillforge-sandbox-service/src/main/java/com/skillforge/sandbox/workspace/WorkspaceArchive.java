package com.skillforge.sandbox.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.ArtifactFile;
import com.skillforge.execution.ExecutionLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 排他权内冻结项目，后台上传读取冻结 ZIP；恢复只写隔离暂存目录。 */
@Component
public class WorkspaceArchive {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceArchive.class);
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String FILE_PREFIX = "files/";
    private final ObjectMapper json;

    /** 外层元数据用于持久记录、内容合并及对象完整性验证。 */
    public record Manifest(String sha256, long size, String contentDigest, List<ArtifactFile> files) { }

    /** 内置清单绑定文件路径、摘要、目录和修订；不包含宿主路径或对象凭据。 */
    private record ArchiveManifest(int format, long revision, String contentDigest,
                                   List<String> directories, List<ArtifactFile> files) { }

    /** 固定字段顺序用于跨进程稳定摘要，不依赖 Map.of 的迭代顺序。 */
    private record ContentManifest(List<String> directories, List<ArtifactFile> files) { }

    /** JSON 配置来自服务装配，所有正文仅进入冻结包。 */
    public WorkspaceArchive(ObjectMapper json) {
        log.debug("初始化工作区快照入口");
        this.json = json;
        log.debug("完成初始化工作区快照入口");
    }

    /** 后台冻结使用线程中断取消，显式调用应传入调用预算检查。 */
    public Manifest freeze(Path project, Path zip, long revision) {
        log.debug("开始后台工作区快照冻结 revision={}", revision);
        try {
            return freeze(project, zip, revision, () -> false);
        } finally {
            log.debug("结束后台工作区快照冻结 revision={}", revision);
        }
    }

    /** 流式创建新 ZIP；取消检查由本次调用传递，失败时清理本次半成品。 */
    public Manifest freeze(Path project, Path zip, long revision, BooleanSupplier cancelled) {
        log.debug("开始冻结工作区快照 revision={}", revision);
        boolean created = false;
        boolean succeeded = false;
        try {
            interrupted(cancelled);
            Path root = project.toAbsolutePath().normalize();
            WorkspaceFiles.requireDirectory(root);
            Path target = zip.toAbsolutePath().normalize();
            if (target.startsWith(root)) throw WorkspaceFiles.rejected("workspace_snapshot_path_rejected", "快照文件必须位于项目目录之外");
            ArchiveManifest descriptor = describe(root, revision, cancelled);
            MessageDigest digest = WorkspaceFiles.digest();
            try (var output = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(target,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)), digest);
                 var archive = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                created = true;
                entry(archive, MANIFEST_ENTRY);
                archive.write(json.writeValueAsBytes(descriptor));
                archive.closeEntry();
                for (String directory : descriptor.directories()) {
                    interrupted(cancelled);
                    entry(archive, FILE_PREFIX + directory + "/");
                    archive.closeEntry();
                }
                byte[] buffer = new byte[ExecutionLimits.STREAM_BUFFER_BYTES];
                for (ArtifactFile file : descriptor.files()) {
                    interrupted(cancelled);
                    Path source = WorkspaceFiles.resolve(root, file.path(), false);
                    WorkspaceFiles.requireFile(source);
                    entry(archive, FILE_PREFIX + file.path());
                    MessageDigest content = WorkspaceFiles.digest();
                    long size = 0;
                    try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            interrupted(cancelled);
                            size += count;
                            if (size > file.size()) throw WorkspaceFiles.rejected("workspace_snapshot_changed", "冻结期间项目文件发生变化");
                            archive.write(buffer, 0, count);
                            content.update(buffer, 0, count);
                        }
                    }
                    if (size != file.size() || !hex(content).equals(file.sha256())) {
                        throw WorkspaceFiles.rejected("workspace_snapshot_changed", "冻结期间项目文件发生变化");
                    }
                    archive.closeEntry();
                }
            }
            interrupted(cancelled);
            Manifest result = new Manifest(hex(digest), Files.size(target), descriptor.contentDigest(), List.copyOf(descriptor.files()));
            succeeded = true;
            return result;
        } catch (IOException failure) {
            throw WorkspaceFiles.ioFailure(failure);
        } finally {
            if (created && !succeeded) removeFailedArchive(zip);
            log.debug("结束冻结工作区快照 revision={} succeeded={}", revision, succeeded);
        }
    }

    /** 后台恢复使用线程中断取消；用户操作应传入当前调用的期限检查。 */
    public Manifest restore(Path zip, Path staging) {
        log.debug("开始后台工作区快照恢复");
        try {
            return restore(zip, staging, () -> false);
        } finally {
            log.debug("结束后台工作区快照恢复");
        }
    }

    /** 完整验证所有字节并检查当前调用取消；成功返回前不能发布暂存目录。 */
    public Manifest restore(Path zip, Path staging, BooleanSupplier cancelled) {
        log.debug("开始恢复工作区快照");
        boolean succeeded = false;
        try {
            interrupted(cancelled);
            if (!WorkspaceFiles.requireOrdinary(zip).isRegularFile()) {
                throw WorkspaceFiles.rejected("workspace_snapshot_file_required", "快照必须是普通文件");
            }
            long archiveSize = Files.size(zip);
            if (archiveSize > ExecutionLimits.TRANSFER_BYTES + ExecutionLimits.CONTROL_RESPONSE_BYTES) {
                throw WorkspaceFiles.rejected("workspace_snapshot_limit", "压缩快照超过容量限制");
            }
            rejectLinkMetadata(zip, cancelled);
            Path root = staging.toAbsolutePath().normalize();
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                WorkspaceFiles.requireDirectory(root);
                try (var entries = Files.newDirectoryStream(root)) {
                    if (entries.iterator().hasNext()) throw WorkspaceFiles.rejected("workspace_restore_staging_not_empty", "恢复暂存目录必须为空");
                }
            } else Files.createDirectory(root);
            WorkspaceFiles.writable(root, true);
            ArchiveManifest descriptor;
            Set<String> received = new HashSet<>();
            Set<String> receivedDirectories = new HashSet<>();
            try (var archive = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip, LinkOption.NOFOLLOW_LINKS)), StandardCharsets.UTF_8)) {
                ZipEntry first = archive.getNextEntry();
                if (first == null || first.isDirectory() || !first.getName().equals(MANIFEST_ENTRY)) {
                    throw WorkspaceFiles.rejected("workspace_snapshot_manifest_missing", "快照首项必须是内置清单");
                }
                byte[] manifestBytes = archive.readNBytes(ExecutionLimits.CONTROL_RESPONSE_BYTES + 1);
                interrupted(cancelled);
                if (manifestBytes.length > ExecutionLimits.CONTROL_RESPONSE_BYTES) {
                    throw WorkspaceFiles.rejected("workspace_snapshot_manifest_limit", "快照清单超过容量限制");
                }
                descriptor = json.readValue(manifestBytes, ArchiveManifest.class);
                validateManifest(root, descriptor, cancelled);
                archive.closeEntry();
                Map<String, ArtifactFile> expected = new HashMap<>();
                descriptor.files().forEach(file -> expected.put(file.path(), file));
                Set<String> directories = Set.copyOf(descriptor.directories());
                byte[] buffer = new byte[ExecutionLimits.STREAM_BUFFER_BYTES];
                ZipEntry next;
                long total = 0;
                while ((next = archive.getNextEntry()) != null) {
                    interrupted(cancelled);
                    String name = next.getName();
                    if (!name.startsWith(FILE_PREFIX)) throw WorkspaceFiles.rejected("workspace_snapshot_entry_rejected", "快照含未声明条目");
                    String relative = name.substring(FILE_PREFIX.length());
                    if (next.isDirectory()) {
                        relative = relative.substring(0, relative.length() - 1);
                        if (!directories.contains(relative) || !receivedDirectories.add(relative)) {
                            throw WorkspaceFiles.rejected("workspace_snapshot_entry_rejected", "快照目录重复或未声明");
                        }
                        Path directory = WorkspaceFiles.resolve(root, relative, false);
                        Files.createDirectories(directory);
                        WorkspaceFiles.writable(directory, true);
                        if (archive.read() != -1) throw WorkspaceFiles.rejected("workspace_snapshot_entry_rejected", "快照目录不能包含内容");
                    } else {
                        ArtifactFile file = expected.get(relative);
                        if (file == null || !received.add(relative)) throw WorkspaceFiles.rejected("workspace_snapshot_entry_rejected", "快照文件重复或未声明");
                        Path target = WorkspaceFiles.resolve(root, relative, false);
                        Files.createDirectories(target.getParent());
                        MessageDigest content = WorkspaceFiles.digest();
                        long size = 0;
                        // 不解释 ZIP 中的 POSIX 类型或链接属性，所有条目仅生成 CREATE_NEW 普通文件。
                        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS)) {
                            int count;
                            while ((count = archive.read(buffer)) != -1) {
                                interrupted(cancelled);
                                size += count;
                                total += count;
                                if (size > file.size() || total > ExecutionLimits.TRANSFER_BYTES) {
                                    throw WorkspaceFiles.rejected("workspace_snapshot_size_mismatch", "快照内容大小不匹配或超过容量限制");
                                }
                                content.update(buffer, 0, count);
                                output.write(buffer, 0, count);
                            }
                        }
                        if (size != file.size() || !hex(content).equals(file.sha256())) {
                            throw WorkspaceFiles.rejected("workspace_snapshot_integrity_mismatch", "快照文件摘要或大小不匹配");
                        }
                        WorkspaceFiles.writable(target, false);
                    }
                    archive.closeEntry();
                }
                if (!received.equals(expected.keySet()) || !receivedDirectories.equals(directories)) {
                    throw WorkspaceFiles.rejected("workspace_snapshot_incomplete", "快照条目不完整");
                }
                for (String directory : descriptor.directories()) {
                    interrupted(cancelled);
                    WorkspaceFiles.writable(WorkspaceFiles.resolve(root, directory, false), true);
                }
            }
            Manifest result = new Manifest(archiveDigest(zip, cancelled), archiveSize, descriptor.contentDigest(), List.copyOf(descriptor.files()));
            interrupted(cancelled);
            succeeded = true;
            return result;
        } catch (IOException failure) {
            throw WorkspaceFiles.ioFailure(failure);
        } finally {
            // 失败暂存保留由工作区恢复协调器清理，绝不触碰当前可用项目目录。
            log.debug("结束恢复工作区快照 succeeded={}", succeeded);
        }
    }

    /** 不跟随链接遍历，清单大小受文件数和字节数双重约束。 */
    private ArchiveManifest describe(Path root, long revision, BooleanSupplier cancelled) throws IOException {
        log.debug("开始构建工作区冻结清单 revision={}", revision);
        List<String> directories = new ArrayList<>();
        List<ArtifactFile> files = new ArrayList<>();
        long[] total = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /** 根目录不计入清单，所有子目录都验证安全路径。 */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                log.debug("开始收集工作区快照目录");
                interrupted(cancelled);
                WorkspaceFiles.requireDirectory(directory);
                if (!directory.equals(root)) {
                    String relative = WorkspaceFiles.relative(root, directory);
                    WorkspaceFiles.resolve(root, relative, false);
                    directories.add(relative);
                    entryCount(directories.size() + files.size());
                }
                log.debug("结束收集工作区快照目录");
                return FileVisitResult.CONTINUE;
            }

            /** 文件摘要流式计算；链接与特殊文件由 requireFile 拒绝。 */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                log.debug("开始收集工作区快照文件");
                interrupted(cancelled);
                BasicFileAttributes verified = WorkspaceFiles.requireFile(file);
                String relative = WorkspaceFiles.relative(root, file);
                WorkspaceFiles.resolve(root, relative, false);
                total[0] += verified.size();
                if (total[0] > ExecutionLimits.TRANSFER_BYTES) throw WorkspaceFiles.rejected("workspace_snapshot_limit", "工作区快照超过字节容量限制");
                files.add(new ArtifactFile(relative, verified.size(), archiveDigest(file, cancelled)));
                entryCount(directories.size() + files.size());
                log.debug("结束收集工作区快照文件");
                return FileVisitResult.CONTINUE;
            }
        });
        directories.sort(String::compareTo);
        files.sort(Comparator.comparing(ArtifactFile::path));
        String contentDigest = contentDigest(directories, files);
        log.debug("结束构建工作区冻结清单 files={} directories={} bytes={}", files.size(), directories.size(), total[0]);
        return new ArchiveManifest(1, revision, contentDigest, List.copyOf(directories), List.copyOf(files));
    }

    /** 清单严格声明类型、唯一路径、父目录及完整摘要，避免 ZIP 变成任意解包接口。 */
    private void validateManifest(Path root, ArchiveManifest descriptor, BooleanSupplier cancelled) throws IOException {
        log.debug("开始校验工作区快照清单");
        if (descriptor == null || descriptor.format() != 1 || descriptor.revision() < 0
                || descriptor.files() == null || descriptor.directories() == null || descriptor.contentDigest() == null) {
            throw WorkspaceFiles.rejected("workspace_snapshot_manifest_rejected", "快照清单格式无效");
        }
        entryCount(descriptor.files().size() + descriptor.directories().size());
        Set<String> names = new HashSet<>();
        Set<String> directories = new HashSet<>();
        for (String directory : descriptor.directories()) {
            interrupted(cancelled);
            if (directory == null || !names.add(directory)) throw WorkspaceFiles.rejected("workspace_snapshot_path_rejected", "快照路径重复");
            Path target = WorkspaceFiles.resolve(root, directory, false);
            if (!WorkspaceFiles.relative(root, target).equals(directory)) throw WorkspaceFiles.rejected("workspace_snapshot_path_rejected", "快照路径未规范化");
            directories.add(directory);
        }
        long total = 0;
        for (ArtifactFile file : descriptor.files()) {
            interrupted(cancelled);
            if (file == null || file.path() == null || !names.add(file.path()) || file.size() < 0
                    || file.size() > ExecutionLimits.TRANSFER_BYTES || file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}")) {
                throw WorkspaceFiles.rejected("workspace_snapshot_manifest_rejected", "快照文件声明无效");
            }
            Path target = WorkspaceFiles.resolve(root, file.path(), false);
            if (!WorkspaceFiles.relative(root, target).equals(file.path())) throw WorkspaceFiles.rejected("workspace_snapshot_path_rejected", "快照路径未规范化");
            total += file.size();
            if (total > ExecutionLimits.TRANSFER_BYTES) throw WorkspaceFiles.rejected("workspace_snapshot_limit", "快照声明超过字节容量限制");
        }
        for (String name : names) {
            interrupted(cancelled);
            int slash = name.lastIndexOf('/');
            if (slash >= 0 && !directories.contains(name.substring(0, slash))) {
                throw WorkspaceFiles.rejected("workspace_snapshot_manifest_rejected", "快照缺少父目录声明");
            }
        }
        if (!contentDigest(descriptor.directories(), descriptor.files()).equals(descriptor.contentDigest())) {
            throw WorkspaceFiles.rejected("workspace_snapshot_integrity_mismatch", "快照内容清单摘要不匹配");
        }
        log.debug("结束校验工作区快照清单 files={} bytes={}", descriptor.files().size(), total);
    }

    /** 摘要与修订、ZIP 压缩元数据无关，JSON 字段边界避免路径歧义。 */
    private String contentDigest(List<String> directories, List<ArtifactFile> files) throws IOException {
        log.debug("开始计算工作区内容清单摘要");
        MessageDigest digest = WorkspaceFiles.digest();
        List<String> sortedDirectories = directories.stream().sorted().toList();
        List<ArtifactFile> sortedFiles = files.stream().sorted(Comparator.comparing(ArtifactFile::path)).toList();
        digest.update(json.writeValueAsBytes(new ContentManifest(sortedDirectories, sortedFiles)));
        String result = hex(digest);
        log.debug("结束计算工作区内容清单摘要");
        return result;
    }

    /** 空间内节点数量也受限，防止目录洪泛耗尽清单内存。 */
    private void entryCount(int count) {
        log.debug("开始验证工作区快照条目容量 count={}", count);
        if (count > ExecutionLimits.MAX_FILES) throw WorkspaceFiles.rejected("workspace_snapshot_limit", "工作区快照条目超过容量限制");
        log.debug("结束验证工作区快照条目容量");
    }

    /** 固定时间减少无内容变化的压缩元数据差异。 */
    private void entry(ZipOutputStream archive, String name) throws IOException {
        log.debug("开始写入工作区快照条目");
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        archive.putNextEntry(entry);
        log.debug("结束写入工作区快照条目");
    }

    /** 转为协议使用的小写十六进制摘要。 */
    private String hex(MessageDigest digest) {
        log.debug("开始转换工作区快照摘要");
        String result = HexFormat.of().formatHex(digest.digest());
        log.debug("结束转换工作区快照摘要");
        return result;
    }

    /** 每个流式批次同时遵守线程中断和本次调用期限，不依赖跨线程 Future 取消。 */
    private void interrupted(BooleanSupplier cancelled) {
        log.debug("开始验证工作区快照取消状态");
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw WorkspaceFiles.rejected("workspace_cancelled", "工作区快照操作已取消或超过截止时间");
        }
        log.debug("结束验证工作区快照取消状态");
    }

    /** 仅删除本次创建的 ZIP 半成品，不删除原项目或已有备份。 */
    private void removeFailedArchive(Path zip) {
        log.debug("开始清理工作区快照半成品");
        try {
            Files.deleteIfExists(zip);
        } catch (IOException cleanup) {
            log.warn("工作区快照半成品清理失败 type={}", cleanup.getClass().getSimpleName());
        }
        log.debug("结束清理工作区快照半成品");
    }

    /** Java ZIP API 不公开 Unix 类型位，读取中央目录拒绝链接与设备元数据。 */
    private void rejectLinkMetadata(Path zip, BooleanSupplier cancelled) throws IOException {
        log.debug("开始验证工作区快照 ZIP 节点类型");
        try (var input = new RandomAccessFile(zip.toFile(), "r")) {
            long length = input.length();
            int tailSize = (int) Math.min(length, 65_557L);
            byte[] tail = new byte[tailSize];
            input.seek(length - tailSize);
            input.readFully(tail);
            int end = -1;
            for (int index = tailSize - 22; index >= 0; index--) {
                if (unsigned(tail, index, 4) == 0x06054b50L && index + 22 + unsigned(tail, index + 20, 2) == tailSize) {
                    end = index;
                    break;
                }
            }
            if (end < 0 || unsigned(tail, end + 4, 2) != 0 || unsigned(tail, end + 6, 2) != 0) {
                throw WorkspaceFiles.rejected("workspace_snapshot_zip_rejected", "快照 ZIP 中央目录无效");
            }
            int count = (int) unsigned(tail, end + 10, 2);
            if (count > ExecutionLimits.MAX_FILES + 1 || unsigned(tail, end + 8, 2) != count) {
                throw WorkspaceFiles.rejected("workspace_snapshot_limit", "快照 ZIP 条目超过容量限制");
            }
            long position = unsigned(tail, end + 16, 4);
            long centralEnd = position + unsigned(tail, end + 12, 4);
            if (centralEnd != length - tailSize + end) throw WorkspaceFiles.rejected("workspace_snapshot_zip_rejected", "快照 ZIP 中央目录边界无效");
            byte[] header = new byte[46];
            for (int index = 0; index < count; index++) {
                interrupted(cancelled);
                if (position + header.length > centralEnd) throw WorkspaceFiles.rejected("workspace_snapshot_zip_rejected", "快照 ZIP 条目不完整");
                input.seek(position);
                input.readFully(header);
                if (unsigned(header, 0, 4) != 0x02014b50L) throw WorkspaceFiles.rejected("workspace_snapshot_zip_rejected", "快照 ZIP 条目格式无效");
                int type = (int) (unsigned(header, 38, 4) >>> 16) & 0170000;
                if (type != 0 && type != 0100000 && type != 0040000) {
                    throw WorkspaceFiles.rejected("workspace_snapshot_node_rejected", "快照不能包含链接或特殊节点");
                }
                position += 46 + unsigned(header, 28, 2) + unsigned(header, 30, 2) + unsigned(header, 32, 2);
            }
            if (position != centralEnd) throw WorkspaceFiles.rejected("workspace_snapshot_zip_rejected", "快照 ZIP 中央目录不完整");
        }
        log.debug("结束验证工作区快照 ZIP 节点类型");
    }

    /** ZIP 协议的少量定长小端字段，不读取无界压缩内容。 */
    private long unsigned(byte[] bytes, int offset, int length) {
        log.debug("开始读取 ZIP 定长字段");
        long value = 0;
        for (int index = 0; index < length; index++) value |= (long) (bytes[offset + index] & 0xff) << (index * 8);
        log.debug("结束读取 ZIP 定长字段");
        return value;
    }

    /** ZIP 字节允许少量清单开销，仍以固定缓冲计算完整对象摘要。 */
    private String archiveDigest(Path zip, BooleanSupplier cancelled) throws IOException {
        log.debug("开始计算工作区快照对象摘要");
        MessageDigest digest = WorkspaceFiles.digest();
        try (var input = Files.newInputStream(zip, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[ExecutionLimits.STREAM_BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) != -1) {
                interrupted(cancelled);
                digest.update(buffer, 0, count);
            }
        }
        String result = hex(digest);
        log.debug("结束计算工作区快照对象摘要");
        return result;
    }
}
