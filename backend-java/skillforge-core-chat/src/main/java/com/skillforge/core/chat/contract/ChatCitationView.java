package com.skillforge.core.chat.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 已实际引用的知识依据公开标记；只允许消息内锚点，不授予材料读取权限。
 * @param ordinal 当前回答中从1开始的依据次序
 * @param itemId 实际采用的EvidenceItem身份
 * @param sourceId 实际来源身份，不包含私有结果材料引用
 * @param sourceVersion 实际采用的来源版本
 * @param label 已经过公开检查的展示名称
 * @param citationId 当前 Run 已注入来源的短标识，仅用于本地锚点
 */
public record ChatCitationView(int ordinal, @JsonProperty("item_id") String itemId,
        @JsonProperty("source_id") String sourceId, @JsonProperty("source_version") String sourceVersion, String label,
        @JsonProperty("citation_id") String citationId) {
    /** 不能用空身份、未知版本或零次序伪造有效引用。 */
    public ChatCitationView {
        if (ordinal < 1 || itemId == null || itemId.isBlank() || sourceId == null || sourceId.isBlank()
                || sourceVersion == null || sourceVersion.isBlank() || label == null || label.isBlank()
                || citationId == null || !citationId.matches("k[0-9a-f]{16}"))
            throw new IllegalArgumentException("chat_citation_invalid");
    }
}
