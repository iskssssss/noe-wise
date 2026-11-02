package com.skillforge.core.shared.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** 公开字符串身份拒绝数字和布尔隐式转型，避免调用端精度丢失后被当作有效身份。 */
public final class StrictStringDeserializer extends JsonDeserializer<String> {
    @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING))
            return (String) context.handleUnexpectedToken(String.class, parser);
        return parser.getText();
    }
}
