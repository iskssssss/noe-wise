package com.skillforge.core.billing.reconciliation;

import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.UsageAdjustment;
import com.skillforge.core.billing.metering.UsageEventKind;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 将第三方账单事实追加到 Usage Ledger；不修改 measured 原事件。 */
@Service
public class UsageReconciliationService {

    /** 单次显式窗口对账允许拉取的最大页数。 */
    static final int MAX_PAGES = 1_000;

    private static final Logger log = LoggerFactory.getLogger(UsageReconciliationService.class);

    private final CostRecorder costRecorder;
    private final UsageReconciliationProviderRegistry providerRegistry;
    private final TenantUsageReconciliationConfigService tenantConfigService;

    public UsageReconciliationService(CostRecorder costRecorder) {
        this(costRecorder, new UsageReconciliationProviderRegistry(List.of()),
                (TenantUsageReconciliationConfigService) null);
    }

    @Autowired
    public UsageReconciliationService(CostRecorder costRecorder,
                                      UsageReconciliationProviderRegistry providerRegistry,
                                      ObjectProvider<TenantUsageReconciliationConfigService> tenantConfig) {
        this(costRecorder, providerRegistry,
                tenantConfig == null ? null : tenantConfig.getIfAvailable());
    }

    public UsageReconciliationService(
            CostRecorder costRecorder, UsageReconciliationProvider provider) {
        this(costRecorder,
                new UsageReconciliationProviderRegistry(provider == null ? List.of() : List.of(provider)),
                (TenantUsageReconciliationConfigService) null);
    }

    UsageReconciliationService(CostRecorder costRecorder,
                               UsageReconciliationProviderRegistry providerRegistry,
                               TenantUsageReconciliationConfigService tenantConfigService) {
        this.costRecorder = Objects.requireNonNull(costRecorder, "costRecorder");
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.tenantConfigService = tenantConfigService;
    }

    /** 拉取并追加一页；无 Provider 时安全跳过，不发起任何外部调用。 */
    public Result reconcile(String tenantId, Instant from, Instant to, String cursor) {
        validateWindow(tenantId, from, to);
        ProviderSelection selection = selectProvider(tenantId);
        if (selection.failed()) return Result.failed(cursor, 0);
        UsageReconciliationProvider provider = selection.provider();
        if (provider == null) {
            return Result.skipped(cursor);
        }
        return reconcilePage(provider, tenantId, from, to, cursor);
    }

    /**
     * 在同一个 from/to 窗口内受限遍历账单 Provider 的所有页面。
     *
     * <p>空 nextCursor 表示窗口完成；每页事实追加失败、空批次、Provider
     * 异常、游标停滞、游标循环或达到页数上限都会保留当前请求游标并失败，
     * 调用方可以安全地从该游标重放整页。
     */
    public Result reconcileWindow(String tenantId, Instant from, Instant to, String cursor) {
        validateWindow(tenantId, from, to);
        ProviderSelection selection = selectProvider(tenantId);
        if (selection.failed()) return Result.failed(cursor, 0);
        UsageReconciliationProvider provider = selection.provider();
        if (provider == null) {
            return Result.skipped(cursor);
        }

        String currentCursor = cursor;
        Set<String> visitedCursors = new HashSet<>();
        int processed = 0;
        int failed = 0;
        int pages = 0;
        while (pages < MAX_PAGES) {
            if (!visitedCursors.add(currentCursor)) {
                log.warn("账单对账检测到游标循环，停止遍历 tenant={} page={}", tenantId, pages);
                return Result.failed(currentCursor, processed, failed + 1);
            }

            Result pageResult = reconcilePage(provider, tenantId, from, to, currentCursor);
            pages++;
            processed += pageResult.processed();
            failed += pageResult.failed();
            if (!"completed".equals(pageResult.status())) {
                return Result.failed(currentCursor, processed, failed);
            }

            String nextCursor = normalizeCursor(pageResult.nextCursor());
            if (nextCursor.isEmpty()) {
                return Result.completed(processed, "");
            }
            if (nextCursor.equals(currentCursor) || visitedCursors.contains(nextCursor)) {
                log.warn("账单对账检测到游标停滞或循环，停止遍历 tenant={} page={}", tenantId, pages);
                return Result.failed(currentCursor, processed, failed + 1);
            }
            currentCursor = nextCursor;
        }

        log.warn("账单对账达到最大页数，停止遍历 tenant={} maxPages={}", tenantId, MAX_PAGES);
        return Result.failed(currentCursor, processed, failed + 1);
    }

    private String normalizeCursor(String cursor) {
        return cursor == null ? "" : cursor;
    }

    private Result reconcilePage(UsageReconciliationProvider provider,
                                 String tenantId, Instant from, Instant to, String requestCursor) {
        UsageReconciliationProvider.Batch batch;
        try {
            batch = provider.pull(tenantId, from, to, requestCursor);
        } catch (RuntimeException exception) {
            log.warn("第三方账单拉取失败，保留请求游标 tenant={} diagnostic={}", tenantId,
                    com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(exception));
            return Result.failed(requestCursor, 0);
        }
        if (batch == null) {
            log.warn("第三方账单返回空批次，保留请求游标 tenant={}", tenantId);
            return Result.failed(requestCursor, 0);
        }
        if (batch.facts().isEmpty()) {
            log.warn("第三方账单返回无事实批次，保留请求游标 tenant={}", tenantId);
            return Result.failed(requestCursor, 0);
        }
        int processed = 0;
        int failed = 0;
        for (UsageReconciliationProvider.Fact fact : batch.facts()) {
            try {
                costRecorder.recordAdjustment(tenantId, new UsageAdjustment(
                        fact.eventId(), fact.originalEventId(), UsageEventKind.RECONCILIATION,
                        fact.amountStatus(), fact.inputAmount(), fact.outputAmount(), fact.totalAmount(),
                        fact.exchangeRateToCny(), fact.pricingSnapshotVersion(), fact.currency(),
                        fact.metadata()));
                processed++;
            } catch (RuntimeException exception) {
                failed++;
                log.warn("账单事实追加失败，保留请求游标 tenant={} diagnostic={}", tenantId,
                        com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(exception));
            }
        }
        if (failed > 0) {
            return Result.failed(requestCursor, processed, failed);
        }
        return Result.completed(processed, batch.nextCursor());
    }

    private void validateWindow(String tenantId, Instant from, Instant to) {
        if (tenantId == null || tenantId.isBlank() || from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("对账租户和时间窗口不能为空，且 from 必须早于 to");
        }
    }

    private ProviderSelection selectProvider(String tenantId) {
        if (tenantConfigService != null) {
            try {
                TenantUsageReconciliationConfigService.ResolvedTenantConfig config =
                        tenantConfigService.resolveForProvider(tenantId);
                if (config.provider() != null && !config.provider().isBlank()) {
                    return new ProviderSelection(providerRegistry.find(config.provider()).orElse(null), false);
                }
            } catch (RuntimeException exception) {
                log.warn("租户账单配置选择失败 tenant={} reason=usage_config_failed diagnostic={}", tenantId,
                        com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(exception));
                return new ProviderSelection(null, true);
            }
        }
        return new ProviderSelection(providerRegistry.onlyProvider().orElse(null), false);
    }

    private record ProviderSelection(UsageReconciliationProvider provider, boolean failed) { }

    public record Result(String status, int processed, int failed, String nextCursor) {
        static Result skipped(String cursor) {
            return new Result("skipped", 0, 0, cursor == null ? "" : cursor);
        }

        static Result failed(String cursor, int failed) {
            return failed(cursor, 0, failed);
        }

        static Result failed(String cursor, int processed, int failed) {
            return new Result("failed", Math.max(0, processed), Math.max(1, failed),
                    cursor == null ? "" : cursor);
        }

        static Result completed(int processed, String nextCursor) {
            return new Result("completed", Math.max(0, processed), 0,
                    nextCursor == null ? "" : nextCursor);
        }

    }
}
