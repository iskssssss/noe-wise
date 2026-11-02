package com.skillforge.institution.media;

import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.media.MediaRef;
import java.time.Instant;

/** 媒体不可变元数据；content 只在注册时临时携带，实际正文归租户内 SessionAttachmentBodyStore。 */
public record SessionMediaRow(String id, String tenantId, String sessionId, String productSessionId, String runId, String name,
        long size, String sha256, String mimeType, int width, int height,
        byte[] content, String sourceId, String transform, Instant createdAt) {
    MediaRef reference() {
        return new MediaRef(new SessionRef(new TenantId(tenantId), sessionId), id,
                sha256, mimeType, size, width, height);
    }
}
