package com.skillforge.institution.group;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 当前真实群管理员维护策略；关闭不要求已移除的执行 Agent 仍有资格。 */
@Service
public class GroupMemoryExtractionPolicyService {
    private final GroupMemoryExtractionMapper mapper;
    private final GroupControlMapper controls;
    private final GroupBindingService groups;
    private final InstitutionIntegrationService integrations;

    public GroupMemoryExtractionPolicyService(GroupMemoryExtractionMapper mapper, GroupControlMapper controls,
            GroupBindingService groups, InstitutionIntegrationService integrations) {
        this.mapper=mapper; this.controls=controls; this.groups=groups; this.integrations=integrations;
    }

    @Transactional(readOnly=true)
    public GroupMemoryExtractionPolicy.View get(String group, long operator) {
        var actor=authorize(group,operator,"groups.memory.read");
        return view(group,mapper.policy(actor.tenantId(),actor.integrationId(),group));
    }

    @Transactional
    public GroupMemoryExtractionPolicy.View put(GroupMemoryExtractionPolicy.Command command) {
        String group=command.groupBindingId();
        var actor=authorize(group,command.operatorEndUserId(),"groups.memory.write");
        long tenant=Long.parseLong(actor.tenantId()), groupId=Long.parseLong(group);
        controls.ensure(tenant,actor.integrationId(),groupId);
        if(controls.lock(tenant,actor.integrationId(),groupId)==null)throw new IllegalStateException("memory_group_control_missing");
        var prior=mapper.policy(actor.tenantId(),actor.integrationId(),group);
        if((prior==null?0:prior.revision())!=command.expectedRevision())throw new ApiException(HttpStatus.CONFLICT,"memory_group_policy_revision_conflict");
        if(command.enabled()) {
            groups.requireServiceAgent(actor.tenantId(),actor.integrationId(),group,command.extractorAgentId());
            if(!mapper.agentEnabled(actor.tenantId(),Long.toString(command.extractorAgentId())))
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"memory_group_extractor_disabled");
        }
        var row=new GroupMemoryExtractionMapper.Policy(actor.tenantId(),actor.integrationId(),group,command.enabled(),
                Long.toString(command.extractorAgentId()),command.expectedRevision()+1,actor.principalId());
        if(mapper.savePolicy(row,command.expectedRevision())!=1)throw new ApiException(HttpStatus.CONFLICT,"memory_group_policy_revision_conflict");
        return view(group,row);
    }

    private ServicePrincipalContext.Identity authorize(String group,long operator,String scope) {
        var actor=ServicePrincipalContext.get();
        if(actor==null||!Objects.equals(actor.tenantId(),TenantContext.getTenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN,"group_memory_principal_required");
        integrations.requirePrincipal(actor,scope);
        groups.requireActive(actor.tenantId(),actor.integrationId(),group);
        groups.requireMember(actor.tenantId(),actor.integrationId(),group,operator,true);
        return actor;
    }

    private static GroupMemoryExtractionPolicy.View view(String group,GroupMemoryExtractionMapper.Policy row) {
        return row==null?new GroupMemoryExtractionPolicy.View(group,false,null,0)
                :new GroupMemoryExtractionPolicy.View(group,row.enabled(),row.extractorAgentId(),row.revision());
    }
}
