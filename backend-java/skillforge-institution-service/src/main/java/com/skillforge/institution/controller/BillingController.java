package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;


import com.skillforge.institution.common.TenantIds;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.billing.dto.billing.BillingRecordQuery;
import com.skillforge.core.billing.dto.billing.RequestDetailResponse;
import com.skillforge.core.billing.dto.billing.RequestListItem;
import com.skillforge.core.billing.dto.billing.RequestListResponse;
import com.skillforge.core.billing.dto.billing.UsageAggregateRow;
import com.skillforge.core.billing.dto.billing.UsageByTypeRow;
import com.skillforge.core.billing.dto.billing.UsageDetailResponse;
import com.skillforge.core.billing.dto.billing.UsageEventItem;
import com.skillforge.core.billing.dto.billing.UsageEventListResponse;
import com.skillforge.core.billing.dto.billing.UsageGroupedRow;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.application.TraceViewService;
import com.skillforge.core.chat.tracing.TraceQuery;
import com.skillforge.core.chat.tracing.TraceView;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.time.LocalDate;
import com.skillforge.common.time.BusinessTime;
import com.skillforge.core.billing.application.ModelBillingService;
import com.skillforge.core.billing.dto.billing.ModelBillingContracts;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费接口控制器（usage_events 最小单元口径，N28）。
 *
 * <p>流水只记录最小单元（每次 AI 调用/每次用户请求），token/金额/次数统计
 * 全部由查询基于 usage_events 聚合得出。提供月度用量（含维度分组）、
 * 事件流水、请求全链路总流水（可细拆最小单元）及只读模型月费用。
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    /** 用量事件表映射器。 */
    private final CostEventMapper costEventMapper;

    /** 按原模型计价事实读取月费用。 */
    private final ModelBillingService billing;


    /** 从权威账本重建问题到结果的过程轨迹。 */
    private final TraceViewService traceViewService;

    /** 维度分组白名单（group_by 参数取值）。 */
    private static final Set<String> GROUP_BY_DIMENSIONS =
            Set.of("agent", "end_user", "channel", "model", "event_type");

    /**
     * 构造计费控制器。
     *
     * @param costEventMapper 用量事件表映射器
     * @param billing 账单应用服务
     */
    @org.springframework.beans.factory.annotation.Autowired
    public BillingController(
            CostEventMapper costEventMapper,
            ModelBillingService billing,
            TraceViewService traceViewService) {
        this.costEventMapper = costEventMapper;
        this.billing = billing;
        this.traceViewService = traceViewService;
    }

    /**
     * 查询从问题到结果的脱敏过程轨迹。
     *
     * <p>与旧的费用明细不同，该接口组合 Session Inbox、Execution Ledger 和 Usage
     * Ledger；不会重新执行模型、知识检索或 Workflow。</p>
     */
    @Operation(summary = "问题到结果轨迹", description = "按 rootInputId 返回脱敏输入、执行事件和费用事件时间线；回放不重新执行运行时")
    @RequirePermission("foundation.billing.read")
    @GetMapping("/traces/detail")
    public ApiResponse<TraceView> trace(@RequestParam("root_input_id") String rootInputId) {
        if (traceViewService == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "轨迹服务未配置");
        }
        return ApiResponses.wrap(traceViewService.query(TenantIds.requireTenantId(),
                new TraceQuery(rootInputId, TraceQuery.Audience.USER, Set.of(), null, null)));
    }

    /**
     * 查询当前租户用量事件流水（分页）。
     *
     * <p>事件类型：request（请求根事件）/ llm.chat / llm.embedding / llm.rerank / knowledge.source / workflow.external；
     * 按事件时间倒序。支持事件类型/请求归组/关联维度/日期范围过滤。
     *
     * @param query 流水查询条件
     * @return 事件流水分页结果
     */
    @Operation(summary = "用量事件流水", description = "分页查询 usage_events 最小单元流水，支持事件类型/request_id/end_user/agent/Agent Runtime Run/channel/model/scope/日期范围过滤；日期为 YYYY-MM-DD（北京时间 日界）；页码缺省 1，每页缺省 20 最大 200")
    @RequirePermission("foundation.billing.read")
    @GetMapping("/records")
    public ApiResponse<UsageEventListResponse> records(BillingRecordQuery query) {
        String tenantId = TenantIds.requireTenantId();
        int pageNo = query.page() == null || query.page() < 1
                ? ApiConstants.DEFAULT_PAGE : query.page();
        int size = query.pageSize() == null || query.pageSize() < 1
                ? ApiConstants.DEFAULT_PAGE_SIZE : Math.min(query.pageSize(), ApiConstants.MAX_PAGE_SIZE);
        LambdaQueryWrapper<CostEventDO> wrapper = new LambdaQueryWrapper<CostEventDO>()
                .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                .orderByDesc(CostEventDO::getOccurredAt);
        if (query.eventType() != null && !query.eventType().isBlank()) {
            wrapper.eq(CostEventDO::getEventType, query.eventType());
        }
        if (query.amountStatus() != null && !query.amountStatus().isBlank()) {
            wrapper.eq(CostEventDO::getAmountStatus, query.amountStatus());
        }
        if (query.requestId() != null && !query.requestId().isBlank()) {
            wrapper.eq(CostEventDO::getRequestId, query.requestId());
        }
        if (query.endUserId() != null) {
            wrapper.eq(CostEventDO::getEndUserId, query.endUserId());
        }
        if (query.agentId() != null) {
            wrapper.eq(CostEventDO::getAgentId, query.agentId());
        }
        if (query.runId() != null && !query.runId().isBlank()) {
            wrapper.eq(CostEventDO::getRunId, query.runId());
        }
        if (query.channelId() != null && !query.channelId().isBlank()) {
            wrapper.eq(CostEventDO::getChannelId, query.channelId());
        }
        if (query.model() != null && !query.model().isBlank()) {
            wrapper.eq(CostEventDO::getModel, query.model());
        }
        if (query.scopeId() != null && !query.scopeId().isBlank()) {
            wrapper.eq(CostEventDO::getScopeId, query.scopeId());
        }
        if (query.from() != null && !query.from().isBlank()) {
            wrapper.ge(CostEventDO::getOccurredAt, parseDayStart(query.from()));
        }
        if (query.to() != null && !query.to().isBlank()) {
            wrapper.lt(CostEventDO::getOccurredAt,
                    parseDayStart(query.to()).plus(1, ChronoUnit.DAYS));
        }
        Page<CostEventDO> result = costEventMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<UsageEventItem> items = result.getRecords().stream().map(this::eventItem).toList();
        return ApiResponses.wrap(new UsageEventListResponse(result.getTotal(), items));
    }

    /**
     * 查询当前租户月度用量（基于最小单元聚合）。
     *
     * <p>{@code group_by} 可选维度：agent / end_user / channel / model / event_type；
     * 聚合口径：calls=request 根事件数（配额口径），tokens/金额=llm 类事件汇总（request 不计）。
     *
     * @param groupBy 维度分组键，可空
     * @return 月度用量详情
     */
    @Operation(summary = "月度用量", description = "基于 usage_events 最小单元聚合：calls=请求数（request 根事件）、tokens/金额=llm 类事件汇总；group_by 可选 agent/end_user/channel/model/event_type")
    @RequirePermission("foundation.billing.read")
    @GetMapping("/usage")
    public ApiResponse<UsageDetailResponse> usage(
            @RequestParam(required = false, name = "group_by") String groupBy) {
        String tenantId = TenantIds.requireTenantId();
        Instant monthStart = monthStart();
        Long tenantIdLong = Long.parseLong(tenantId);
        UsageAggregateRow total = costEventMapper.sumMonthly(tenantIdLong, monthStart);
        List<UsageByTypeRow> byTypeRows = costEventMapper.groupByType(tenantIdLong, monthStart);
        List<UsageGroupedRow> grouped = null;
        if (groupBy != null && !groupBy.isBlank()) {
            if (!GROUP_BY_DIMENSIONS.contains(groupBy)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "group_by 仅支持 agent / end_user / channel / model / event_type");
            }
            grouped = costEventMapper.groupByDimension(tenantIdLong, monthStart, groupBy);
        }
        return ApiResponses.wrap(new UsageDetailResponse(
                total,
                byTypeRows,
                grouped));
    }

    /**
     * 请求列表（request 根事件分页 + 全链路聚合）。
     *
     * <p>支撑「用户 ↔ agent 会话聊天全链路流水」入口：
     * 按 end_user / agent / 时间窗过滤，选中一个请求后
     * 经 {@code GET /billing/requests/detail?request_id=...} 查看总流水并展开最小单元。
     *
     * @param endUserId    C 端用户 ID 过滤，可空
     * @param agentId      Agent ID 过滤，可空
     * @param runId        Agent Runtime Run ID 过滤，可空
     * @param from         起始日期（YYYY-MM-DD），可空
     * @param to           结束日期（YYYY-MM-DD），可空
     * @param page         页码，缺省 1
     * @param pageSize     每页条数，缺省 20 最大 200
     * @return 请求列表（含每请求全链路聚合）
     */
    @Operation(summary = "请求列表", description = "request 根事件分页；每项携带该请求全链路聚合（LLM 调用次数/tokens/金额）；过滤：end_user_id/agent_id/日期范围")
    @RequirePermission("foundation.billing.read")
    @GetMapping("/requests")
    public ApiResponse<RequestListResponse> requests(
            @RequestParam(required = false) Long endUserId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        String tenantId = TenantIds.requireTenantId();
        int pageNo = page == null || page < 1 ? ApiConstants.DEFAULT_PAGE : page;
        int size = pageSize == null || pageSize < 1
                ? ApiConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, ApiConstants.MAX_PAGE_SIZE);
        LambdaQueryWrapper<CostEventDO> wrapper = new LambdaQueryWrapper<CostEventDO>()
                .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                .eq(CostEventDO::getEventType, CostType.REQUEST.value())
                .orderByDesc(CostEventDO::getOccurredAt);
        if (endUserId != null) {
            wrapper.eq(CostEventDO::getEndUserId, endUserId);
        }
        if (agentId != null) {
            wrapper.eq(CostEventDO::getAgentId, agentId);
        }
        if (runId != null && !runId.isBlank()) {
            wrapper.eq(CostEventDO::getRunId, runId);
        }
        if (from != null && !from.isBlank()) {
            wrapper.ge(CostEventDO::getOccurredAt, parseDayStart(from));
        }
        if (to != null && !to.isBlank()) {
            wrapper.lt(CostEventDO::getOccurredAt, parseDayStart(to).plus(1, ChronoUnit.DAYS));
        }
        Page<CostEventDO> result = costEventMapper.selectPage(new Page<>(pageNo, size), wrapper);
        Long tenantIdLong = Long.parseLong(tenantId);
        Map<String, UsageAggregateRow> summaries = result.getRecords().isEmpty() ? Map.of()
                : costEventMapper.sumByRequests(tenantIdLong, result.getRecords().stream().map(CostEventDO::getRequestId).toList())
                  .stream().collect(java.util.stream.Collectors.toMap(UsageAggregateRow::getRequestId, java.util.function.Function.identity()));
        List<RequestListItem> items = result.getRecords().stream().map(event -> {
            UsageAggregateRow agg = summaries.get(event.getRequestId());
            return new RequestListItem(
                    event.getRequestId(),
                    event.getOccurredAt(),
                    event.getSessionId(),
                    event.getEndUserId(),
                    event.getChannelId(),
                    event.getAgentId(),
                    event.getRunId(),
                    event.getScopeId(),
                    event.getScopeType(),
                    agg.getCalls() == null ? 0 : agg.getCalls(),
                    agg.getTokens() == null ? 0 : agg.getTokens(),
                    agg.getTotalAmountCents(),
                    "CNY", agg.getConfirmedCost(), agg.getMissingCostCount() == null ? 0 : agg.getMissingCostCount(), agg.getUsageStatus());
        }).toList();
        return ApiResponses.wrap(new RequestListResponse(result.getTotal(), items));
    }

    /**
     * 一次请求全链路总流水（聚合 + 最小单元明细展开）。
     *
     * <p>总流水 = 该 request_id 下全部最小单元聚合（LLM 调用次数 / tokens / 金额）；
     * {@code items} 为最小单元明细（细拆），含父子链 {@code parent_event_id}。
     *
     * @param requestId 请求根 ID
     * @return 全链路总流水与明细
     */
    @Operation(summary = "请求全链路总流水", description = "一次请求（request_id）的全部最小单元聚合（总流水）+ 明细展开（细拆）；支撑用户↔agent 会话聊天全链路对账")
    @RequirePermission("foundation.billing.read")
    @GetMapping("/requests/detail")
    public ApiResponse<RequestDetailResponse> requestDetail(@RequestParam("request_id") String requestId) {
        String tenantId = TenantIds.requireTenantId();
        Long tenantIdLong = Long.parseLong(tenantId);
        CostEventDO root = costEventMapper.selectOne(
                new LambdaQueryWrapper<CostEventDO>()
                        .eq(CostEventDO::getTenantId, tenantIdLong)
                        .eq(CostEventDO::getRequestId, requestId)
                        .eq(CostEventDO::getEventType, CostType.REQUEST.value())
                        .last("LIMIT 1"));
        if (root == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "请求不存在: " + requestId);
        }
        UsageAggregateRow agg = costEventMapper.sumByRequests(tenantIdLong, List.of(requestId)).getFirst();
        List<CostEventDO> events = costEventMapper.selectList(
                new LambdaQueryWrapper<CostEventDO>()
                        .eq(CostEventDO::getTenantId, tenantIdLong)
                        .and(w -> w.eq(CostEventDO::getRequestId, requestId).or().eq(CostEventDO::getRootInputId, requestId))
                        .orderByAsc(CostEventDO::getOccurredAt));
        List<UsageEventItem> items = events.stream().map(this::eventItem).toList();
        return ApiResponses.wrap(new RequestDetailResponse(
                requestId,
                root.getOccurredAt(),
                new RequestDetailResponse.RequestSummary(
                        agg.getCalls() == null ? 0 : agg.getCalls(),
                        agg.getTokens() == null ? 0 : agg.getTokens(),
                        agg.getPromptTokens() == null ? 0 : agg.getPromptTokens(),
                        agg.getCompletionTokens() == null ? 0 : agg.getCompletionTokens(),
                        agg.getTotalAmountCents(),
                        "CNY", agg.getConfirmedCost(), agg.getMissingCostCount() == null ? 0 : agg.getMissingCostCount(), agg.getUsageStatus()),
                items));
    }

    /** 查询当前机构按北京时间月份归属的模型费用，全部金额来自原用量账本。 */
    @RequirePermission("foundation.billing.read")
    @GetMapping("/monthly")
    public ApiResponse<ModelBillingContracts.Monthly> monthly(
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize) {
        return ApiResponses.wrap(billing.monthly(Long.parseLong(TenantIds.requireTenantId()), month, page, pageSize));
    }

    /**
     * 解析日期为 北京时间 当日 00:00。
     *
     * @param value YYYY-MM-DD 字符串
     * @return 当日起始时间
     */
    private Instant parseDayStart(String value) {
        try {
            return LocalDate.parse(value).atStartOfDay(BusinessTime.ZONE).toInstant();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "日期必须为 YYYY-MM-DD 格式");
        }
    }

    /**
     * 计算当月第一天（北京时间）。
     *
     * @return 当月起始时间
     */
    private Instant monthStart() {
        return LocalDate.now(BusinessTime.ZONE)
                .with(TemporalAdjusters.firstDayOfMonth())
                .atStartOfDay(BusinessTime.ZONE)
                .toInstant();
    }

    /**
     * 将事件实体映射为响应项。
     *
     * @param event 事件实体
     * @return 响应项
     */
    private UsageEventItem eventItem(CostEventDO event) {
        return new UsageEventItem(
                event.getId(),
                event.getEventType(),
                event.getProvider(),
                event.getModel(),
                event.getQuantity(),
                event.getTokens(),
                event.getPromptTokens(),
                event.getCompletionTokens(),
                event.getCachedInputTokens(),
                event.getCacheWriteInputTokens(),
                event.getReasoningOutputTokens(),
                event.getUsageSource(),
                event.getEstimatorVersion(),
                event.getInputAmountCents(),
                event.getOutputAmountCents(),
                event.getTotalAmountCents(),
                event.getCurrency(),
                event.getInputAmount(),
                event.getOutputAmount(),
                event.getTotalAmount(),
                event.getExchangeRateToCny(),
                event.getPricingSnapshotVersion(),
                event.getUsageEventKind(),
                event.getAmountStatus(),
                event.getAdjustmentOfEventId(),
                event.getEventId(),
                event.getRequestId(),
                event.getRootInputId(),
                event.getParentEventId(),
                event.getSessionId(),
                event.getEndUserId(),
                event.getChannelId(),
                event.getAgentId(),
                event.getRunId(),
                event.getWorkflowInstanceId(),
                event.getExecutionId(),
                event.getScopeId(),
                event.getScopeType(),
                event.getCallPoint(),
                event.getOccurredAt(),
                event.getCreatedAt(),
                event.getImageCount()==null?null:event.getImageCount().toString(),
                event.getVideoCount()==null?null:event.getVideoCount().toString(),
                event.getCharacters()==null?null:event.getCharacters().toString(),
                event.getDurationSeconds()==null?null:event.getDurationSeconds().toPlainString(),
                event.getPricingModelId()==null?null:event.getPricingModelId().toString(),
                event.getPricingModelRevision());
    }

}
