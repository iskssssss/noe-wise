package com.skillforge.runtime.session;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;

/** 宿主使用的冻结 schema 校验入口；公开签名和继承关系均不暴露 Core 类型。 */
public final class SessionSchemaValidation {
    private SessionSchemaValidation() { }

    /** 按完整规范参数和原冻结 schema 校验；解析限额由 Session 根据输入长度构造。 */
    public static void validate(TypedPayload schema, TypedPayload payload) {
        int units = Math.max(1024, Math.max(schema.canonicalJson().length(), payload.canonicalJson().length()));
        SessionRuntimePayloads.validateAgainstSchema(schema, payload, new CodecLimits(units, units, 128));
    }
}
