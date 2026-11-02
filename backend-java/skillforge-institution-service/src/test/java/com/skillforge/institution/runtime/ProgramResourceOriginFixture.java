package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 资源消费测试中的受控来源边界；普通Run仍须有精确Store事实，Program父链另由真实实现验收。 */
public final class ProgramResourceOriginFixture {
    private ProgramResourceOriginFixture() { }

    public static ObjectProvider<AgentRuntimeProgramTools> ordinary(ExecutionStore store) {
        var programs=mock(AgentRuntimeProgramTools.class);
        when(programs.resourceOrigin(any(RunIdentity.class))).thenAnswer(call->{
            RunIdentity identity=call.getArgument(0);
            var run=store.load(identity.runRef()).orElseThrow(()->new IllegalStateException("fixture_run_missing"));
            if(!run.identity().equals(identity))throw new IllegalStateException("fixture_run_mismatch");
            return identity;
        });
        when(programs.resourceOrigin(any(ExecutionStore.Run.class))).thenAnswer(call->{
            ExecutionStore.Run run=call.getArgument(0);
            if(AgentRuntimeProgramTools.owns(run))throw new IllegalStateException("fixture_program_requires_original_proof");
            return run.identity();
        });
        return provider(programs);
    }

    @SuppressWarnings("unchecked")
    public static ObjectProvider<AgentRuntimeProgramTools> provider(AgentRuntimeProgramTools programs) {
        var provider=(ObjectProvider<AgentRuntimeProgramTools>)mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(Objects.requireNonNull(programs));return provider;
    }
}
