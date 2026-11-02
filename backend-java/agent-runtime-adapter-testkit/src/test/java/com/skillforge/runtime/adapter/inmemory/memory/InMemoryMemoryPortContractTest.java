package com.skillforge.runtime.adapter.inmemory.memory;

import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.port.MemoryPort;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.adapter.testkit.MemoryPortContract;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/** 对 development Memory Store 运行共享一致性合同。 */
final class InMemoryMemoryPortContractTest extends MemoryPortContract {

    private InMemoryMemoryPort.State state;

    @Override
    protected MemoryPort createPort() {
        state = new InMemoryMemoryPort.State();
        return recreatePort();
    }

    @Override
    protected MemoryPort recreatePort() {
        return new InMemoryMemoryPort(state, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Override
    protected Optional<MemoryFact> findCurrent(
            MemoryPort subject, PortCallContext context, MemoryFactRef ref) {
        return ((InMemoryMemoryPort) subject).findCurrent(context, ref);
    }

    @Override
    protected void assertStoreTime(Instant sampledAt) {
        org.junit.jupiter.api.Assertions.assertEquals(NOW, sampledAt);
    }
}
