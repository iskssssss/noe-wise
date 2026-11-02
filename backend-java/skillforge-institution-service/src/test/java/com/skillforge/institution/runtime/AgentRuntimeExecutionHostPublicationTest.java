package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Store返回已提交终态后，正式成果必须先于依赖完成消费者收口。真实发布事务由PG集成测试覆盖。 */
class AgentRuntimeExecutionHostPublicationTest {
    @Test void everyCommittedTerminalOutcomeReconcilesBeforeCompletionConsumers() {
        var order=new ArrayList<String>();
        var host=host(identity->order.add("publication"),reference->order.add("dependencies"));
        for(var status:List.of(RunStatus.COMPLETED,RunStatus.FAILED,RunStatus.CANCELLED,RunStatus.TERMINATED)) {
            order.clear();host.committed(run(status,ExecutionStore.Phase.FINISHED));
            assertEquals(List.of("publication","dependencies"),order);
        }
    }

    @Test void activeOrUnknownRunDoesNotAcquirePublicationCompletion() {
        var order=new ArrayList<String>();
        var host=host(identity->order.add("publication"),reference->order.add("dependencies"));
        host.committed(run(RunStatus.RUNNING,ExecutionStore.Phase.READY));
        host.committed(run(RunStatus.RUNNING,ExecutionStore.Phase.UNCERTAIN));
        assertEquals(List.of("dependencies","dependencies"),order);
    }

    @Test void publicationFailurePreservesPendingWorkBeforeDownstreamCompletion() {
        var consumed=new ArrayList<RunRef>();
        var host=host(identity->{throw new IllegalStateException("publication_temporarily_unavailable");},consumed::add);
        assertThrows(IllegalStateException.class,()->host.committed(run(RunStatus.COMPLETED,ExecutionStore.Phase.FINISHED)));
        assertTrue(consumed.isEmpty());
    }

    private AgentRuntimeExecutionHost host(Consumer<RunIdentity> publication,Consumer<RunRef> changed) {
        return new AgentRuntimeExecutionHost(null,null,null,null,null,null,()->null,publication,changed,(tenant,limit)->{});
    }

    private ExecutionStore.Run run(RunStatus status,ExecutionStore.Phase phase) {
        var tenant=new TenantId("7");var input=new InputId("publication-input");var agent=new AgentId("101");
        var owner=SessionOwner.user(new SessionRef(tenant,"publication-session"),agent,new EndUserId(8));
        var identity=new RunIdentity(owner,agent,input,new RunRef(tenant,"publication-run"),
                new TraceEnvelope("publication-trace","publication-span",input,Optional.empty()),new ResultRef("publication-identity"),
                new RunRequester.EndUser(owner.requireUser()),Optional.empty(),new RunOriginRef(tenant,"input:"+input.value()));
        var run=mock(ExecutionStore.Run.class);when(run.identity()).thenReturn(identity);when(run.status()).thenReturn(status);when(run.phase()).thenReturn(phase);
        return run;
    }
}
