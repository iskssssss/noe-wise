package com.skillforge.institution.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.common.TenantIds;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原恢复回执与草稿、挂载替换共用事务，不根据当前配置推断原请求结果。 */
@Service
public class AgentRevisionRestoreService {
    private final AgentManagementService agents;
    private final AgentRevisionRestoreReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final FoundationAccess access;
    public AgentRevisionRestoreService(AgentManagementService agents, AgentRevisionRestoreReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context, FoundationAccess access) {
        this.agents=agents; this.receipts=receipts; this.context=context; this.access=access;
    }
    @Transactional(rollbackFor=Exception.class)
    public Result restore(String agentId, int revision, UUID requestId) {
        access.require("foundation.agents.update");
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        String actor=subject(tenant);
        context.bindTenant(tenant);
        agents.getAgentItem(agentId);
        access.requireResource("agent",agentId,"manage");
        if(receipts.claim(tenant,requestId.toString(),actor,agentId,revision)==0) {
            var row=receipts.find(tenant,requestId.toString());
            if(row==null || !actor.equals(row.actorRef()) || !agentId.equals(row.agentId()) || revision!=row.revision())
                throw new ApiException(HttpStatus.CONFLICT,"AGENT_RESTORE_IDENTITY_CONFLICT","恢复请求标识已用于不同内容或操作人",false);
            return new Result(requestId,row.agentId(),row.revision());
        }
        agents.restoreRevision(agentId,revision);
        return new Result(requestId,agentId,revision);
    }
    @Transactional(readOnly=true)
    public Result result(String agentId, UUID requestId) {
        access.require("foundation.agents.read");
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        String actor=subject(tenant);
        context.bindTenant(tenant);
        agents.getAgentItem(agentId);
        var row=receipts.find(tenant,requestId.toString());
        if(row==null || !actor.equals(row.actorRef()) || !agentId.equals(row.agentId())) return null;
        return new Result(requestId,row.agentId(),row.revision());
    }
    private String subject(long tenant) {
        var subject=access.currentSubject();
        if(subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN,"恢复身份与当前机构不匹配");
        return subject.memberId();
    }
    public record Result(@JsonProperty("request_id") UUID requestId,
            @JsonProperty("agent_id") String agentId, int revision) {}
}
