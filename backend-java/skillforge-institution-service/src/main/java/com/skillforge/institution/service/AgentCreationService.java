package com.skillforge.institution.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.agent.dto.request.CreateAgentRequest;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.common.TenantIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 创建草稿和原请求回执在同一事务提交。 */
@Service
public class AgentCreationService {
    private final AgentManagementService agents;
    private final AgentCreateReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final FoundationAccess access;
    private final ObjectMapper json;
    public AgentCreationService(AgentManagementService agents, AgentCreateReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context, FoundationAccess access, ObjectMapper json) {
        this.agents = agents; this.receipts = receipts; this.context = context; this.access = access; this.json = json;
    }
    @Transactional(rollbackFor = Exception.class)
    public Result create(Command command) {
        access.require("foundation.agents.create");
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        String actor = subject(tenant), requestId = command.requestId().toString(), fingerprint = fingerprint(command.payload());
        context.bindTenant(tenant);
        if (receipts.claim(requestId, tenant, actor, fingerprint) == 0) {
            var row = receipts.find(requestId);
            if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "AGENT_CREATE_IDENTITY_CONFLICT", "智能体创建请求标识已用于不同内容或操作人", false);
            return new Result(command.requestId(), row.agentId());
        }
        var agent = agents.createAgent(command.payload());
        if (receipts.complete(requestId, agent.id()) != 1) throw new IllegalStateException("智能体创建回执写入失败");
        return new Result(command.requestId(), agent.id().toString());
    }
    @Transactional(readOnly = true)
    public Result result(UUID requestId) {
        access.require("foundation.agents.read");
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var row = receipts.find(requestId.toString());
        if (row == null || !actor.equals(row.actorRef())) return null;
        agents.getAgentItem(row.agentId());
        return new Result(requestId, row.agentId());
    }
    private String subject(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "智能体创建身份与当前机构不匹配");
        return subject.memberId();
    }
    private String fingerprint(CreateAgentRequest payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(payload))); }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) { throw new IllegalStateException("智能体创建请求无法编码", failure); }
    }
    public record Command(@JsonProperty("request_id") @NotNull UUID requestId,
                          @NotNull @Valid CreateAgentRequest payload) { }
    public record Result(@JsonProperty("request_id") UUID requestId, @JsonProperty("agent_id") String agentId) { }
}
