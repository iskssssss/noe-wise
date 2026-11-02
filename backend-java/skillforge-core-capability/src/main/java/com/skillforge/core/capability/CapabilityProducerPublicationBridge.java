package com.skillforge.core.capability;

import com.skillforge.core.shared.exception.ApiException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 四类 Capability producer 的内部发布桥接。
 *
 * <p>Workflow、Knowledge、Skill 与 Tool 的生产服务只依赖此窄边界，不直接依赖
 * mapper。一次调用在同一事务中完成 Definition 复用/创建、Draft 创建、严格校验和
 * Definition revision CAS 发布。桥接层只负责公共发布编排；类型治理字段仍完全由
 * producer descriptor 和 Catalog Codec 负责。
 */
@Service
public class CapabilityProducerPublicationBridge {

    private static final String PUBLISHED = "PUBLISHED";
    private static final String DUPLICATE = CapabilityCatalogErrors.DUPLICATE;

    private final CapabilityCatalogService catalog;

    public CapabilityProducerPublicationBridge(CapabilityCatalogService catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * 发布一个 producer 提供的 Capability 版本。
     *
     * <p>已存在的同一 versionKey 只有在状态为 PUBLISHED 且 contentHash 相同时才
     * 幂等返回；Draft、Deprecated、Archived 或内容不一致均返回稳定冲突。新的版本
     * 必须经过 Catalog 的 create/validate/publish 生命周期，不能直接写入或执行能力。
     */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityProducerPublicationResult publish(
            CapabilityProducerPublicationRequest request) {
        ValidatedRequest input = validate(request);
        CapabilityDefinitionView definition = catalog.findDefinitionForWrite(
                        input.scope(), input.capabilityType(), input.capabilityKey())
                .orElseGet(() -> catalog.createDefinition(
                        input.scope(), input.capabilityType(), input.capabilityKey(),
                        input.name(), input.description()));

        return publish(input, definition);
    }

    /**
     * 首次安装预置包。任何已存在的定义（包括草稿、停用或归档）均由后台管理，
     * 应用升级不得替换其默认版本或恢复已撤销的发布。
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<CapabilityProducerPublicationResult> installIfAbsent(
            CapabilityProducerPublicationRequest request) {
        ValidatedRequest input = validate(request);
        if (catalog.findDefinitionForWrite(input.scope(), input.capabilityType(), input.capabilityKey()).isPresent()) {
            return Optional.empty();
        }
        // 唯一键裁定并发安装；不在冲突后复用别的安装者或管理员创建的定义继续发布。
        CapabilityDefinitionView definition = catalog.createDefinition(input.scope(), input.capabilityType(),
                input.capabilityKey(), input.name(), input.description());
        return Optional.of(publish(input, definition));
    }

    private CapabilityProducerPublicationResult publish(ValidatedRequest input, CapabilityDefinitionView definition) {

        Optional<CapabilityVersionView> existing = catalog.findVersionByKey(
                input.scope(), definition.id(), input.versionKey());
        if (existing.isPresent()) {
            return resolveExisting(input, definition, existing.get());
        }

        // DB 也对 (definition_id, content_hash) 建唯一约束；提前查找把该约束转换为
        // 不依赖数据库异常文本的稳定业务冲突码，并拒绝用新 key 重复注册同一内容。
        if (catalog.findVersionByContentHash(
                input.scope(), definition.id(), input.contentHash()).isPresent()) {
            throw conflict("capability content hash already belongs to another version key");
        }

        CapabilityVersionView draft;
        try {
            draft = catalog.createDraftVersion(
                    input.scope(), definition.id(), input.versionKey(), input.name(),
                    input.description(), input.contentHash(), input.descriptor());
        } catch (ApiException exception) {
            throw mapDuplicateToConflict(exception);
        }

        CapabilityValidationView validation = catalog.validateVersion(
                input.scope(), definition.id(), draft.id());
        if (!validation.valid()) {
            throw validationFailure(validation);
        }

        CapabilityVersionView published;
        try {
            published = catalog.publishVersion(
                    input.scope(), definition.id(), draft.id(), validation.definitionRevision());
        } catch (ApiException exception) {
            throw mapDuplicateToConflict(exception);
        }
        // publishVersion atomically advances Definition status/revision. Reload through the
        // scope-bound Catalog API so the producer receives the committed identity snapshot,
        // rather than the pre-publish DRAFT view used to orchestrate the transaction.
        CapabilityDefinitionView publishedDefinition = catalog.getDefinition(
                input.scope(), definition.id());
        return new CapabilityProducerPublicationResult(publishedDefinition, published, false);
    }

    private CapabilityProducerPublicationResult resolveExisting(
            ValidatedRequest input,
            CapabilityDefinitionView definition,
            CapabilityVersionView existing) {
        if (!PUBLISHED.equals(existing.status())
                || !Objects.equals(input.contentHash(), existing.contentHash())) {
            throw conflict("capability version key already exists with a non-identical state");
        }
        if (!Objects.equals(existing.capabilityType(), input.capabilityType())
                || !Objects.equals(existing.definitionId(), definition.id())
                || !Objects.equals(existing.tenantId(), input.scope().tenantId())) {
            // The Catalog query already enforces this identity. Keep the check here so a
            // malformed adapter cannot turn a cross-scope result into an idempotent success.
            throw conflict("capability version identity does not match the write scope");
        }
        return new CapabilityProducerPublicationResult(definition, existing, true);
    }

    private ValidatedRequest validate(CapabilityProducerPublicationRequest request) {
        if (request == null) {
            throw invalid("publication request is required");
        }
        CapabilityCatalogScope scope = request.scope();
        if (scope == null) {
            throw invalid("scope is required");
        }
        CapabilityType type = request.capabilityType();
        if (type == null) {
            throw invalid("capabilityType is required");
        }
        String capabilityKey = text(request.capabilityKey(), "capabilityKey");
        String versionKey = text(request.versionKey(), "versionKey");
        String name = text(request.name(), "name");
        if (request.description() == null) {
            throw invalid("description is required");
        }
        String contentHash = text(request.contentHash(), "contentHash");
        Map<String, Object> descriptor = request.descriptor();
        if (descriptor == null) {
            throw invalid("descriptor is required");
        }
        Map<String, Object> immutableDescriptor = Collections.unmodifiableMap(
                new LinkedHashMap<>(descriptor));
        return new ValidatedRequest(scope, type, capabilityKey, versionKey, name,
                request.description(), contentHash, immutableDescriptor);
    }

    private String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        return value.trim();
    }

    private ApiException validationFailure(CapabilityValidationView validation) {
        CapabilityCatalogError first = validation.errors().stream().findFirst()
                .orElse(new CapabilityCatalogError(
                        CapabilityCatalogErrors.DESCRIPTOR_INVALID,
                        "descriptor validation failed"));
        return CapabilityCatalogErrors.exception(
                HttpStatus.UNPROCESSABLE_ENTITY, first.code(), first.message());
    }

    private ApiException mapDuplicateToConflict(ApiException exception) {
        if (DUPLICATE.equals(exception.getCode())) {
            return conflict("capability publication conflicts with an existing version", exception);
        }
        return exception;
    }

    private ApiException invalid(String message) {
        return CapabilityCatalogErrors.exception(
                HttpStatus.UNPROCESSABLE_ENTITY, CapabilityCatalogErrors.INVALID_ARGUMENT, message);
    }

    private ApiException conflict(String message) {
        return conflict(message, null);
    }

    private ApiException conflict(String message, Throwable cause) {
        return CapabilityCatalogErrors.exception(
                HttpStatus.CONFLICT, CapabilityCatalogErrors.CONFLICT, message, cause);
    }

    private record ValidatedRequest(
            CapabilityCatalogScope scope,
            CapabilityType capabilityType,
            String capabilityKey,
            String versionKey,
            String name,
            String description,
            String contentHash,
            Map<String, Object> descriptor) {
    }
}
