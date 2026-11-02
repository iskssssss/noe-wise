package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/** 无数据库或供应商请求，核验生产消费者能拿到装饰工厂且不同派发不串用审计。 */
class LiveDispatchedModelAuditTest {
    @Test void primaryFactoryDecoratesEachExactDispatchAndRetainsOneSharedSuiteState() {
        try (var context = new AnnotationConfigApplicationContext(Wiring.class)) {
            var factory = context.getBean(AgentRuntimeModelInvocationAudit.class);
            var original = context.getBean("agentRuntimeModelInvocationAudit", AgentRuntimeModelInvocationAudit.class);
            assertInstanceOf(LiveDispatchedModelAudit.class, factory);
            var shared = context.getBean(Wiring.class);
            var registered = mock(ExecutionStore.DispatchResult.Registered.class);
            var prepared = mock(ExecutionStore.Prepared.class);
            var binding = mock(JdbcModelBindings.Binding.class);
            var otherRegistered = mock(ExecutionStore.DispatchResult.Registered.class);
            var first = mock(ModelInvocationAudit.class); var second = mock(ModelInvocationAudit.class);
            when(original.dispatched(registered, prepared, binding)).thenReturn(first);
            when(original.dispatched(otherRegistered, prepared, binding)).thenReturn(second);
            var call = mock(ModelCall.class); var provider = mock(ModelInvocationAudit.Provider.class);
            var usage = mock(ModelUsage.class);
            when(first.start(call, provider)).thenReturn(true); when(second.start(call, provider)).thenReturn(false);
            var firstDispatch = factory.dispatched(registered, prepared, binding);
            var secondDispatch = factory.dispatched(otherRegistered, prepared, binding);
            assertEquals(2, shared.decorations.get());
            assertTrue(firstDispatch.start(call, provider)); assertFalse(secondDispatch.start(call, provider));
            firstDispatch.usageReported(call, provider, 7, usage);
            firstDispatch.succeeded(call, provider, usage); secondDispatch.unresolved(call, provider, "original_unknown");
            assertEquals(2, shared.starts.get());
            verify(original).dispatched(registered, prepared, binding); verify(original).dispatched(otherRegistered, prepared, binding);
            verify(first).start(call, provider); verify(second).start(call, provider);
            verify(first).usageReported(call, provider, 7, usage); verify(first).succeeded(call, provider, usage);
            verify(second).unresolved(call, provider, "original_unknown"); verifyNoMoreInteractions(first, second);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Wiring {
        final AtomicInteger decorations = new AtomicInteger(); final AtomicInteger starts = new AtomicInteger();
        @Bean AgentRuntimeModelInvocationAudit agentRuntimeModelInvocationAudit() { return mock(AgentRuntimeModelInvocationAudit.class); }
        @Bean @Primary AgentRuntimeModelInvocationAudit meteredFactory(
                @Qualifier("agentRuntimeModelInvocationAudit") AgentRuntimeModelInvocationAudit original) {
            return new LiveDispatchedModelAudit(original, mock(ExternalInvocationLedger.class), mock(CostRecorder.class),
                    mock(PlatformTransactionManager.class), com.skillforge.institution.runtime.WorkflowResourceOriginFixture.ordinary(), actual -> { decorations.incrementAndGet(); return actual; },
                    new LiveDispatchedModelAudit.Callbacks() {
                        @Override public boolean start(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider) { starts.incrementAndGet(); return delegate.start(call, provider); }
                        @Override public void usageReported(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, long revision, ModelUsage usage) { delegate.usageReported(call, provider, revision, usage); }
                        @Override public void succeeded(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, ModelUsage usage) { delegate.succeeded(call, provider, usage); }
                        @Override public void failed(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, String reason) { delegate.failed(call, provider, reason); }
                        @Override public void unresolved(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, String reason) { delegate.unresolved(call, provider, reason); }
                    });
        }
    }
}
