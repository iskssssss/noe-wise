package com.skillforge.core.model.media.realtime;

import java.math.BigDecimal;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 实时任务的固定列与单次音频发送登记；不保存第二份请求 JSON。 */
@Mapper
public interface ModelRealtimeMapper {
    String bindTenant(@Param("tenant") String tenant);
    Row operation(@Param("owner") String owner, @Param("operation") String operation);
    Row find(@Param("owner") String owner, @Param("id") String id);
    int insert(Row row);
    int started(@Param("id") String id, @Param("at") Instant at);
    int partial(@Param("id") String id, @Param("text") String text, @Param("at") Instant at);
    int claimAudio(@Param("owner") String owner, @Param("id") String id,
            @Param("sequence") long sequence, @Param("at") Instant at);
    int finish(@Param("owner") String owner, @Param("id") String id, @Param("at") Instant at);
    int complete(Row row);
    int asset(Row row);
    int assetUnavailable(@Param("id") String id, @Param("at") Instant at);

    /** 密文与连接字段仅供受信应用服务，不能作为管理 API 响应。 */
    final class Row {
        public String id, ownerId, tenantId, operationId, commandSha256, kind, modelConfigId;
        public long modelRevision;
        public String providerId, serviceId, modelId, endpoint, credentialEncrypted, providerTaskId;
        public int timeoutSeconds, sampleRateHz;
        public String language, inputText, voice, status, partialText, finalText, reasonCode;
        public boolean finishing;
        public long nextAudioSequence;
        public Instant createdAt, updatedAt, deadline;
        public Long inputTokens, outputTokens, characters;
        public BigDecimal durationSeconds;
        public String assetId, assetSourceEncrypted, assetMediaType, assetName, assetSha256;
        public Long assetSizeBytes;
        public boolean assetAvailable;
        @Override public String toString() { return "RealtimeSessionRow[id=" + id + ",status=" + status + "]"; }
    }
}
