package com.skillforge.runtime.memory;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.PortCallContext;
import java.util.List;
import java.util.Objects;

/** Runtime 证明每个事件已提交后，再授权记忆提取来源。 */
public interface MemorySourcePolicy {

    /** 返回显式审计决定；null、异常或拒绝都不能成为允许。 */
    Decision authorize(Request request);

    /** 向策略提供精确草稿和不可变的已提交来源 envelope。 */
    record Request(
            PortCallContext context,
            MemoryFactDraft draft,
            List<CommittedSessionEvent> sources) {

        public Request {
            context = Objects.requireNonNull(context, "context");
            draft = Objects.requireNonNull(draft, "draft");
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("sources must not be empty");
            }
        }
    }

    /** 由 memory.written 固化的来源审计决定。 */
    sealed interface Decision permits Decision.Allowed, Decision.Denied {

        /** 精确来源闭包与草稿已获允许。 */
        record Allowed(ResultRef auditRef) implements Decision {
            public Allowed {
                auditRef = Objects.requireNonNull(auditRef, "auditRef");
            }
        }

        /** 草稿或来源以稳定安全原因被拒绝。 */
        record Denied(String reasonCode, ResultRef auditRef) implements Decision {
            public Denied {
                Objects.requireNonNull(reasonCode, "reasonCode");
                if (reasonCode.isBlank()) {
                    throw new IllegalArgumentException("reasonCode must not be blank");
                }
                auditRef = Objects.requireNonNull(auditRef, "auditRef");
            }
        }
    }
}
