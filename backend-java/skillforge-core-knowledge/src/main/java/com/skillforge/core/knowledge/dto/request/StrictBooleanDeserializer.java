package com.skillforge.core.knowledge.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Jackson 标量布尔严格反序列化器；拒绝字符串/数字等隐式 coercion。 */
public final class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return switch (parser.currentToken()) {
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            default -> (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
        };
    }
}
