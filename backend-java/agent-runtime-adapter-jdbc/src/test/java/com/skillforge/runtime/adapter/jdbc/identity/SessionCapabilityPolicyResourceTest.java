package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionCapabilityPolicyResourceTest {
    @Test void onlyExactOwnerInputAndActionAreAccepted(){
        var owner=SessionOwner.user(new SessionRef(new TenantId("1"),"session"),new AgentId("10"),new EndUserId(1));
        var payload=SessionCapabilityPolicyResource.resource(owner,new InputId("input\"\\\n"));
        assertEquals(Optional.of(owner),SessionCapabilityPolicyResource.owner(SessionCapabilityPolicyResource.ACTION,payload));
        assertTrue(SessionCapabilityPolicyResource.owner("session.claim",payload).isEmpty());
        assertTrue(SessionCapabilityPolicyResource.owner(SessionCapabilityPolicyResource.ACTION,new TypedPayload(payload.schemaId(),payload.canonicalJson()+" ")).isEmpty());
        assertTrue(SessionCapabilityPolicyResource.owner(SessionCapabilityPolicyResource.ACTION,new TypedPayload(payload.schemaId(),"[\"1\",\"10\",\"session\",\"01\",\"input\"]")).isEmpty());
        assertTrue(SessionCapabilityPolicyResource.owner(SessionCapabilityPolicyResource.ACTION,new TypedPayload(payload.schemaId(),"[\"1\",\"10\",\"session\",\"1\",\"\"]")).isEmpty());
    }
}
