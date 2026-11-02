package com.skillforge.institution.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.dto.billing.BillingRecordQuery;
import com.skillforge.core.billing.dto.billing.UsageAggregateRow;
import com.skillforge.core.billing.dto.billing.UsageByTypeRow;
import com.skillforge.core.billing.dto.billing.UsageGroupedRow;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.application.ModelBillingService;
import com.skillforge.core.billing.dto.billing.ModelBillingContracts;
import com.skillforge.core.chat.application.TraceViewService;
import com.skillforge.core.chat.tracing.TraceView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 计费与只读 Trace 控制面测试；覆盖多维筛选、聚合、账单和脱敏轨迹入口。 */
class BillingControllerTest {

    private static final String TENANT = "9001";
    private CostEventMapper events;
    private ModelBillingService modelBilling;
    private TraceViewService traces;
    private BillingController controller;

    @BeforeAll
    static void initTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), CostEventDO.class);
    }

    @BeforeEach
    void setUp() {
        events = mock(CostEventMapper.class);
        modelBilling = mock(ModelBillingService.class);
        traces = mock(TraceViewService.class);
        controller = new BillingController(events, modelBilling, traces);
        TenantContext.setTenantId(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void traceUsesCurrentTenantAndReadonlyAudience() {
        TraceView view = new TraceView("root-1", List.of(), TraceView.UsageSummary.empty(), List.of());
        when(traces.query(eq(TENANT), any())).thenReturn(view);

        assertThat(controller.trace("root-1").data()).isSameAs(view);
        verify(traces).query(eq(TENANT), any());
    }

    @Test
    void recordsBuildsAllFiltersAndMapsEvents() {
        CostEventDO event = event("event-1", CostType.REQUEST.value());
        event.setProvider("openai");
        event.setModel("gpt-test");
        event.setTokens(12L);
        event.setPromptTokens(8L);
        event.setCompletionTokens(4L);
        Page<CostEventDO> page = new Page<>(2, 200);
        page.setTotal(1);
        page.setRecords(List.of(event));
        when(events.selectPage(any(), any())).thenReturn(page);

        var result = controller.records(new BillingRecordQuery("llm.chat", "request-1", 2L, 3L,
                "run-4", "channel-1", "gpt-test", "scope-1", "2026-08-01", "2026-08-02", 2, 500, null));

        assertThat(result.data().total()).isEqualTo(1);
        assertThat(result.data().items()).hasSize(1);
        assertThat(result.data().items().getFirst().model()).isEqualTo("gpt-test");
    }

    /** 前端提交纯日期，真实 MVC/Controller 按北京日界含起点、排除终日次日零点。 */
    @Test
    void recordsAndRequestsAcceptCalendarDatesAtExactBeijingBoundaries() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<LambdaQueryWrapper<CostEventDO>>();
        when(events.selectPage(any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return new Page<CostEventDO>(1, 20).setRecords(List.of());
        });
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        // 使用本隔离夹具的已注册列名；业务日界预期仍为下面独立给出的绝对时刻。
        var dateColumn = TableInfoHelper.getTableInfo(CostEventDO.class).getFieldList().stream()
                .filter(field -> field.getProperty().equals("occurredAt"))
                .map(com.baomidou.mybatisplus.core.metadata.TableFieldInfo::getColumn).findFirst().orElseThrow();
        for (var dates : new String[][] {
                {"2026-10-12", "2026-10-12", "2026-10-11T16:00:00Z", "2026-10-12T16:00:00Z"},
                {"2024-02-29", "2024-03-01", "2024-02-28T16:00:00Z", "2024-03-01T16:00:00Z"},
                {"2026-12-31", "2027-01-01", "2026-12-30T16:00:00Z", "2027-01-01T16:00:00Z"}
        }) {
            for (String path : List.of("/api/v1/billing/records", "/api/v1/billing/requests")) {
                mvc.perform(get(path).param("from", dates[0]).param("to", dates[1]))
                        .andExpect(status().isOk());
                var query = captured.get();
                assertThat(query.getSqlSegment()).contains(dateColumn + " >=", dateColumn + " <");
                assertThat(query.getParamNameValuePairs().values())
                        .contains(Instant.parse(dates[2]), Instant.parse(dates[3]), Long.parseLong(TENANT));
            }
        }
    }

    @Test
    void usageSupportsDimensionGroupingAndRejectsUnknownDimension() {
        UsageAggregateRow total = new UsageAggregateRow();
        total.setCalls(3L);
        total.setTokens(40L);
        total.setTotalAmountCents(9L);
        UsageByTypeRow type = new UsageByTypeRow();
        type.setEventType("llm.chat");
        type.setQuantity(2L);
        when(events.sumMonthly(any(Long.class), any(Instant.class))).thenReturn(total);
        when(events.groupByType(any(Long.class), any(Instant.class))).thenReturn(List.of(type));
        UsageGroupedRow group = new UsageGroupedRow();
        group.setDimension("agent-1");
        group.setCalls(3L);
        when(events.groupByDimension(any(Long.class), any(Instant.class), eq("agent")))
                .thenReturn(List.of(group));

        var result = controller.usage("agent");
        assertThat(result.data().total().getCalls()).isEqualTo(3L);
        assertThat(result.data().grouped()).hasSize(1);
        assertThat(result.data().eventTypes()).containsExactly(type);

        assertThatThrownBy(() -> controller.usage("not-supported"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("group_by");
    }

    @Test
    void requestsAndRequestDetailAggregateRootAndChildren() {
        CostEventDO root = event("event-root", CostType.REQUEST.value());
        root.setRequestId("request-1");
        root.setCurrency("CNY");
        Page<CostEventDO> page = new Page<>(1, 20);
        page.setTotal(1);
        page.setRecords(List.of(root));
        when(events.selectPage(any(), any())).thenReturn(page);
        UsageAggregateRow aggregate = new UsageAggregateRow();
        aggregate.setCalls(1L);
        aggregate.setTokens(16L);
        aggregate.setPromptTokens(10L);
        aggregate.setCompletionTokens(6L);
        aggregate.setTotalAmountCents(2L);
        aggregate.setRequestId("request-1");
        aggregate.setConfirmedCost("0.02");
        aggregate.setMissingCostCount(0L);
        aggregate.setUsageStatus("recorded");
        when(events.sumByRequests(Long.parseLong(TENANT), List.of("request-1"))).thenReturn(List.of(aggregate));
        when(events.selectOne(any())).thenReturn(root);
        when(events.selectList(any())).thenReturn(List.of(root));

        var list = controller.requests(5L, 6L, "run-7", "2026-08-01", "2026-08-02", 0, 300);
        var detail = controller.requestDetail("request-1");

        assertThat(list.data().items().getFirst().requestId()).isEqualTo("request-1");
        assertThat(detail.data().summary().tokens()).isEqualTo(16L);
        assertThat(detail.data().items()).hasSize(1);
    }

    @Test
    void requestDetailRejectsMissingRoot() {
        when(events.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> controller.requestDetail("missing"))
                .isInstanceOf(ApiException.class).hasMessageContaining("请求不存在");
    }

    @Test
    void monthlyDelegatesWithCurrentTenantAndPreservesUnknownAmountAndDecimalStrings() {
        var line = new ModelBillingContracts.Line("9007199254740993", "media.speech", "synthetic", "model", Instant.parse("2026-08-01T00:00:00Z"),
                "invocation", "attempt", "session", "run", "1", null, null, null, null, "10", null,
                "frozen-media-v1", "9007199254740994", 3L, "0.0008", "CNY", "0.0008", "priced");
        var monthly = new ModelBillingContracts.Monthly("2026-08", "CNY", 2, "0.0008", null, 1,
                null, null, 2, 1, 20, List.of(line));
        when(modelBilling.monthly(9001L, "2026-08", 1, 20)).thenReturn(monthly);
        var result = controller.monthly("2026-08", 1, 20).data();
        assertThat(result).isSameAs(monthly); assertThat(result.totalAmount()).isNull();
        assertThat(result.items().getFirst().id()).isEqualTo("9007199254740993");
        assertThat(result.knownAmount()).isEqualTo("0.0008");
        verify(modelBilling).monthly(9001L, "2026-08", 1, 20);
    }

    @Test
    void invalidDatesAndMonthsFailClosed() {
        Page<CostEventDO> page = new Page<>(1, 20);
        when(events.selectPage(any(), any())).thenReturn(page);
        assertThatThrownBy(() -> controller.records(new BillingRecordQuery(null, null, null, null, null,
                null, null, null, "bad", null, null, null, null))).isInstanceOf(ApiException.class);

    }

    @Test
    void traceWithoutServiceIsUnavailable() {
        BillingController controllerWithoutTrace = new BillingController(events, modelBilling, null);
        assertThatThrownBy(() -> controllerWithoutTrace.trace("root"))
                .isInstanceOf(ApiException.class).hasMessageContaining("未配置");
    }

    private CostEventDO event(String eventId, String type) {
        CostEventDO event = new CostEventDO();
        event.setId(1L);
        event.setTenantId(Long.parseLong(TENANT));
        event.setEventId(eventId);
        event.setEventType(type);
        event.setRequestId("request-1");
        event.setQuantity(1);
        event.setOccurredAt(Instant.parse("2026-08-01T00:00:00Z"));
        event.setCreatedAt(event.getOccurredAt());
        return event;
    }
}
