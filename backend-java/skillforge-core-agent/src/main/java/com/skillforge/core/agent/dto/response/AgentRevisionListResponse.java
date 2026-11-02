package com.skillforge.core.agent.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** 智能体发布历史概要，详细配置通过修订详情读取。 */
public record AgentRevisionListResponse(int total, List<Item> items) {
    public record Item(@JsonProperty("revision_id") Long revisionId, int revision,
            @JsonProperty("published_at") Instant publishedAt, String name, boolean current) { }
}
