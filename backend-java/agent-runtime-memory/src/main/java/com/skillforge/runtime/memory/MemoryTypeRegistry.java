package com.skillforge.runtime.memory;

import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.TypedPayload;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 解析显式 Memory 类型并验证其不透明 payload schema。 */
public interface MemoryTypeRegistry {

    /** 未知类型返回空，由 Runtime 拒绝。 */
    Optional<Registration> find(MemoryTypeRef type);

    /** SDK-2D 所需的冻结最小策略；高级提取策略不在其中。 */
    record Registration(
            MemoryTypeRef type,
            Set<MemoryScope> allowedScopes,
            List<Schema> schemas,
            RetentionClassRef retentionClass,
            boolean recentEpisodic,
            boolean forceReview,
            PayloadValidator validator) {

        public Registration {
            type = Objects.requireNonNull(type, "type");
            allowedScopes = Set.copyOf(Objects.requireNonNull(allowedScopes, "allowedScopes"));
            schemas = List.copyOf(Objects.requireNonNull(schemas, "schemas"));
            retentionClass = Objects.requireNonNull(retentionClass, "retentionClass");
            validator = Objects.requireNonNull(validator, "validator");
            if (allowedScopes.isEmpty() || schemas.isEmpty()) {
                throw new IllegalArgumentException("Memory registration scopes and schemas must not be empty");
            }
            HashSet<String> versions = new HashSet<>();
            for (Schema schema : schemas) {
                if (!versions.add(schema.schemaVersion())) {
                    throw new IllegalArgumentException("Memory schema versions must be distinct");
                }
            }
            if (recentEpisodic && !allowedScopes.contains(MemoryScope.USER_AGENT)) {
                throw new IllegalArgumentException("RECENT_EPISODIC type must allow USER_AGENT scope");
            }
        }

        /** 解析某个事实 schema version 对应的精确 TypedPayload schema。 */
        public Optional<Schema> schema(String schemaVersion) {
            return schemas.stream().filter(value -> value.schemaVersion().equals(schemaVersion)).findFirst();
        }
    }

    /** 将事实 schema version 绑定到不透明 TypedPayload schema id。 */
    record Schema(String schemaVersion, String payloadSchemaId) {
        public Schema {
            schemaVersion = text(schemaVersion, "schemaVersion");
            payloadSchemaId = text(payloadSchemaId, "payloadSchemaId");
        }
    }

    /** 由宿主注册表提供 schema 实现，本模块不带宽松默认实现。 */
    @FunctionalInterface
    interface PayloadValidator {
        Validation validate(String schemaVersion, TypedPayload payload);
    }

    /** 显式验证结果；缺失结果或异常都按拒绝处理。 */
    sealed interface Validation permits Validation.Allowed, Validation.Rejected {

        /** 规范 payload 符合已注册 schema。 */
        record Allowed() implements Validation { }

        /** payload 已被安全拒绝。 */
        record Rejected(String reasonCode) implements Validation {
            public Rejected {
                reasonCode = text(reasonCode, "reasonCode");
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
