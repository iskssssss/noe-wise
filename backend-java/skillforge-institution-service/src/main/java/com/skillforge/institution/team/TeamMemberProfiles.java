package com.skillforge.institution.team;

import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityVersionView;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;

/** 团队与授权目录共用的成员公开摘要；不读取或复制完整提示词。 */
@Service
public class TeamMemberProfiles {
    private final AgentPublicationService publications;
    private final CapabilityCatalogService catalog;

    public TeamMemberProfiles(AgentPublicationService publications, CapabilityCatalogService catalog) {
        this.publications = publications;
        this.catalog = catalog;
    }

    public TeamDefinition.Member member(AgentDO agent, String version) {
        var published = publications.requirePublished(agent.getTenantId(), agent.getId());
        return member(published.agent(), version, published.mountSet());
    }

    /** 发布时使用与冻结配置相同的挂载集合，避免摘要与执行配置分离。 */
    public TeamDefinition.Member member(AgentDO agent, String version, CapabilityMountSetResult mounted) {
        List<CapabilityMount> active = active(agent, mounted);
        var versions = catalog.publishedVersions(CapabilityCatalogScope.tenant(agent.getTenantId()),
                active.stream().map(mount -> Long.valueOf(mount.capability().versionId())).distinct().toList());
        return member(agent, version, active, versions);
    }

    /** 渠道目录先批量读取装配与发布版本，再按调用方给定的 Agent 顺序投影。 */
    public Map<Long, TeamDefinition.Member> members(long tenant, List<AgentDO> agents, String version) {
        if (agents.isEmpty()) return Map.of();
        if (agents.stream().anyMatch(agent -> !Long.valueOf(tenant).equals(agent.getTenantId())))
            throw new IllegalArgumentException("成员目录必须属于同一机构");
        var published = publications.currentPublishedAll(tenant, agents.stream().map(AgentDO::getId).distinct().toList());
        return membersFromPublications(tenant, agents.stream().map(agent -> published.get(agent.getId()))
                .filter(java.util.Objects::nonNull).toList(), version);
    }

    /** 目录调用方复用同一次批量发布读取，不能将工作草稿投影给聊天用户。 */
    public Map<Long, TeamDefinition.Member> membersFromPublications(long tenant,
            List<AgentPublicationService.Publication> published, String version) {
        if (published.isEmpty()) return Map.of();
        var agents = published.stream().map(AgentPublicationService.Publication::agent).toList();
        if (agents.stream().anyMatch(agent -> !Long.valueOf(tenant).equals(agent.getTenantId())))
            throw new IllegalArgumentException("成员目录必须属于同一机构");
        var mounted = published.stream().collect(java.util.stream.Collectors.toMap(
                publication -> publication.agent().getId(), AgentPublicationService.Publication::mountSet));
        Map<Long, List<CapabilityMount>> active = new LinkedHashMap<>();
        agents.forEach(agent -> active.put(agent.getId(), active(agent, mounted.get(agent.getId()))));
        var versionIds = active.values().stream().flatMap(List::stream)
                .map(mount -> Long.valueOf(mount.capability().versionId())).distinct().toList();
        var versions = catalog.publishedVersions(CapabilityCatalogScope.tenant(tenant), versionIds);
        Map<Long, TeamDefinition.Member> result = new LinkedHashMap<>();
        agents.forEach(agent -> result.put(agent.getId(), member(agent, version, active.get(agent.getId()), versions)));
        return result;
    }

    private List<CapabilityMount> active(AgentDO agent, CapabilityMountSetResult mounted) {
        return mounted == null ? List.of() : mounted.mounts().stream()
                .filter(CapabilityMount::active)
                .filter(mount -> agent.getTenantId().equals(mount.tenantId()) && agent.getId().equals(mount.agentId()))
                .toList();
    }

    private TeamDefinition.Member member(AgentDO agent, String version, List<CapabilityMount> active,
            List<CapabilityVersionView> versions) {
        var summaries = active.stream().flatMap(mount -> versions.stream()
                .filter(value -> "PUBLISHED".equals(value.status())
                        && value.id().toString().equals(mount.capability().versionId())
                        && value.definitionId().toString().equals(mount.capability().capabilityId())
                        && value.capabilityType() == mount.capability().type())
                .map(value -> new TeamDefinition.CapabilitySummary(value.capabilityType().name(),
                        value.name(), value.description()))).distinct().toList();
        return new TeamDefinition.Member(agent.getId().toString(), version, agent.getName(),
                agent.getRoleDescription(), summaries);
    }
}
