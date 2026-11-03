package com.skillforge.institution.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.dto.capability.CreateCapabilityDeploymentRequest;
import com.skillforge.institution.service.TenantToolDeploymentProviderValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工具部署与原请求回执在同一事务中提交。 */
@Service
public class ToolDeploymentCreationService {
    private final FoundationAccess access;
    private final CapabilityCatalogDatabaseContext context;
    private final CapabilityCatalogService catalog;
    private final TenantToolDeploymentProviderValidator validator;
    private final ToolDeploymentCreateReceiptMapper receipts;
    private final ObjectMapper json;

    public ToolDeploymentCreationService(FoundationAccess access, CapabilityCatalogDatabaseContext context,
            CapabilityCatalogService catalog, TenantToolDeploymentProviderValidator validator,
            ToolDeploymentCreateReceiptMapper receipts, ObjectMapper json) {
        this.access = access;
        this.context = context;
        this.catalog = catalog;
        this.validator = validator;
        this.receipts = receipts;
        this.json = json;
    }

    @Transactional
    public Result create(long tenant, Command command) {
        access.require("foundation.capabilities.create");
        String actor = subject(tenant);
        CreateCapabilityDeploymentRequest payload = normalize(command.payload());
        String fingerprint = fingerprint(payload);
        String requestId = command.requestId().toString();
        context.bindTenant(tenant);
        if (receipts.claim(tenant, requestId, actor, fingerprint) == 0) {
            var existing = receipts.find(tenant, requestId);
            if (existing == null || !actor.equals(existing.actorRef()) || !fingerprint.equals(existing.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "工具部署创建请求标识已用于不同内容或操作人");
            return result(command.requestId(), existing);
        }
        var provider = provider(payload.providerType());
        var environment = environment(payload.environment());
        validator.validate(tenant, provider, payload.providerRef(), environment);
        var deployment = catalog.createDeployment(CapabilityCatalogScope.tenant(tenant), payload.deploymentKey(),
                payload.toolVersionId(), provider, payload.providerRef(), environment, payload.status());
        if (receipts.complete(tenant, requestId, deployment.id(), deployment.deploymentKey()) != 1)
            throw new IllegalStateException("工具部署创建回执写入失败");
        return new Result(command.requestId(), "SUCCEEDED", deployment.id().toString(), deployment.deploymentKey(), null, null);
    }

    /** 首次事务已回滚后，重新竞争同一请求身份并持久记录确定拒绝。 */
    @Transactional
    public Result reject(long tenant, Command command) {
        access.require("foundation.capabilities.create");
        String actor = subject(tenant);
        var payload = normalize(command.payload());
        String fingerprint = fingerprint(payload);
        String requestId = command.requestId().toString();
        context.bindTenant(tenant);
        if (receipts.claim(tenant, requestId, actor, fingerprint) == 1) {
            if (receipts.reject(tenant, requestId, payload.deploymentKey()) != 1)
                throw new IllegalStateException("工具部署拒绝回执写入失败");
        }
        var row = receipts.find(tenant, requestId);
        if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
            throw new ApiException(HttpStatus.CONFLICT, "工具部署创建请求标识已用于不同内容或操作人");
        return result(command.requestId(), row);
    }

    @Transactional(readOnly = true)
    public Result result(long tenant, UUID requestId) {
        access.require("foundation.capabilities.read");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var row = receipts.find(tenant, requestId.toString());
        return row == null || !actor.equals(row.actorRef()) ? null : result(requestId, row);
    }

    private Result result(UUID requestId, ToolDeploymentCreateReceiptMapper.Row row) {
        if ("REJECTED".equals(row.outcome()))
            return new Result(requestId, "REJECTED", null, row.deploymentKey(),
                    "TOOL_DEPLOYMENT_CREATE_REJECTED", "工具部署创建被拒绝，请检查部署标识是否已存在及工具与提供方配置。");
        if (!"SUCCEEDED".equals(row.outcome()))
            throw new IllegalStateException("工具部署创建回执尚未完成");
        if (row.deploymentId() == null || row.deploymentKey() == null)
            throw new IllegalStateException("工具部署创建回执不完整");
        return new Result(requestId, "SUCCEEDED", row.deploymentId().toString(), row.deploymentKey(), null, null);
    }

    private String subject(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }

    private CreateCapabilityDeploymentRequest normalize(CreateCapabilityDeploymentRequest request) {
        return new CreateCapabilityDeploymentRequest(request.deploymentKey().trim(), request.toolVersionId(),
                request.providerType().trim().toUpperCase(Locale.ROOT), request.providerRef().trim(),
                request.environment().trim().toUpperCase(Locale.ROOT),
                request.status() == null || request.status().isBlank() ? "ACTIVE" : request.status().trim().toUpperCase(Locale.ROOT));
    }

    private ToolDeployment.ProviderType provider(String value) {
        try {
            return ToolDeployment.ProviderType.valueOf(value);
        } catch (IllegalArgumentException failure) {
            throw CapabilityCatalogErrors.exception(HttpStatus.UNPROCESSABLE_ENTITY,
                    CapabilityCatalogErrors.DEPLOYMENT_INVALID, "不支持的 provider_type: " + value, failure);
        }
    }

    private ToolDeployment.Environment environment(String value) {
        try {
            return ToolDeployment.Environment.valueOf(value);
        } catch (IllegalArgumentException failure) {
            throw CapabilityCatalogErrors.exception(HttpStatus.UNPROCESSABLE_ENTITY,
                    CapabilityCatalogErrors.DEPLOYMENT_INVALID, "不支持的 environment: " + value, failure);
        }
    }

    private String fingerprint(CreateCapabilityDeploymentRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(request)));
        } catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw new IllegalStateException("工具部署创建请求无法编码", failure);
        }
    }

    public record Command(@JsonProperty("request_id") @NotNull UUID requestId,
            @NotNull @Valid CreateCapabilityDeploymentRequest payload) { }
    public record Result(@JsonProperty("request_id") UUID requestId, String status,
            @JsonProperty("deployment_id") String deploymentId,
            @JsonProperty("deployment_key") String deploymentKey,
            @JsonProperty("error_code") String errorCode, String message) { }
}
