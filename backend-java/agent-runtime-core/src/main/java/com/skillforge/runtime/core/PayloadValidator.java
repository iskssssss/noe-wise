package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.TypedPayload;

/** codec 消费边界注入所属 registry；字段验证不证明来源授权或调用已持久化。 */
@FunctionalInterface
public interface PayloadValidator {
    /** 校验 schema/version、规范文本和业务结构；非法载荷拒绝，技术异常保留原始原因。 */
    void validate(TypedPayload payload, CodecLimits limits);
}
