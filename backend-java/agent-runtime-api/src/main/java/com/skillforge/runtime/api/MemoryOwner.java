package com.skillforge.runtime.api;

/** 记忆的封闭归属；群公共事实不携带个人或服务 Agent 归属。 */
public sealed interface MemoryOwner permits MemoryOwner.Session, MemoryOwner.UserAgent,
        MemoryOwner.User, MemoryOwner.Group, MemoryOwner.GroupMember {
    MemoryScope scope();
    TenantId tenantId();

    /** 会话事实随 Session 共享，不绑定执行它的某个 Agent。 */
    record Session(SessionRef sessionRef) implements MemoryOwner {
        public Session { sessionRef = ApiContracts.requireNonNull(sessionRef, "sessionRef"); }
        public MemoryScope scope() { return MemoryScope.SESSION; }
        public TenantId tenantId() { return sessionRef.tenantId(); }
    }

    /** 真实用户与某个 Agent 之间的个人事实。 */
    record UserAgent(TenantId tenantId, EndUserId endUserId, AgentId agentId) implements MemoryOwner {
        public UserAgent {
            tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
            endUserId = ApiContracts.requireNonNull(endUserId, "endUserId");
            agentId = ApiContracts.requireNonNull(agentId, "agentId");
        }
        public MemoryScope scope() { return MemoryScope.USER_AGENT; }
    }

    /** 真实用户跨获授权 Agent 使用的个人事实。 */
    record User(TenantId tenantId, EndUserId endUserId) implements MemoryOwner {
        public User {
            tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
            endUserId = ApiContracts.requireNonNull(endUserId, "endUserId");
        }
        public MemoryScope scope() { return MemoryScope.USER; }
    }

    /** 绑定一个群生命周期的公共事实。 */
    record Group(GroupBindingRef groupBindingRef) implements MemoryOwner {
        public Group { groupBindingRef = ApiContracts.requireNonNull(groupBindingRef, "groupBindingRef"); }
        public MemoryScope scope() { return MemoryScope.GROUP; }
        public TenantId tenantId() { return groupBindingRef.tenantId(); }
    }

    /** 描述对象与访问者分离；对象退出群不改变原事实归属。 */
    record GroupMember(GroupBindingRef groupBindingRef, EndUserId subjectEndUserId) implements MemoryOwner {
        public GroupMember {
            groupBindingRef = ApiContracts.requireNonNull(groupBindingRef, "groupBindingRef");
            subjectEndUserId = ApiContracts.requireNonNull(subjectEndUserId, "subjectEndUserId");
        }
        public MemoryScope scope() { return MemoryScope.GROUP_MEMBER; }
        public TenantId tenantId() { return groupBindingRef.tenantId(); }
    }
}
