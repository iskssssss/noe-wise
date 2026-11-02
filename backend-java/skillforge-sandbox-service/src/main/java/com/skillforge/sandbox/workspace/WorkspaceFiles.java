package com.skillforge.sandbox.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.WorkspaceCommand;
import com.skillforge.sandbox.api.SandboxConflictException;
import com.skillforge.sandbox.api.SandboxRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 在调用方持有工作区排他权期间操作文件；任何路径均不得越界或经过符号链接。 */
@Component
public class WorkspaceFiles {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceFiles.class);
    private static final int TEXT_PAGE = 32_768;
    private static final int DIRECTORY_PAGE = 100;
    private final ObjectMapper json;

    /** 复用宿主 JSON 配置解析精确文本替换请求，不在日志记录正文。 */
    public WorkspaceFiles(ObjectMapper json) {
        log.debug("初始化工作区文件操作入口");
        this.json = json;
        log.debug("完成初始化工作区文件操作入口");
    }

    /** 执行受控文件操作；expectedRevision 已由调用方在排他权内校验。 */
    public Map<String, Object> execute(Path root, WorkspaceCommand command) {
        String operation = command.operation();
        log.debug("开始工作区文件操作 operation={}", operation);
        boolean succeeded = false;
        try {
            checkDeadline(command);
            Path path = resolve(root, command.path(), "list".equals(operation) || "read".equals(operation));
            Map<String, Object> result = switch (operation == null ? "" : operation) {
                case "list" -> list(root, path, command);
                case "read" -> read(root, path, command);
                case "write" -> write(root, path, command);
                case "patch" -> patch(root, path, command);
                case "mkdir" -> mkdir(root, path);
                case "move" -> move(root, path, command);
                case "delete" -> delete(root, path, command);
                default -> throw rejected("workspace_operation_rejected", "不支持的工作区文件操作");
            };
            succeeded = true;
            return result;
        } catch (IOException failure) {
            throw ioFailure(failure);
        } finally {
            log.debug("结束工作区文件操作 operation={} succeeded={}", operation, succeeded);
        }
    }

    /** 按名称排序分页；只读取直接子项元数据，避免为列目录计算整棵树摘要。 */
    private Map<String, Object> list(Path root, Path path, WorkspaceCommand command) throws IOException {
        log.debug("开始工作区目录分页");
        requireDirectory(path);
        int offset = offset(command);
        int limit = limit(command, DIRECTORY_PAGE);
        List<Path> children = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(path)) {
            for (Path child : stream) {
                checkDeadline(command);
                requireOrdinary(child);
                children.add(child);
                if (children.size() > ExecutionLimits.MAX_FILES) {
                    throw rejected("workspace_directory_limit", "目录条目超过工作区容量限制");
                }
            }
        }
        children.sort(Comparator.comparing(child -> child.getFileName().toString()));
        int end = Math.min(children.size(), (int) Math.min(Integer.MAX_VALUE, (long) offset + limit));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = Math.min(offset, children.size()); index < end; index++) {
            Path child = children.get(index);
            BasicFileAttributes attributes = requireOrdinary(child);
            entries.add(Map.of("path", relative(root, child), "name", child.getFileName().toString(),
                    "type", attributes.isDirectory() ? "directory" : "file", "size", attributes.isDirectory() ? 0L : attributes.size()));
        }
        log.debug("结束工作区目录分页 entries={}", entries.size());
        return Map.of("entries", entries, "offset", offset, "next_offset", Math.max(offset, end), "truncated", end < children.size());
    }

    /** UTF-8 严格解码并按 UTF-16 字符位置返回有限页；完整摘要流式计算。 */
    private Map<String, Object> read(Path root, Path path, WorkspaceCommand command) throws IOException {
        log.debug("开始工作区文本分页读取");
        BasicFileAttributes attributes = requireFile(path);
        int offset = offset(command);
        int limit = limit(command, TEXT_PAGE);
        StringBuilder text = new StringBuilder(limit + 1);
        try (Reader reader = reader(path)) {
            copyCharacters(reader, null, offset, command);
            char[] buffer = new char[Math.min(limit + 1, 4096)];
            while (text.length() <= limit) {
                checkDeadline(command);
                int count = reader.read(buffer, 0, Math.min(buffer.length, limit + 1 - text.length()));
                if (count == -1) break;
                text.append(buffer, 0, count);
            }
        }
        boolean truncated = text.length() > limit;
        if (truncated) text.setLength(limit);
        String sha256 = sha256(path);
        log.debug("结束工作区文本分页读取 characters={} truncated={}", text.length(), truncated);
        return Map.of("path", relative(root, path), "content", text.toString(), "offset", offset,
                "next_offset", (long) offset + text.length(), "truncated", truncated, "sha256", sha256, "size", attributes.size());
    }

    /** 创建或校验基准后覆盖普通文件；先写同目录临时文件，再原子替换。 */
    private Map<String, Object> write(Path root, Path path, WorkspaceCommand command) throws IOException {
        log.debug("开始工作区文本写入");
        requireDirectory(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            requireFile(path);
            checkExpected(path, command);
        }
        String content = content(command);
        Path temporary = Files.createTempFile(path.getParent(), ".workspace-", ".tmp");
        try {
            try (Writer writer = writer(temporary)) {
                writer.write(content);
            }
            writable(temporary, false);
            checkDeadline(command);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        Map<String, Object> result = fileResult(root, path);
        log.debug("结束工作区文本写入");
        return result;
    }

    /** 先流式定位唯一命中，再流式替换，重复命中或基准过期均不修改文件。 */
    private Map<String, Object> patch(Path root, Path path, WorkspaceCommand command) throws IOException {
        log.debug("开始工作区精确替换");
        requireFile(path);
        checkExpected(path, command);
        JsonNode replacement;
        try {
            replacement = json.readTree(content(command));
        } catch (JsonProcessingException failure) {
            var error = rejected("workspace_patch_rejected", "替换请求不是有效 JSON 对象");
            error.initCause(failure);
            throw error;
        }
        if (replacement == null || !replacement.isObject() || !replacement.path("old_text").isTextual()
                || !replacement.path("new_text").isTextual() || replacement.path("old_text").asText().isEmpty()) {
            throw rejected("workspace_patch_rejected", "替换请求需要非空 old_text 和文本 new_text");
        }
        String oldText = replacement.path("old_text").asText();
        String newText = replacement.path("new_text").asText();
        long position = uniqueMatch(path, oldText, command);
        Path temporary = Files.createTempFile(path.getParent(), ".workspace-", ".tmp");
        try {
            try (Reader input = reader(path); Writer output = writer(temporary)) {
                copyCharacters(input, output, position, command);
                copyCharacters(input, null, oldText.length(), command);
                output.write(newText);
                copyCharacters(input, output, Long.MAX_VALUE, command);
            }
            if (Files.size(temporary) > ExecutionLimits.TRANSFER_BYTES) {
                throw rejected("workspace_file_limit", "替换后的文件超过容量限制");
            }
            writable(temporary, false);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        Map<String, Object> result = fileResult(root, path);
        log.debug("结束工作区精确替换");
        return result;
    }

    /** 创建缺少的祖先目录；resolve 已拒绝任何已有链接与特殊节点。 */
    private Map<String, Object> mkdir(Path root, Path path) throws IOException {
        log.debug("开始工作区建目录");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw conflict("workspace_path_exists", "目标路径已存在");
        }
        Path cursor = root.toAbsolutePath().normalize();
        for (Path part : cursor.relativize(path)) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(cursor);
                writable(cursor, true);
            } else requireDirectory(cursor);
        }
        log.debug("结束工作区建目录");
        return Map.of("path", relative(root, path), "created", true);
    }

    /** 移动必须匹配源基准，且不覆盖目标；目录基准由工作区修订提供。 */
    private Map<String, Object> move(Path root, Path path, WorkspaceCommand command) throws IOException {
        log.debug("开始工作区移动");
        requireOrdinary(path);
        checkExpected(path, command);
        Path target = resolve(root, command.targetPath(), false);
        requireDirectory(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw conflict("workspace_path_exists", "移动目标已存在");
        }
        Files.move(path, target);
        log.debug("结束工作区移动");
        return Map.of("path", relative(root, path), "target_path", relative(root, target), "moved", true);
    }

    /** 删除文件或空目录；不隐式递归删除，也不允许删除工作区根。 */
    private Map<String, Object> delete(Path root, Path path, WorkspaceCommand command) throws IOException {
        log.debug("开始工作区删除");
        requireOrdinary(path);
        checkExpected(path, command);
        Files.delete(path);
        log.debug("结束工作区删除");
        return Map.of("path", relative(root, path), "deleted", true);
    }

    /** 验证安全相对路径及所有已存在祖先；命令进程静止由调用方排他权保证。 */
    static Path resolve(Path root, String value, boolean allowRoot) throws IOException {
        log.debug("开始验证工作区相对路径");
        Path base = root.toAbsolutePath().normalize();
        requireDirectory(base);
        if (value == null || value.isBlank()) value = ".";
        if (value.length() > 4096 || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0
                || value.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw rejected("workspace_path_rejected", "工作区路径格式无效");
        }
        Path relative;
        try {
            relative = Path.of(value);
        } catch (java.nio.file.InvalidPathException failure) {
            throw rejected("workspace_path_rejected", "工作区路径格式无效");
        }
        if (relative.isAbsolute()) throw rejected("workspace_path_rejected", "工作区路径必须相对项目目录");
        for (Path part : relative) {
            if (part.toString().equals("..")) throw rejected("workspace_path_rejected", "工作区路径不允许父目录跳转");
        }
        Path candidate = base.resolve(relative).normalize();
        if (!candidate.startsWith(base) || (!allowRoot && candidate.equals(base))) {
            throw rejected("workspace_path_rejected", "工作区路径越界或试图修改根目录");
        }
        Path cursor = base;
        for (Path part : base.relativize(candidate)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) requireOrdinary(cursor);
        }
        log.debug("结束验证工作区相对路径");
        return candidate;
    }

    /** 拒绝链接、设备、套接字等非普通节点。 */
    static BasicFileAttributes requireOrdinary(Path path) throws IOException {
        log.debug("开始验证工作区节点类型");
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || (!attributes.isRegularFile() && !attributes.isDirectory())) {
            throw rejected("workspace_node_rejected", "工作区只允许普通文件和目录");
        }
        log.debug("结束验证工作区节点类型");
        return attributes;
    }

    /** 文件 I/O 与摘要均有统一大小上限。 */
    static BasicFileAttributes requireFile(Path path) throws IOException {
        log.debug("开始验证工作区普通文件");
        BasicFileAttributes attributes = requireOrdinary(path);
        if (!attributes.isRegularFile()) throw rejected("workspace_file_required", "操作需要普通文件");
        if (attributes.size() > ExecutionLimits.TRANSFER_BYTES) throw rejected("workspace_file_limit", "文件超过工作区容量限制");
        log.debug("结束验证工作区普通文件");
        return attributes;
    }

    /** 已存在目录必须是普通目录。 */
    static void requireDirectory(Path path) throws IOException {
        log.debug("开始验证工作区目录");
        if (!requireOrdinary(path).isDirectory()) throw rejected("workspace_directory_required", "操作需要目录");
        log.debug("结束验证工作区目录");
    }

    /** 返回跨平台一致的相对路径。 */
    static String relative(Path root, Path path) {
        log.debug("开始转换工作区相对路径");
        String result = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        log.debug("结束转换工作区相对路径");
        return result.isEmpty() ? "." : result;
    }

    /** 独占挂载目录允许容器 uid 65532 写入，Windows 由挂载映射权限。 */
    static void writable(Path path, boolean directory) throws IOException {
        log.debug("开始设置工作区可写权限 directory={}", directory);
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwxrwxrwx" : "rw-rw-rw-"));
        } catch (UnsupportedOperationException ignored) {
            // Docker Desktop 挂载不支持 POSIX 权限；不跟随链接更改宿主目标。
        }
        log.debug("结束设置工作区可写权限");
    }

    /** 流式摘要限制读入字节，不将大文件载入内存。 */
    static String sha256(Path path) throws IOException {
        log.debug("开始工作区文件摘要");
        requireFile(path);
        MessageDigest digest = digest();
        long size = 0;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[ExecutionLimits.STREAM_BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > ExecutionLimits.TRANSFER_BYTES) throw rejected("workspace_file_limit", "文件超过工作区容量限制");
                if (Thread.currentThread().isInterrupted()) throw rejected("workspace_cancelled", "工作区操作已取消");
                digest.update(buffer, 0, count);
            }
        }
        String result = HexFormat.of().formatHex(digest.digest());
        log.debug("结束工作区文件摘要 bytes={}", size);
        return result;
    }

    /** SHA-256 为平台标准算法，缺失属于运行环境错误。 */
    static MessageDigest digest() {
        log.debug("开始初始化工作区摘要算法");
        try {
            MessageDigest result = MessageDigest.getInstance("SHA-256");
            log.debug("结束初始化工作区摘要算法");
            return result;
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行环境缺少 SHA-256", failure);
        }
    }

    /** 未提供基准时拒绝覆盖；同时提供摘要和修订时摘要仍必须相符。 */
    private void checkExpected(Path path, WorkspaceCommand command) throws IOException {
        log.debug("开始工作区文件基准校验");
        if (command.expectedSha256() != null) {
            if (!command.expectedSha256().matches("[0-9a-f]{64}") || !requireOrdinary(path).isRegularFile()
                    || !sha256(path).equals(command.expectedSha256())) {
                throw conflict("workspace_file_changed", "文件内容与预期摘要不符");
            }
        } else if (command.expectedRevision() == null) {
            throw conflict("workspace_baseline_required", "修改已有文件或目录需要基准摘要或工作区修订");
        }
        log.debug("结束工作区文件基准校验");
    }

    /** KMP 保持固定扫描内存，同时识别重叠命中，避免静默替换多个位置。 */
    private long uniqueMatch(Path path, String needle, WorkspaceCommand command) throws IOException {
        log.debug("开始流式定位工作区精确替换");
        int[] fallback = new int[needle.length()];
        for (int index = 1, matched = 0; index < needle.length(); index++) {
            while (matched > 0 && needle.charAt(index) != needle.charAt(matched)) matched = fallback[matched - 1];
            if (needle.charAt(index) == needle.charAt(matched)) matched++;
            fallback[index] = matched;
        }
        long position = -1;
        long scanned = 0;
        int matched = 0;
        try (Reader input = reader(path)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkDeadline(command);
                for (int index = 0; index < count; index++, scanned++) {
                    char character = buffer[index];
                    while (matched > 0 && character != needle.charAt(matched)) matched = fallback[matched - 1];
                    if (character == needle.charAt(matched)) matched++;
                    if (matched == needle.length()) {
                        if (position >= 0) throw conflict("workspace_patch_ambiguous", "old_text 在文件中不止一次出现");
                        position = scanned - needle.length() + 1;
                        matched = fallback[matched - 1];
                    }
                }
            }
        }
        if (position < 0) throw conflict("workspace_patch_not_found", "文件中未找到 old_text");
        log.debug("结束流式定位工作区精确替换");
        return position;
    }

    /** 流式跳过或复制指定字符数；Long.MAX_VALUE 表示复制剩余内容。 */
    private void copyCharacters(Reader input, Writer output, long maximum, WorkspaceCommand command) throws IOException {
        log.debug("开始流式复制工作区文本");
        char[] buffer = new char[4096];
        long copied = 0;
        while (copied < maximum) {
            checkDeadline(command);
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, maximum - copied));
            if (count == -1) break;
            if (output != null) output.write(buffer, 0, count);
            copied += count;
        }
        log.debug("结束流式复制工作区文本 characters={}", copied);
    }

    /** 严格 UTF-8 解码，二进制及损坏文本明确失败。 */
    private Reader reader(Path path) throws IOException {
        log.debug("开始创建工作区文本读取器");
        Reader result = new InputStreamReader(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        log.debug("结束创建工作区文本读取器");
        return result;
    }

    /** 严格 UTF-8 编码，未配对代理字符不能静默转换成问号。 */
    private Writer writer(Path path) throws IOException {
        log.debug("开始创建工作区文本写入器");
        Writer result = new OutputStreamWriter(Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        log.debug("结束创建工作区文本写入器");
        return result;
    }

    /** 写入正文受内部控制消息容量约束，不引入无限文本缓冲。 */
    private String content(WorkspaceCommand command) {
        log.debug("开始验证工作区文本容量");
        if (command.content() == null || command.content().length() > ExecutionLimits.CONTROL_RESPONSE_BYTES / 4) {
            throw rejected("workspace_content_rejected", "文本内容缺失或超过控制消息容量");
        }
        log.debug("结束验证工作区文本容量");
        return command.content();
    }

    /** 修改结果只返回摘要和大小，正文不进入日志。 */
    private Map<String, Object> fileResult(Path root, Path path) throws IOException {
        log.debug("开始构建工作区文件结果");
        Map<String, Object> result = Map.of("path", relative(root, path), "sha256", sha256(path), "size", Files.size(path));
        log.debug("结束构建工作区文件结果");
        return result;
    }

    /** 分页起点必须非负。 */
    private int offset(WorkspaceCommand command) {
        log.debug("开始验证工作区分页起点");
        int offset = command.offset() == null ? 0 : command.offset();
        if (offset < 0) throw rejected("workspace_page_rejected", "分页起点必须非负");
        log.debug("结束验证工作区分页起点");
        return offset;
    }

    /** 超过上限时直接拒绝，避免调用方误认为请求得到完整响应。 */
    private int limit(WorkspaceCommand command, int maximum) {
        log.debug("开始验证工作区分页长度");
        int limit = command.limit() == null ? maximum : command.limit();
        if (limit < 1 || limit > maximum) throw rejected("workspace_page_rejected", "分页长度超出允许范围");
        log.debug("结束验证工作区分页长度");
        return limit;
    }

    /** 排队后和每个流式批次都遵守原调用取消及截止时间。 */
    private void checkDeadline(WorkspaceCommand command) {
        log.debug("开始验证工作区调用期限");
        if (Thread.currentThread().isInterrupted() || command.deadlineAt() != null && !Instant.now().isBefore(command.deadlineAt())) {
            throw rejected("workspace_cancelled", "工作区调用已取消或超过截止时间");
        }
        log.debug("结束验证工作区调用期限");
    }

    /** 请求错误保持明确代码，不包含文件正文。 */
    static SandboxRequestException rejected(String code, String message) {
        log.debug("构建工作区请求错误 code={}", code);
        return new SandboxRequestException(code, message);
    }

    /** 冲突需要调用方重新读取当前修订，禁止自动覆盖。 */
    private SandboxConflictException conflict(String code, String message) {
        log.debug("构建工作区冲突错误 code={}", code);
        return new SandboxConflictException(code, message);
    }

    /** I/O 错误保留原因链，公开信息不暴露物理宿主路径。 */
    static SandboxRequestException ioFailure(IOException cause) {
        log.debug("转换工作区文件系统错误 type={}", cause.getClass().getSimpleName());
        String code = cause instanceof java.nio.charset.CharacterCodingException ? "workspace_utf8_required"
                : cause instanceof java.nio.file.NoSuchFileException ? "workspace_path_missing"
                : cause instanceof java.nio.file.DirectoryNotEmptyException ? "workspace_directory_not_empty" : "workspace_io_failed";
        String message = switch (code) {
            case "workspace_utf8_required" -> "文件内容不是有效 UTF-8 文本";
            case "workspace_path_missing" -> "工作区文件或父目录不存在";
            case "workspace_directory_not_empty" -> "仅允许删除空目录";
            default -> "工作区文件操作失败";
        };
        var result = rejected(code, message);
        result.initCause(cause);
        return result;
    }
}
