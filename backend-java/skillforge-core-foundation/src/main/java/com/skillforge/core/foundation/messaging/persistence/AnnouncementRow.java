package com.skillforge.core.foundation.messaging.persistence;

import java.time.Instant;

/** 公告权威发布状态与唯一共享正文；发布后正文、格式及摘要不可变，收件关系只引用本身份。
 * @param bodyFormat 正文编码与媒体类型，固定为text/plain;charset=utf-8
 * @param bodyDigest 正文UTF-8字节的SHA-256摘要，不跨作用域复用
 * @param bodyCipher 公告唯一AES-GCM正文，终态全部引用退休后为NULL
 */
public record AnnouncementRow(String id, String scopeKey, String tenantId, String title, String bodyCipher,
        String audience, int recipients, String status, String deduplicationKey, String fingerprint,
        String createdBy, Instant createdAt, String bodyFormat, String bodyDigest) { }
