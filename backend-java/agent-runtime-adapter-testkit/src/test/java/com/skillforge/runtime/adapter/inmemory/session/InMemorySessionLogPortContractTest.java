package com.skillforge.runtime.adapter.inmemory.session;

import com.skillforge.runtime.adapter.testkit.SessionLogPortContract;
import com.skillforge.runtime.api.port.SessionLogPort;

final class InMemorySessionLogPortContractTest extends SessionLogPortContract {

    @Override
    protected SessionLogPort createPort() {
        return new InMemorySessionLogPort();
    }
}
