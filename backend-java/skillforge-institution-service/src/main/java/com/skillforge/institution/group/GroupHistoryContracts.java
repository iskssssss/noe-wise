package com.skillforge.institution.group;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.chat.dto.chat.SessionOutboxCursorResponse;
import com.skillforge.core.chat.message.ChatMessageRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 群公开历史与连续事件分别分页；两者均读取同一权威Session Outbox。 */
public final class GroupHistoryContracts {
    private GroupHistoryContracts() { }

    /** 消息按首次公开顺序正序显示；下一页继续当前固定水位，增量从eventCursor恢复。 */
    @Schema(name="GroupHistoryPage")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HistoryPage(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String sessionId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<ChatMessageRecord> items,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String nextCursor,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String eventCursor) {
        public HistoryPage { items=List.copyOf(items); }
    }

    /** 连续事件按Outbox序号递增，不领取、不确认，也不执行原输入。 */
    @Schema(name="GroupEventsPage")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventsPage(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String sessionId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<SessionOutboxCursorResponse.Event> items,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String nextCursor,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore) {
        public EventsPage { items=List.copyOf(items); }
    }
}
