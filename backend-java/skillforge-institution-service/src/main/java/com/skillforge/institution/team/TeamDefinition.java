package com.skillforge.institution.team;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/** 团队是发布和接入对象，成员版本由发布服务捕获，客户端不能提交执行身份。 */
public record TeamDefinition(String id, String name, String description, String status, long revision,
        List<Member> members, @JsonProperty("primary_agent_id") String primaryAgentId) {
    public TeamDefinition {
        name = text(name, 100); description = Objects.requireNonNullElse(description, "");
        if (members == null || members.isEmpty() || members.size() > 20)
            throw new IllegalArgumentException("团队成员数量无效");
        members = List.copyOf(members);
        if (members.stream().map(Member::agentId).distinct().count() != members.size()
                || members.stream().noneMatch(member -> member.agentId().equals(primaryAgentId)))
            throw new IllegalArgumentException("主助手必须属于工作室，成员不能重复");
    }

    public record Member(@JsonProperty("agent_id") String agentId,
            @JsonProperty("agent_version") String agentVersion, String name,
            @JsonProperty("role_description") String roleDescription, List<CapabilitySummary> capabilities) {
        public Member {
            agentId = text(agentId, 100);
            roleDescription = Objects.requireNonNullElse(roleDescription, "");
            capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
            agentVersion = Objects.requireNonNullElse(agentVersion, "");
            name = Objects.requireNonNullElse(name, "");
        }
    }
    /** 公开摘要不携带 Descriptor、提示词或凭据。 */
    public record CapabilitySummary(String type, String name, String description) { }

    private static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum)
            throw new IllegalArgumentException("团队字段缺失或过长");
        return value.trim();
    }
}
