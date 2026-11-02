package com.skillforge.runtime.starter;

import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeCall;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolCall;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.PayloadValidator;
import com.skillforge.runtime.session.spi.SessionCapabilities;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** 把宿主 schema 注册表接到 HTTP 适配器，不提供默认允许。 */
final class AgentRuntimePayloads {
    private final PayloadValidator schemas;
    private final CodecLimits limits;

    AgentRuntimePayloads(PayloadValidator schemas, CodecLimits limits) {
        this.schemas = Objects.requireNonNull(schemas);
        this.limits = Objects.requireNonNull(limits);
    }

    BiPredicate<ModelCall, TypedPayload> modelOutputs() {
        return (call, payload) -> {
            Objects.requireNonNull(call);
            return allowed(payload);
        };
    }

    Predicate<ToolCall> toolInputs() {
        return call -> {
            Objects.requireNonNull(call);
            try {
                if (!allowed(call.arguments()) || !allowed(call.target().inputSchema())) { return false; }
                SessionRuntimePayloads.validateAgainstSchema(call.target().inputSchema(), call.arguments(), limits);
                return true;
            } catch (RuntimeException rejected) { return false; }
        };
    }

    BiPredicate<ToolCall, TypedPayload> toolOutputs() {
        return (call, payload) -> {
            Objects.requireNonNull(call);
            try {
                if (!allowed(payload) || !allowed(call.target().outputSchema())) { return false; }
                SessionRuntimePayloads.validateToolOutput(call.target().outputSchema(), payload, limits);
                return true;
            } catch (RuntimeException rejected) { return false; }
        };
    }

    Predicate<EvidenceBundle> knowledgeOutputs() {
        return bundle -> {
            Objects.requireNonNull(bundle);
            return bundle.items().stream().allMatch(item -> allowed(item.content()));
        };
    }

    static Predicate<ToolCall> toolMembership(SessionToolDirectory tools) {
        Objects.requireNonNull(tools);
        return call -> {
            try {
                return tools.requireCurrent(call.context().callContext().runIdentity(),
                        call.target()).binding().equals(call.binding());
            } catch (RuntimeException rejected) {
                return false;
            }
        };
    }

    TypedPayload safeToolOutput(ToolCall call,TypedPayload payload){
        Objects.requireNonNull(call);Objects.requireNonNull(payload);
        if(!allowed(payload)||!allowed(call.target().outputSchema()))
            throw new IllegalArgumentException("agent_runtime_tool_output_rejected");
        return SessionRuntimePayloads.safeToolOutput(call.target().outputSchema(),payload,
                call.target().sensitiveFields(),limits);
    }

    static Predicate<com.skillforge.runtime.api.port.ToolPortal.ToolContinuationCall>
            toolContinuationMembership(SessionToolDirectory tools) {
        Objects.requireNonNull(tools);
        return call -> {
            try {
                return tools.requireCurrent(call.context().callContext().runIdentity(),
                        call.target()).binding().equals(call.binding());
            } catch (RuntimeException rejected) {
                return false;
            }
        };
    }

    static Predicate<KnowledgeCall> knowledgeMembership(SessionCapabilities capabilities) {
        Objects.requireNonNull(capabilities);
        return call -> {
            try {
                capabilities.requireKnowledge(call.context().callContext().runIdentity().owner(),
                        call.snapshot(), call.target());
                return true;
            } catch (RuntimeException rejected) {
                return false;
            }
        };
    }

    private boolean allowed(TypedPayload payload) {
        try {
            schemas.validate(payload, limits);
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }
}
