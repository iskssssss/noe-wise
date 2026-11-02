package com.skillforge.core.agent.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 保存并发布的原请求回执；查询此对象不会重新执行写入。 */
public record AgentSaveAndPublishResponse(
        @JsonProperty("request_id") UUID requestId,
        @JsonProperty("agent_id") @Schema(type="string",pattern="[1-9][0-9]*")
        @JsonSerialize(using=ToStringSerializer.class) Long agentId,
        @JsonProperty("publication_id") @Schema(type="string",pattern="[1-9][0-9]*")
        @JsonSerialize(using=ToStringSerializer.class) Long publicationId,
        int revision,
        @JsonProperty("published_at") Instant publishedAt) {
}
