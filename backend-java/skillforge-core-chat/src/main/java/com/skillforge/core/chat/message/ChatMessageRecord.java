package com.skillforge.core.chat.message;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import com.skillforge.core.chat.contract.ChatReferenceView;

/**
 * 会话消息结构化记录。
 *
 * @param messageId 消息幂等 ID
 * @param role      消息角色：user / assistant / system
 * @param content   消息正文
 * @param createdAt 消息创建时间
 * @param metadata  附加元数据（execution_id / plan_id / intent / card 等），可空
 */
public record ChatMessageRecord(
        String messageId,
        String role,
        String content,
        Instant createdAt,
        Map<String, Object> metadata,
        long messageVersion,
        List<ChatReferenceView> references) {
    public static final long INITIAL_VERSION=1;
    /** 历史 JSON 与实时正文使用相同顶层协议字段，值仍由单一公开投影元数据拥有。 */
    @com.fasterxml.jackson.annotation.JsonProperty("phase")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String phase() { return publicText("phase"); }

    @com.fasterxml.jackson.annotation.JsonProperty("state")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String state() { return publicText("state"); }

    private String publicText(String field) {
        return metadata != null && metadata.get(field) instanceof String value ? value : null;
    }
    public ChatMessageRecord {
        if (messageVersion <= 0) throw new IllegalArgumentException("message version must be positive");
        references = List.copyOf(references);
    }
    /** 不可变消息首次公开版本；正文与来源版本不混用。 */
    public ChatMessageRecord(String messageId,String role,String content,Instant createdAt,Map<String,Object> metadata) {
        this(messageId,role,content,createdAt,metadata,INITIAL_VERSION,List.of());
    }
}
