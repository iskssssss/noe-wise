package com.skillforge.runtime.adapter.jdbc.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SessionPolicyResourceTest {
    static final SessionOwner OWNER=SessionOwner.user(new SessionRef(new TenantId("tenant"),"session"),new AgentId("agent"),new EndUserId(1));
    static final RunIdentity RUN=new RunIdentity(OWNER, OWNER.target().requireAgent(), new InputId("input"), new RunRef(OWNER.sessionRef().tenantId(),"run"), new TraceEnvelope("trace","span",new InputId("root"),Optional.of(new EventId("cause-event"))), new ResultRef("verification"), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(),"execution-input:"+(new RunRef(OWNER.sessionRef().tenantId(),"run")).runId()));
    @Test void explicitActionsUseOnlyTheirCompleteResourceShapes(){
        var claim=SessionPolicyResource.claim(OWNER);var run=SessionPolicyResource.run(RUN,Optional.empty());var cancel=SessionPolicyResource.run(RUN,Optional.of(new InputId("cancel")));
        assertEquals(Optional.of(OWNER),SessionPolicyResource.owner("session.claim",claim));
        for(var action:List.of("session.execute","session.commit"))assertEquals(Optional.of(OWNER),SessionPolicyResource.owner(action,run));
        assertEquals(Optional.of(OWNER),SessionPolicyResource.owner("session.cancel",cancel));
        assertTrue(SessionPolicyResource.owner("session.cancel",run).isEmpty());assertTrue(SessionPolicyResource.owner("session.commit",claim).isEmpty());
        assertTrue(SessionPolicyResource.owner("unknown",run).isEmpty());
    }
    @Test void inputBindsOriginalEnvelopeAndRunWithoutAcceptingOtherSchema(){
        var envelope=new InboundEnvelope(new AuthenticationRef("authentication"),Optional.of(RUN.causationIdentityRef()),OWNER.sessionRef(),OWNER.target(),OWNER.target().requireAgent(),OWNER.subject(),RUN.requester(),RUN.causationInputId(),new EventId("event"),InboundKind.MESSAGE,new RunTarget.NewRun(),"api","request",RUN.trace(),new TypedPayload("input.v1","{}"),Instant.EPOCH,RUN.sourceMessageRef(),RUN.originRef());
        var resource=SessionPolicyResource.input(envelope,RUN.runRef(),"fingerprint");
        assertEquals(Optional.of(OWNER),SessionPolicyResource.owner("session.execute",resource));
        assertTrue(SessionPolicyResource.owner("session.commit",resource).isEmpty());
        assertThrows(IllegalArgumentException.class,()->SessionPolicyResource.input(envelope,new RunRef(new TenantId("foreign"),"run"),"fingerprint"));
        assertTrue(resource.canonicalJson().contains("\"verification\",\"run\",\"fingerprint\""));
    }
    @Test void whitespaceExtraFieldsNoncanonicalNumbersAndMissingIdentityAreRejected(){
        var resource=SessionPolicyResource.run(RUN,Optional.empty());var body=resource.canonicalJson();
        for(var malformed:List.of(body+" ",body.substring(0,body.length()-1)+",\"extra\"]",body.replace("\"1\"","\"01\""),body.replace("\"run\"","\"\""),body.replace("\"root\"","\"\"")))
            assertTrue(SessionPolicyResource.owner("session.execute",new TypedPayload(resource.schemaId(),malformed)).isEmpty());
    }
}
