package com.skillforge.core.billing.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.core.billing.export.PlatformUsageRecorder;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.ModelPriceSource;
import com.skillforge.core.billing.pricing.MediaCostPrice;
import com.skillforge.core.billing.pricing.ExchangeRateProvider;
import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.shared.tracing.TraceContext;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 媒体真实供应商操作独立于外层工具次数；原Job观察只追加并保持原归因。 */
class MediaUsageMeteringTest {
    private static final ModelRevisionRef MODEL = new ModelRevisionRef("7001", 3);
    private static final String INVOCATION = "media-job:8101";
    private static final String ATTEMPT = INVOCATION + ":create:1";

    @BeforeAll
    static void registerUsageMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "media-usage-test"), CostEventDO.class);
    }

    @Test void startedKeepsActualCallAndUnknownMediaWithoutInferringAnyPrice() {
        var fixture = new Fixture();
        fixture.start(source("original-run"));

        CostEventDO started = fixture.rows.get(ATTEMPT + ":usage-report:0");
        assertThat(started.getQuantity()).isEqualTo(1);
        assertThat(started.getUsageSource()).isEqualTo("UNKNOWN");
        assertThat(started.getAmountStatus()).isEqualTo("unresolved");
        assertThat(started.getPromptTokens()).isNull(); assertThat(started.getImageCount()).isNull();
        assertThat(started.getCharacters()).isNull(); assertThat(started.getDurationSeconds()).isNull();
        assertThat(started.getTotalAmount()).isNull(); assertThat(started.getTotalAmountCents()).isNull();
        assertThat(started.getCurrency()).isEqualTo("USD");
        assertThat(started.getPricingModelId()).isEqualTo(7001);
        assertThat(started.getPricingModelRevision()).isEqualTo(3);
    }

    @Test void unknownObservationsKeepPartialReportsAndNeverProduceMeasuredOrZero() {
        var fixture = new Fixture(); fixture.start(source("original-run"));
        fixture.recorder.recordMediaObservation("9", INVOCATION, 1,
                new MediaUsage(55L, null, null, null, null, new BigDecimal("2.125")), false, null);
        fixture.recorder.recordMediaObservation("9", INVOCATION, 2,
                new MediaUsage(null, 13L, null, null, null, null), false, null);

        var latest = fixture.rows.get(ATTEMPT + ":media-observation:2");
        assertThat(latest.getPromptTokens()).isEqualTo(55);
        assertThat(latest.getCompletionTokens()).isEqualTo(13);
        assertThat(latest.getDurationSeconds()).isEqualByComparingTo("2.125");
        assertThat(latest.getImageCount()).isNull();
        assertThat(latest.getUsageEventKind()).isEqualTo("usage_report");
        assertThat(latest.getAmountStatus()).isEqualTo("unresolved");
        assertThat(fixture.rows).doesNotContainKey(ATTEMPT + ":usage");
    }

    @Test void terminalCopiesOriginalAttributionDespiteAnotherRunQueryAndRemainsUnpriced() {
        var fixture = new Fixture(); fixture.start(source("original-run"));
        try (var ignored = MeterScope.openIsolated(source("query-run"))) {
            fixture.recorder.recordMediaObservation("9", INVOCATION, 1,
                    new MediaUsage(null, null, null, null, 18L, null), true, null);
        }
        var terminal = fixture.rows.get(ATTEMPT + ":usage");
        assertThat(terminal.getRunId()).isEqualTo("original-run");
        assertThat(terminal.getSessionId()).isEqualTo("session-original");
        assertThat(terminal.getRootInputId()).isEqualTo("root-original");
        assertThat(terminal.getAgentRevisionId()).isEqualTo(91001);
        assertThat(terminal.getOriginKind()).isEqualTo("native");
        assertThat(terminal.getCharacters()).isEqualTo(18);
        assertThat(terminal.getUsageEventKind()).isEqualTo("measured");
        assertThat(terminal.getUsageSnapshotRevision()).isEqualTo(1);
        assertThat(terminal.getAmountStatus()).isEqualTo("unpriced");
        assertThat(terminal.getTotalAmount()).isNull(); assertThat(terminal.getTotalAmountCents()).isNull();
    }

    @Test void repeatAndIdenticalLaterObservationDoNotCreateAnotherEconomicUsage() {
        var fixture = new Fixture(); fixture.start(source("original-run"));
        var usage = new MediaUsage(null, null, 1L, null, null, null);
        fixture.recorder.recordMediaObservation("9", INVOCATION, 1, usage, true, null);
        fixture.recorder.recordMediaObservation("9", INVOCATION, 1, usage, true, null);
        fixture.recorder.recordMediaObservation("9", INVOCATION, 8, usage, true, null);
        fixture.recorder.recordMediaObservation("9", INVOCATION, 9, MediaUsage.empty(), false, null);

        assertThat(fixture.rows).hasSize(3);
        assertThat(fixture.rows.values().stream().filter(row -> "measured".equals(row.getUsageEventKind()))).hasSize(1);
        assertThat(fixture.enqueued).isEqualTo(3);
    }

    @Test void conflictingSameObservationOrTerminalCannotOverwriteOriginalFact() {
        var fixture = new Fixture(); fixture.start(source("original-run"));
        fixture.recorder.recordMediaObservation("9", INVOCATION, 1,
                new MediaUsage(null, null, 1L, null, null, null), true, null);
        assertThatThrownBy(() -> fixture.recorder.recordMediaObservation("9", INVOCATION, 1,
                new MediaUsage(null, null, 2L, null, null, null), true, null))
                .hasMessage("media_observation_conflict");
        assertThatThrownBy(() -> fixture.recorder.recordMediaObservation("9", INVOCATION, 2,
                new MediaUsage(null, null, 2L, null, null, null), true, null))
                .hasMessage("media_terminal_usage_conflict");
        assertThat(fixture.rows.get(ATTEMPT + ":usage").getImageCount()).isEqualTo(1);
        assertThat(fixture.rows).hasSize(3);
    }

    @Test void historicalJobWithoutStartedFactCannotUseCurrentRunToBackfillUsage() {
        var fixture = new Fixture();
        try (var ignored = MeterScope.openIsolated(source("unrelated-query"))) {
            fixture.recorder.recordMediaObservation("9", "historical-job", 1,
                    new MediaUsage(55L, 13L, null, null, null, new BigDecimal("2")), true, null);
        }
        assertThat(fixture.rows).isEmpty(); assertThat(fixture.enqueued).isZero();
    }

    @Test void zeroIsReportedButNegativeOrModelScopeConflictIsRejected() {
        var fixture = new Fixture(); fixture.start(source("original-run"));
        fixture.recorder.recordMediaObservation("9", INVOCATION, 1,
                new MediaUsage(0L, null, 0L, null, null, BigDecimal.ZERO), true, null);
        var terminal = fixture.rows.get(ATTEMPT + ":usage");
        assertThat(terminal.getUsageSource()).isEqualTo("PROVIDER_REPORTED");
        assertThat(terminal.getPromptTokens()).isZero(); assertThat(terminal.getImageCount()).isZero();
        assertThat(terminal.getTokens()).isNull();
        assertThatThrownBy(() -> new MediaUsage(null, null, -1L, null, null, null))
                .hasMessage("media_usage_negative");
        assertThatThrownBy(() -> fixture.start(source("different-run")))
                .hasMessage("media_started_identity_conflict");
    }

    @Test void knownCancellationBeforeHttpClosesTheOriginalAttemptAsNotApplicableWithoutFakeProviderZero() {
        var fixture = new Fixture(); fixture.start(source("original-run"));
        fixture.recorder.recordMediaNotSubmitted("9", INVOCATION, 1);
        fixture.recorder.recordMediaNotSubmitted("9", INVOCATION, 1);

        var terminal = fixture.rows.get(ATTEMPT + ":usage");
        assertThat(terminal.getQuantity()).isZero();
        assertThat(terminal.getAmountStatus()).isEqualTo("not_applicable");
        assertThat(terminal.getUsageSource()).isEqualTo("UNKNOWN");
        assertThat(terminal.getPromptTokens()).isNull(); assertThat(terminal.getImageCount()).isNull();
        assertThat(terminal.getDurationSeconds()).isNull(); assertThat(terminal.getTotalAmount()).isNull();
        assertThat(fixture.rows).hasSize(2).containsKey(ATTEMPT + ":usage-report:0");
        assertThat(fixture.enqueued).isEqualTo(2);
    }

    @Test void notSubmittedCannotEraseAnAlreadyReportedSupplierObservation() {
        var fixture = new Fixture(); fixture.start(source("original-run"));
        fixture.recorder.recordMediaObservation("9", INVOCATION, 1,
                new MediaUsage(55L, null, null, null, null, null), false, null);
        assertThatThrownBy(() -> fixture.recorder.recordMediaNotSubmitted("9", INVOCATION, 2))
                .hasMessage("media_not_submitted_conflict");
        assertThat(fixture.rows).doesNotContainKey(ATTEMPT + ":usage");
        assertThat(fixture.rows.get(ATTEMPT + ":media-observation:1").getPromptTokens()).isEqualTo(55);
    }

    @Test void terminalPricesRealImagesFromTheOriginalRevisionWhileUnknownReportHasNoMoney() {
        var prices = org.mockito.Mockito.spy(mediaPrices("CNY", "2.5"));
        var fixture = new Fixture(prices, null); fixture.start(source("original-run"));
        var usage = new MediaUsage(null, null, 3L, null, null, null);
        fixture.recorder.recordMediaObservation("9", INVOCATION, 1, usage, false, null);
        assertThat(fixture.rows.get(ATTEMPT + ":media-observation:1").getTotalAmount()).isNull();
        assertThat(fixture.rows).doesNotContainKey(ATTEMPT + ":usage");
        try (var ignored = MeterScope.openIsolated(source("query-run"))) {
            fixture.recorder.recordMediaObservation("9", INVOCATION, 2, usage, true, null);
        }
        var terminal = fixture.rows.get(ATTEMPT + ":usage");
        assertThat(terminal.getQuantity()).isEqualTo(1);
        assertThat(terminal.getImageCount()).isEqualTo(3L);
        assertThat(terminal.getTotalAmount()).isEqualByComparingTo("7.5");
        assertThat(terminal.getTotalAmountCents()).isEqualTo(8L);
        assertThat(terminal.getAmountStatus()).isEqualTo("priced");
        assertThat(terminal.getRunId()).isEqualTo("original-run");
        assertThat(terminal.getOccurredAt()).isEqualTo(fixture.rows.get(ATTEMPT + ":usage-report:0").getOccurredAt());
        assertThat(terminal.getExchangeRateToCny()).isEqualByComparingTo("1");
        assertThat(terminal.getPricingSnapshotVersion()).isEqualTo("frozen-model-price@fx-cny-identity-v1");
        fixture.recorder.recordMediaObservation("9", INVOCATION, 3, usage, true, null);
        org.mockito.Mockito.verify(prices, org.mockito.Mockito.times(1)).calculateMedia(MODEL, CostType.MEDIA_IMAGE, usage, null);
    }

    @Test void foreignMediaCostKeepsItsCurrencyAndMissingFxAsUnresolved() {
        var fixture = new Fixture(mediaPrices("USD", "2.5"), null); fixture.start(source("original-run"));
        fixture.recorder.recordMediaObservation("9", INVOCATION, 1,
                new MediaUsage(null, null, 2L, null, null, null), true, null);
        var terminal = fixture.rows.get(ATTEMPT + ":usage");
        assertThat(terminal.getTotalAmount()).isEqualByComparingTo("5");
        assertThat(terminal.getCurrency()).isEqualTo("USD");
        assertThat(terminal.getExchangeRateToCny()).isNull();
        assertThat(terminal.getAmountStatus()).isEqualTo("unresolved");
    }

    @Test void foreignFxUsesOriginalOperationTimeAndFreezesTheReturnedSnapshot() {
        var fx = mock(ExchangeRateProvider.class);
        when(fx.toCny(anyString(), any())).thenReturn(java.util.Optional.of(new ExchangeRateProvider.Rate(
                new BigDecimal("7.25"), java.time.Instant.parse("2026-10-26T00:00:00Z"), "original-fx")));
        var fixture = new Fixture(mediaPrices("USD", "2.5"), fx); fixture.start(source("original-run"));
        fixture.recorder.recordMediaObservation("9", INVOCATION, 1,
                new MediaUsage(null, null, 2L, null, null, null), true, null);
        var terminal = fixture.rows.get(ATTEMPT + ":usage");
        org.mockito.Mockito.verify(fx).toCny("USD", fixture.rows.get(ATTEMPT + ":usage-report:0").getOccurredAt());
        assertThat(terminal.getExchangeRateToCny()).isEqualByComparingTo("7.25");
        assertThat(terminal.getPricingSnapshotVersion()).isEqualTo("frozen-model-price@fx-original-fx");
        assertThat(terminal.getAmountStatus()).isEqualTo("priced");
    }

    @Test void excessiveCostKeepsTheSupplierMetricAndPublishesAnUnpricedTerminal() {
        var fixture = new Fixture(mediaPrices("CNY", "9223372036854775807"), null);
        fixture.start(source("original-run"));
        fixture.recorder.recordMediaObservation("9", INVOCATION, 1,
                new MediaUsage(null, null, Long.MAX_VALUE, null, null, null), true, null);
        var terminal = fixture.rows.get(ATTEMPT + ":usage");
        assertThat(terminal.getImageCount()).isEqualTo(Long.MAX_VALUE);
        assertThat(terminal.getTotalAmount()).isNull(); assertThat(terminal.getTotalAmountCents()).isNull();
        assertThat(terminal.getAmountStatus()).isEqualTo("unpriced");
        assertThat(fixture.enqueued).isEqualTo(3);
    }

    @Test void knownNoSendDoesNotInvokeMediaPricingEvenWhenTheModelHasARealPrice() {
        var prices = org.mockito.Mockito.spy(mediaPrices("CNY", "2.5"));
        var fixture = new Fixture(prices, null); fixture.start(source("original-run"));
        fixture.recorder.recordMediaNotSubmitted("9", INVOCATION, 1);
        org.mockito.Mockito.verify(prices, org.mockito.Mockito.never()).calculateMedia(any(), any(), any(), any());
        assertThat(fixture.rows.get(ATTEMPT + ":usage").getAmountStatus()).isEqualTo("not_applicable");
    }

    private static PriceCalculator mediaPrices(String currency, String imagePrice) {
        var source = mock(ModelPriceSource.class);
        when(source.snapshot(MODEL)).thenReturn(new ModelPriceSource.Snapshot("frozen-model-price", Map.of(
                new ModelPriceSource.Key("aliyun-bailian", "image-model"),
                new ModelPriceSource.Price(null, null, null, currency,
                        new MediaCostPrice(MediaCostPrice.Unit.OUTPUT_IMAGE, new BigDecimal(imagePrice), null, null, null), null, null))));
        return new PriceCalculator(source);
    }

    private static CostContext source(String run) {
        return CostContext.builder().requestId("input-original").rootInputId("root-original")
                .sessionId("session-original").runId(run).agentId(44L).endUserId(55L)
                .attributionRunId("resource-root").callPoint("agent-runtime.media")
                .traceContext(new TraceContext("input-original", "root-original", "trace", "span", "event", null)).build();
    }

    private static final class Fixture {
        final Map<String, CostEventDO> rows = new LinkedHashMap<>();
        final CostEventMapper mapper = mock(CostEventMapper.class);
        final PlatformUsageRecorder exports = mock(PlatformUsageRecorder.class);
        final CostRecorder recorder;
        int enqueued;

        Fixture() { this(unpricedCalculator(), null); }

        private static PriceCalculator unpricedCalculator() {
            var prices = mock(PriceCalculator.class);
            when(prices.unpricedMedia(MODEL)).thenReturn(new PriceCalculator.Amounts(null, null, null, "USD", null, null, null, "model:7001:3", null));
            when(prices.calculateMedia(org.mockito.ArgumentMatchers.eq(MODEL), any(), any(), org.mockito.ArgumentMatchers.nullable(com.skillforge.core.billing.pricing.MediaPricingContext.class)))
                    .thenReturn(new PriceCalculator.Amounts(null, null, null, "USD", null, null, null, "model:7001:3", null));
            return prices;
        }

        Fixture(PriceCalculator prices, ExchangeRateProvider fx) {
            recorder = new CostRecorder(mapper, prices, fx, exports);
            when(mapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<CostEventDO>>any())).thenAnswer(call -> {
                var wrapper = (AbstractWrapper<?, ?, ?>) call.getArgument(0);
                wrapper.getSqlSegment(); var values = wrapper.getParamNameValuePairs().values();
                return rows.values().stream().filter(row -> values.contains(row.getEventId()) && values.contains(row.getTenantId()))
                        .findFirst().orElse(null);
            });
            when(mapper.mediaStarted(anyLong(), anyString())).thenAnswer(call -> rows.values().stream()
                    .filter(row -> row.getTenantId().equals(call.getArgument(0)) && row.getInvocationId().equals(call.getArgument(1))
                            && Objects.equals(row.getReportRevision(), 0L)).findFirst().orElse(null));
            when(mapper.latestMediaObservation(anyLong(), anyString())).thenAnswer(call -> rows.values().stream()
                    .filter(row -> row.getTenantId().equals(call.getArgument(0)) && row.getAttemptId().equals(call.getArgument(1)))
                    .max(Comparator.<CostEventDO>comparingLong(row -> row.getReportRevision() != null ? row.getReportRevision()
                            : row.getUsageSnapshotRevision()).thenComparing(row -> "measured".equals(row.getUsageEventKind())))
                    .orElse(null));
            when(mapper.insertUsageReport(any())).thenAnswer(call -> save(call.getArgument(0)));
            when(mapper.insert(any(CostEventDO.class))).thenAnswer(call -> save(call.getArgument(0)));
            doAnswer(call -> { CostEventDO event = call.getArgument(0); event.setOriginKind("native");
                event.setActorRef("member:7"); event.setAgentRevisionId(91001L); return null;
            }).when(exports).attribute(any(CostEventDO.class), anyString());
            doAnswer(call -> { enqueued++; return null; }).when(exports).enqueue(any());
        }

        int save(CostEventDO row) { return rows.putIfAbsent(row.getEventId(), row) == null ? 1 : 0; }

        void start(CostContext source) {
            recorder.recordMediaStarted("9", source, new ExternalInvocationRequest(INVOCATION, ATTEMPT,
                    ExternalOperationType.MODEL_IMAGE, "aliyun-bailian", "image-model", "operation",
                    source.traceContext(), Map.of()), CostType.MEDIA_IMAGE, MODEL);
        }
    }
}
