package com.skillforge.core.capability;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 标准 Skill 目录与 ZIP 的有界摄取器；不执行包内任何资源。 */
public final class SkillPackageValidator {

    public static final int MAX_FILES = 256;
    public static final int MAX_PATH_LENGTH = 240;
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_PACKAGE_BYTES = 10 * 1024 * 1024;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "txt", "json", "yaml", "yml", "csv", "tsv", "xml", "html", "css",
            "js", "mjs", "cjs", "ts", "py", "sh", "ps1", "java", "kt", "sql");
    private static final Set<String> ROOT_KEYS = Set.of(
            "name", "description", "license", "compatibility", "metadata", "allowed-tools");
    /** 产品专有执行声明只保留原文，不参与本平台权限、模型或执行生命周期。 */
    private static final Set<String> EXECUTION_EXTENSION_KEYS = Set.of(
            "hooks", "context", "agent", "model", "shell", "effort", "disable-model-invocation",
            "user-invocable", "deps", "required_environment_variables", "required_credential_files");

    public SkillPackage fromDirectory(Map<String, byte[]> sourceFiles) {
        Map<String, byte[]> files = unwrapSingleRootDirectory(safeDirectory(sourceFiles));
        byte[] skillMd = files.get("SKILL.md");
        if (skillMd == null) {
            throw invalid("skill package requires root SKILL.md");
        }
        SkillPackage.Metadata metadata = frontmatter(utf8(skillMd, "SKILL.md"));
        List<SkillPackage.FileEntry> entries = files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> file(entry.getKey(), entry.getValue()))
                .toList();
        return new SkillPackage("SKILL.md", metadata, entries);
    }

    /** 编辑草稿只检查摄取安全；允许空包、缺失入口与未完成的 frontmatter。 */
    public Map<String, byte[]> safeDirectory(Map<String, byte[]> sourceFiles) {
        if (sourceFiles == null || sourceFiles.size() > MAX_FILES) {
            throw invalid("skill package file count is invalid");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, byte[]> source : sourceFiles.entrySet()) {
            String path = normalizePath(source.getKey());
            byte[] bytes = source.getValue();
            if (bytes == null || bytes.length > MAX_FILE_BYTES) {
                throw invalid("skill package file exceeds size limit: " + path);
            }
            if (isText(path)) utf8(bytes, path);
            total = Math.addExact(total, bytes.length);
            if (total > MAX_PACKAGE_BYTES || files.putIfAbsent(path, bytes.clone()) != null) {
                throw invalid("skill package exceeds budget or contains duplicate paths");
            }
        }
        for (String path : files.keySet()) {
            int slash = path.indexOf('/');
            while (slash >= 0) {
                if (files.containsKey(path.substring(0, slash))) {
                    throw invalid("skill package file conflicts with directory: " + path);
                }
                slash = path.indexOf('/', slash + 1);
            }
        }
        return files;
    }

    public SkillPackage fromZip(byte[] archive) {
        return fromDirectory(safeZip(archive));
    }

    /** ZIP 解包和编辑保存共用安全边界，不要求包已经可以发布。 */
    public Map<String, byte[]> safeZip(byte[] archive) {
        if (archive == null || archive.length == 0 || archive.length > MAX_PACKAGE_BYTES) {
            throw invalid("skill zip size is invalid");
        }
        // ZipInputStream 对普通字节流可能直接返回 EOF；不能把损坏包当作空草稿覆盖。
        if (archive.length < 4 || archive[0] != 'P' || archive[1] != 'K'
                || !((archive[2] == 3 && archive[3] == 4) || (archive[2] == 5 && archive[3] == 6))) {
            throw invalid("invalid skill zip signature");
        }
        int expectedEntries = zipEntryCount(archive);
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_FILES * 2) throw invalid("skill zip contains too many entries");
                if (entry.isDirectory()) {
                    String directory = entry.getName();
                    normalizePath(directory.substring(0, directory.length() - 1));
                    // 不能让 getNextEntry 隐式解压并丢弃目录负载，绕过文件/总量预算。
                    if (zip.read() != -1) throw invalid("skill zip directory contains payload");
                    continue;
                }
                if (files.size() >= MAX_FILES) {
                    throw invalid("skill zip contains too many files");
                }
                String path = normalizePath(entry.getName());
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    if (output.size() > MAX_FILE_BYTES) {
                        throw invalid("skill zip entry exceeds size limit");
                    }
                }
                byte[] bytes = output.toByteArray();
                total = Math.addExact(total, bytes.length);
                if (total > MAX_PACKAGE_BYTES || files.putIfAbsent(path, bytes) != null) {
                    throw invalid("skill zip exceeds budget or contains duplicate paths");
                }
            }
        } catch (IOException | ArithmeticException exception) {
            throw invalid("invalid skill zip", exception);
        }
        if (entries != expectedEntries) throw invalid("skill zip entry count mismatch");
        return unwrapSingleRootDirectory(safeDirectory(files));
    }

    /** 验证 ZIP 尾记录及中央目录边界，截断流不能被误认为合法空包。 */
    private static int zipEntryCount(byte[] archive) {
        var buffer = java.nio.ByteBuffer.wrap(archive).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int offset = archive.length - 22; offset >= Math.max(0, archive.length - 65557); offset--) {
            if (buffer.getInt(offset) != 0x06054b50
                    || offset + 22 + Short.toUnsignedInt(buffer.getShort(offset + 20)) != archive.length) continue;
            int entries = Short.toUnsignedInt(buffer.getShort(offset + 10));
            long size = Integer.toUnsignedLong(buffer.getInt(offset + 12));
            long start = Integer.toUnsignedLong(buffer.getInt(offset + 16));
            if (buffer.getShort(offset + 4) != 0 || buffer.getShort(offset + 6) != 0
                    || Short.toUnsignedInt(buffer.getShort(offset + 8)) != entries
                    || entries > MAX_FILES * 2 || start + size != offset
                    || (entries == 0 && (start != 0 || size != 0))
                    || (entries > 0 && (size < 46 || start + 4 > offset || buffer.getInt((int) start) != 0x02014b50))) {
                throw invalid("invalid skill zip directory");
            }
            return entries;
        }
        throw invalid("skill zip end record is missing");
    }

    /**
     * 用户常把技能目录整包压缩，得到 {@code name/SKILL.md}。
     * 仅当全部文件共享同一顶层目录且其中有 SKILL.md 时去掉这一层。
     */
    private static Map<String, byte[]> unwrapSingleRootDirectory(Map<String, byte[]> files) {
        if (files.containsKey("SKILL.md")) {
            return files;
        }
        String prefix = null;
        for (String path : files.keySet()) {
            int slash = path.indexOf('/');
            if (slash <= 0) {
                return files;
            }
            String first = path.substring(0, slash);
            if (prefix == null) {
                prefix = first;
            } else if (!prefix.equals(first)) {
                return files;
            }
        }
        if (prefix == null || !files.containsKey(prefix + "/SKILL.md")) {
            return files;
        }
        Map<String, byte[]> unwrapped = new LinkedHashMap<>();
        String lead = prefix + "/";
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            unwrapped.put(entry.getKey().substring(lead.length()), entry.getValue());
        }
        return unwrapped;
    }

    public String packageSha256(SkillPackage skillPackage) {
        MessageDigest digest = digest();
        skillPackage.files().stream().sorted(Comparator.comparing(SkillPackage.FileEntry::path))
                .forEach(file -> {
                    digest.update(file.path().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(file.sha256().getBytes(StandardCharsets.US_ASCII));
                    digest.update((byte) '\n');
                });
        return hex(digest.digest());
    }

    private SkillPackage.FileEntry file(String path, byte[] bytes) {
        boolean text = isText(path);
        String content = text ? utf8(bytes, path) : Base64.getEncoder().encodeToString(bytes);
        return new SkillPackage.FileEntry(path, mediaType(path),
                text ? SkillPackage.Encoding.UTF8 : SkillPackage.Encoding.BASE64,
                content, hex(digest().digest(bytes)), bytes.length);
    }

    /** 有界解析声明，不渲染模板、不展开命令，未知字段在原始 SKILL.md 中保留。 */
    private Map<?, ?> frontmatterValues(String source) {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("---\n")) {
            throw invalid("SKILL.md requires YAML frontmatter");
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            throw invalid("SKILL.md frontmatter is not terminated");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(16 * 1024);
        Object parsed;
        try {
            parsed = new Yaml(new SafeConstructor(options)).load(normalized.substring(4, end));
        } catch (RuntimeException exception) {
            throw invalid("SKILL.md frontmatter is invalid YAML", exception);
        }
        if (!(parsed instanceof Map<?, ?> values)) {
            throw invalid("SKILL.md frontmatter must be a mapping");
        }
        for (Object rawKey : values.keySet()) {
            if (!(rawKey instanceof String)) {
                throw invalid("SKILL.md frontmatter 字段名必须是字符串");
            }
        }
        return values;
    }

    /** 只投影标准字段；其他声明仍由原文字节和包摘要保护，不擅自转换或执行。 */
    private SkillPackage.Metadata frontmatter(String source) {
        Map<?, ?> values = frontmatterValues(source);
        String name = requiredScalar(values, "name");
        if (name.length() > 64 || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw invalid("SKILL.md name must be a lowercase hyphenated identifier");
        }
        String description = requiredScalar(values, "description");
        if (description.length() > 1024) {
            throw invalid("SKILL.md description exceeds limit");
        }
        Map<String, String> extensions = new LinkedHashMap<>();
        Object metadata = values.get("metadata");
        if (metadata != null) {
            if (!(metadata instanceof Map<?, ?> metadataValues)) {
                throw invalid("SKILL.md metadata must be a mapping");
            }
            metadataValues.forEach((key, value) -> {
                if (!(key instanceof String textKey) || textKey.isBlank()
                        || !(value instanceof String textValue) || textValue.isBlank()) {
                    throw invalid("SKILL.md metadata entries must be non-blank strings");
                }
                extensions.put(textKey, textValue);
            });
        }
        List<String> allowedTools = allowedTools(values.get("allowed-tools"));
        return new SkillPackage.Metadata(name, description,
                optionalScalar(values, "license"), optionalScalar(values, "compatibility"),
                extensions, allowedTools);
    }

    /**
     * 为维护台报告已保存包的支持范围；只检查声明及文件存在性，不探测环境或执行资源。
     * 标准包格式可通过，但产品扩展不因此生效，compatibility 也不能冒充运行能力证明。
     */
    public List<SkillDraftValidation.Issue> notices(SkillPackage skillPackage) {
        String source = skillPackage.requireFile(skillPackage.entrypoint()).content();
        Map<?, ?> values = frontmatterValues(source);
        List<SkillDraftValidation.Issue> result = new ArrayList<>();
        for (Object rawKey : values.keySet()) {
            String key = (String) rawKey;
            if (ROOT_KEYS.contains(key)) continue;
            boolean execution = EXECUTION_EXTENSION_KEYS.contains(key);
            result.add(new SkillDraftValidation.Issue("SKILL.md", null,
                    SkillDraftValidation.Severity.WARNING,
                    execution ? "SKILL_EXECUTION_EXTENSION_UNSUPPORTED" : "SKILL_FRONTMATTER_UNINTERPRETED",
                    "顶层字段 " + key + (execution
                            ? " 已原样保留；本平台不解释或执行此扩展，也不会据此改变权限、模型或运行方式。"
                            : " 已原样保留；本平台不解释此扩展。")));
        }
        if (source.contains("!`") || source.contains("```!")) {
            result.add(new SkillDraftValidation.Issue("SKILL.md", null,
                    SkillDraftValidation.Severity.WARNING, "SKILL_INLINE_COMMAND_UNSUPPORTED",
                    "检测到内联命令标记；内容按原文读取，导入、校验和加载不会运行或替换这些命令。"));
        }
        if (skillPackage.metadata().compatibility().isPresent()) {
            result.add(new SkillDraftValidation.Issue("SKILL.md", null,
                    SkillDraftValidation.Severity.INFO, "SKILL_COMPATIBILITY_DECLARED",
                    "compatibility 是包作者的兼容性声明；本次格式校验未核实当前 Agent、运行环境和依赖。"));
        }
        if (!skillPackage.metadata().allowedTools().isEmpty()) {
            result.add(new SkillDraftValidation.Issue("SKILL.md", null,
                    SkillDraftValidation.Severity.INFO, "SKILL_TOOL_DECLARATIONS_ONLY",
                    "allowed-tools 仅保留工具使用声明，不授予权限或自动挂载工具；执行使用 Agent 当前获授权的能力。"));
        }
        if (skillPackage.files().stream().anyMatch(file -> file.path().startsWith("scripts/"))) {
            result.add(new SkillDraftValidation.Issue("scripts/", null,
                    SkillDraftValidation.Severity.INFO, "SKILL_EXECUTION_NOT_VERIFIED",
                    "包内含脚本资源；格式校验未执行脚本、安装依赖或验证运行入口，实际执行由当前 Agent 与沙箱核验。"));
        }
        return List.copyOf(result);
    }

    private static String normalizePath(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_PATH_LENGTH || raw.indexOf('\0') >= 0) {
            throw invalid("skill package path is invalid");
        }
        String value = raw.replace('\\', '/');
        if (value.startsWith("/") || value.endsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw invalid("skill package path must be relative");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("skill package path traversal is forbidden");
            }
        }
        return String.join("/", segments);
    }

    private static boolean isText(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String mediaType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/yaml";
        if (isText(path)) return "text/plain";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static String utf8(byte[] bytes, String path) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw invalid("skill text file is not valid UTF-8: " + path, exception);
        }
    }

    private static String requiredScalar(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid("SKILL.md requires string " + key);
        }
        return text;
    }

    private static Optional<String> optionalScalar(Map<?, ?> values, String key) {
        if (!values.containsKey(key)) return Optional.empty();
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid("SKILL.md " + key + " must be a non-blank string");
        }
        return Optional.of(text);
    }

    private static List<String> allowedTools(Object value) {
        if (value == null) return List.of();
        List<?> rawValues;
        if (value instanceof String text) {
            rawValues = text.isBlank() ? List.of() : List.of(text.trim().split("\\s+"));
        } else if (value instanceof List<?> list) {
            rawValues = list;
        } else {
            throw invalid("SKILL.md allowed-tools must be a string or string sequence");
        }
        List<String> result = new ArrayList<>();
        for (Object item : rawValues) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw invalid("SKILL.md allowed-tools entries must be non-blank strings");
            }
            result.add(text);
        }
        if (result.stream().distinct().count() != result.size()) {
            throw invalid("SKILL.md allowed-tools entries must be unique");
        }
        return List.copyOf(result);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Exception cause) {
        return new IllegalArgumentException(message, cause);
    }
}
