package com.skillforge.core.billing.application;

import com.skillforge.common.context.TenantContext;
import com.skillforge.common.time.BusinessTime;
import com.skillforge.core.billing.dto.billing.ModelBillingContracts.Line;
import com.skillforge.core.billing.dto.billing.ModelBillingContracts.Monthly;
import com.skillforge.core.billing.persistence.ModelBillingMapper;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只读月度模型费用；已落账的价格、汇率与更正是唯一金额来源。 */
@Service
public class ModelBillingService {
    private final ModelBillingMapper mapper;
    private final FoundationAccess access;
    private final Clock clock;

    @Autowired
    public ModelBillingService(ModelBillingMapper mapper, FoundationAccess access) {
        this(mapper, access, BusinessTime.clock());
    }

    public ModelBillingService(ModelBillingMapper mapper, FoundationAccess access, Clock clock) {
        this.mapper = mapper; this.access = access; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Monthly monthly(long tenant, String month, Integer page, Integer pageSize) {
        access.require("foundation.billing.read");
        var subject = access.currentSubject();
        if (tenant <= 0 || !Long.toString(tenant).equals(TenantContext.getTenantId())
                || !subject.platform() && !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "租户上下文不匹配");
        int selectedPage = page == null ? 1 : page;
        int selectedSize = pageSize == null ? 20 : pageSize;
        if (selectedPage < 1 || selectedSize < 1 || selectedSize > 200)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "页码须大于零，每页须为1至200条");
        YearMonth selectedMonth;
        try {
            selectedMonth = month == null || month.isBlank() ? YearMonth.now(clock.withZone(BusinessTime.ZONE)) : YearMonth.parse(month);
        } catch (DateTimeParseException invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "month 必须为 YYYY-MM");
        }
        var from = selectedMonth.atDay(1).atStartOfDay(BusinessTime.ZONE).toInstant();
        var to = selectedMonth.plusMonths(1).atDay(1).atStartOfDay(BusinessTime.ZONE).toInstant();
        var summary = mapper.summarize(tenant, from, to);
        var items = mapper.list(tenant, from, to, (long) (selectedPage - 1) * selectedSize, selectedSize)
                .stream().map(ModelBillingService::line).toList();
        String known = yuan(summary.getKnownAmountCents());
        return new Monthly(selectedMonth.toString(), "CNY", summary.getModelCalls(), known,
                summary.getMissingCostCount() == 0 ? known : null, summary.getMissingCostCount(),
                text(summary.getPromptTokens()), text(summary.getCompletionTokens()), summary.getModelCalls(),
                selectedPage, selectedSize, items);
    }

    private static Line line(ModelBillingMapper.Row row) {
        return new Line(text(row.getId()), row.getEventType(), row.getProvider(), row.getModel(), row.getOccurredAt(),
                row.getInvocationId(), row.getAttemptId(), row.getSessionId(), row.getRunId(), text(row.getQuantity()),
                text(row.getPromptTokens()), text(row.getCompletionTokens()), text(row.getImageCount()), text(row.getVideoCount()),
                text(row.getCharacters()), text(row.getDurationSeconds()), row.getPricingSnapshotVersion(),
                text(row.getPricingModelId()), row.getPricingModelRevision(), yuan(row.getOriginalAmountCents()),
                row.getOriginalCurrency(), yuan(row.getAmountCents()), row.getCostStatus());
    }

    private static String yuan(BigDecimal cents) { return cents == null ? null : cents.movePointLeft(2).toPlainString(); }
    private static String text(Object value) { return value == null ? null : value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString(); }
}
