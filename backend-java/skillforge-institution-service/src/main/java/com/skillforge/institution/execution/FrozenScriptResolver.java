package com.skillforge.institution.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.skillforge.core.capability.connector.CodeExecutionArgumentException;
import com.skillforge.core.capability.connector.CodeExecutionArguments;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.institution.artifact.ArtifactStorage;
import com.skillforge.institution.runtime.AgentRuntimeExecutionCatalog;
import com.skillforge.institution.runtime.AgentRuntimeExecutionWorker;
import com.skillforge.institution.runtime.AgentRuntimeProgramTools;
import com.skillforge.runtime.session.FrozenSkillResource;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 只读取当前执行槽已经提交的Skill激活状态，再逐字节校验原冻结目录中的包。 */
@Component
public class FrozenScriptResolver {
    private final ExecutionStore executions;
    private final AgentRuntimeExecutionCatalog catalogs;
    private final ObjectProvider<AgentRuntimeExecutionWorker> workers;
    private final ObjectProvider<AgentRuntimeProgramTools> programs;
    /** Worker延迟解析只用于打破宿主装配循环，不在工具边界重建执行上下文。 */
    public FrozenScriptResolver(ExecutionStore executions,AgentRuntimeExecutionCatalog catalogs,
            ObjectProvider<AgentRuntimeExecutionWorker> workers, ObjectProvider<AgentRuntimeProgramTools> programs) {
        this.executions=Objects.requireNonNull(executions);this.catalogs=Objects.requireNonNull(catalogs);
        this.workers=Objects.requireNonNull(workers);
        this.programs=Objects.requireNonNull(programs);
    }
    /** 从本轮已激活的精确包取得脚本；语言检查不改变整包冻结来源。 */
    public Script resolve(PlatformToolExecutionContext context,String handle,String path,String language) {
        var resources=resolveResources(context,handle);
        var script=resources.stream().filter(value->value.path().equals(path)).findFirst().orElseThrow();
        boolean matchingLanguage=switch(language) {
            case "python" -> path.endsWith(".py");
            case "node" -> path.endsWith(".js")||path.endsWith(".mjs")||path.endsWith(".cjs");
            default -> false;
        };
        if(!path.startsWith("scripts/")||!matchingLanguage||!"UTF8".equals(script.encoding()))
            throw new IllegalArgumentException("skill_script_language_mismatch");
        return new Script(script.content(),path,resources);
    }

    /** 工作区命令只能显式取得本轮已激活的整包，不根据模型路径读取宿主文件。 */
    public List<FrozenSkillResource> resolveResources(PlatformToolExecutionContext context,String handle) {
        var identity=context.run();
        var run=executions.load(identity.runRef()).orElseThrow(()->new IllegalStateException("skill_run_missing"));
        if(!run.identity().equals(identity))throw new IllegalStateException("skill_run_mismatch");
        identity=programs.getObject().resourceOrigin(identity);
        run=executions.load(identity.runRef()).orElseThrow(()->new IllegalStateException("skill_run_missing"));
        if(!run.identity().equals(identity))throw new IllegalStateException("skill_run_mismatch");
        var active=workers.getObject().activeAgentMaterials(identity,run.progress().context())
                .orElseThrow(()->new IllegalStateException("skill_active_execution_missing"));
        var original=executions.material(identity.runRef().tenantId(),AgentRuntimeExecutionCatalog.reference(identity.runRef()))
                .orElseThrow(()->new IllegalStateException("skill_frozen_catalog_missing"));
        var catalog=catalogs.read(original,identity);
        if(!catalog.skills().equals(active.configuration().skills())
                ||!catalog.availableCapabilities().equals(active.configuration().availableCapabilities()))
            throw new IllegalStateException("skill_frozen_catalog_mismatch");
        if(!active.skills().activation().activeHandles().contains(handle))throw new IllegalStateException("skill_not_activated");
        var mount=catalog.skills().stream().filter(value->value.handle().equals(handle)).findFirst()
                .orElseThrow(()->new IllegalStateException("skill_frozen_mount_missing"));
        return verifiedResources(mount.descriptor().resources().stream().map(resource->new FrozenSkillResource(
                resource.path(),resource.encoding(),resource.content(),resource.sha256(),resource.size())).toList());
    }

    /** 原始目录结构与每个文件字节必须完全匹配冻结来源，校验失败不得向沙箱发送请求。 */
    static List<FrozenSkillResource> verifiedResources(List<FrozenSkillResource> resources) {
        var paths=new HashSet<String>();
        for(var resource:resources) {
            ArtifactStorage.safePath(resource.path());
            if(!paths.add(resource.path()))throw new IllegalStateException("skill_resource_path_duplicate");
            byte[] content=bytes(resource);
            try {
                if(content.length!=resource.size()||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).equals(resource.sha256()))
                    throw new IllegalStateException("skill_resource_digest_mismatch");
            }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
        return List.copyOf(resources);
    }
    /** 冻结资源编码决定原始字节，不能把二进制模板当成 UTF-8 正文。 */
    private static byte[] bytes(FrozenSkillResource resource) {
        return switch(resource.encoding()){case "UTF8"->resource.content().getBytes(StandardCharsets.UTF_8);case "BASE64"->Base64.getDecoder().decode(resource.content());default->throw new IllegalArgumentException("skill_resource_encoding_rejected");};
    }
    public record Script(String code,String path,List<FrozenSkillResource> inputs) {
        private static final ObjectMapper JSON = new ObjectMapper();

        public Script {
            inputs = List.copyOf(inputs);
        }

        /** 冻结清单拥有依赖版本；调用只能省略或提供同名同版本子集。 */
        public List<CodeExecutionArguments.Dependency> dependencies(String language,
                List<CodeExecutionArguments.Dependency> declared) {
            var manifest = inputs.stream().filter(resource -> "manifest.json".equals(resource.path()))
                    .findFirst();
            if (manifest.isEmpty()) return List.copyOf(declared);
            List<CodeExecutionArguments.Dependency> frozen;
            try {
                var root = JSON.readValue(bytes(manifest.get()), DependencyManifest.class);
                if (root == null) throw CodeExecutionArgumentException.frozenManifestInvalid();
                if (root.dependencies() == null && root.alternateEntries() == null) return List.copyOf(declared);
                var entries = new LinkedHashMap<String, List<CodeExecutionArguments.Dependency>>();
                registerEntry(entries, root.entry(), root.dependencies());
                if (root.alternateEntries() != null) for (var alternate : root.alternateEntries()) {
                    if (alternate == null) throw CodeExecutionArgumentException.frozenManifestInvalid();
                    registerEntry(entries, new ScriptEntry(alternate.language(), alternate.scriptPath()), alternate.dependencies());
                }
                frozen = entries.get(language + ":" + path);
                if (frozen == null) throw CodeExecutionArgumentException.frozenManifestInvalid();
            } catch (IOException | IllegalArgumentException exception) {
                // 冻结包配置错误不能归因于模型，也不回显资源正文。
                throw CodeExecutionArgumentException.frozenManifestInvalid();
            }
            var versions = new HashMap<String, String>();
            frozen.forEach(dependency -> versions.put(dependency.name(), dependency.version()));
            for (var dependency : declared) {
                if (!versions.containsKey(dependency.name()))
                    throw CodeExecutionArgumentException.dependencyNotDeclared();
                if (!versions.get(dependency.name()).equals(dependency.version()))
                    throw CodeExecutionArgumentException.dependencyVersionMismatch();
            }
            return frozen;
        }

        private static void registerEntry(Map<String, List<CodeExecutionArguments.Dependency>> entries,
                ScriptEntry entry, List<CodeExecutionArguments.Dependency> dependencies) {
            if (entry == null || entry.scriptPath() == null || !entry.scriptPath().startsWith("scripts/")
                    || dependencies == null) throw CodeExecutionArgumentException.frozenManifestInvalid();
            ArtifactStorage.safePath(entry.scriptPath());
            boolean matching = switch (entry.language() == null ? "" : entry.language()) {
                case "python" -> entry.scriptPath().endsWith(".py");
                case "node" -> entry.scriptPath().endsWith(".js") || entry.scriptPath().endsWith(".mjs") || entry.scriptPath().endsWith(".cjs");
                default -> false;
            };
            if (!matching) throw CodeExecutionArgumentException.frozenManifestInvalid();
            var validated = CodeExecutionArguments.validatedDependencies(JSON.convertValue(dependencies, List.class), entry.language());
            if (entries.putIfAbsent(entry.language() + ":" + entry.scriptPath(), validated) != null)
                throw CodeExecutionArgumentException.frozenManifestInvalid();
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static final class DependencyManifest {
            @JsonProperty("entry")
            private ScriptEntry entry;
            @JsonSetter(value = "dependencies", nulls = Nulls.FAIL)
            private List<CodeExecutionArguments.Dependency> dependencies;
            @JsonSetter(value = "alternate_entries", nulls = Nulls.FAIL)
            private List<AlternateEntry> alternateEntries;

            ScriptEntry entry() { return entry; }
            List<CodeExecutionArguments.Dependency> dependencies() { return dependencies; }
            List<AlternateEntry> alternateEntries() { return alternateEntries; }
        }
        private record ScriptEntry(String language, @JsonProperty("script_path") String scriptPath) { }
        private record AlternateEntry(String language, @JsonProperty("script_path") String scriptPath,
                List<CodeExecutionArguments.Dependency> dependencies) { }
    }
}
