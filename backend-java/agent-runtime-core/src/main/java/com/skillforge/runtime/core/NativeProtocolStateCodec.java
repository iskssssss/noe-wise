package com.skillforge.runtime.core;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.NativeProtocol;
import com.skillforge.runtime.api.port.ModelPort.NativeProtocolState;
import java.util.List;
import java.util.Optional;

/** 私有原生续接状态与持久请求、结果共用的精确编码。 */
final class NativeProtocolStateCodec {
    private NativeProtocolStateCodec() { }

    static CanonicalJson.Value encode(Optional<NativeProtocolState> state) {
        return state.<CanonicalJson.Value>map(value -> CanonicalJson.object(List.of(
                new CanonicalJson.Field("protocol", CanonicalJson.string(value.protocol().name())),
                new CanonicalJson.Field("dialectVersion", CanonicalJson.string(value.dialectVersion())),
                new CanonicalJson.Field("modelId", CanonicalJson.string(value.modelId())),
                new CanonicalJson.Field("items", CanonicalJson.array(value.items().stream().map(item -> CanonicalJson.object(List.of(
                        new CanonicalJson.Field("schemaId", CanonicalJson.string(item.schemaId())),
                        new CanonicalJson.Field("canonicalJson", CanonicalJson.string(item.canonicalJson()))))).toList())))))
                .orElseGet(CanonicalJson::nil);
    }

    static Optional<NativeProtocolState> decode(CanonicalJson.Value value) {
        if (value.isNull()) return Optional.empty();
        value.requireFields("protocol", "dialectVersion", "modelId", "items");
        var items = value.field("items").arrayValue().stream().map(item -> {
            item.requireFields("schemaId", "canonicalJson");
            return new TypedPayload(item.field("schemaId").stringValue(), item.field("canonicalJson").stringValue());
        }).toList();
        return Optional.of(new NativeProtocolState(NativeProtocol.valueOf(value.field("protocol").stringValue()),
                value.field("dialectVersion").stringValue(), value.field("modelId").stringValue(), items));
    }

    static void preflight(OutcomeEncodingBudget budget, Optional<NativeProtocolState> state, int depth) {
        if (state.isEmpty()) { budget.nil(depth); return; }
        var value = state.orElseThrow();
        budget.object(depth, "protocol", "dialectVersion", "modelId", "items");
        budget.string(depth + 1, value.protocol().name()); budget.string(depth + 1, value.dialectVersion());
        budget.string(depth + 1, value.modelId()); budget.array(depth + 1, value.items().size());
        value.items().forEach(item -> budget.payload(depth + 2, item));
    }
}
