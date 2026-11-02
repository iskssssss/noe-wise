package com.skillforge.runtime.adapter.inmemory.execution;

import com.skillforge.runtime.adapter.testkit.ExecutionLedgerPortContract;
import com.skillforge.runtime.api.port.ExecutionLedgerPort;
import com.skillforge.runtime.api.port.RunIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

final class InMemoryExecutionLedgerPortContractTest
        extends ExecutionLedgerPortContract {

    @Override
    protected ExecutionLedgerFixture createFixture(Instant initialTime) {
        MutableClock clock = new MutableClock(initialTime);
        InMemoryExecutionLedgerPort ledger = new InMemoryExecutionLedgerPort(clock);
        return new ExecutionLedgerFixture() {
            @Override
            public ExecutionLedgerPort ledger() {
                return ledger;
            }

            @Override
            public void establishRun(RunIdentity owner) {
                // 内存 Execution ledger 不依赖 façade Run 存储。
            }

            @Override
            public Instant storageTime() {
                return clock.instant();
            }

            @Override
            public void advance(Duration duration) {
                clock.advance(duration);
            }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!getZone().equals(zone)) {
                throw new IllegalArgumentException("test clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return current;
        }

        private synchronized void advance(Duration duration) {
            if (duration.isNegative()) {
                throw new IllegalArgumentException("duration must not be negative");
            }
            current = current.plus(duration);
        }
    }
}
