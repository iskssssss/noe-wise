package com.skillforge.core.capability.connector;

import static com.skillforge.core.capability.connector.CodeExecutionArgumentException.Code.*;

import com.skillforge.execution.ExecutionLimits;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** {@code code_execute} 的严格参数边界。 */
public record CodeExecutionArguments(
        String mode,
        String language,
        String code,
        String skillHandle,
        String scriptPath,
        List<Dependency> dependencies,
        List<String> attachmentIds,
        List<ArtifactInput> artifactInputs,
        Map<String, String> inputFiles,
        List<String> outputFiles,
        boolean useWorkspace) {

    private static final Set<String> FIELDS = Set.of(
            "mode", "language", "code", "skill_handle", "script_path", "dependencies", "attachment_ids",
            "artifact_inputs", "input_files", "output_files", "use_workspace");
    private static final Set<String> DEPENDENCY_FIELDS = Set.of("name", "version");
    private static final Pattern NODE_PACKAGE_PART = Pattern.compile(
            "[a-z0-9](?:[a-z0-9._-]{0,212}[a-z0-9])?");
    private static final Pattern PYTHON_PACKAGE = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9._-]{0,212}[A-Za-z0-9])?");
    private static final Pattern NODE_EXACT_VERSION = Pattern.compile(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private static final Pattern PYTHON_EXACT_VERSION = Pattern.compile(
            "(?:[1-9][0-9]*!)?[0-9]+(?:\\.[0-9]+)*(?:(?:a|b|rc)[0-9]+)?"
                    + "(?:\\.post[0-9]+)?(?:\\.dev[0-9]+)?"
                    + "(?:\\+[a-z0-9]+(?:[._-][a-z0-9]+)*)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    public CodeExecutionArguments {
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
        attachmentIds = List.copyOf(attachmentIds == null ? List.of() : attachmentIds);
        artifactInputs = List.copyOf(artifactInputs == null ? List.of() : artifactInputs);
        inputFiles = Map.copyOf(inputFiles);
        outputFiles = List.copyOf(outputFiles);
    }

    /**
     * 解析并验证模型可控参数。模式互斥在这里强制执行，不依赖 JSON Schema 的组合关键字。
     *
     * @param arguments Tool 参数
     * @return 已验证的执行参数
     * @throws CodeExecutionArgumentException 参数缺失、混用、未知或不安全；诊断不回显输入值
     */
    public static CodeExecutionArguments parse(Map<String, Object> arguments) {
        if (arguments == null) {
            throw reject(ARGUMENTS_REQUIRED, "", "执行参数不能为空。", "提供 code_execute 参数对象。");
        }
        rejectUnknownFields(arguments, FIELDS, "");
        String mode = requiredToken(arguments, "mode");
        String language = requiredToken(arguments, "language");
        if (!Set.of("inline", "skill").contains(mode)) {
            throw reject(UNSUPPORTED_VALUE, "/mode", "执行模式不受支持。", "mode 只能为 inline 或 skill。");
        }
        if (!Set.of("python", "node").contains(language)) {
            throw reject(UNSUPPORTED_VALUE, "/language", "执行语言不受支持。", "language 只能为 python 或 node。");
        }

        String code = null;
        String skillHandle = null;
        String scriptPath = null;
        if ("inline".equals(mode)) {
            code = requiredSource(arguments, "code");
            rejectPresent(arguments, "skill_handle", "inline");
            rejectPresent(arguments, "script_path", "inline");
        } else {
            rejectPresent(arguments, "code", "skill");
            skillHandle = requiredSource(arguments, "skill_handle");
            scriptPath = requiredToken(arguments, "script_path");
            validateScriptPath(scriptPath, language);
        }

        List<Dependency> dependencies = parseDependencies(arguments.get("dependencies"), language);
        return new CodeExecutionArguments(mode, language, code, skillHandle, scriptPath, dependencies,
                parseAttachmentIds(arguments.get("attachment_ids")),
                parseArtifactInputs(arguments.get("artifact_inputs")),
                parseInputFiles(arguments.get("input_files")), parseOutputFiles(arguments.get("output_files")),
                parseUseWorkspace(arguments));
    }

    /** 仅模型选择是否使用当前会话工作区，工作区归属与授权仍由可信宿主绑定。 */
    private static boolean parseUseWorkspace(Map<String, Object> arguments) {
        if (!arguments.containsKey("use_workspace")) return false;
        if (!(arguments.get("use_workspace") instanceof Boolean useWorkspace)) {
            throw reject(INVALID_TYPE, "/use_workspace", "工作区选项必须为布尔值。",
                    "使用 true 或 false；省略时默认 false，不接受 null 或字符串。");
        }
        return useWorkspace;
    }

    private static List<String> parseAttachmentIds(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values))
            throw reject(INVALID_TYPE, "/attachment_ids", "附件标识必须为数组。", "传入当前会话已上传附件的 UUID 数组。");
        if (values.size() > ExecutionLimits.MAX_ATTACHMENT_INPUTS)
            throw reject(LIMIT_EXCEEDED, "/attachment_ids", "附件数量超出上限。", "单次最多选择 " + ExecutionLimits.MAX_ATTACHMENT_INPUTS + " 个附件。");
        var ids = new ArrayList<String>(values.size());
        var unique = new HashSet<String>();
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            String field = "/attachment_ids/" + index;
            if (!(value instanceof String id) || !isCanonicalUuid(id))
                throw reject(INVALID_FORMAT, field, "附件标识不是规范 UUID。", "使用当前会话附件列表返回的规范 UUID，不能猜测标识。");
            if (!unique.add(id)) throw reject(DUPLICATE_VALUE, field, "附件标识重复。", "删除重复附件标识。");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 临时文本输入只在本次沙箱中使用，不发布为产物。 */
    private static Map<String, String> parseInputFiles(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> values))
            throw reject(INVALID_TYPE, "/input_files", "文本输入文件必须为对象。", "使用相对路径到文本内容的对象映射。");
        if (values.size() > ExecutionLimits.MAX_FILES)
            throw reject(LIMIT_EXCEEDED, "/input_files", "输入文件数量超出上限。", "单次最多提供 " + ExecutionLimits.MAX_FILES + " 个输入文件。");
        var files = new java.util.TreeMap<String, String>();
        long size = 0;
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String path) || !(entry.getValue() instanceof String content))
                throw reject(INVALID_TYPE, "/input_files", "文本输入文件的路径和内容必须为字符串。", "将二进制内容作为已授权附件或产物引用传入。");
            validateFilePath(path, "/input_files");
            size += content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (size > ExecutionLimits.TRANSFER_BYTES)
                throw reject(LIMIT_EXCEEDED, "/input_files", "输入文件总字节数超出传输上限。", "缩小输入文件总大小，或仅选择本次需要的文件。");
            files.put(path, content);
        }
        return files;
    }

    private static List<String> parseOutputFiles(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values))
            throw reject(INVALID_TYPE, "/output_files", "输出文件必须为数组。", "提供需要交付的相对文件路径数组。");
        if (values.size() > ExecutionLimits.MAX_FILES)
            throw reject(LIMIT_EXCEEDED, "/output_files", "输出文件数量超出上限。", "单次最多声明 " + ExecutionLimits.MAX_FILES + " 个输出路径。");
        var files = new java.util.TreeSet<String>();
        for (int index = 0; index < values.size(); index++) {
            String field = "/output_files/" + index;
            if (!(values.get(index) instanceof String path))
                throw reject(INVALID_TYPE, field, "输出路径必须为字符串。", "提供需要交付的相对文件路径。");
            validateFilePath(path.endsWith("/") ? path.substring(0, path.length() - 1) : path, field);
            if (path.equals(ExecutionLimits.PUBLIC_RESULT_FILE) || path.startsWith(ExecutionLimits.PUBLIC_RESULT_FILE + "/"))
                throw reject(UNSAFE_PATH, field, "业务结果文件是内部保留路径。", "不要将 result.json 或 result.json/ 列入 output_files；下载成果使用其他路径。");
            if (!files.add(path)) throw reject(DUPLICATE_VALUE, field, "输出路径重复。", "删除重复输出路径。");
        }
        return List.copyOf(files);
    }

    private static void validateFilePath(String path, String field) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")
                || path.chars().anyMatch(c -> c < 32 || c == 127)
                || java.util.Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw reject(UNSAFE_PATH, field, "文件路径必须为安全相对路径。", "移除绝对路径、反斜线、空路径段及 . 或 .. 路径段。");
    }

    private static List<ArtifactInput> parseArtifactInputs(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values))
            throw reject(INVALID_TYPE, "/artifact_inputs", "产物输入必须为数组。", "提供包含 artifact_id 和 path 的产物引用数组。");
        if (values.size() > ExecutionLimits.MAX_FILES)
            throw reject(LIMIT_EXCEEDED, "/artifact_inputs", "产物输入数量超出上限。", "单次最多选择 " + ExecutionLimits.MAX_FILES + " 个产物文件。");
        var inputs = new ArrayList<ArtifactInput>();
        var unique = new HashSet<ArtifactInput>();
        for (int index = 0; index < values.size(); index++) {
            String field = "/artifact_inputs/" + index;
            if (!(values.get(index) instanceof Map<?, ?> item))
                throw reject(INVALID_TYPE, field, "产物引用必须为对象。", "提供 artifact_id 和清单内 path。");
            rejectUnknownFields(item, Set.of("artifact_id", "path"), field);
            String id = requiredToken(item, "artifact_id", field);
            String path = requiredToken(item, "path", field);
            if (!isCanonicalUuid(id))
                throw reject(INVALID_FORMAT, field + "/artifact_id", "产物标识不是规范 UUID。", "从真实执行回执或清单复制授权可读取的 ready 候选或产物 UUID，无需先正式发布，不能猜测标识。");
            validateFilePath(path, field + "/path");
            var input = new ArtifactInput(id, path);
            if (!unique.add(input)) throw reject(DUPLICATE_VALUE, field, "产物输入重复。", "删除重复的产物文件引用。");
            inputs.add(input);
        }
        return List.copyOf(inputs);
    }

    /** 模型仅选择已发布产物及清单内路径，不提供存储位置或身份。 */
    public record ArtifactInput(String artifactId, String path) {
        public ArtifactInput {
            if (artifactId == null || !artifactId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")
                    || path.chars().anyMatch(c -> c < 32 || c == 127)
                    || java.util.Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
                throw reject(INVALID_FORMAT, "/artifact_inputs", "产物文件引用不合法。", "使用规范产物 UUID 与清单中的安全相对路径。");
        }
        public String inputPath() { return "artifacts/" + artifactId + "/" + path; }
    }

    /** 冻结技能清单与直接调用共用包名、精确版本及重复依赖校验。 */
    public static List<Dependency> validatedDependencies(Object raw, String language) {
        if (!Set.of("python", "node").contains(language))
            throw reject(UNSUPPORTED_VALUE, "/language", "执行语言不受支持。", "language 只能为 python 或 node。");
        return parseDependencies(raw, language);
    }

    private static List<Dependency> parseDependencies(Object raw, String language) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values)) {
            throw reject(INVALID_TYPE, "/dependencies", "依赖必须为数组。", "提供包含 name 和精确 version 的依赖数组。");
        }
        List<Dependency> dependencies = new ArrayList<>(values.size());
        Set<String> names = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String field = "/dependencies/" + index;
            if (!(values.get(index) instanceof Map<?, ?> dependency)) {
                throw reject(INVALID_TYPE, field, "依赖项必须为对象。", "每个依赖只包含 name 和精确 version。");
            }
            rejectUnknownFields(dependency, DEPENDENCY_FIELDS, field);
            String name = requiredToken(dependency, "name", field);
            String version = requiredToken(dependency, "version", field);
            validatePackageName(name, language, field + "/name");
            Pattern versionPattern = "node".equals(language) ? NODE_EXACT_VERSION : PYTHON_EXACT_VERSION;
            if (version.length() > 128 || !versionPattern.matcher(version).matches()) {
                throw reject(INVALID_FORMAT, field + "/version", "依赖版本必须为精确版本。",
                        "node".equals(language) ? "使用完整三段 SemVer；禁止范围、标签、命令或 URL。"
                                : "使用精确 Python 包版本；禁止范围、标签、命令或 URL。");
            }
            if (!names.add(name)) {
                throw reject(DUPLICATE_VALUE, field + "/name", "依赖名称重复。", "每个依赖仅声明一个精确版本。");
            }
            dependencies.add(new Dependency(name, version));
        }
        return List.copyOf(dependencies);
    }

    private static void validatePackageName(String name, String language, String field) {
        if (name.length() > 214) {
            throw reject(LIMIT_EXCEEDED, field, "依赖名称过长。", "使用不超过 214 字符的合法包名。");
        }
        if ("python".equals(language)) {
            if (!PYTHON_PACKAGE.matcher(name).matches()) {
                throw invalidPackageName(field);
            }
            return;
        }
        if (name.startsWith("@")) {
            int slash = name.indexOf('/');
            if (slash <= 1 || slash == name.length() - 1 || name.indexOf('/', slash + 1) >= 0
                    || !NODE_PACKAGE_PART.matcher(name.substring(1, slash)).matches()
                    || !NODE_PACKAGE_PART.matcher(name.substring(slash + 1)).matches()) {
                throw invalidPackageName(field);
            }
            return;
        }
        if (!NODE_PACKAGE_PART.matcher(name).matches()) {
            throw invalidPackageName(field);
        }
    }

    private static CodeExecutionArgumentException invalidPackageName(String field) {
        return reject(INVALID_FORMAT, field, "依赖名称不合法。", "使用当前语言的合法包名，禁止命令、URL 或仓库地址。");
    }

    private static void validateScriptPath(String path, String language) {
        if (path.indexOf('\\') >= 0 || path.startsWith("/") || !path.startsWith("scripts/")) {
            throw unsafeScriptPath();
        }
        String[] segments = path.split("/", -1);
        if (segments.length < 2) {
            throw unsafeScriptPath();
        }
        for (String segment : segments) {
            if (!PATH_SEGMENT.matcher(segment).matches() || ".".equals(segment) || "..".equals(segment)) {
                throw unsafeScriptPath();
            }
        }
        boolean matchesLanguage = "python".equals(language)
                ? path.endsWith(".py")
                : path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".cjs");
        if (!matchesLanguage) {
            throw reject(INVALID_FORMAT, "/script_path", "脚本扩展名与执行语言不匹配。",
                    "python 使用 .py；node 使用 .js、.mjs 或 .cjs，且脚本必须来自已激活冻结技能。");
        }
    }

    private static CodeExecutionArgumentException unsafeScriptPath() {
        return reject(UNSAFE_PATH, "/script_path", "脚本必须为 scripts/ 下的安全相对文件路径。",
                "从已激活技能清单选择脚本，使用正斜线，不包含空段、. 或 ..。");
    }

    private static void rejectPresent(Map<String, Object> values, String field, String mode) {
        if (values.containsKey(field)) {
            throw reject(FIELD_FORBIDDEN_FOR_MODE, "/" + field, mode + " 模式禁止 " + field + "。",
                    "删除整个 " + field + " 字段；空字符串或 null 仍被拒绝。"
                            + ("skill".equals(mode) ? "保留 skill_handle 和 script_path。" : "保留 code。"));
        }
    }

    private static String requiredToken(Map<?, ?> values, String field) {
        return requiredToken(values, field, "");
    }

    private static String requiredToken(Map<?, ?> values, String field, String parent) {
        String value = requiredSource(values, field, parent);
        if (!value.equals(value.trim())) {
            throw reject(INVALID_FORMAT, parent + "/" + field, "字段不能包含首尾空白。", "移除该字段值的首尾空白。");
        }
        return value;
    }

    private static String requiredSource(Map<?, ?> values, String field) {
        return requiredSource(values, field, "");
    }

    private static String requiredSource(Map<?, ?> values, String field, String parent) {
        Object raw = values.get(field);
        if (raw == null || raw instanceof String value && value.isBlank())
            throw reject(REQUIRED_FIELD, parent + "/" + field, "缺少必填的非空字段。", "提供非空字符串 " + field + "。");
        if (!(raw instanceof String value))
            throw reject(INVALID_TYPE, parent + "/" + field, "字段类型必须为字符串。", "将 " + field + " 作为字符串提供。");
        return value;
    }

    private static void rejectUnknownFields(Map<?, ?> values, Set<String> allowed, String field) {
        for (Object key : values.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                // 未知键也是模型输入，可能藏有敏感文本；只定位受控父对象，不回显未知键。
                throw reject(UNKNOWN_FIELD, field, "参数对象包含未知字段。",
                        "删除未知字段；此对象只允许：" + String.join(", ", new java.util.TreeSet<>(allowed)) + "。");
            }
        }
    }

    private static CodeExecutionArgumentException reject(CodeExecutionArgumentException.Code code,
            String field, String message, String hint) {
        return new CodeExecutionArgumentException(code, field, message, hint);
    }

    /** 动态依赖的结构化精确版本。 */
    public record Dependency(String name, String version) {
        public Dependency {
            if (name == null || version == null) {
                throw reject(REQUIRED_FIELD, "/dependencies", "依赖名称和版本不能为空。", "为每个依赖提供 name 和精确 version。");
            }
        }
    }
}
