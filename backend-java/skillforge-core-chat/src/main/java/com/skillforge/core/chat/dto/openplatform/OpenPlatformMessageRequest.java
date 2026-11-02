package com.skillforge.core.chat.dto.openplatform;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.chat.contract.ChatInputReference;
import java.util.List;
import java.util.Map;

/** 结构化消息复用 Chat 的内容和公开材料选择器；context 仅承载业务参数。 */
public record OpenPlatformMessageRequest(List<ChatContent> content, Map<String,Object> context,
                                         List<ChatInputReference> references) {
    public OpenPlatformMessageRequest {
        content = content == null ? List.of() : List.copyOf(content);
        context = context == null ? Map.of() : Map.copyOf(context);
        references = references == null ? List.of() : List.copyOf(references);
    }
    /** 机构适配器可用纯文本构建规范 content，不提供另一套 HTTP 协议。 */
    public OpenPlatformMessageRequest(String text) { this(ChatContent.text(text), Map.of(), List.of()); }
    @JsonIgnore public String text() { return ChatContent.textValue(content); }
}
