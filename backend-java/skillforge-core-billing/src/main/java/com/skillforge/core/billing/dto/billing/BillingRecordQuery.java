package com.skillforge.core.billing.dto.billing;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用量流水查询对象（usage_events 最小单元，N28）。
 *
 * <p>分页查询用量事件，支持事件类型、请求归组、关联维度与日期范围过滤；
 * 时间按 北京时间 日界解析（YYYY-MM-DD）。
 *
 * @param eventType 事件类型过滤（request / llm.chat / llm.embedding / llm.rerank / knowledge.source / workflow.external），可空
 * @param requestId 请求根 ID 过滤（一次请求全链路），可空
 * @param endUserId C 端用户 ID 过滤，可空
 * @param agentId   Agent ID 过滤，可空
 * @param runId Agent Runtime Run ID 过滤，可空
 * @param channelId 渠道 ID 过滤，可空
 * @param model     模型名过滤，可空
 * @param scopeId   三方透传实体标识过滤，可空
 * @param from      起始日期（YYYY-MM-DD，北京时间 当日 00:00 起），可空
 * @param to        结束日期（YYYY-MM-DD，北京时间 次日 00:00 前），可空
 * @param page      页码，缺省 1
 * @param pageSize  每页条数，缺省 20，最大 200
 */
public record BillingRecordQuery(
        @Schema(description = "事件类型过滤（request / llm.chat / llm.embedding / llm.rerank / knowledge.source / workflow.external），可空") String eventType,
        @Schema(description = "请求根 ID 过滤（一次请求全链路），可空") String requestId,
        @Schema(description = "C 端用户 ID 过滤，可空") Long endUserId,
        @Schema(description = "Agent ID 过滤，可空") Long agentId,
        @Schema(description = "Agent Runtime Run ID 过滤，可空") String runId,
        @Schema(description = "渠道 ID 过滤，可空") String channelId,
        @Schema(description = "模型名过滤，可空") String model,
        @Schema(description = "三方透传实体标识过滤，可空") String scopeId,
        @Schema(description = "起始日期（YYYY-MM-DD，北京时间 当日 00:00 起），可空") String from,
        @Schema(description = "结束日期（YYYY-MM-DD，北京时间 次日 00:00 前），可空") String to,
        @Schema(description = "页码，缺省 1") Integer page,
        @Schema(description = "每页条数，缺省 20，最大 200") Integer pageSize,
        @Schema(description="费用状态过滤") String amountStatus) {
}
