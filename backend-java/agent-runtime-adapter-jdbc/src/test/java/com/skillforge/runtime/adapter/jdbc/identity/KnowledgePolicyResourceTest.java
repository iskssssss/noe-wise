package com.skillforge.runtime.adapter.jdbc.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.KnowledgePortal.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class KnowledgePolicyResourceTest {
    /** 派发前只有真实Action/请求材料；v3严格解析47字段（含原始请求者与来源）并能提供IAM执行者，无伪造Attempt。 */
    @Test void directDispatchResourceUsesOnlyAvailableFactsAndRejectsExtraOrCrossIdentityFields() {
        var tenant=new TenantId("1");var owner=SessionOwner.user(new SessionRef(tenant,"direct-session"),new AgentId("10"),new EndUserId(1));
        var trace=new TraceEnvelope("trace","span",new InputId("direct-input"),Optional.empty());
        var run=new RunIdentity(owner, owner.target().requireAgent(), new InputId("direct-input"), new RunRef(tenant,"direct-run"), trace, new ResultRef("verification"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(),"execution-input:"+(new RunRef(tenant,"direct-run")).runId()));
        var snapshot=new CapabilitySnapshotRef(owner.sessionRef(),run.causationInputId(),"frozen-mounts",2);
        var versions=Set.of(new KnowledgeSourceVersion("20","v1"));var operators=Set.of(KnowledgeOperatorType.KEYWORD);
        var target=new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:mount"),new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE,"40","50"),"20",3,versions,operators,Duration.ofSeconds(2));
        var request=new KnowledgeRequest("direct-request",tenant,run.requester(),run.agentId(),owner.sessionRef(),trace,KnowledgeMode.GROUNDING,"answer",new com.skillforge.runtime.api.KnowledgeOperation.Search("exact query"),
                Set.of("20"),versions,operators,new KnowledgeBudget(1,3,100,1000,2000),Instant.parse("2026-10-22T00:00:00Z"),Optional.empty(),true);
        var action=new ActionRef(tenant,"direct-action");var reference=new ResultRef("actual-request-material");var source=new EventId("actual-source-event");var idempotency=new IdempotencyKey(action.actionId());
        var resource=KnowledgePolicyResource.dispatch(run,snapshot,target,request,source,idempotency,action,reference,"actual-fingerprint");
        assertEquals("skillforge.runtime.knowledge.execute-resource.v4",resource.schemaId());
        var fields=AdmissionResource.flatStrings(resource.canonicalJson(),47);
        assertEquals(List.of(action.actionId(),reference.value(),"actual-fingerprint"),fields.subList(44,47));
        assertEquals(Optional.of(owner),KnowledgePolicyResource.owner("knowledge.execute",resource));
        assertEquals(Optional.of(new SessionPolicyResource.ExecutionTarget(run.runRef(),run.agentId())),KnowledgePolicyResource.executionTarget("knowledge.execute",resource));
        assertTrue(KnowledgePolicyResource.owner("knowledge.execute",new TypedPayload(resource.schemaId(),resource.canonicalJson().replaceAll("\\]$",",\"invented-attempt\"]"))).isEmpty());
        assertTrue(KnowledgePolicyResource.owner("knowledge.execute",new TypedPayload("skillforge.runtime.knowledge.execute-resource.v2",resource.canonicalJson())).isEmpty());
        assertThrows(IllegalArgumentException.class,()->KnowledgePolicyResource.dispatch(run,
                new CapabilitySnapshotRef(new SessionRef(tenant,"other-session"),run.causationInputId(),"mounts",2),target,request,source,idempotency,action,reference,"fingerprint"));
        assertThrows(IllegalArgumentException.class,()->KnowledgePolicyResource.dispatch(run,snapshot,target,request,source,idempotency,
                new ActionRef(new TenantId("2"),action.actionId()),reference,"fingerprint"));
    }

    @Test void resourceBindsEveryRequestFieldAndRejectsMalformedOwnerShape() {
        var owner=SessionOwner.user(new SessionRef(new TenantId("tenant"),"session"),new AgentId("agent"),new EndUserId(1));
        var identity=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(owner.sessionRef().tenantId(),owner.requireUser()),owner,Set.of("sessions.write"),7,new ResultRef("verified"));
        var call=call(identity);var resource=resource(call);
        assertEquals(Optional.of(new SessionPolicyResource.ExecutionTarget(call.context().callContext().runIdentity().runRef(),
                call.context().callContext().runIdentity().agentId())),KnowledgePolicyResource.executionTarget("knowledge.execute",resource));
        assertTrue(KnowledgePolicyResource.executionTarget("knowledge.query",resource).isEmpty());
        assertEquals(Optional.of(owner),KnowledgePolicyResource.owner("knowledge.execute",resource));
        assertTrue(KnowledgePolicyResource.owner("knowledge.query",resource).isEmpty());
        for(String invalid:List.of(resource.canonicalJson()+" ",resource.canonicalJson().replace("\"agent\"","\"\""),
                resource.canonicalJson().replace("\"GROUNDING\"","\"NONE\""),resource.canonicalJson().replace("\"2000\"","\"0\"")))
            assertTrue(KnowledgePolicyResource.owner("knowledge.execute",new TypedPayload(resource.schemaId(),invalid)).isEmpty());
        var changed=new KnowledgeCall(call.context(),call.snapshot(),call.target(),call.request(),new EventId("other-source-event"),call.idempotencyKey());
        assertNotEquals(resource,resource(changed));
        var original=call.request();
        var changedRequest=new KnowledgeRequest(original.knowledgeRequestId(),original.tenantId(),original.requester(),original.agentId(),original.sessionRef(),original.trace(),
                original.mode(),original.intent(),new com.skillforge.runtime.api.KnowledgeOperation.Search("changed query"),original.allowedSourceIds(),original.allowedSourceVersions(),original.allowedOperators(),new KnowledgeBudget(2,3,40,500,1999),
                original.asOf(),Optional.of(original.asOf()),false);
        assertNotEquals(resource,resource(new KnowledgeCall(call.context(),call.snapshot(),call.target(),changedRequest,call.sourceEventId(),call.idempotencyKey())));
    }

    static TypedPayload resource(VerifiedIdentity identity){return resource(call(identity));}
    private static TypedPayload resource(KnowledgeCall call) {
        var dispatch=call.context().dispatch();
        return KnowledgePolicyResource.dispatch(call.context().callContext().runIdentity(),call.snapshot(),call.target(),call.request(),
                call.sourceEventId(),call.idempotencyKey(),dispatch.action(),dispatch.requestRef(),dispatch.fingerprint());
    }
    private static KnowledgeCall call(VerifiedIdentity identity) {
        var owner=identity.owner();var trace=new TraceEnvelope("trace","span",new InputId("root"),Optional.empty());
        var run=new RunIdentity(owner, owner.target().requireAgent(), new InputId("input"), new RunRef(owner.sessionRef().tenantId(),"run"), trace, new ResultRef("cause"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(),"execution-input:"+(new RunRef(owner.sessionRef().tenantId(),"run")).runId()));
        var action=new ActionRef(owner.sessionRef().tenantId(),"action");var now=Instant.now();
        var dispatch=new ProviderDispatch(run.runRef(),action,"invocation","attempt",PortalCallKind.KNOWLEDGE_EXECUTE,
                new ResultRef("invocation-ref"),new ResultRef("capability-ref"),"fingerprint",1);
        var provider=ProviderCallContext.dispatched(new PortCallContext(run,identity,new InvocationControl(now.plusSeconds(30),()->false)),dispatch);
        var snapshot=new CapabilitySnapshotRef(owner.sessionRef(),run.causationInputId(),"snapshot",1);
        var versions=Set.of(new KnowledgeSourceVersion("source","v1"));var operators=Set.of(KnowledgeOperatorType.KEYWORD);
        var target=new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:docs"),new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE,"capability","version"),"descriptor",1,versions,operators,Duration.ofSeconds(2));
        var request=new KnowledgeRequest("request",owner.sessionRef().tenantId(),run.requester(),owner.target().requireAgent(),owner.sessionRef(),trace,
                KnowledgeMode.GROUNDING,"answer",new com.skillforge.runtime.api.KnowledgeOperation.Search("question🚀"),Set.of("source"),versions,operators,new KnowledgeBudget(2,3,40,500,2000),Instant.parse("2026-09-10T00:00:00Z"),Optional.empty(),true);
        return new KnowledgeCall(provider,snapshot,target,request,new EventId("source-event"),new IdempotencyKey("key"));
    }
}
