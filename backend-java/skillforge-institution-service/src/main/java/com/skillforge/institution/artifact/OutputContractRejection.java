package com.skillforge.institution.artifact;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** 成果清单的可恢复校验反馈；只返回合法契约，不回显被拒绝的输入或文件内容。 */
public final class OutputContractRejection extends IllegalArgumentException {
    public record Correction(String key, String field,
            @JsonProperty("allowed_values") List<String> allowedValues,
            @JsonProperty("omit_field") boolean omitField) {
        public Correction { allowedValues = List.copyOf(allowedValues); }
    }

    private final List<Correction> corrections;
    private final String instruction;

    public OutputContractRejection(String code, List<Correction> corrections) {
        this(code, corrections, "交付尚未冻结。按所列字段修正完整清单后重试；omit_field=true 时省略该字段，不能传空字符串。"
                + "无法交付时用空 deliverables 和非空 abandon_reason 显式放弃，不直接回复绕过门禁。");
    }

    /** 登记和冻结使用各自的恢复指引，避免把尚未登记的请求当成不可变计划。 */
    public OutputContractRejection(String code, List<Correction> corrections, String instruction) {
        super(code);
        this.corrections = List.copyOf(corrections);
        this.instruction = java.util.Objects.requireNonNull(instruction);
    }

    public Map<String, Object> feedback() {
        return Map.of("success", false, "error", getMessage(), "corrections", corrections,
                "instruction", instruction);
    }
}
