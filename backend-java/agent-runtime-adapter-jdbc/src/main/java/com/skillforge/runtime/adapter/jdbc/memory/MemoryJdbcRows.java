package com.skillforge.runtime.adapter.jdbc.memory;

/** MyBatis 专用扁平行；领域对象只从规范 fact_data 解码并与索引列交叉核对。 */
final class MemoryFactRow {
    public String tenantId;
    /** 仅治理历史读取填充，永久来源抑制的提交截止。 */
    public java.time.Instant governanceRecordedAt;
    public String factId;
    public long revision;
    public Long endUserId;
    public String groupBindingId;
    public Long subjectEndUserId;
    public String agentId;
    public String sessionId;
    public String scope;
    public String typeId;
    public String status;
    public String sourceSessionId;
    public long createdEpochSecond;
    public int createdNano;
    public long validFromEpochSecond;
    public int validFromNano;
    public Long validToEpochSecond;
    public Integer validToNano;
    public Long expiresEpochSecond;
    public Integer expiresNano;
    public String supersededFactId;
    /** 本事实内唯一不可变正文及来源所属的 revision；不跨事实或 scope 复用。 */
    public long contentRevision;
    /** contentRevision 正文的 SHA-256，用于拒绝损坏及错误引用。 */
    public String factDataDigest;
    /** 精确偏好提取语义摘要；仍须读取正文逐字段确认，不单凭摘要授予权限。 */
    public String extractionDigest;
    public byte[] factData;
    /** 仅 findCurrentBatch 填充，保留缺失项与重复请求的位置。 */
    public int requestOrdinal;
    /** 仅 listCurrent 填充：来源 Run 是否冻结过任务，以及是否属于本次查询任务。 */
    public boolean sourceTaskLinked;
    public boolean currentTaskLinked;
}

/** 批次中每项的事实与独立访问身份；不使用共享 owner 扩大读取范围。 */
record MemoryCurrentFactRequest(int requestOrdinal, String factId, Long endUserId,
        String agentId, String sessionId, String groupBindingId, String targetKind, String targetId) { }

final class MemoryOperationRow {
    public String tenantId;
    public String idempotencyKey;
    public String commandKind;
    public byte[] commandData;
    public String resultFactId;
    public long resultRevision;
}

final class MemorySourceRow {
    public String tenantId;
    public String factId;
    public long revision;
    public int sourceOrdinal;
    public String agentId;
    public String sourceTargetKind;
    public String sourceTargetId;
    public String sourceSessionId;
    public String sourceEventId;
}

final class MemoryTimeRow {
    public long epochSecond;
    public int nano;
}

/** 自动偏好提取追加来源的不可变结果引用；不改写原事实的来源集合。 */
final class MemoryExtractionEvidenceRow {
    public String factId;
    public long revision;
}

/** 搜索只返回有界候选身份与原量纲分，正文仍从权威revision精确还原。 */
final class MemorySearchHitRow { public String factId; public long revision; public double score; }
final class MemorySearchStatsRow { public long visibleDocumentCount; public long readyVectorCount; public long missingDocumentCount; }
final class MemorySearchDocumentRow {
    public String tenantId; public String factId; public long contentRevision;
    public String projectionVersion; public String analyzerVersion; public String textDigest; public String searchText;
    public String analyzedText; public String runData; public String identityData;
}
final class MemoryEmbeddingRow {
    public String tenantId; public String factId; public long contentRevision; public String projectionVersion; public String analyzerVersion;
    public String modelId; public long modelRevision; public int dimensions; public String textDigest; public String searchText;
    public String invocationId; public String state; public String vectorText; public String leaseToken; public java.time.Instant leaseUntil;
    public String runData; public String identityData; public String reasonCode;
}
final class MemoryQueryEmbeddingRow {
    public String tenantId; public String operationId; public String fingerprint; public String invocationId; public String state;
    public String vectorText; public String reasonCode;
}
record MemorySearchFilter(String tenant, Long endUserId, String agentId, String sessionId, String groupBindingId,
        String targetKind, String targetId, java.util.List<String> scopes, Long subjectEndUserId, java.util.List<String> types,
        long asOfSecond, int asOfNano, long evaluatedSecond, int evaluatedNano, String taskId, boolean recent,
        long recentAfterSecond, int recentAfterNano, String projectionVersion, String analyzerVersion,
        String queryLexemes, String modelId, Long modelRevision, Integer dimensions, String vectorText, double cosineMinimum, int candidateLimit) { }

final class MemoryRebuildRow { public String factId; public long revision; public String runData; public String identityData; public byte[] commandData; }

/** 已提交提取来源的原始业务时刻；不以任务创建时间替代来源时序。 */
final class MemorySourcePositionRow { public long epochSecond; public int nano; }
