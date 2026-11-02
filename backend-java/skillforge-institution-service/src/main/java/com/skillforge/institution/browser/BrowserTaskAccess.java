package com.skillforge.institution.browser;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.execution.browser.BrowserReceipt;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimeProgramTools;
import com.skillforge.institution.runtime.AgentRuntimeWorkflowNodeExecutions;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** UI 与模型工具共用原任务来源授权；个人设备归属不能替代产品会话和委派可读性。 */
@Service
public final class BrowserTaskAccess {
    private final ArtifactAccess artifacts;
    private final AgentRunBindings bindings;
    private final ObjectProvider<ExecutionStore> executions;
    private final ObjectProvider<AgentRuntimeProgramTools> programs;
    private final ObjectProvider<AgentRuntimeWorkflowNodeExecutions> workflows;

    public BrowserTaskAccess(ArtifactAccess artifacts, AgentRunBindings bindings,
            ObjectProvider<ExecutionStore> executions, ObjectProvider<AgentRuntimeProgramTools> programs,
            ObjectProvider<AgentRuntimeWorkflowNodeExecutions> workflows) {
        this.artifacts = artifacts;
        this.bindings = bindings;
        this.executions = executions;
        this.programs = programs;
        this.workflows = workflows;
    }

    /** 页面使用当前认证主体，所选产品会话只收窄结果。 */
    public boolean readableCurrent(String tenant, String user, BrowserReceipt receipt, String selected) {
        return readable(tenant, user, receipt, selected, null);
    }

    /** 工具线程只从本次可信 Run 的精确持久绑定取得请求者，不读取模型参数或线程登录态。 */
    public boolean readableForRun(RunIdentity current, BrowserReceipt receipt) {
        try {
            var origin = resourceOrigin(current);
            var caller = bindings.find(origin.runRef());
            if (caller.isEmpty() || !caller.get().owner().equals(origin.owner())
                    || !caller.get().actor().equals(origin.agentId())) return false;
            return readable(current.owner().sessionRef().tenantId().value(),
                    Long.toString(current.owner().requireUser().value()), receipt, null, caller.get().scope().requester());
        } catch (ExecutionStore.Rejection denied) {
            return false;
        }
    }

    private boolean readable(String tenant, String user, BrowserReceipt receipt, String selected,
            AgentRunBindings.Requester requester) {
        try {
            if (receipt.run() == null || receipt.run().isBlank() || receipt.session() == null || receipt.session().isBlank())
                return false;
            var execution = executions.getObject().load(new RunRef(new TenantId(tenant), receipt.run()));
            if (execution.isEmpty() || !execution.get().identity().owner().sessionRef().sessionId().equals(receipt.session()))
                return false;
            var origin = resourceOrigin(execution.get().identity());
            var found = bindings.find(origin.runRef());
            if (found.isEmpty()) return false;
            var source = found.get();
            if (!source.owner().equals(origin.owner()) || !source.actor().equals(origin.agentId())
                    || !source.owner().sessionRef().tenantId().value().equals(tenant)
                    || !Long.toString(source.owner().requireUser().value()).equals(user)
                    || !source.owner().sessionRef().sessionId().equals(receipt.session())) return false;
            String product = source.scope().origin().sessionRef().sessionId();
            if (selected != null && !selected.equals(product)) return false;
            if (requester == null) {
                artifacts.authorizeCurrent(tenant, product);
                bindings.authorizeRead(source, source.actor().value());
            } else {
                bindings.authorizeRead(source, source.actor().value(), requester);
            }
            return true;
        } catch (SecurityException | ExecutionStore.Rejection denied) {
            return false;
        } catch (ApiException denied) {
            if (Set.of(HttpStatus.FORBIDDEN, HttpStatus.GONE, HttpStatus.NOT_FOUND).contains(denied.getStatus()))
                return false;
            throw denied;
        }
    }

    /** 复用已登记子Run的持久父链解析；延迟取得执行装配，避免Handler注册与来源解析器成环。 */
    private RunIdentity resourceOrigin(RunIdentity actual) {
        var origin = programs.getObject().resourceOrigin(actual);
        return workflows.getObject().resourceOrigin(origin);
    }
}
