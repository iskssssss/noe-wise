package com.skillforge.core.model.media;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 媒体任务固定列映射；原调用登记与供应商返回各自保留明确状态。 */
@Mapper
public interface ModelMediaJobMapper {
    Row find(@Param("owner") String owner, @Param("id") String id);
    Row operation(@Param("owner") String owner, @Param("operation") String operation);
    String bindTenant(@Param("tenant") String tenant);
    int insert(@Param("r") Row row);
    int update(@Param("r") Row row, @Param("expected") long expected);
    int stop(@Param("owner") String owner, @Param("id") String id);
    List<AssetRow> assets(@Param("job") String job);
    int insertAsset(@Param("a") AssetRow asset);
    int materialize(@Param("id") String id, @Param("mediaType") String mediaType,
            @Param("size") long size, @Param("sha256") String sha256, @Param("name") String name);
    int insertSource(@Param("job") String job, @Param("ordinal") int ordinal,
            @Param("kind") String kind, @Param("source") String encryptedSource);
    int insertLanguage(@Param("job") String job, @Param("ordinal") int ordinal, @Param("language") String language);
    int insertChannel(@Param("job") String job, @Param("ordinal") int ordinal, @Param("channel") int channel);

    final class Row {
        public String id, ownerId, tenantId, operationId, commandSha256, modelConfigId, providerId, serviceId,
                profileVersion, modelId, kind, endpoint, credentialEncrypted, status, providerRequestId, providerTaskId, reasonCode;
        public String inputText, negativePrompt, size, resolution, ratio, voice, language;
        public Integer outputCount, videoDurationSeconds, speakerCount;
        public Long seed;
        public Boolean promptExtend, watermark, audioEnabled, diarization, enableItn;
        public long modelRevision, lockVersion;
        public int timeoutSeconds;
        public boolean waitingStopped;
        public Instant createdAt, updatedAt;
        public Long inputTokens, outputTokens, imageCount, videoCount, characters;
        public BigDecimal durationSeconds;
        @Override public String toString() { return "MediaJobRow[id=" + id + ",status=" + status + "]"; }
    }
    final class AssetRow {
        public String id, jobId, kind, sourceEncrypted, mediaType, sha256, name;
        public Long sizeBytes;
        public boolean available;
        @Override public String toString() { return "MediaJobAssetRow[id=" + id + "]"; }
    }
}
