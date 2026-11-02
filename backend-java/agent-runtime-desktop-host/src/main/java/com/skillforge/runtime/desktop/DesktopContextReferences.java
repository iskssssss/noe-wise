package com.skillforge.runtime.desktop;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionContextViews;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 只读本 Run 实际保存的上下文材料，不重新派发原能力。 */
final class DesktopContextReferences {
    private static final int MAX_PAGE_CHARACTERS = 8000;
    private DesktopContextReferences() { }

    static DesktopTools.Result read(ExecutionStore store, AgentExecutionCodec codec, ExecutionStore.Run run,
                                    TypedPayload arguments, BooleanSupplier cancelled) {
        var args = DesktopJson.parse(arguments.canonicalJson());
        var idNode = args.get("source_event_id");
        if (idNode == null || !idNode.isTextual() || idNode.textValue().isBlank() || idNode.textValue().length() > 512)
            return DesktopTools.failure("INVALID_ARGUMENT", "Invalid context reference identity.");
        String id = idNode.textValue();
        var offsetNode = args.path("offset"); var limitNode = args.path("limit");
        if ((!offsetNode.isMissingNode() && (!offsetNode.isIntegralNumber() || !offsetNode.canConvertToInt() || offsetNode.intValue() < 0))
                || (!limitNode.isMissingNode() && (!limitNode.isIntegralNumber() || !limitNode.canConvertToInt() || limitNode.intValue() < 1 || limitNode.intValue() > MAX_PAGE_CHARACTERS)))
            return DesktopTools.failure("INVALID_ARGUMENT", "Invalid context reference page.");
        if (cancelled.getAsBoolean()) return DesktopTools.failure("CANCELLED_BEFORE_DISPATCH", "The operation was cancelled.");
        var saved = store.event(run.identity().runRef(), new EventId(id)).orElse(null);
        if (saved == null || !saved.run().equals(run.identity().runRef())
                || !AgentExecutionContextViews.EVENT.equals(saved.event().type())
                || !AgentExecutionContextViews.SCHEMA.equals(saved.event().data().schemaId())
                || !id.equals("execution-context-material:" + AgentExecutionCodec.digest(saved.event().data().canonicalJson())))
            return DesktopTools.failure("CONTEXT_REFERENCE_UNAVAILABLE", "Context reference is unavailable.");
        com.fasterxml.jackson.databind.JsonNode body;
        try { body = DesktopJson.parse(saved.event().data().canonicalJson()); }
        catch (IllegalArgumentException invalid) { return DesktopTools.failure("CONTEXT_REFERENCE_UNAVAILABLE", "Context reference is unavailable."); }
        var fields = new HashSet<String>(); body.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("sourceContext", "messages")) || !body.path("sourceContext").isTextual()
                || !body.path("messages").isObject()) return DesktopTools.failure("CONTEXT_REFERENCE_UNAVAILABLE", "Context reference is unavailable.");
        var source = new ResultRef(body.path("sourceContext").textValue());
        var material = store.material(run.identity().runRef().tenantId(), source).orElse(null);
        if (material == null || !source.equals(AgentExecutionCodec.reference(material.content())))
            return DesktopTools.failure("CONTEXT_REFERENCE_UNAVAILABLE", "Context reference is unavailable.");
        var data = body.path("messages");
        if (!data.path("schemaId").isTextual() || !data.path("canonicalJson").isTextual())
            return DesktopTools.failure("CONTEXT_REFERENCE_UNAVAILABLE", "Context reference is unavailable.");
        var payload = new TypedPayload(data.path("schemaId").textValue(), data.path("canonicalJson").textValue());
        try { codec.decodeInput(payload); }
        catch (IllegalArgumentException invalid) { return DesktopTools.failure("CONTEXT_REFERENCE_UNAVAILABLE", "Context reference is unavailable."); }
        String content = payload.canonicalJson(); int size = content.codePointCount(0, content.length());
        int offset = offsetNode.isMissingNode() ? 0 : offsetNode.intValue();
        int limit = limitNode.isMissingNode() ? MAX_PAGE_CHARACTERS : limitNode.intValue();
        if (offset > size) return DesktopTools.failure("INVALID_ARGUMENT", "Context offset is outside the source.");
        int endOffset = (int) Math.min((long) size, (long) offset + limit);
        int begin = content.offsetByCodePoints(0, offset), end = content.offsetByCodePoints(0, endOffset);
        if (cancelled.getAsBoolean()) return DesktopTools.failure("CANCELLED_BEFORE_DISPATCH", "The operation was cancelled.");
        return DesktopTools.success(DesktopTools.object().put("source_event_id", id).put("offset", offset).put("next_offset", endOffset)
                .put("end", endOffset == size).put("source_complete", true).put("content", content.substring(begin, end)));
    }
}
