package com.skillforge.core.chat.contract;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** 会话输入容量的公开查询结果；资源身份和运行预算不进入展示契约。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatLimitsResponse(Attachments attachments, References references, Selections selections) {
    public static ChatLimitsResponse defaults() {
        return new ChatLimitsResponse(new Attachments(ChatAttachmentLimits.MAX_FILE_BYTES,
                ChatAttachmentLimits.MAX_FILES_PER_MESSAGE, ChatAttachmentLimits.MAX_MESSAGE_BYTES,
                ChatAttachmentLimits.EXTENSIONS.stream().sorted().toList()),
                new References(ChatInputReference.MAX_REFERENCES, ChatInputReference.MAX_ARTIFACT_FILES),
                new Selections(ChatInputReference.MAX_ARTIFACT_FILES));
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Attachments(long maxFileBytes, int maxFiles, long maxTotalBytes, List<String> extensions) {
        public Attachments { extensions = List.copyOf(extensions); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record References(int maxItems, int maxFileSelectors) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Selections(int maxSelectableFiles) { }
}
