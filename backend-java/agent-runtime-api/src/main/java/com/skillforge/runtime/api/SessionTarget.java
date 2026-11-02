package com.skillforge.runtime.api;

/** 会话创建时冻结的服务目标，与每个 Run 的实际执行 Agent 分离。 */
public record SessionTarget(Kind kind, String id) {
    /** 服务目录类型；GROUP 不暗含 Team 或主 Agent。 */
    public enum Kind { AGENT, TEAM, GROUP }

    public SessionTarget {
        kind = ApiContracts.requireNonNull(kind, "kind");
        id = ApiContracts.requireText(id, "id");
    }

    public static SessionTarget agent(AgentId agentId) {
        return new SessionTarget(Kind.AGENT, ApiContracts.requireNonNull(agentId, "agentId").value());
    }

    public static SessionTarget team(String teamId) {
        return new SessionTarget(Kind.TEAM, teamId);
    }

    /** 群服务目录使用同一内部绑定 ID；机构一致性由 SessionOwner 检查。 */
    public static SessionTarget group(GroupBindingRef groupBindingRef) {
        return new SessionTarget(Kind.GROUP, ApiContracts.requireNonNull(groupBindingRef, "groupBindingRef").groupBindingId());
    }

    /** 仅用于直连 Agent 的快捷构造；团队执行必须显式指定实际 Agent。 */
    public AgentId requireAgent() {
        if (kind != Kind.AGENT) throw new IllegalStateException("non-Agent target requires an explicit execution agent");
        return new AgentId(id);
    }
}
