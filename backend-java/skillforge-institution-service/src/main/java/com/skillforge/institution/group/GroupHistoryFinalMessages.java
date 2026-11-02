package com.skillforge.institution.group;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 派发只消费同群原Run已正式采纳的准确final，不从私有Action或普通文本提升发送资格。 */
@Component
public final class GroupHistoryFinalMessages implements GroupDeliveryFinalMessages {
    private final ExecutionStore executions;
    private final GroupBindingService groups;
    private final GroupHistoryMapper mapper;
    private final GroupHistoryService history;
    private final AgentRuntimeExecutionProjection projection;

    public GroupHistoryFinalMessages(ExecutionStore executions,GroupBindingService groups,GroupHistoryMapper mapper,
            GroupHistoryService history,AgentRuntimeExecutionProjection projection) {
        this.executions=executions;this.groups=groups;this.mapper=mapper;this.history=history;this.projection=projection;
    }

    @Override
    public FinalMessage requireFinal(String tenant,String integration,String group,String runId,String messageId,long version) {
        if(!Objects.equals(tenant,TenantContext.getTenantId())||version<1)throw denied();
        var binding=groups.requireActive(tenant,integration,group);
        var run=executions.load(new RunRef(new TenantId(tenant),runId)).orElseThrow(GroupHistoryFinalMessages::denied);
        var owner=run.identity().owner();
        if(!(owner.subject() instanceof SessionSubject.Group subject)||!subject.groupBindingRef().groupBindingId().equals(group)
                ||!owner.sessionRef().sessionId().equals(binding.sessionId())||run.phase()!=ExecutionStore.Phase.FINISHED
                ||run.status()!=RunStatus.COMPLETED)throw denied();
        groups.requireServiceAgent(tenant,integration,group,Long.parseLong(run.identity().agentId().value()));
        var row=mapper.exactMessage(Long.parseLong(tenant),binding.sessionId(),messageId,version);
        if(row==null||!runId.equals(row.runtimeRunId()))throw denied();
        var message=projection.publicMessage(history.output(row));
        if(!message.messageId().equals(messageId)||message.messageVersion()!=version||!"assistant".equals(message.role())
                ||!"completed".equals(message.state())||!"final".equals(message.phase()))throw denied();
        return new FinalMessage(run.identity(),message.messageId(),message.messageVersion(),message.content());
    }

    private static ApiException denied(){return new ApiException(HttpStatus.FORBIDDEN,"group_final_not_available","未找到当前有权派发的原群正式final",false);}
}
