package com.skillforge.runtime.core;

import com.skillforge.runtime.api.port.ModelPort.AssistantTurn;
import com.skillforge.runtime.api.port.ModelPort.AssistantContent;
import java.util.List;
import java.util.Optional;

/** 原生回合事实在私有结果与冻结模型结果中使用同一封闭编码。 */
final class AssistantTurnCodec {
    private AssistantTurnCodec() { }

    /** 缺失事实显式编码为 null；不得从规范动作重建供应商正文。 */
    static CanonicalJson.Value encode(Optional<AssistantTurn> value) {
        return value.<CanonicalJson.Value>map(turn -> CanonicalJson.object(List.of(
                new CanonicalJson.Field("contents", CanonicalJson.array(
                        turn.contents().stream().map(AssistantTurnCodec::block).toList())))))
                .orElseGet(CanonicalJson::nil);
    }

    /** 严格拒绝缺失或额外字段；枚举不接受未知或历史别名。 */
    static Optional<AssistantTurn> decode(CanonicalJson.Value value) {
        if (value.isNull()) return Optional.empty();
        value.requireFields("contents");
        return Optional.of(new AssistantTurn(value.field("contents").arrayValue().stream()
                .map(AssistantTurnCodec::block).toList()));
    }

    private static CanonicalJson.Value block(AssistantContent value) {
        return switch (value) {
            case AssistantContent.Text text -> CanonicalJson.object(List.of(
                    new CanonicalJson.Field("type", CanonicalJson.string("text")),
                    new CanonicalJson.Field("text", CanonicalJson.string(text.text()))));
            case AssistantContent.ToolCall call -> CanonicalJson.object(List.of(
                    new CanonicalJson.Field("type", CanonicalJson.string("tool_call")),
                    new CanonicalJson.Field("callId", CanonicalJson.string(call.callId())),
                    new CanonicalJson.Field("wireName", CanonicalJson.string(call.wireName())),
                    new CanonicalJson.Field("argumentsJson", CanonicalJson.string(call.argumentsJson()))));
        };
    }

    private static AssistantContent block(CanonicalJson.Value value) {
        return switch (value.field("type").stringValue()) {
            case "text" -> {
                value.requireFields("type", "text");
                yield new AssistantContent.Text(value.field("text").stringValue());
            }
            case "tool_call" -> {
                value.requireFields("type", "callId", "wireName", "argumentsJson");
                yield new AssistantContent.ToolCall(value.field("callId").stringValue(),
                        value.field("wireName").stringValue(), value.field("argumentsJson").stringValue());
            }
            default -> throw new IllegalArgumentException("invalid assistant content type");
        };
    }
}
