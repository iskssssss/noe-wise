package com.skillforge.sandbox.execution;

import com.skillforge.execution.DependencySpec;
import com.skillforge.execution.ArtifactFile;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.ExecuteRequest;
import com.skillforge.execution.ExecutionProfile;
import com.skillforge.execution.Language;
import com.skillforge.execution.WorkspaceBinding;
import com.skillforge.sandbox.api.SandboxRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 校验执行 profile、输入与依赖，并生成包含冻结环境身份的稳定请求摘要。 */
@Component
public class RequestPolicy {

    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern PYTHON_NAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");
    private static final Pattern PYTHON_VERSION = Pattern.compile(
            "(?i)(?:[0-9]+!)?[0-9]+(?:\\.[0-9]+)*(?:(?:a|b|rc)[0-9]+)?(?:\\.post[0-9]+)?(?:\\.dev[0-9]+)?(?:\\+[a-z0-9]+(?:[._-][a-z0-9]+)*)?");
    private static final Pattern NODE_NAME = Pattern.compile(
            "(?:@[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?/)?[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?");
    private static final Pattern NODE_VERSION = Pattern.compile(
            "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_WORKFLOW_INPUT_JSON_BYTES = ExecutionLimits.WORKFLOW_RESULT_BYTES;

    private final ObjectMapper objectMapper;
    private final ObjectMapper strictJson;
    private final Clock clock;

    @Autowired
    public RequestPolicy(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    RequestPolicy(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.strictJson = objectMapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = clock;
    }

    public ValidatedRequest validate(ExecuteRequest request) {
        return validate(request, List.of(), null, null);
    }

    public ValidatedRequest validate(ExecuteRequest request,List<ArtifactFile> artifactInputs) {
        return validate(request, artifactInputs, null, null);
    }

    ValidatedRequest validate(ExecuteRequest request, List<ArtifactFile> artifactInputs,
                              String imageReference, String imageId) {
        if (!TASK_ID.matcher(request.taskId()).matches()) {
            throw new SandboxRequestException("INVALID_TASK_ID", "taskId 只能包含字母、数字、点、下划线和短横线，长度不超过 128");
        }
        if (request.profile() == null) {
            throw new SandboxRequestException("INVALID_EXECUTION_PROFILE", "profile 必须显式指定");
        }
        String inputJson = validateProfileBoundary(request, artifactInputs);
        if (request.programTools()
                && (request.language() != Language.NODE || !request.inputs().isEmpty()
                || request.scriptPath() != null)) {
            throw new SandboxRequestException("INVALID_PROGRAM_TOOLS_MODE",
                    "programTools 仅允许 Node.js inline 执行");
        }
        List<DependencySpec> dependencies = request.dependencies().stream()
                .map(dependency -> validateDependency(request.language(), dependency))
                .sorted(Comparator.comparing(DependencySpec::name).thenComparing(DependencySpec::version))
                .toList();
        Set<String> names = new HashSet<>();
        for (DependencySpec dependency : dependencies) {
            String normalizedName = dependency.name().toLowerCase(Locale.ROOT);
            if (request.language() == Language.PYTHON) {
                normalizedName = normalizedName.replaceAll("[-_.]+", "-");
            }
            if (!names.add(normalizedName)) {
                throw new SandboxRequestException("DUPLICATE_DEPENDENCY", "dependencies 包含重复包名: " + dependency.name());
            }
        }

        List<ArtifactFile> inputs = validateInputs(request, artifactInputs);
        if (request.outputFiles().size() > ExecutionLimits.MAX_FILES)
            throw new SandboxRequestException("INVALID_FILES", "交付文件声明超过限制");
        var outputFiles = new java.util.TreeSet<String>();
        for (String path : request.outputFiles()) {
            safeRelativePath(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
            if (request.profile() == ExecutionProfile.STANDARD
                    && (path.equals(ExecutionLimits.PUBLIC_RESULT_FILE) || path.startsWith(ExecutionLimits.PUBLIC_RESULT_FILE + "/")))
                throw new SandboxRequestException("INVALID_PUBLIC_RESULT_FILE", "result.json 是保留业务结果通道，不能声明为交付成果");
            if (!outputFiles.add(path)) throw new SandboxRequestException("INVALID_FILES", "交付文件声明重复");
        }
        if(artifactInputs==null||artifactInputs.size()>ExecutionLimits.MAX_FILES)throw new SandboxRequestException("INVALID_ARTIFACT_INPUTS","产物输入清单无效");
        var artifactPaths=new HashSet<String>();
        for(var file:artifactInputs) {
            String path=safeRelativePath(file.path());
            // 冻结 Skill 包可携带 examples、references、assets 等资源；必须与已验证输入完整匹配。
            if(!(inputs.contains(file) || path.startsWith("artifacts/") || path.startsWith("data/") || path.startsWith("scripts/"))
                    ||file.size()<0||file.sha256()==null||!SHA256.matcher(file.sha256()).matches()
                    ||!artifactPaths.add(path))
                throw new SandboxRequestException("INVALID_ARTIFACT_INPUTS","产物输入路径、大小或摘要无效");
        }
        long totalBytes=0;
        for(var file:artifactInputs) {
            totalBytes=Math.addExact(totalBytes,file.size());
            if(totalBytes>ExecutionLimits.TRANSFER_BYTES)
                throw new SandboxRequestException("INVALID_ARTIFACT_INPUTS","执行文件总量超过传输限制");
        }
        artifactInputs=artifactInputs.stream().sorted(Comparator.comparing(ArtifactFile::path)).toList();

        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("taskId", request.taskId());
        canonical.put("language", request.language().value());
        canonical.put("code", request.code());
        canonical.put("profile", request.profile().name());
        canonical.put("programTools", request.programTools());
        // 工作区身份属于可信宿主请求事实；重放不得换绑另一个会话目录。
        canonical.set("workspace", objectMapper.valueToTree(request.workspace()));
        canonical.put("deadlineAt", request.deadlineAt().toString());
        canonical.set("executionPolicy", objectMapper.valueToTree(request.executionPolicy()));
        if(request.profile()==ExecutionProfile.STANDARD)
            canonical.put("publicResultProtocol", "fixed-typed-result-v1");
        canonical.set("outputFiles", objectMapper.valueToTree(outputFiles));
        if (request.environmentVersion() == null) canonical.putNull("environmentVersion");
        else canonical.put("environmentVersion", request.environmentVersion());
        if (inputJson == null) canonical.putNull("inputJson");
        else canonical.set("inputJson", readStrictObject(inputJson));
        if (imageReference == null) canonical.putNull("imageReference");
        else canonical.put("imageReference", imageReference);
        if (imageId == null) canonical.putNull("imageId");
        else canonical.put("imageId", imageId);
        if (request.scriptPath() == null) {
            canonical.putNull("scriptPath");
        } else {
            canonical.put("scriptPath", request.scriptPath());
        }
        ArrayNode dependencyNodes = canonical.putArray("dependencies");
        dependencies.forEach(dependency -> dependencyNodes.addObject()
                .put("name", dependency.name())
                .put("version", dependency.version()));
        ArrayNode inputNodes = canonical.putArray("inputs");
        inputs.forEach(input -> inputNodes.addObject()
                .put("path", input.path())
                .put("sha256", input.sha256())
                .put("size", input.size()));
        ArrayNode artifactNodes=canonical.putArray("artifactInputs");
        artifactInputs.forEach(file->artifactNodes.addObject().put("path",file.path()).put("size",file.size()).put("sha256",file.sha256()));
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return new ValidatedRequest(request.taskId(), request.language(), request.code(), dependencies,
                    inputs, request.scriptPath(), request.programTools(), request.deadlineAt(),
                    HexFormat.of().formatHex(digest), request.executionPolicy(), artifactInputs,
                    List.copyOf(outputFiles), request.profile(),
                    request.environmentVersion(), inputJson, imageReference, imageId, request.workspace());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("无法生成执行请求摘要", serializationFailure);
        }
    }

    private List<ArtifactFile> validateInputs(ExecuteRequest request, List<ArtifactFile> manifest) {
        if(request.inputs().isEmpty()) {
            if(request.scriptPath()!=null)
                throw new SandboxRequestException("INVALID_SCRIPT_PATH","inline 执行不得传递 scriptPath");
            return List.of();
        }
        boolean shellResources=request.profile()==ExecutionProfile.WORKSPACE_SHELL_V1;
        String scriptPath=shellResources?null:safeRelativePath(request.scriptPath());
        if(!shellResources) {
            boolean matches=request.language()==Language.PYTHON?scriptPath.endsWith(".py")
                    :scriptPath.endsWith(".js")||scriptPath.endsWith(".mjs")||scriptPath.endsWith(".cjs");
            if(!scriptPath.startsWith("scripts/")||!matches)
                throw new SandboxRequestException("INVALID_SCRIPT_PATH","脚本必须位于 scripts/ 且与语言匹配");
        }
        if(request.inputs().size()>ExecutionLimits.MAX_FILES)
            throw new SandboxRequestException("INVALID_FILES","脚本文件数超过限制");
        var paths=new HashSet<String>();
        for(var input:request.inputs()) {
            String path=safeRelativePath(input.path());
            if(!paths.add(path)||manifest==null||!manifest.contains(input))
                throw new SandboxRequestException("INPUT_INTEGRITY_MISMATCH","脚本元数据必须与传输清单一致");
            if(shellResources&&(path.equals("main.sh")||path.startsWith("main.sh/")))
                throw new SandboxRequestException("INVALID_SCRIPT_PATH","冻结资源不得覆盖工作区 Shell 入口");
            if(request.profile()==ExecutionProfile.WORKFLOW_SCRIPT_V1
                    &&(!path.startsWith("scripts/")||!(request.language()==Language.PYTHON?path.endsWith(".py"):path.endsWith(".js"))))
                throw new SandboxRequestException("INVALID_WORKFLOW_RESOURCE","Workflow 仅接受语言匹配的 scripts/ 资源");
        }
        if(!shellResources&&!paths.contains(scriptPath))throw new SandboxRequestException("SCRIPT_NOT_FOUND","脚本不在冻结清单中");
        return request.inputs().stream().sorted(Comparator.comparing(ArtifactFile::path)).toList();
    }

    private String validateProfileBoundary(ExecuteRequest request, List<ArtifactFile> artifactInputs) {
        if (request.profile() == ExecutionProfile.WORKSPACE_SHELL_V1) {
            if (request.language() != Language.SHELL || request.workspace() == null || request.code().isBlank()
                    || request.programTools() || !request.dependencies().isEmpty()
                    || request.scriptPath() != null
                    || request.environmentVersion() != null || request.inputJson() != null) {
                throw new SandboxRequestException("INVALID_WORKSPACE_SHELL_PROFILE",
                        "WORKSPACE_SHELL_V1 必须绑定工作区，仅执行 Bash 命令，不接受依赖、programTools 或业务结果文件");
            }
            return null;
        }
        if (request.language() == Language.SHELL) {
            throw new SandboxRequestException("INVALID_EXECUTION_LANGUAGE",
                    "shell 仅允许 WORKSPACE_SHELL_V1 执行 profile");
        }
        if (request.profile() == ExecutionProfile.STANDARD) {
            if (request.environmentVersion() != null || request.inputJson() != null) {
                throw new SandboxRequestException("INVALID_STANDARD_PROFILE",
                        "STANDARD 不接受 environmentVersion 或 inputJson");
            }
            return null;
        }
        if (request.workspace() != null) {
            throw new SandboxRequestException("INVALID_WORKFLOW_SCRIPT_PROFILE",
                    "WORKFLOW_SCRIPT_V1 不允许绑定持久工作区");
        }
        if (!request.code().isEmpty() || !request.dependencies().isEmpty() || request.programTools()
                || !request.outputFiles().isEmpty()
                || artifactInputs == null || !new HashSet<>(artifactInputs).equals(new HashSet<>(request.inputs()))) {
            throw new SandboxRequestException("INVALID_WORKFLOW_SCRIPT_PROFILE",
                    "WORKFLOW_SCRIPT_V1 禁止 inline code、依赖、临时文件、产物和 programTools");
        }
        if (request.inputs().isEmpty() || request.inputs().size() > ExecutionLimits.MAX_FILES
                || request.scriptPath() == null || request.scriptPath().isBlank()) {
            throw new SandboxRequestException("INVALID_WORKFLOW_SCRIPT_PROFILE",
                    "WORKFLOW_SCRIPT_V1 必须提供冻结脚本资源和 scriptPath");
        }
        if (request.environmentVersion() == null || request.environmentVersion().isBlank()) {
            throw new SandboxRequestException("INVALID_ENVIRONMENT_VERSION", "Workflow environmentVersion 不能为空");
        }
        if (request.inputJson() == null
                || request.inputJson().getBytes(StandardCharsets.UTF_8).length > MAX_WORKFLOW_INPUT_JSON_BYTES) {
            throw new SandboxRequestException("INVALID_WORKFLOW_INPUT", "Workflow inputJson 必须存在且不超过 8MiB");
        }
        JsonNode parsed = readStrictObject(request.inputJson());
        return writeCanonicalJson(canonicalize(parsed));
    }

    private JsonNode readStrictObject(String value) {
        try {
            JsonNode parsed = strictJson.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw new SandboxRequestException("INVALID_WORKFLOW_INPUT", "Workflow inputJson 必须是单一 JSON 对象");
            }
            return parsed;
        } catch (SandboxRequestException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new SandboxRequestException("INVALID_WORKFLOW_INPUT", "Workflow inputJson 必须是严格 JSON 对象");
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode normalized = objectMapper.createObjectNode();
            var names = new java.util.ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> normalized.set(name, canonicalize(value.get(name))));
            return normalized;
        }
        if (value.isArray()) {
            ArrayNode normalized = objectMapper.createArrayNode();
            value.forEach(item -> normalized.add(canonicalize(item)));
            return normalized;
        }
        return value.deepCopy();
    }

    private String writeCanonicalJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new IllegalStateException("无法规范化 Workflow inputJson", impossible);
        }
    }

    private void requireStrictUtf8(byte[] content) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content));
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new SandboxRequestException("INVALID_WORKFLOW_RESOURCE", "Workflow 脚本资源必须是严格 UTF-8");
        }
    }

    private String safeRelativePath(String value) {
        if (value == null || value.isBlank() || value.equals(".") || value.contains(":")
                || value.indexOf('\\') >= 0 || value.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new SandboxRequestException("INVALID_INPUT_PATH", "输入路径必须是使用 / 分隔的安全相对路径");
        }
        try {
            Path path = Path.of(value);
            String normalized = path.normalize().toString().replace('\\', '/');
            if (path.isAbsolute() || !normalized.equals(value) || normalized.equals("..")
                    || normalized.startsWith("../") || normalized.startsWith("/")) {
                throw new SandboxRequestException("INVALID_INPUT_PATH", "输入路径不得绝对定位或穿越: " + safe(value));
            }
            return normalized;
        } catch (InvalidPathException invalid) {
            throw new SandboxRequestException("INVALID_INPUT_PATH", "非法输入路径: " + safe(value));
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    private DependencySpec validateDependency(Language language, DependencySpec dependency) {
        String name = dependency.name().trim();
        String version = dependency.version().trim();
        Pattern namePattern = language == Language.PYTHON ? PYTHON_NAME : NODE_NAME;
        Pattern versionPattern = language == Language.PYTHON ? PYTHON_VERSION : NODE_VERSION;
        if (!namePattern.matcher(name).matches()) {
            throw new SandboxRequestException("INVALID_DEPENDENCY_NAME", "非法依赖包名: " + safe(name));
        }
        if (!versionPattern.matcher(version).matches()) {
            throw new SandboxRequestException("INVALID_DEPENDENCY_VERSION", "依赖必须使用精确版本: " + safe(version));
        }
        return new DependencySpec(name, version);
    }

    private String safe(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, 80);
        return new String(bytes, 0, length, StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}]", "?");
    }

    public record ValidatedRequest(
            String taskId,
            Language language,
            String code,
            List<DependencySpec> dependencies,
            List<ArtifactFile> inputs,
            String scriptPath,
            boolean programTools,
            java.time.Instant deadlineAt,
            String digest,
            com.skillforge.execution.ExecutionPolicy executionPolicy,
            List<ArtifactFile> artifactInputs,
            List<String> outputFiles,
            ExecutionProfile profile,
            String environmentVersion,
            String inputJson,
            String imageReference,
            String imageId,
            WorkspaceBinding workspace
    ) {
        public String entryPath() {
            if (scriptPath != null) {
                return "/input/" + scriptPath;
            }
            return switch (language) {
                case PYTHON -> "/input/main.py";
                case NODE -> "/input/main.mjs";
                case SHELL -> "/input/main.sh";
            };
        }
    }

}
