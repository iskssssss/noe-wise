package com.skillforge.institution.runtime;

import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.SessionExecutionWorkflows;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 已提交父取消驱动原Workflow清理；不建队列、线程、Provider许可或新的节点。 */
@Component
public final class AgentRuntimeWorkflowCancellations {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AgentRuntimeWorkflowCancellations.class);
    private final ExecutionStore store;
    private final SdkWorkflowPortal workflows;
    private final SessionExecutionWorkflows codec;
    private final JdbcIdentityPolicyPort identities;
    private final AgentRuntimeHostProvisioner provisioner;
    private final AgentRuntimeProperties properties;
    private final WorkflowCancellationMapper candidates;
    private final Clock clock;

    public AgentRuntimeWorkflowCancellations(ExecutionStore store,SdkWorkflowPortal workflows,SessionExecutionWorkflows codec,
            JdbcIdentityPolicyPort identities,AgentRuntimeHostProvisioner provisioner,AgentRuntimeProperties properties,
            WorkflowCancellationMapper candidates,Clock clock) {
        this.store=Objects.requireNonNull(store);this.workflows=Objects.requireNonNull(workflows);this.codec=Objects.requireNonNull(codec);
        this.identities=Objects.requireNonNull(identities);this.provisioner=Objects.requireNonNull(provisioner);this.properties=Objects.requireNonNull(properties);
        this.candidates=Objects.requireNonNull(candidates);this.clock=Objects.requireNonNull(clock);
    }

    /** 仅接已提交地址；所有归属、取消事实及当前取消IAM由真实Store与SDK再次证明。 */
    public void changed(RunRef reference) {
        try {
            var found=store.load(reference);if(found.isEmpty())return;var parent=found.orElseThrow();
            if(!parent.cancelled()||parent.status()!=RunStatus.CANCELLED||parent.phase()!=ExecutionStore.Phase.FINISHED||parent.currentAction().isEmpty())return;
            var action=store.action(parent.currentAction().orElseThrow()).orElseThrow();
            if(action.kind()!=ExecutionStore.Kind.WORKFLOW)return;
            if(!action.run().equals(reference))throw new IllegalArgumentException("workflow_cancel_action_owner_mismatch");
            var material=store.material(reference.tenantId(),action.request()).orElseThrow();
            if(!action.fingerprint().equals(com.skillforge.runtime.session.AgentExecutionCodec.digest(material.content().schemaId()+"\0"+material.content().canonicalJson())))
                throw new IllegalArgumentException("workflow_cancel_request_fingerprint_mismatch");
            if(!(codec.decode(material.content()) instanceof SessionExecutionWorkflows.Dispatch source))throw new IllegalArgumentException("workflow_cancel_original_dispatch_required");
            var input=new InputId("workflow-parent-cancel:"+reference.runId()+":"+action.reference().actionId());
            var actor=authenticate(parent.identity(),input);
            workflows.cancelCommittedParent(actor,source.executionRef(),input,new InvocationControl(clock.instant().plusSeconds(5),()->Thread.currentThread().isInterrupted()));
        } catch(RuntimeException unavailable) {
            LOG.warn("Workflow父取消清理暂缓 tenant={} run={} failureType={}",reference.tenantId().value(),reference.runId(),unavailable.getClass().getSimpleName(),unavailable);
        }
    }
    /** 复用同一Worker五秒有界兜底；未收敛的原Execution继续保留真实取消意图。 */
    public void recover(TenantId tenant,int limit) {
        if(limit<=0||limit>100)throw new IllegalArgumentException("workflow_cancel_recovery_limit_invalid");
        for(var parent:candidates.cancelledParents(tenant.value(),limit))changed(new RunRef(tenant,parent));
    }
    private VerifiedIdentity authenticate(RunIdentity parent,InputId input) {
        provisioner.provisionAccess(parent.runRef().tenantId().value());
        var authentication=identities.registerTrusted(new PrincipalRef.SystemPrincipal(parent.runRef().tenantId(),properties.getWorkerId()),parent.owner(),"chat","workflow-cancel",Duration.ofMinutes(5));
        return switch(identities.verify(new IdentityPolicyPort.IdentityRequest(authentication,parent.owner(),"chat","workflow-cancel",new RequestId(input.value()),Optional.of(input),parent.trace().traceId(),parent.trace().spanId()))) {
            case IdentityPolicyPort.IdentityResult.Verified verified->verified.identity();
            case IdentityPolicyPort.IdentityResult.Rejected rejected->throw new SecurityException("workflow_cancel_identity_rejected");
        };
    }
}
