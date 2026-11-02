package com.skillforge.core.workflow;

import com.skillforge.core.workflow.execution.WorkflowDefinitionContract;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** DefinitionVersion 脚本资源的规范化、发布冻结与导出视图。 */
public final class WorkflowDefinitionResources {
    private WorkflowDefinitionResources() {}

    /** 导出文件保持原路径、UTF-8 字节正文与已核验摘要。 */
    public record ExportFile(String path, String content, String sha256) {}

    /**
     * 规范化完整 definition 中的资源快照。
     *
     * <p>共享契约先核验路径、引用、Base64、摘要与预算；这里按路径排序并重建字段，
     * 确保草稿、发布版本和导出 YAML 使用同一份稳定表示。
     */
    public static Map<String, Object> freeze(Map<String, Object> definition) {
        Objects.requireNonNull(definition, "definition");
        WorkflowDefinitionContract.validateScriptResources(definition);
        var resources = WorkflowDefinitionContract.resources(definition);
        List<Map<String, Object>> frozen = resources.values().stream()
                .sorted(Comparator.comparing(WorkflowDefinitionContract.Resource::path))
                .map(resource -> {
                    byte[] content = Base64.getDecoder().decode(resource.contentBase64());
                    utf8(content, resource.path());
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("path", resource.path());
                    value.put("content_base64", resource.contentBase64());
                    value.put("sha256", resource.sha256());
                    return Collections.unmodifiableMap(new LinkedHashMap<>(value));
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>(definition);
        if (definition.containsKey("resources")) result.put("resources", frozen);
        return result;
    }

    /** 将冻结资源还原为 Builder 导出包中的原路径文本文件。 */
    public static List<ExportFile> exportFiles(Map<String, Object> definition) {
        Map<String, Object> frozen = freeze(definition);
        List<ExportFile> files = new ArrayList<>();
        WorkflowDefinitionContract.resources(frozen).values().stream()
                .sorted(Comparator.comparing(WorkflowDefinitionContract.Resource::path))
                .forEach(resource -> {
                    byte[] content = Base64.getDecoder().decode(resource.contentBase64());
                    files.add(new ExportFile(
                            resource.path(), utf8(content, resource.path()), resource.sha256()));
                });
        return List.copyOf(files);
    }

    private static String utf8(byte[] content, String path) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException rejected) {
            throw new IllegalArgumentException(
                    "脚本资源必须是 UTF-8 文本: " + path, rejected);
        }
    }
}
