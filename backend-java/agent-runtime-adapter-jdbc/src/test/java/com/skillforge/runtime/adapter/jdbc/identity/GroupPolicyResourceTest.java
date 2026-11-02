package com.skillforge.runtime.adapter.jdbc.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.ModelBindingRef;
import com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 群IAM资源保留真实请求者和原来源，不把群ID或实际Agent转换为EndUser。 */
class GroupPolicyResourceTest {
    private static final TenantId TENANT = new TenantId("41");
    private static final GroupBindingRef GROUP = new GroupBindingRef(TENANT,"702");
    private static final SessionOwner OWNER = new SessionOwner(new SessionRef(TENANT,"shared"),
            SessionSubject.group(GROUP),SessionTarget.group(GROUP));

    /** 单群可由不同实际Agent执行；恢复原身份与当前Agent字段独立。 */
    @Test void groupResourceKeepsOwnerRequesterSourceOriginAndActualAgentSeparate() {
        var first = run(OWNER,"80",new RunRequester.EndUser(new EndUserId(9)));
        var second = run(OWNER,"81",new RunRequester.EndUser(new EndUserId(10)));
        for (var identity : List.of(first,second)) {
            var resource = SessionPolicyResource.run(identity,Optional.empty());
            assertEquals(Optional.of(OWNER),SessionPolicyResource.owner("session.execute",resource));
            assertEquals(Optional.of(new SessionPolicyResource.ExecutionTarget(identity.runRef(),identity.agentId())),
                    SessionPolicyResource.execution("session.execute",resource));
            assertEquals(identity,RunPolicyFields.restore(OWNER,identity.agentId(),identity.causationInputId(),identity.runRef(),
                    identity.trace(),identity.causationIdentityRef(),RunPolicyFields.values(identity),0));
            assertThrows(IllegalStateException.class,OWNER::requireUser);
        }
        assertNotEquals(first.agentId(),second.agentId());
        assertNotEquals(first.requester(),second.requester());
    }

    /** 群派生Run沿同租户原消息来源；System请求者仍保存真实系统主体。 */
    @Test void derivedAndSystemRunsKeepTheirOriginalSource() {
        var child = new SessionOwner(new SessionRef(TENANT,"delegation:child"),OWNER.subject(),OWNER.target());
        var identity = run(child,"82",new RunRequester.System(new PrincipalRef.SystemPrincipal(TENANT,"workflow-worker")));
        assertEquals(identity,RunPolicyFields.restore(child,identity.agentId(),identity.causationInputId(),identity.runRef(),
                identity.trace(),identity.causationIdentityRef(),RunPolicyFields.values(identity),0));
        assertEquals(OWNER.sessionRef(),identity.sourceMessageRef().orElseThrow().sessionRef());
    }

    /** 缺来源、伪个人主体和非规范请求者均不进入模型或Session授权。 */
    @Test void missingOriginAndFakeUserCannotMasqueradeAsGroupIdentity() {
        var identity = run(OWNER,"80",new RunRequester.EndUser(new EndUserId(9)));
        var resource = ModelPolicyResource.dispatch(identity,new ModelBindingRef(TENANT,"model",1),
                new RequestedModelStrategy("default","1"));
        assertEquals(Optional.of(OWNER),ModelPolicyResource.owner("model.generate",resource));
        for (var malformed : List.of(resource.canonicalJson().replace("group:702","9"),
                resource.canonicalJson().replace("group-trigger:trigger",""),
                resource.canonicalJson().replace("user:9","user:09"))) {
            assertTrue(ModelPolicyResource.owner("model.generate",new TypedPayload(resource.schemaId(),malformed)).isEmpty());
        }
    }

    /** 夹具仅构造真实群请求身份，不依赖个人用户占位。 */
    private static RunIdentity run(SessionOwner owner,String agent,RunRequester requester) {
        var input = new InputId("group-trigger:trigger");
        return new RunIdentity(owner,new AgentId(agent),input,new RunRef(TENANT,"run:"+agent),
                new TraceEnvelope("trace","span:"+agent,input,Optional.empty()),new ResultRef("verified"),requester,
                Optional.of(new SourceMessageRef(OWNER.sessionRef(),"source")),new RunOriginRef(TENANT,"group-trigger:trigger"));
    }
}
