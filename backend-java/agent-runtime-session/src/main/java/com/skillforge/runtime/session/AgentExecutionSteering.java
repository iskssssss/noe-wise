package com.skillforge.runtime.session;

import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.json.CodecLimits;
import java.util.Objects;

/** 原Run补充输入的纯协议；不借用人类等待响应，不改变原任务身份或重新执行旧动作。 */
public final class AgentExecutionSteering {
    public static final String SCHEMA = "skillforge.runtime.execution.supplemental-input.v1";
    private final AgentExecutionCodec codec;

    public AgentExecutionSteering(CodecLimits limits) { codec = new AgentExecutionCodec(Objects.requireNonNull(limits)); }

    /** input由已认证入口稳定生成；正文始终是USER内容，不能改写SYSTEM或原模型配对。 */
    public record Input(InputId input, String text) {
        public Input {
            Objects.requireNonNull(input, "input");
            if (input.value().length() > 256 || text == null || text.isBlank() || text.length() > 8192) throw invalid();
        }
        @Override public String toString() { return "ExecutionSupplementalInput[REDACTED]"; }
    }

    /** 固定字段编码同时受主链材料大小、节点和深度限制。 */
    public TypedPayload encode(InputId input, String text) {
        var accepted = new Input(input, text); var value = AgentExecutionCodec.object();
        value.put("inputId", accepted.input().value()); value.put("text", accepted.text()); return codec.encode(SCHEMA, value);
    }

    /** 只恢复已保存的精确原文；不从控制请求补齐或生成旧材料。 */
    public Input decode(TypedPayload payload) {
        var value = codec.parse(payload, SCHEMA); AgentExecutionCodec.fields(value, "inputId", "text");
        var input = new Input(new InputId(AgentExecutionCodec.string(value, "inputId")), AgentExecutionCodec.string(value, "text"));
        if (!encode(input.input(), input.text()).equals(payload)) throw invalid();
        return input;
    }

    /** 同一补充输入只产生一个稳定消息身份，活跃采纳与恢复共用原payload。 */
    public ModelMessage message(InputId expected, TypedPayload payload) {
        var input = decode(payload);
        if (!input.input().equals(expected)) throw invalid();
        return new ModelMessage(messageId(input.input()), ModelRole.USER, input.text());
    }

    public static String messageId(InputId input) { return "execution-supplement:" + Objects.requireNonNull(input).value(); }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_supplemental_input_invalid"); }
}
