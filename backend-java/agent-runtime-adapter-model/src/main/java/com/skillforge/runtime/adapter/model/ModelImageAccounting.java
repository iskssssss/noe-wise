package com.skillforge.runtime.adapter.model;

import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.media.ModelImagePolicy;

/** 与冻结图片变体一致的准备计量；媒体引用与 Base64 均不属于文本 token。 */
public final class ModelImageAccounting {
    private ModelImageAccounting() { }

    public static long tokens(ModelContent.Image image, ModelImagePolicy policy) {
        return com.skillforge.runtime.api.port.ModelTokenBounds.imageTokens(image, policy);
    }

    /** 工具图片桥接锚点是固定协议文字，计量与 Codec 必须共享同一事实。 */
    public static String toolImageAnchor(String callId) {
        return "Tool result images for tool_call_id: " + callId + "\n";
    }
}
