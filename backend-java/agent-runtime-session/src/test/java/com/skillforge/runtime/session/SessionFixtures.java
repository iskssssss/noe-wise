package com.skillforge.runtime.session;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.core.*;
import java.time.*;
import java.util.*;

/** 新主链及纯协议测试共用的不可变值；没有旧checkpoint、journal或恢复状态。 */
final class SessionFixtures {
    static final CodecLimits LIMITS = new CodecLimits(1_000_000, 100_000, 128);
    static final Instant NOW = Instant.parse("2026-09-08T00:00:00.000000007Z");
    static final TenantId TENANT = new TenantId("t");
    static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "s"), new AgentId("a"), new EndUserId(1));
    static final InputId INPUT = new InputId("i");
    static final RunIdentity IDENTITY = new RunIdentity(OWNER, OWNER.target().requireAgent(), INPUT, new RunRef(TENANT, "r"), new TraceEnvelope("trace", "span", INPUT, Optional.empty()), new ResultRef("verified"), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), INPUT.value()));
    static final VerifiedIdentity ADMISSION = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, OWNER.requireUser()),
            OWNER, Set.of("z", "a"), 1, new ResultRef("verified"));
    static final CapabilitySnapshotRef CAPABILITY = new CapabilitySnapshotRef(OWNER.sessionRef(), INPUT, "snapshot", 1);
    static final LoopBudget BUDGET = new LoopBudget.Enforced(10, 11, 12, 1000, 2000, 3000, Duration.ofSeconds(60, 7));
    static final TypedPayload CONTENT = new TypedPayload("content.v1", "{}");
    static final TypedPayload OUTPUT = new TypedPayload("output.v1", "{\"text\":\"安全😀\"}");

    private SessionFixtures() { }

}
