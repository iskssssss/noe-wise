package com.skillforge.institution.group;

import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 外部回流只能引用同群原 Run 已公开的准确消息，不能把私有能力结果升级成群事实。 */
@Component
public final class RuntimeGroupMessageSourceValidator implements GroupMessageSourceValidator {
    private final ExecutionStore executions;
    private final AgentRuntimeExecutionProjection projection;
    private final GroupBindingService groups;

    public RuntimeGroupMessageSourceValidator(ExecutionStore executions,
            AgentRuntimeExecutionProjection projection, GroupBindingService groups) {
        this.executions = Objects.requireNonNull(executions);
        this.projection = Objects.requireNonNull(projection);
        this.groups = Objects.requireNonNull(groups);
    }

    @Override
    public void require(String tenantId, String integrationId, String groupBindingId, String sessionId,
            GroupMessageContracts.Author author, List<ChatContent> content) {
        var group = groups.requireActive(tenantId, integrationId, groupBindingId);
        if (!group.sessionId().equals(sessionId)) throw denied();
        // 当前公开投影的回复作者是实际 Agent。System/Tool 必须有各自正式公开来源，
        // 私有 Tool Action、一个任意字符串或 SP 的写 scope 都不能代替这项事实。
        if (!(author instanceof GroupMessageContracts.AgentAuthor agent)) throw denied();
        groups.requireServiceAgent(tenantId, integrationId, groupBindingId, agent.agentId());
        var tenant = new TenantId(tenantId);
        var run = executions.load(new RunRef(tenant, agent.runId())).orElseThrow(RuntimeGroupMessageSourceValidator::denied);
        var owner = run.identity().owner();
        if (!(owner.subject() instanceof SessionSubject.Group subject)
                || !subject.groupBindingRef().groupBindingId().equals(groupBindingId)
                || !owner.sessionRef().equals(new SessionRef(tenant, sessionId))
                || !run.identity().agentId().value().equals(agent.agentId().toString())) throw denied();
        var visible = projection.readSource(owner, run.identity().runRef()).messages().stream()
                .filter(message -> "assistant".equals(message.role()) && message.messageId().equals(agent.sourceRef()))
                .filter(message -> "completed".equals(message.state()))
                .anyMatch(message -> ChatContent.text(message.content()).equals(content));
        if (!visible) throw denied();
    }

    private static ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "group_source_not_public",
                "回流消息必须匹配同群原运行已正式公开的作者与完整内容", false);
    }
}
