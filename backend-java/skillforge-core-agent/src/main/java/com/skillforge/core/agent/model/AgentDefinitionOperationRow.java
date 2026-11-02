package com.skillforge.core.agent.model;

/**
 * 定义生命周期的已提交原回执；仅所属持久化使用，不暴露为HTTP对象。
 * @param actorRef 可信主体稳定引用，不包含凭据版本
 * @param operation create/update/publish/deprecate/restore
 * @param objectScope create固定new，其他操作为稳定定义ID
 * @param requestKey 原Idempotency-Key，保持精确字符
 * @param fingerprint 规范化命令SHA-256，含expectedRevision
 * @param definitionId 操作涉及的稳定定义ID
 * @param resultSnapshot 原安全响应JSON，后续草稿或发布不改写
 */
public record AgentDefinitionOperationRow(String actorRef, String operation, String objectScope, String requestKey,
        String fingerprint, Long definitionId, String resultSnapshot) { }
