package com.skillforge.institution.artifact;

import java.time.Instant;

/** 产物仓储内部行；文件清单为不可变发布快照。 */
public record ArtifactRow(String id, String tenantId, String sessionId, String runId,
        String invocationId, String type, String name, long size, String sha256,
        String manifest, String inputsSha256, Instant createdAt, Instant expiresAt, String status) { }
