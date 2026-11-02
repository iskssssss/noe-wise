package com.skillforge.core.shared.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** CAS 修订等整数协议字段只接受 JSON 整数，拒绝字符串及浮点数截断。 */
public final class StrictLongDeserializer extends JsonDeserializer<Long> {
    /** 溢出由 Jackson 数字读取拒绝，NULL 是否允许由所属请求契约验证。 */
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
        return parser.getLongValue();
    }
}
