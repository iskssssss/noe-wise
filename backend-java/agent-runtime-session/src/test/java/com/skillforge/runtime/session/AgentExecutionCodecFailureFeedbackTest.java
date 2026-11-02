package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.json.CodecLimits;
import org.junit.jupiter.api.Test;

class AgentExecutionCodecFailureFeedbackTest {
    private static final CodecLimits LIMITS = new CodecLimits(100_000, 10_000, 32);
    private static final AgentExecutionCodec CODEC = new AgentExecutionCodec(LIMITS);

    @Test
    void failedToolReceiptExplainsFailureWithoutClaimingItWasNotExecuted() {
        var result = feedback("mcp_tool_rejected");
        assertTrue(result.startsWith("工具调用失败。"));
        assertTrue(result.contains("结果引用：original-receipt"));
        assertTrue(result.contains("原因码：mcp_tool_rejected"));
        assertTrue(result.contains("失败不代表未执行"));
        assertTrue(result.contains("已有副作用"));
    }

    @Test
    void authenticationFailureHasActionableSafeGuidance() {
        var result = feedback("mcp_tool_authentication_required");
        assertTrue(result.contains("认证失败"));
        assertTrue(result.contains("管理员"));
        assertFalse(result.contains("尚未执行"));
    }

    @Test
    void argumentRejectionKeepsItsReceiptAndGuidesANewCorrectedRequestWithoutInventingZeroData() {
        var result=feedback("mcp_tool_arguments_invalid");
        assertTrue(result.startsWith("工具调用失败。"));
        assertTrue(result.contains("原因码：mcp_tool_arguments_invalid"));
        assertTrue(result.contains("结果引用：original-receipt"));
        assertTrue(result.contains("工具拒绝参数，未取得可用数据"));
        assertTrue(result.contains("不能当作零值或没有记录"));
        assertTrue(result.contains("真实 Schema 修正参数"));
        assertTrue(result.contains("修正后的不同参数发起新请求"));
        assertTrue(result.contains("不要原样重试"));
        assertFalse(result.contains("尚未执行"));
        assertFalse(result.contains("private-provider-secret"));
        assertThrows(IllegalArgumentException.class,()->CODEC.toolContents(new TypedPayload(AgentExecutionCodec.TOOL_FAILURE_SCHEMA,
                "{\"reasonCode\":\"mcp_tool_arguments_invalid\",\"resultRef\":\"original-receipt\",\"rawResult\":\"private-provider-secret\"}")));
    }

    @Test
    void onlyKnownPreDispatchReasonClaimsToolWasNotExecuted() {
        assertTrue(feedback("mcp_pre_dispatch_rejected").contains("尚未执行"));
        assertFalse(feedback("mcp_rpc_rejected").contains("尚未执行"));
    }

    @Test
    void unsafeExtraFieldsCannotEnterModelFailureProjection() {
        assertThrows(IllegalArgumentException.class, () -> CODEC.toolContents(new TypedPayload(
                AgentExecutionCodec.TOOL_FAILURE_SCHEMA,
                "{\"reasonCode\":\"failed\",\"resultRef\":\"original-receipt\",\"rawResult\":\"secret\"}")));
    }

    private static String feedback(String reason) {
        var original = new TypedPayload(AgentExecutionCodec.TOOL_FAILURE_SCHEMA,
                "{\"reasonCode\":\"" + reason + "\",\"resultRef\":\"original-receipt\"}");
        var text = (ModelContent.Text) CODEC.toolContents(original).getFirst();
        return text.text();
    }

    @Test
    void duplicateStructuredTextIsOmittedButAdditionalExplanationIsPreserved() {
        var payload = new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                "{\"structuredContent\":{\"count\":4},\"content\":[{\"type\":\"text\",\"text\":\"{\\\"count\\\":4}\"},{\"type\":\"text\",\"text\":\"仅覆盖当前有效记录\"}]}");
        var contents = CODEC.toolContents(payload);
        assertEquals(2, contents.size());
        assertEquals("{\"count\":4}", ((ModelContent.Text) contents.getFirst()).text());
        assertEquals("仅覆盖当前有效记录", ((ModelContent.Text) contents.getLast()).text());
    }

    @Test
    void differentJsonAndTextOnlyResultsAreNotDeduplicated() {
        var payload = new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                "{\"structuredContent\":{\"count\":4},\"content\":[{\"type\":\"text\",\"text\":\"{\\\"count\\\":5}\"}]}");
        assertEquals(2, CODEC.toolContents(payload).size());
        var textOnly = new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                "{\"content\":[{\"type\":\"text\",\"text\":\"same\"},{\"type\":\"text\",\"text\":\"same\"}]}");
        assertEquals(2, CODEC.toolContents(textOnly).size());
    }
}
