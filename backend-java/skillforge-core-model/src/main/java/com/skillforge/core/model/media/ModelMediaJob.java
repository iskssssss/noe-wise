package com.skillforge.core.model.media;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.AssetKind;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** 媒体任务的安全公开投影；状态属于供应商任务，停止本地等待单独表示。 */
public record ModelMediaJob(String id, String operationId, String modelConfigId, String modelId, long modelRevision,
        String kind, Status status, boolean waitingStopped, String providerTaskId, String reasonCode,
        Instant createdAt, Instant updatedAt, Usage usage, List<Asset> assets) {
    public ModelMediaJob { assets = List.copyOf(assets); }
    /** 身份由已鉴权宿主产生，不能接受公开请求体中的 owner。 */
    public record Owner(String tenantId, String actorId, String sessionScope) {
        public Owner(String tenantId, String actorId) { this(tenantId, actorId, null); }
        public Owner {
            if (actorId == null || !actorId.matches("[1-9][0-9]*")
                    || tenantId != null && (!tenantId.matches("[1-9][0-9]*") || sessionScope == null || sessionScope.isBlank())
                    || tenantId == null && sessionScope != null)
                throw new IllegalArgumentException("model_media_owner_invalid");
        }
        public String key() {
            if (tenantId == null) return "platform:" + actorId;
            try {
                String session = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(sessionScope.getBytes(StandardCharsets.UTF_8)));
                return "tenant:" + tenantId + ":" + actorId + ":session:" + session;
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    public record Create(String operationId, String id, ModelWrite payload, ModelMediaInput input) {
        @Override public String toString() { return "MediaJobCreate[REDACTED]"; }
    }
    public record Usage(Long inputTokens, Long outputTokens, Long imageCount, Long videoCount,
            Long characters, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal durationSeconds) { }
    public record Asset(String id, AssetKind kind, String mediaType, Long sizeBytes, String name, boolean available) { }
}
