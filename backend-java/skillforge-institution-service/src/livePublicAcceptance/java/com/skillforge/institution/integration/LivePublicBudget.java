package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只读预算预检：不装配Spring、不开模型连接，只输出冻结模型的安全字段。 */
public final class LivePublicBudget {
    private LivePublicBudget() { }

    static long limit(String name, long ceiling) {
        try {
            long value = Long.parseLong(LivePublicSourceSnapshot.required(name));
            if (value <= 0 || value > ceiling) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("INVALID_LIMIT_" + name);
        }
    }

    public static void main(String[] args) throws Exception {
        var source = LivePublicSourceSnapshot.read();
        long calls = limit("LIVE_PUBLIC_MAX_CALLS", 64);
        long tokens = limit("LIVE_PUBLIC_MAX_TOKENS", 3_000_000);
        if (source.reservation() > tokens) throw new IllegalStateException("BATCH_CANNOT_RESERVE_ONE_CALL");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "PREPARED_NOT_EXECUTED");
        report.put("batch", LivePublicSourceSnapshot.required("LIVE_PUBLIC_BATCH_ID"));
        report.put("candidate", LivePublicSourceSnapshot.required("LIVE_PUBLIC_RUNTIME_REVISION"));
        report.put("model", Map.of("id", source.model().id(), "revision", source.model().revision(),
                "name", source.model().name(), "model_id", source.model().modelId(),
                "provider_id", source.model().providerId(), "service_id", source.model().serviceId()));
        report.put("operational_revisions", source.groups().stream()
                .map(group -> Map.of("key", group.key(), "revision", group.sourceRevision())).toList());
        report.put("max_calls", calls);
        report.put("max_total_tokens", tokens);
        report.put("per_call_input_reservation", source.maxInputTokens());
        report.put("per_call_output_reservation", source.model().maxOutputTokens());
        report.put("per_call_total_reservation", source.reservation());
        BigDecimal input = source.model().inputPricePerMillionCents();
        BigDecimal output = source.model().outputPricePerMillionCents();
        if (input != null && output != null && input.signum() >= 0 && output.signum() >= 0) {
            report.put("configured_price_upper_bound", BigDecimal.valueOf(tokens)
                    .multiply(input.max(output))
                    .divide(BigDecimal.valueOf(100_000_000), 8, RoundingMode.CEILING));
            report.put("currency", source.model().currency());
            report.put("price_basis", "LOCAL_MODEL_CONFIGURATION_NOT_PROVIDER_BILLING_GUARANTEE");
        } else report.put("price_basis", "UNPRICED_REQUIRES_COST_CONFIRMATION");
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
