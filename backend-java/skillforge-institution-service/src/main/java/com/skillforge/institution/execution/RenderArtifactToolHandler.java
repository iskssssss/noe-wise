package com.skillforge.institution.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformMediaToolHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 只把固定渲染脚本交给既有隔离执行通道，不接受模型提供的代码或命令。 */
@Component
public final class RenderArtifactToolHandler implements PlatformMediaToolHandler {

    private static final Set<String> FIELDS = Set.of("artifact_id", "path", "format", "output_name");
    private static final Set<String> FORMATS = Set.of("pdf", "pages", "table");
    private final CodeExecutionToolHandler execution;
    private final ObjectMapper json;
    private final String renderer;

    public RenderArtifactToolHandler(CodeExecutionToolHandler execution, ObjectMapper json) {
        this.execution = java.util.Objects.requireNonNull(execution, "execution");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.renderer = readRenderer();
    }

    @Override
    public String toolName() {
        return "render_artifact";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, PlatformToolExecutionContext context)
            throws Exception {
        RenderRequest request;
        try {
            request = parse(arguments);
        } catch (IllegalArgumentException rejected) {
            return Map.of("success", false, "error", "render_artifact_arguments_rejected");
        }
        Map<String, Object> trusted = new LinkedHashMap<>();
        trusted.put("mode", "inline");
        trusted.put("language", "python");
        trusted.put("code", renderer + "\nmain(" + json.writeValueAsString(request.format()) + ", " + json.writeValueAsString(request.outputName()) + ")\n");
        trusted.put("dependencies", List.of());
        trusted.put("output_files", List.of(switch (request.format()) {
            case "pdf" -> request.outputName();
            case "pages" -> "pages/";
            default -> request.outputName();
        }));
        trusted.put("artifact_inputs", List.of(Map.of(
                "artifact_id", request.input().artifactId(), "path", request.input().path())));
        return withRenderFacts(execution.executeForRender(trusted, context), request);
    }

    /** 仅依据受信执行通道实际收集的文件说明渲染范围，不把渲染成功升级为视觉验收。 */
    private static Map<String,Object> withRenderFacts(Map<String,Object> result, RenderRequest request) {
        if (!Boolean.TRUE.equals(result.get("success")) || !"ready".equals(result.get("delivery_status"))
                || !(result.get("public_presentation") instanceof ProcessPresentation presentation)
                || !(result.get("artifacts") instanceof List<?> artifacts)) return result;
        var paths = new java.util.LinkedHashSet<String>();
        for (Object entry : artifacts) {
            if (!(entry instanceof Map<?,?> artifact) || !(artifact.get("id") instanceof String id) || id.isBlank()
                    || !(artifact.get("paths") instanceof List<?> files)) continue;
            for (Object file : files) if (file instanceof String path
                    && ("pages".equals(request.format()) ? path.matches("pages/page-[0-9]+\\.png")
                    : path.equals(request.outputName()))) paths.add(path);
        }
        if (paths.isEmpty() || presentation.references().isEmpty()) return result;
        String generated = switch (request.format()) {
            case "pages" -> "渲染已完成，已生成并收集 " + paths.size()
                    + " 张候选页图，文件引用见本次结果附件。转换过程另生成了中间 PDF，该 PDF 未作为本次候选附件收集。";
            case "pdf" -> "渲染已完成，已生成并收集候选 PDF，文件引用见本次结果附件。";
            default -> "表格转换已完成，已生成并收集候选 HTML，文件引用见本次结果附件。";
        };
        String facts = generated + "本次工具未执行视觉审阅、元素重叠检测或页面安全区检查。候选文件生成不代表已选为最终交付或已完成发布。";
        var blocks = new ArrayList<>(presentation.results());
        blocks.add(new ProcessPresentation.Text(ProcessPresentation.Text.PLAIN, facts));
        var enriched = new LinkedHashMap<>(result);
        enriched.put("public_presentation", new ProcessPresentation(presentation.title(), presentation.summary(),
                blocks, presentation.references()));
        var content = new ArrayList<Object>();
        if (result.get("content") instanceof List<?> original) content.addAll(original);
        content.add(Map.of("type", "text", "text", facts));
        enriched.put("content", List.copyOf(content));
        return enriched;
    }

    private static RenderRequest parse(Map<String, Object> arguments) {
        if (arguments == null || !FIELDS.containsAll(arguments.keySet()) || !arguments.keySet().containsAll(Set.of("artifact_id", "path", "format"))) {
            throw new IllegalArgumentException("render artifact arguments are invalid");
        }
        Object rawFormat = arguments.get("format");
        if (!(rawFormat instanceof String format) || !FORMATS.contains(format)) {
            throw new IllegalArgumentException("render artifact format is invalid");
        }
        try {
            return new RenderRequest(new ArtifactInput(requireText(arguments.get("artifact_id")),
                    requireText(arguments.get("path"))), format, outputName(arguments,format));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("render artifact input is invalid", invalid);
        }
    }

    private static String outputName(Map<String,Object> arguments,String format) {
        if("pages".equals(format)) {
            if(arguments.containsKey("output_name"))throw new IllegalArgumentException("page collection has no single output name");
            return "";
        }
        String extension="pdf".equals(format)?"pdf":"html";
        if(!arguments.containsKey("output_name"))return "pdf".equals(format)?"rendered.pdf":"index.html";
        String name=requireText(arguments.get("output_name"));
        String safe=com.skillforge.institution.artifact.DeliverableFilename.validateFilename(name);
        if(!safe.equals(name) || !name.toLowerCase(java.util.Locale.ROOT).endsWith("."+extension))
            throw new IllegalArgumentException("output filename must be canonical with matching extension");
        return safe;
    }

    private static String requireText(Object value) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.trim())) {
            throw new IllegalArgumentException("render artifact input is invalid");
        }
        return text;
    }

    private static String readRenderer() {
        try (InputStream input = new ClassPathResource("renderer/render_artifact.py").getInputStream()) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取受信产物渲染脚本", failure);
        }
    }

    private record RenderRequest(ArtifactInput input, String format, String outputName) {
    }
}
