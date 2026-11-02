package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.PrincipalRef;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionSubjectJsonTest {
    private final ObjectMapper json=SessionSubjectJson.configure(new ObjectMapper().findAndRegisterModules());

    @Test void internalSnapshotsRoundTripExactSubjectWithoutInventingGroupUser() throws Exception {
        var tenant=new TenantId("1");var ref=new GroupBindingRef(tenant,"group-lifecycle");
        var group=new SessionOwner(new SessionRef(tenant,"group-session"),SessionSubject.group(ref),SessionTarget.group(ref));
        String groupJson=json.writeValueAsString(group);
        assertEquals(group,json.readValue(groupJson,SessionOwner.class));
        assertTrue(groupJson.contains("\"kind\":\"GROUP\""));assertFalse(groupJson.contains("endUserId"));
        var user=SessionOwner.user(new SessionRef(tenant,"user-session"),new AgentId("7"),new EndUserId(9));
        assertEquals(user,json.readValue(json.writeValueAsString(user),SessionOwner.class));
    }

    @Test void absentUnknownOrLegacySubjectCannotBeReinterpretedAsAUser() {
        String prefix="{\"sessionRef\":{\"tenantId\":{\"value\":\"1\"},\"sessionId\":\"session\"},\"target\":{\"kind\":\"AGENT\",\"id\":\"7\"},";
        for(String branch:new String[]{"\"endUserId\":{\"value\":9}}", "\"subject\":{\"endUserId\":{\"value\":9}}}",
                "\"subject\":{\"kind\":\"UNKNOWN\",\"endUserId\":{\"value\":9}}}"}) {
            assertThrows(JsonProcessingException.class,()->json.readValue(prefix+branch,SessionOwner.class));
        }
    }

    @Test void runSnapshotsPreserveActualAgentRequesterSourceAndOrigin() throws Exception {
        var tenant=new TenantId("1");var groupRef=new GroupBindingRef(tenant,"group-lifecycle");
        var group=new SessionOwner(new SessionRef(tenant,"group-session"),SessionSubject.group(groupRef),SessionTarget.group(groupRef));
        var user=SessionOwner.user(new SessionRef(tenant,"user-session"),new AgentId("7"),new EndUserId(9));
        var input=new InputId("original-input");
        var trace=new TraceEnvelope("trace","span",input,Optional.empty());
        var source=Optional.of(new SourceMessageRef(group.sessionRef(),"recorded-source"));
        for(var run:List.of(
                new RunIdentity(user,new AgentId("7"),input,new RunRef(tenant,"personal"),trace,new ResultRef("verified"),
                        new RunRequester.EndUser(new EndUserId(9)),Optional.empty(),new RunOriginRef(tenant,"input:original")),
                new RunIdentity(group,new AgentId("8"),input,new RunRef(tenant,"foreground"),trace,new ResultRef("verified"),
                        new RunRequester.EndUser(new EndUserId(11)),source,new RunOriginRef(tenant,"group-trigger:original")),
                new RunIdentity(group,new AgentId("8"),input,new RunRef(tenant,"background"),trace,new ResultRef("verified"),
                        new RunRequester.System(new PrincipalRef.SystemPrincipal(tenant,"extractor")),source,new RunOriginRef(tenant,"system-task:original")))) {
            var encoded=json.writeValueAsString(run);
            assertEquals(run,json.readValue(encoded,RunIdentity.class));
            assertEquals(encoded,json.writeValueAsString(json.readValue(encoded,RunIdentity.class)));
        }
    }

    @Test void missingOrUnknownRequesterCannotBecomeTheLatestSpeaker() throws Exception {
        var tenant=new TenantId("1");var owner=SessionOwner.user(new SessionRef(tenant,"personal"),new AgentId("7"),new EndUserId(9));
        var input=new InputId("input");
        var run=new RunIdentity(owner,new AgentId("7"),input,new RunRef(tenant,"run"),
                new TraceEnvelope("trace","span",input,Optional.empty()),new ResultRef("verified"),
                new RunRequester.EndUser(new EndUserId(9)),Optional.empty(),new RunOriginRef(tenant,"input:original"));
        var missing=json.valueToTree(run);((com.fasterxml.jackson.databind.node.ObjectNode)missing).remove("requester");
        assertThrows(JsonProcessingException.class,()->json.treeToValue(missing,RunIdentity.class));
        var unknown=json.valueToTree(run);((com.fasterxml.jackson.databind.node.ObjectNode)unknown.path("requester")).put("kind","LATEST_SPEAKER");
        assertThrows(JsonProcessingException.class,()->json.treeToValue(unknown,RunIdentity.class));
    }
}
