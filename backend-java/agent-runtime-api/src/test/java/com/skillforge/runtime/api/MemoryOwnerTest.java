package com.skillforge.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证封闭归属不再依赖个人空字段，群查询不能扩展到个人或全群成员。 */
class MemoryOwnerTest {
    private static final TenantId TENANT = new TenantId("owner-tenant");
    private static final EndUserId USER = new EndUserId(7);
    private static final AgentId AGENT = new AgentId("owner-agent");
    private static final SessionRef SESSION = new SessionRef(TENANT, "owner-session");
    private static final GroupBindingRef GROUP = new GroupBindingRef(TENANT, "owner-group");

    @Test
    void branchesContainOnlyTheirActualOwnership() {
        List<MemoryOwner> owners = List.of(new MemoryOwner.Session(SESSION),
                new MemoryOwner.UserAgent(TENANT, USER, AGENT), new MemoryOwner.User(TENANT, USER),
                new MemoryOwner.Group(GROUP), new MemoryOwner.GroupMember(GROUP, USER));
        assertEquals(List.of(MemoryScope.SESSION, MemoryScope.USER_AGENT, MemoryScope.USER,
                MemoryScope.GROUP, MemoryScope.GROUP_MEMBER), owners.stream().map(MemoryOwner::scope).toList());
        assertEquals(Set.of(TENANT), owners.stream().map(MemoryOwner::tenantId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of("groupBindingRef"), java.util.Arrays.stream(MemoryOwner.Group.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertThrows(NullPointerException.class, () -> new MemoryOwner.Group(null));
        assertThrows(NullPointerException.class, () -> new MemoryOwner.GroupMember(GROUP, null));
        assertThrows(NullPointerException.class, () -> new MemoryOwner.UserAgent(TENANT, USER, null));
    }

    @Test
    void queryCannotChangeOwnershipThroughScopeOrSubject() {
        assertEquals(Set.of(MemoryScope.GROUP, MemoryScope.GROUP_MEMBER),
                query(new MemoryOwner.Group(GROUP), Optional.of(USER), Set.of(MemoryScope.GROUP, MemoryScope.GROUP_MEMBER)).scopes());
        assertThrows(IllegalArgumentException.class,
                () -> query(new MemoryOwner.Group(GROUP), Optional.empty(), Set.of(MemoryScope.USER)));
        assertThrows(IllegalArgumentException.class,
                () -> query(new MemoryOwner.GroupMember(GROUP, USER), Optional.empty(), Set.of(MemoryScope.GROUP)));
        assertThrows(IllegalArgumentException.class,
                () -> query(new MemoryOwner.GroupMember(GROUP, USER), Optional.of(new EndUserId(8)), Set.of(MemoryScope.GROUP_MEMBER)));
        assertThrows(IllegalArgumentException.class,
                () -> query(new MemoryOwner.User(TENANT, USER), Optional.of(USER), Set.of(MemoryScope.USER)));
    }

    private static MemoryQuery query(MemoryOwner owner, Optional<EndUserId> subject, Set<MemoryScope> scopes) {
        return new MemoryQuery("owner-query",
                owner,
                subject,
                "read",
                Optional.empty(),
                scopes,
                Set.of(),
                MemoryQueryProfile.DEFAULT,
                Instant.EPOCH,
                MemoryRetrievalPolicy.lexicalV1(),
                5,
                100,
                1000,
                true);
    }
}
