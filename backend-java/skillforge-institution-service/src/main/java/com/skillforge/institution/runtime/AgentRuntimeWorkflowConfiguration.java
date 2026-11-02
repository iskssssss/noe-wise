package com.skillforge.institution.runtime;

import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.runtime.adapter.jdbc.session.JdbcWorkflowPolicy;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.port.WorkflowPortal;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用 SDK Workflow Portal 替换 Starter 的固定拒绝实现。
 *
 * <p>未配置的外部节点在当次调用失败关闭，不把整个门户做成恒 Denied。
 * 参数/响应走与模型准备相同的 payload 校验；outputs 只接受引擎结果 schema。</p>
 */
@Configuration
public class AgentRuntimeWorkflowConfiguration {

    @Bean
    com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls agentRuntimeWorkflowScriptCalls(
            DataSource dataSource,org.springframework.beans.factory.ObjectProvider<SdkWorkflowPortal> portals,
            com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort identities) {
        return new com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls(dataSource,
                (session,identity,execution,preparation)-> {
                    if (!(identities.authorize(session, new com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationRequest(identity,"workflow.execute",
                            com.skillforge.runtime.adapter.jdbc.identity.WorkflowPolicyResource.execution(new WorkflowPortal.WorkflowQuery(identity,execution.executionRef()),execution.owner().agentId()),
                            execution.owner().trace().traceId(),execution.owner().trace().spanId())) instanceof com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationResult.Allowed))
                        throw new SecurityException("workflow_script_access_rejected");
                    portals.getObject().requireScriptSource(session,identity,execution,preparation);
                }, configuration -> {
                    identities.configureTransaction(configuration);
                    JdbcWorkflowBindings.configure(configuration);
                });
    }

    @Bean
    com.skillforge.runtime.session.spi.WorkflowInteractionView agentRuntimeWorkflowInteractions(
            DataSource dataSource, JdbcWorkflowPolicy policy) {
        return new com.skillforge.core.workflow.runtime.SdkWorkflowInteractionView(dataSource, policy);
    }

    @Bean
    SdkWorkflowPortal agentRuntimeSdkWorkflowPortal(
            DataSource dataSource,
            JdbcWorkflowBindings bindings,
            JdbcWorkflowPolicy policy,
            StateMachineEngine engine,
            WorkflowParser parser,
            AgentRuntimeWorkflowNodes nodes,
            AgentRuntimePayloadValidation payloads,
            com.fasterxml.jackson.databind.ObjectMapper json,
            Clock clock, com.skillforge.runtime.session.spi.ExecutionStore store,
            com.skillforge.runtime.session.SessionExecutionWorkflows workflowRequests) {
        Objects.requireNonNull(dataSource);
        return new SdkWorkflowPortal(
                dataSource,
                bindings,
                policy,
                engine,
                parser,
                nodes,
                call -> payloads.accepts(call.parameters()),
                call -> payloads.accepts(call.response()),
                (binding, output) -> {
                    if(!AgentRuntimeHostContracts.WORKFLOW_OUTPUT_SCHEMA.equals(output.schemaId()))return false;
                    try {
                        var descriptor=json.readTree(binding.descriptorJson());
                        var schema=descriptor.get("output_schema");
                        return schema!=null&&schema.isObject()&&payloads.accepts(
                                new com.skillforge.runtime.api.TypedPayload("json-schema",json.writeValueAsString(schema)),output);
                    } catch(Exception rejected) {return false;}
                },
                clock, store, new AgentRuntimeWorkflowSourceVerifier(store, workflowRequests));
    }

}
