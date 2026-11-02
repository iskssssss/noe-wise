package com.skillforge.institution.artifact;

import com.skillforge.common.context.TenantContext;
import com.skillforge.common.time.BusinessTime;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 仅列出可信执行会话的有效产物引用，供模型选择后续只读输入；不开放文件正文或访问票据。 */
@Component
public class ArtifactListToolHandler implements PlatformToolHandler {
    private static final Set<String> PARAMETERS = Set.of("offset", "limit");
    private final ArtifactStorage storage;
    private final DeliverableService deliverables;

    public ArtifactListToolHandler(ArtifactStorage storage,DeliverableService deliverables) {
        this.storage = storage; this.deliverables=deliverables;
    }

    @Override public String toolName() { return "list_artifacts"; }

    @Override public Map<String,Object> execute(Map<String,Object> args, PlatformToolExecutionContext context) {
        if(args == null || !PARAMETERS.containsAll(args.keySet()))
            throw new IllegalArgumentException("list_artifacts_arguments_rejected");
        int offset = integer(args, "offset", 0, 0, 10000);
        int limit = integer(args, "limit", 30, 1, 100);
        var owner = context.run().owner();
        String tenant = owner.sessionRef().tenantId().value();
        String session = owner.sessionRef().sessionId();
        String previous = TenantContext.getTenantId();
        TenantContext.setTenantId(tenant);
        try {
            var page=storage.listForTool(tenant,session,offset,limit);
            List<Map<String,Object>> items=page.artifacts().stream().map(ArtifactListToolHandler::reference).toList();
            return Map.of("success", true, "artifacts", items, "total", page.total(),
                    "offset", offset, "has_more", (long) offset + items.size() < page.total(),
                    "current_deliverables",deliverables.currentOutputs(context.run()));
        } finally {
            if(previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    private static Map<String,Object> reference(ArtifactMetadata artifact) {
        var files = artifact.files().stream().map(file -> Map.<String,Object>of(
                "file_id", artifact.id()+":"+file.path(), "path", file.path(), "size", file.size(), "sha256", file.sha256())).toList();
        var inputs = artifact.artifactInputs().stream().map(input -> Map.<String,Object>of(
                "artifact_id", input.artifactId(), "path", input.path(),
                "size", input.size(), "sha256", input.sha256())).toList();
        return Map.of("id", artifact.id(), "name", artifact.name(), "type", artifact.type(),
                "run_id", artifact.runId(), "expires_at", BusinessTime.format(artifact.expiresAt()),
                "files", files, "artifact_inputs", inputs);
    }

    private static int integer(Map<String,Object> args, String name, int fallback, int minimum, int maximum) {
        if(!args.containsKey(name)) return fallback;
        Object raw = args.get(name);
        if(!(raw instanceof Number number)) throw new IllegalArgumentException("list_artifacts_arguments_rejected");
        try {
            int value = new BigDecimal(number.toString()).intValueExact();
            if(value < minimum || value > maximum) throw new IllegalArgumentException("list_artifacts_arguments_rejected");
            return value;
        } catch(ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("list_artifacts_arguments_rejected", invalid);
        }
    }
}
