package com.skillforge.runtime.api;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionLogPort.ReadSessionLog;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionStorePort;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 群共享归属与现有个人 Runtime 的明确分界，防止群主或触发者被当作 owner。 */
class SessionSubjectContractTest {
    private static final TenantId TENANT = new TenantId("tenant");
    private static final SessionRef SESSION = new SessionRef(TENANT, "shared-session");
    private static final GroupBindingRef GROUP = new GroupBindingRef(TENANT, "opaque-group-lifecycle");
    private static final EndUserId USER = new EndUserId(9007199254740993L);

    /** 群归属只由同机构、同生命周期绑定和 GROUP 目录构成。 */
    @Test void groupOwnerHasNoEndUserAndDoesNotDependOnAMemberOrAgent() throws Exception {
        var subject = SessionSubject.group(GROUP);
        var owner = new SessionOwner(SESSION, subject, SessionTarget.group(GROUP));
        assertEquals(SESSION, owner.sessionRef()); assertEquals(subject, owner.subject());
        assertEquals(SessionTarget.Kind.GROUP, owner.target().kind());
        assertEquals(GROUP.groupBindingId(), owner.target().id());
        assertThrows(IllegalStateException.class, owner::requireUser);
        assertThrows(NoSuchMethodException.class, () -> SessionOwner.class.getMethod("endUserId"));
        assertEquals(owner, new SessionOwner(SESSION, SessionSubject.group(GROUP), SessionTarget.group(GROUP)));
    }

    /** 封闭构造器拒绝跨机构、错绑定目录和用户/群混用，不用空值形成群分支。 */
    @Test void subjectAndTargetMustMatchTheirExactTenantAndLifecycle() {
        var otherTenant = new GroupBindingRef(new TenantId("other"), GROUP.groupBindingId());
        assertThrows(IllegalArgumentException.class, () -> new SessionOwner(SESSION, SessionSubject.group(otherTenant), SessionTarget.group(otherTenant)));
        assertThrows(IllegalArgumentException.class, () -> new SessionOwner(SESSION, SessionSubject.group(GROUP), new SessionTarget(SessionTarget.Kind.GROUP, "other-group")));
        assertThrows(IllegalArgumentException.class, () -> new SessionOwner(SESSION, SessionSubject.group(GROUP), SessionTarget.team("team")));
        assertThrows(IllegalArgumentException.class, () -> new SessionOwner(SESSION, SessionSubject.group(GROUP), SessionTarget.agent(new AgentId("agent"))));
        assertThrows(IllegalArgumentException.class, () -> SessionOwner.user(SESSION, SessionTarget.group(GROUP), USER));
        assertThrows(IllegalArgumentException.class, () -> new GroupBindingRef(TENANT, " "));
    }

    /** 个人正式工厂继续保存真实用户，Agent/Team 两个分支均显式可用。 */
    @Test void personalFactoriesPreserveRealUserAndTarget() {
        var agent = SessionOwner.user(SESSION, new AgentId("agent"), USER);
        var team = SessionOwner.user(SESSION, SessionTarget.team("team"), USER);
        assertEquals(SessionSubject.user(USER), agent.subject()); assertEquals(USER, agent.requireUser());
        assertEquals(USER, team.requireUser()); assertEquals(SessionTarget.Kind.TEAM, team.target().kind());
    }

    /** GI-03 群执行、只读和领域日志使用群身份；个人SessionStore仍拒绝群输入。 */
    @Test void groupExecutionAndDomainLogUseRealSubjectWhilePersonalStorageStillRejectsGroup() {
        var owner = new SessionOwner(SESSION, SessionSubject.group(GROUP), SessionTarget.group(GROUP));
        var trace = new TraceEnvelope("trace", "span", new InputId("input"), Optional.empty());
        var requester = new RunRequester.EndUser(USER);
        var source = new SourceMessageRef(SESSION, "committed-message");
        var run = new RunIdentity(owner, new AgentId("agent"), new InputId("input"), new RunRef(TENANT, "run"),
                trace, new ResultRef("verified"), requester, Optional.of(source), new RunOriginRef(TENANT, "trigger"));
        assertEquals(requester, run.requester()); assertEquals(Optional.of(source), run.sourceMessageRef());
        assertEquals(owner, new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, USER), owner,
                Set.of("session.read"), 1, new ResultRef("verified")).owner());
        var access = new AccessEnvelope(new AuthenticationRef("auth"), TENANT, owner.target(), Optional.empty(),
                owner.subject(), new RequestId("request"), "group", "external", "trace", "span");
        assertTrue(access.agentId().isEmpty());
        assertThrows(IllegalStateException.class, () -> new SessionStorePort.SessionLookup(owner));
        assertThrows(IllegalStateException.class, () -> new SessionStorePort.CreateSession(owner, "group", "scope", "persona", new InputId("input"), trace, Instant.EPOCH));
        assertEquals(owner, new ReadSessionLog(owner, 0, 10).owner());
        assertThrows(IllegalArgumentException.class, () -> new AccessEnvelope(new AuthenticationRef("auth"), TENANT, owner.target(), java.util.Optional.of(new AgentId("agent")), com.skillforge.runtime.api.SessionSubject.user(USER), new RequestId("request"), "group", "external", "trace", "span"));
        assertThrows(IllegalArgumentException.class, () -> new InboundEnvelope(new AuthenticationRef("auth"), Optional.empty(), SESSION, owner.target(), new AgentId("agent"), com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), new InputId("input"), new EventId("event"), InboundKind.MESSAGE, new RunTarget.NewRun(), "group", "external", trace, new TypedPayload("input.v1", "{}"), Instant.EPOCH, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), new InputId("input").value())));
    }
    /** 请求者、当前操作者与群归属分别验证；派生群 Run 继承原消息 Session。 */
    @Test void groupRunFreezesRequesterOriginAndAllowsInheritedSource() {
        var owner = new SessionOwner(new SessionRef(TENANT, "child-session"), SessionSubject.group(GROUP), SessionTarget.group(GROUP));
        var source = Optional.of(new SourceMessageRef(SESSION, "message"));
        var trace = new TraceEnvelope("trace", "span", new InputId("input"), Optional.empty());
        var system = new RunRequester.System(new PrincipalRef.SystemPrincipal(TENANT, "background"));
        var run = new RunIdentity(owner, new AgentId("serving-agent"), new InputId("input"), new RunRef(TENANT, "child"),
                trace, new ResultRef("verified"), system, source, new RunOriginRef(TENANT, "original-trigger"));
        assertEquals(system, run.requester()); assertEquals(SESSION, run.sourceMessageRef().orElseThrow().sessionRef());
        assertThrows(IllegalArgumentException.class, () -> new RunIdentity(owner, run.agentId(), run.causationInputId(), run.runRef(), trace,
                run.causationIdentityRef(), new RunRequester.System(new PrincipalRef.SystemPrincipal(new TenantId("other"), "system")),
                source, run.originRef()));
        assertThrows(IllegalArgumentException.class, () -> new RunIdentity(owner, run.agentId(), run.causationInputId(), run.runRef(), trace,
                run.causationIdentityRef(), system, source, new RunOriginRef(new TenantId("other"), "trigger")));
        var personal = SessionOwner.user(SESSION, new AgentId("agent"), USER);
        assertThrows(IllegalArgumentException.class, () -> new RunIdentity(personal, new AgentId("agent"), run.causationInputId(), run.runRef(), trace,
                run.causationIdentityRef(), new RunRequester.EndUser(new EndUserId(2)), Optional.empty(), run.originRef()));
    }

}
