package com.skillforge.runtime.session;

import com.skillforge.runtime.json.RuntimeJson;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.PayloadValidator;

/** 注册input-log产生的上下文正文；只验证有界规范字符串，不授予来源或指令信任。 */
public final class SessionPreparationContent implements PayloadValidator {
    public static final String SCHEMA = "skillforge.runtime.session.context-text.v1";

    static TypedPayload encode(String text) { return new TypedPayload(SCHEMA, RuntimeJson.writeCanonical(text)); }

    @Override public void validate(TypedPayload payload, CodecLimits limits) {
        if (payload == null || !SCHEMA.equals(payload.schemaId()) || !(RuntimeJson.parseCanonical(payload.canonicalJson(), limits) instanceof String)) {
            throw new IllegalArgumentException("session_preparation_content_invalid");
        }
    }
}
