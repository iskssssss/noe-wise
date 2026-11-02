package com.skillforge.institution.service;
import lombok.Data;
/** 人工回复受理账本；不作为 Runtime 模型上下文的第二来源。 */
@Data
public class HandoffReplyRequest {
 private Long tenantId;private String commandId;private String ticketId;private String sessionId;
 private Long endUserId;private Long operatorId;private String operatorName;private String content;
 private String requestFingerprint;private String status;private String sourceEventId;private Long sourceSequence;private String errorCode;
}
