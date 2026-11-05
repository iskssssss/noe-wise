package com.skillforge.institution.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 启停CAS与原结果原子提交；查询回执不从当前部署推测原操作。 */
@Service
public class ToolDeploymentStateService {
    private final FoundationAccess access;
    private final CapabilityCatalogDatabaseContext context;
    private final CapabilityCatalogService catalog;
    private final ToolDeploymentStateReceiptMapper receipts;

    public ToolDeploymentStateService(FoundationAccess access, CapabilityCatalogDatabaseContext context,
            CapabilityCatalogService catalog, ToolDeploymentStateReceiptMapper receipts) {
        this.access = access; this.context = context; this.catalog = catalog; this.receipts = receipts;
    }

    @Transactional
    public Result change(long tenant, Command command) {
        access.require(command.active() ? "foundation.capabilities.run" : "foundation.capabilities.cancel");
        String actor = subject(tenant), fingerprint = fingerprint(command), id = command.requestId().toString();
        context.bindTenant(tenant);
        if (claim(tenant, command, actor, fingerprint) == 0) return original(tenant, command, actor, fingerprint);
        var updated = catalog.setDeploymentActive(CapabilityCatalogScope.tenant(tenant), command.deploymentKey(),
                command.expectedRevision(), command.active());
        if (receipts.complete(tenant, id, updated.status(), updated.revision()) != 1)
            throw new IllegalStateException("工具部署启停回执写入失败");
        return new Result(command.requestId(), "SUCCEEDED", command.deploymentKey(), updated.status(), updated.revision(), null, null);
    }

    /** 仅在原事务因确定CAS拒绝回滚后调用；再次竞争原身份，避免覆盖并发成功。 */
    @Transactional
    public Result reject(long tenant, Command command) {
        access.require(command.active() ? "foundation.capabilities.run" : "foundation.capabilities.cancel");
        String actor = subject(tenant), fingerprint = fingerprint(command);
        context.bindTenant(tenant);
        if (claim(tenant, command, actor, fingerprint) == 1
                && receipts.reject(tenant, command.requestId().toString()) != 1)
            throw new IllegalStateException("工具部署启停拒绝回执写入失败");
        return original(tenant, command, actor, fingerprint);
    }

    @Transactional(readOnly = true)
    public Result result(long tenant, UUID requestId) {
        access.require("foundation.capabilities.read");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var row = receipts.find(tenant, requestId.toString());
        return row == null || !actor.equals(row.actorRef()) ? null : result(requestId, row);
    }

    private int claim(long tenant, Command command, String actor, String fingerprint) {
        return receipts.claim(tenant, command.requestId().toString(), actor, fingerprint,
                command.deploymentKey(), command.expectedRevision(), command.active());
    }

    private Result original(long tenant, Command command, String actor, String fingerprint) {
        var row = receipts.find(tenant, command.requestId().toString());
        if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_DEPLOYMENT_STATE_REQUEST_CONFLICT",
                    "工具部署启停请求标识已用于不同参数或操作人", false);
        return result(command.requestId(), row);
    }

    private Result result(UUID requestId, ToolDeploymentStateReceiptMapper.Row row) {
        if ("REJECTED".equals(row.outcome()))
            return new Result(requestId, "REJECTED", row.deploymentKey(), null, null,
                    "TOOL_DEPLOYMENT_STATE_REVISION_CONFLICT", "部署修订已变化，原启停未执行；请读取最新状态后决定后续操作。");
        if (!"SUCCEEDED".equals(row.outcome()) || row.resultRevision() == null || row.deploymentStatus() == null)
            throw new IllegalStateException("工具部署启停回执不完整");
        return new Result(requestId, "SUCCEEDED", row.deploymentKey(), row.deploymentStatus(), row.resultRevision(), null, null);
    }

    private String subject(long tenant) {
        var actor = access.currentSubject();
        if (actor.platform() || !Long.toString(tenant).equals(actor.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return actor.memberId();
    }

    private String fingerprint(Command command) {
        // key长度有界，换行按原文编码，固定字段长度前缀避免分隔符歧义。
        String text = "tool-deployment.state.v1\n" + command.deploymentKey().length() + ":"
                + command.deploymentKey() + "\n" + command.expectedRevision() + "\n" + command.active();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record Command(@JsonProperty("request_id") @NotNull UUID requestId,
            @JsonProperty("deployment_key") @NotBlank @Size(max=128) String deploymentKey,
            @JsonProperty("expected_revision") @Min(1) long expectedRevision, @NotNull Boolean active) { }
    public record Result(@JsonProperty("request_id") UUID requestId, String status,
            @JsonProperty("deployment_key") String deploymentKey,
            @JsonProperty("deployment_status") String deploymentStatus, Long revision,
            @JsonProperty("error_code") String errorCode, @JsonProperty("error_message") String errorMessage) { }
}
