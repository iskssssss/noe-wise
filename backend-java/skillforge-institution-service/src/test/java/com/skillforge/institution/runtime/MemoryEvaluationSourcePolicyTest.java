package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.memory.MemorySourcePolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 直接验证宿主来源策略，避免仅以 Runtime 的替身策略证明生产来源授权。 */
class MemoryEvaluationSourcePolicyTest {
    private static final TenantId TENANT = new TenantId("702");
    private static final AgentId AGENT = new AgentId("802");
    private static final EndUserId USER = new EndUserId(7);
    private static final SessionOwner OWNER = SessionOwner.user(
            new SessionRef(TENANT, "memory-evaluation-session"), AGENT, USER);
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private final com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests=org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class);
    private final MemorySourcePolicy policy = new AgentRuntimeMemorySourcePolicy(
            org.mockito.Mockito.mock(com.skillforge.institution.group.GroupMemorySources.class), tests);

    /** 合法的偏好和情节必须保留宿主策略的审计引用。 */
    @Test
    void acceptsCommittedOwnerSourcesForBothRegisteredTypes() {
        for (String type : List.of("preference", "episode")) {
            MemorySourcePolicy.Decision.Allowed allowed = assertInstanceOf(
                    MemorySourcePolicy.Decision.Allowed.class,
                    policy.authorize(request(type, List.of(source(OWNER, "source-1")))));
            assertEquals(AgentRuntimeHostContracts.MEMORY_SOURCE_POLICY, allowed.auditRef());
        }
    }

    /** 从合法来源只改变租户、用户、Agent 或 Session，均不得借混合来源集合获准。 */
    @Test
    void rejectsEveryForeignSourceEvenWhenAnotherSourceBelongsToCaller() {
        List<SessionOwner> foreignOwners = List.of(
                SessionOwner.user(new SessionRef(new TenantId("703"),
                        OWNER.sessionRef().sessionId()), AGENT, USER),
                SessionOwner.user(OWNER.sessionRef(), AGENT, new EndUserId(8)),
                SessionOwner.user(OWNER.sessionRef(), new AgentId("803"), USER),
                SessionOwner.user(new SessionRef(TENANT, "foreign-session"), AGENT, USER));
        for (SessionOwner foreignOwner : foreignOwners) {
            MemorySourcePolicy.Decision.Denied denied = assertInstanceOf(
                    MemorySourcePolicy.Decision.Denied.class,
                    policy.authorize(request("preference", List.of(
                            source(OWNER, "source-1"), source(foreignOwner, "source-2")))));
            assertEquals("memory_source_session_mismatch", denied.reasonCode());
            assertEquals(AgentRuntimeHostContracts.MEMORY_SOURCE_POLICY, denied.auditRef());
        }
    }

    /** 归属正确也不能把未登记类型当成已授权候选。 */
    @Test
    void rejectsUnregisteredTypeWithStableAuditDecision() {
        MemorySourcePolicy.Decision.Denied denied = assertInstanceOf(
                MemorySourcePolicy.Decision.Denied.class,
                policy.authorize(request("summary", List.of(source(OWNER, "source-1")))));
        assertEquals("memory_source_type_unregistered", denied.reasonCode());
        assertEquals(AgentRuntimeHostContracts.MEMORY_SOURCE_POLICY, denied.auditRef());
    }

    /** extract 只可保存待范围确认的 USER 候选；敏感标记本身不能授权 ACTIVE 共享。 */
    @Test
    void extractorCannotCreateActiveUserEvenWhenReviewFlagIsSet() {
        for (boolean review : List.of(false, true)) {
            MemorySourcePolicy.Decision.Denied denied = assertInstanceOf(
                    MemorySourcePolicy.Decision.Denied.class, policy.authorize(request(
                            "preference", List.of(source(OWNER, "source-1")), MemoryScope.USER,
                            FactStatus.ACTIVE, review, Set.of("memory:extract"))));
            assertEquals("memory_user_scope_review_required", denied.reasonCode());
            assertEquals(AgentRuntimeHostContracts.MEMORY_SOURCE_POLICY, denied.auditRef());
        }
    }

    /** USER 候选必须同时保留待确认状态和审核标记，不能只设置其中一个。 */
    @Test
    void extractorMayKeepUserPendingOnlyWithReviewRequired() {
        assertInstanceOf(MemorySourcePolicy.Decision.Allowed.class, policy.authorize(request(
                "preference", List.of(source(OWNER, "source-1")), MemoryScope.USER,
                FactStatus.PENDING_CONFIRM, true, Set.of("memory:extract"))));
        MemorySourcePolicy.Decision.Denied denied = assertInstanceOf(
                MemorySourcePolicy.Decision.Denied.class, policy.authorize(request(
                        "preference", List.of(source(OWNER, "source-1")), MemoryScope.USER,
                        FactStatus.PENDING_CONFIRM, false, Set.of("memory:extract"))));
        assertEquals("memory_user_scope_review_required", denied.reasonCode());
    }

    /** 管理动作的可信权限允许明确 USER 写入，但仍须通过原来源闭包与类型检查。 */
    @Test
    void trustedManagerMayWriteActiveUserWithoutWeakeningSourceChecks() {
        assertInstanceOf(MemorySourcePolicy.Decision.Allowed.class, policy.authorize(request(
                "preference", List.of(source(OWNER, "source-1")), MemoryScope.USER,
                FactStatus.ACTIVE, false, Set.of("memory:manage"))));
        SessionOwner foreign = SessionOwner.user(new SessionRef(TENANT, "foreign-session"), AGENT, USER);
        MemorySourcePolicy.Decision.Denied denied = assertInstanceOf(
                MemorySourcePolicy.Decision.Denied.class, policy.authorize(request(
                        "preference", List.of(source(foreign, "source-1")), MemoryScope.USER,
                        FactStatus.ACTIVE, false, Set.of("memory:manage"))));
        assertEquals("memory_source_session_mismatch", denied.reasonCode());
    }

    @Test void isolatedTestSourceCannotBePromotedByExtractorOrManager() {
        org.mockito.Mockito.when(tests.isolated(702L,"memory-evaluation-session")).thenReturn(true);
        for(var permissions:List.of(Set.of("memory:extract"),Set.of("memory:manage"))) {
            var denied=assertInstanceOf(MemorySourcePolicy.Decision.Denied.class,policy.authorize(request("preference",List.of(source(OWNER,"test-source")),
                    MemoryScope.USER,FactStatus.ACTIVE,true,permissions)));
            assertEquals("workflow_test_memory_denied",denied.reasonCode());
        }
    }

    /** 构造已完成身份闭包的调用和同来源草稿；不模拟 Runtime 已承担的提交核验。 */
    private static MemorySourcePolicy.Request request(String type, List<CommittedSessionEvent> sources) {
        return request(type, sources, MemoryScope.USER_AGENT, FactStatus.ACTIVE, false, Set.of("memory:extract"));
    }

    /** 从同 owner 的真实契约 envelope 只改变被测 scope、状态与可信权限。 */
    private static MemorySourcePolicy.Request request(String type, List<CommittedSessionEvent> sources,
            MemoryScope scope, FactStatus status, boolean review, Set<String> permissions) {
        InputId input = new InputId("memory-evaluation-input");
        RunIdentity run = new RunIdentity(OWNER, new AgentId((OWNER).target().id()), input, new RunRef(TENANT, "memory-evaluation-run"), new TraceEnvelope("memory-evaluation-trace", "memory-evaluation-span", input, Optional.empty()), new ResultRef("memory-evaluation-identity"), new RunRequester.EndUser((OWNER).requireUser()), java.util.Optional.empty(), new RunOriginRef((OWNER).sessionRef().tenantId(), "input:" + (input).value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.SystemPrincipal(TENANT, "memory-extractor"), OWNER,
                permissions, 1, new ResultRef("memory-evaluation-verification"));
        PortCallContext context = new PortCallContext(run, identity,
                new InvocationControl(NOW.plusSeconds(30), () -> false));
        MemoryFactDraft draft = new MemoryFactDraft(
                switch (scope) { case SESSION -> new MemoryOwner.Session(OWNER.sessionRef()); case USER -> new MemoryOwner.User(OWNER.sessionRef().tenantId(), USER); case USER_AGENT -> new MemoryOwner.UserAgent(OWNER.sessionRef().tenantId(), USER, AGENT); default -> throw new IllegalArgumentException("personal fixture scope"); },
                new MemoryTypeRef(type),
                new RetentionClassRef("standard"),
                "v1",
                "user:7",
                "theme",
                new TypedPayload("skillforge.memory." + type + ".v1", "{\"value\":\"dark\"}"),
                sources.stream().map(CommittedSessionEvent::eventId).toList(),
                OWNER.sessionRef(),
                Optional.empty(),
                Optional.empty(),
                "memory-extractor-v1",
                NOW,
                Optional.empty(),
                Optional.empty(),
                0.9,
                0.7,
                review,
                Optional.empty(),
                status);
        return new MemorySourcePolicy.Request(context, draft, sources);
    }

    /** 创建带正序号的当前格式来源 envelope，外来身份仍在各自租户内保持合法。 */
    private static CommittedSessionEvent source(SessionOwner owner, String eventId) {
        InputId input = new InputId("source-input:" + eventId);
        return new CommittedSessionEvent(owner, input, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), new EventId(eventId), 1, "inbound.message", "v1",
                new TraceEnvelope("source-trace:" + eventId, "source-span:" + eventId, input, Optional.empty()),
                new TypedPayload("inbound.message.v1", "{\"content\":[{\"type\":\"text\",\"text\":\"偏好深色\"}]}"), NOW);
    }
}
