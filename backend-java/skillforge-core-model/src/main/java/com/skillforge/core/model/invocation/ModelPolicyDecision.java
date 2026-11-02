package com.skillforge.core.model.invocation;

/** 模型调用前治理结果。 */
public record ModelPolicyDecision(Outcome outcome, String reasonCode) {

    public enum Outcome {
        ALLOW,
        DENY
    }

    public ModelPolicyDecision {
        if (outcome == null) {
            throw new IllegalArgumentException("model policy outcome is required");
        }
        reasonCode = reasonCode == null ? "" : reasonCode;
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOW;
    }

    public static ModelPolicyDecision allow(String reasonCode) {
        return new ModelPolicyDecision(Outcome.ALLOW, reasonCode);
    }

    public static ModelPolicyDecision deny(String reasonCode) {
        return new ModelPolicyDecision(Outcome.DENY, reasonCode);
    }
}
