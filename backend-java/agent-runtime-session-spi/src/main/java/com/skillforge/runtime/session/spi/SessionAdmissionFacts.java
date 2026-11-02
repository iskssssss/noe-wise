package com.skillforge.runtime.session.spi;

import java.time.Duration;
import java.util.Objects;

/** 冻结配置的纯业务预算与上下文类型；不再拥有旧Run受理或恢复事实入口。 */
public interface SessionAdmissionFacts {
    record Budget(int maxBusinessModelCalls, int maxToolCalls, int maxKnowledgeCalls,
                  long maxInputTokens, long maxOutputTokens, long maxTotalTokens, Duration maxWallTime) {
        public Budget {
            Objects.requireNonNull(maxWallTime);
            if (maxBusinessModelCalls < 0 || maxToolCalls < 0 || maxKnowledgeCalls < 0 || maxInputTokens <= 0
                    || maxOutputTokens <= 0 || maxTotalTokens <= 0 || maxWallTime.isZero() || maxWallTime.isNegative()) throw invalid();
        }
    }

    enum ContextKind { AGENT_INPUT, RESTRICTED_WORKFLOW }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("session_admission_fact_invalid");}
}
