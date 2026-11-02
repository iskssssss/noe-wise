package com.skillforge.runtime.api.port;

import java.nio.charset.StandardCharsets;
import java.util.List;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.media.ImageDetail;

/** BPE 词表的保守输入上界；压力估算与供应商实际 usage 分别维护。 */
public final class ModelTokenBounds {
    private static final long REQUEST_FRAMING = 256;
    private static final long MESSAGE_FRAMING = 64;
    private static final long TOOL_FRAMING = 128;
    private ModelTokenBounds() { }
    public static long textInputs(List<String> inputs) {
        long result = REQUEST_FRAMING;
        for (var text : inputs) result = Math.addExact(result, Math.addExact(MESSAGE_FRAMING, text.getBytes(StandardCharsets.UTF_8).length));
        return result;
    }
    public static long input(List<ModelPort.ModelMessage> messages,List<ModelPort.ModelTool> tools) {
        return input(messages, tools, ModelImagePolicy.textOnly());
    }
    /** 图片成本使用冻结规则；工具图片桥接文字采用独立的保守 byte 上界。 */
    public static long input(List<ModelPort.ModelMessage> messages,List<ModelPort.ModelTool> tools,
            ModelImagePolicy imagePolicy) {
        long result=REQUEST_FRAMING;
        for(var message:messages) {
            result=Math.addExact(result,Math.addExact(MESSAGE_FRAMING,
                    message.accountingText().getBytes(StandardCharsets.UTF_8).length));
            boolean images = false;
            for (var content : message.contents()) if (content instanceof ModelContent.Image image) {
                images = true;
                result = Math.addExact(result, imageTokens(image, imagePolicy));
            }
            if (images && message.role() == ModelPort.ModelRole.TOOL) {
                if (!imagePolicy.toolImages()) throw new IllegalArgumentException("model_tool_images_disabled");
                result = Math.addExact(result, Math.addExact(MESSAGE_FRAMING + REQUEST_FRAMING,
                        Math.addExact(message.textContent().getBytes(StandardCharsets.UTF_8).length,
                                message.toolCallId().orElseThrow().getBytes(StandardCharsets.UTF_8).length)));
            }
        }
        for(var tool:tools)result=Math.addExact(result,Math.addExact(TOOL_FRAMING,
                tool.accountingText().getBytes(StandardCharsets.UTF_8).length));
        return result;
    }
    /** AUTO 按 HIGH 保守计量；LOW 使用独立 base，尺寸为冻结准备后实际图片尺寸。 */
    public static long imageTokens(ModelContent.Image image, ModelImagePolicy policy) {
        var media = image.media();
        if (!policy.enabled() || media.byteLength() > policy.maxImageBytes()
                || media.width() > policy.maxDimension() || media.height() > policy.maxDimension())
            throw new IllegalArgumentException("model_image_policy_rejected");
        if (image.detail() == ImageDetail.LOW) return policy.baseTokens();
        long columns = ((long) media.width() + policy.tileSize() - 1) / policy.tileSize();
        long rows = ((long) media.height() + policy.tileSize() - 1) / policy.tileSize();
        return Math.addExact(policy.baseTokens(), Math.multiplyExact(Math.multiplyExact(columns, rows), policy.tileTokens()));
    }
}
