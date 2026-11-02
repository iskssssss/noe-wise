package com.skillforge.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelToolCall;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 原生模型历史只追加真实完整配对，保留工具正文与多模态引用。 */
class ModelConversationTest {
    /** 第二轮完整消息包含实际工具反馈、原模型说明和图片引用，结果乱序不改变调用顺序。 */
    @Test
    void appendsActualSafeFeedbackAndImageInOriginalCallOrder() {
        var history = List.of(new ModelMessage("input", ModelRole.USER, "生成模板并查看截图"));
        var assistant = assistant("model-one", List.of(call("render", "sf_template_render"), call("browser", "sf_browser")));
        var image = new ModelContent.Image(new MediaRef(new SessionRef(new TenantId("tenant"), "session"),
                "screenshot", "a".repeat(64), "image/png", 100, 10, 10), ImageDetail.AUTO);
        var rendered = result("render-result", "render", List.of(new ModelContent.Text("真实模板结果：<h1>标题</h1>")));
        var screenshot = result("browser-result", "browser", List.of(new ModelContent.Text("当前页面截图"), image));
        var next = ModelConversation.append(history, assistant, List.of(screenshot, rendered));
        assertEquals(List.of(history.getFirst(), assistant, rendered, screenshot), next);
        assertEquals("真实模板结果：<h1>标题</h1>", next.get(2).textContent());
        assertEquals(Optional.of("原模型推理"), next.get(1).reasoningContent());
        assertSame(image, next.getLast().contents().getLast());
        assertEquals(1, history.size());
        assertThrows(UnsupportedOperationException.class, () -> next.clear());
    }

    /** 未决、未知、错配和重复结果不能被补成完整模型历史。 */
    @Test
    void rejectsPartialMismatchedAndDuplicateResults() {
        var assistant = assistant("model-one", List.of(call("first", "sf_first"), call("second", "sf_second")));
        var first = result("first-result", "first", List.of(new ModelContent.Text("已保存结果")));
        var wrong = result("wrong-result", "wrong", List.of(new ModelContent.Text("错误结果")));
        assertThrows(IllegalArgumentException.class, () -> ModelConversation.append(List.of(), assistant, List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> ModelConversation.append(List.of(), assistant, List.of(first, wrong)));
        assertThrows(IllegalArgumentException.class, () -> ModelConversation.append(List.of(), assistant, List.of(first, first)));
    }

    /** 已提交回合不能重复插入；正文回复可以作为无工具回合追加。 */
    @Test
    void rejectsReusedMessageAndCallIdentitiesAcrossHistory() {
        var assistant = assistant("model-one", List.of(call("call-one", "sf_render")));
        var feedback = result("result-one", "call-one", List.of(new ModelContent.Text("结果")));
        var history = ModelConversation.append(List.of(), assistant, List.of(feedback));
        assertThrows(IllegalArgumentException.class, () -> ModelConversation.append(history, assistant, List.of(feedback)));
        assertThrows(IllegalArgumentException.class, () -> ModelConversation.append(history,
                assistant("model-two", List.of(call("call-one", "sf_render"))),
                List.of(result("result-two", "call-one", List.of(new ModelContent.Text("结果"))))));
        var response = new ModelMessage("response", ModelRole.ASSISTANT, "完成");
        assertEquals(response, ModelConversation.append(history, response, List.of()).getLast());
    }

    /** 调用保留原 wireName 与规范参数，不根据结果反推工具请求。 */
    private static ModelToolCall call(String id, String wireName) {
        return new ModelToolCall(id, wireName, new TypedPayload("application/json", "{\"title\":\"标题\"}"));
    }

    /** 原生模型回合的正文和 reasoning 原样进入下一轮。 */
    private static ModelMessage assistant(String id, List<ModelToolCall> calls) {
        return new ModelMessage(id, ModelRole.ASSISTANT, "模型进度说明", calls,
                Optional.empty(), Optional.of("原模型推理"));
    }

    /** 结果消息只接收已保存的安全正文和引用，不加载私有输出。 */
    private static ModelMessage result(String id, String callId, List<ModelContent> contents) {
        return new ModelMessage(id, ModelRole.TOOL, contents, List.of(), Optional.of(callId), Optional.empty());
    }
}
