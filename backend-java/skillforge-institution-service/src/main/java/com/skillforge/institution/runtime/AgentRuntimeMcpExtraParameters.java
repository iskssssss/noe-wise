package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.TypedPayload;
import java.util.Optional;

/** 将当前 Run 的原始聊天额外参数投影到 MCP 元数据，不读取模型生成的调用参数。 */
public final class AgentRuntimeMcpExtraParameters {
    /** 直接输入投影的固定解析器，不保存任何Run或模型参数。 */
    private static final ObjectMapper INBOUND_JSON = new ObjectMapper();
    /** 纯协议投影只消费调用边界已读取的不可变输入。 */
    private AgentRuntimeMcpExtraParameters() { }

    /** 新执行frame从已保存的原业务输入提取额外参数；不查旧source，不读取模型工具参数。 */
    public static Optional<TypedPayload> fromInbound(TypedPayload payload) {
        return fromInbound(payload, INBOUND_JSON);
    }

    /** 只投影原输入对象字段，不从字符串、数组或其他schema猜测额外上下文。 */
    private static Optional<TypedPayload> fromInbound(TypedPayload payload, ObjectMapper mapper) {
        if (!"input.v1".equals(payload.schemaId())) return Optional.empty();
        try {
            var value = mapper.readTree(payload.canonicalJson()).path("extraParameters");
            if (!value.isObject()) return Optional.empty();
            return Optional.of(new TypedPayload("application/json", mapper.writeValueAsString(value)));
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("agent_runtime_mcp_extra_parameters_invalid", invalid);
        }
    }

}
