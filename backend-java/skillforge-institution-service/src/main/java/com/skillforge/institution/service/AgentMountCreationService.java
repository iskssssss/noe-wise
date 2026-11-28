package com.skillforge.institution.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.common.TenantIds;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原创建回执与挂载共享事务；读取回执不重新解析或变更当前挂载。 */
@Service
public class AgentMountCreationService {
    private final CapabilityMountManagementService mounts;
    private final AgentMountCreateReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final FoundationAccess access;
    private final InstitutionResourceAccess resources;
    private final ObjectMapper json;

    public AgentMountCreationService(CapabilityMountManagementService mounts, AgentMountCreateReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context, FoundationAccess access,
            InstitutionResourceAccess resources, ObjectMapper json) {
        this.mounts = mounts; this.receipts = receipts; this.context = context;
        this.access = access; this.resources = resources; this.json = json;
    }

    @Transactional(rollbackFor = Exception.class)
    public Result create(UUID requestId, long agentId, CapabilityMountRequest payload) {
        access.require("foundation.agents.create");
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        String actor = subject(tenant), fingerprint = fingerprint(payload);
        context.bindTenant(tenant);
        resources.require("agent", Long.toString(agentId), "manage");
        if (receipts.claim(requestId.toString(), tenant, actor, agentId, fingerprint) == 0) {
            var row = receipts.find(requestId.toString());
            if (row == null || !actor.equals(row.actorRef()) || agentId != row.agentId()
                    || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "AGENT_MOUNT_CREATE_IDENTITY_CONFLICT",
                        "能力挂载创建请求标识已用于不同内容、智能体或操作人", false);
            return new Result(requestId, Long.toString(agentId), row.mountId());
        }
        var created = mounts.create(tenant, agentId, payload).mounts().stream()
                .filter(mount -> mount.capability().capabilityId().equals(payload.capabilityDefinitionId().toString()))
                .findFirst().orElseThrow(() -> new IllegalStateException("创建结果缺少目标能力挂载"));
        if (receipts.complete(requestId.toString(), Long.parseLong(created.mountId())) != 1)
            throw new IllegalStateException("能力挂载创建回执写入失败");
        return new Result(requestId, Long.toString(agentId), created.mountId());
    }

    @Transactional(readOnly = true)
    public Result result(UUID requestId, long agentId) {
        access.require("foundation.agents.read");
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        String actor = subject(tenant);
        context.bindTenant(tenant);
        resources.require("agent", Long.toString(agentId), "read");
        var row = receipts.find(requestId.toString());
        if (row == null || !actor.equals(row.actorRef()) || row.agentId() != agentId) return null;
        return new Result(requestId, Long.toString(agentId), row.mountId());
    }

    private String subject(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "能力挂载创建身份与当前机构不匹配");
        return subject.memberId();
    }

    private String fingerprint(CapabilityMountRequest payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(payload)));
        } catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw new IllegalStateException("能力挂载创建请求无法编码", failure);
        }
    }

    public record Result(@JsonProperty("request_id") UUID requestId,
                         @JsonProperty("agent_id") String agentId,
                         @JsonProperty("mount_id") String mountId) { }
}
