package com.skillforge.runtime.memory;

import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.PortCallContext;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 逐次授权 Memory 读取或变更，不提供默认允许路径。 */
public interface MemoryAccessPolicy {

    /** 对当前精确操作返回显式且带审计引用的决定。 */
    Decision authorize(Request request);

    /** 不能因 MemoryPort 在 Java 中可见而推断授权的操作。 */
    enum Operation {
        QUERY,
        CREATE,
        SUPERSEDE,
        TRANSITION
    }

    /** 将请求的 Memory 域绑定到已验证的当前调用身份。 */
    record Request(
            PortCallContext context,
            MemoryOwner owner,
            Operation operation,
            Set<MemoryScope> scopes,
            Set<MemoryTypeRef> types,
            Optional<MemoryFactRef> factRef) {

        public Request {
            context = Objects.requireNonNull(context, "context");
            owner = Objects.requireNonNull(owner, "owner");
            operation = Objects.requireNonNull(operation, "operation");
            scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
            types = Set.copyOf(Objects.requireNonNull(types, "types"));
            factRef = Objects.requireNonNull(factRef, "factRef");
            if (scopes.isEmpty()) {
                throw new IllegalArgumentException("scopes must not be empty");
            }
            if (operation == Operation.QUERY && factRef.isPresent()) {
                throw new IllegalArgumentException("query access must not name a fact");
            }
            if ((operation == Operation.SUPERSEDE || operation == Operation.TRANSITION)
                    != factRef.isPresent()) {
                throw new IllegalArgumentException("mutation fact identity does not match operation");
            }
        }
    }

    /** 带审计引用且默认拒绝的访问决定。 */
    sealed interface Decision permits Decision.Allowed, Decision.Denied {

        /** 注入策略已授权这份精确请求。 */
        record Allowed(ResultRef auditRef) implements Decision {
            public Allowed {
                auditRef = Objects.requireNonNull(auditRef, "auditRef");
            }
        }

        /** 请求被拒绝，原因不得暴露策略内部数据。 */
        record Denied(String reasonCode, ResultRef auditRef) implements Decision {
            public Denied {
                reasonCode = text(reasonCode, "reasonCode");
                auditRef = Objects.requireNonNull(auditRef, "auditRef");
            }
        }
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
