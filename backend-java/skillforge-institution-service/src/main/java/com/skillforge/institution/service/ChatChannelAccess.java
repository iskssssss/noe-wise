package com.skillforge.institution.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.channel.mapper.ChannelAgentBindingMapper;
import com.skillforge.core.channel.model.ChannelAgentBindingDO;
import com.skillforge.core.agent.constant.AgentConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.chat.ChatTeamService;
import com.skillforge.institution.team.TeamService;
import com.skillforge.institution.team.TeamDefinition;
import com.skillforge.institution.team.TeamMemberProfiles;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 聊天开放入口的渠道绑定授权；目录仅返回已授权目标交集后的委派配置。 */
@Service
public class ChatChannelAccess {
    private final AgentMapper agents;
    private final AgentPublicationService publications;
    private final ChannelAgentBindingMapper bindings;
    private final SessionService sessions;
    private final ChatTeamService userTeams;
    private final TeamService teams;
    private final TeamMemberProfiles profiles;

    public ChatChannelAccess(AgentMapper agents, ChannelAgentBindingMapper bindings, SessionService sessions,
            ChatTeamService userTeams, TeamService teams, TeamMemberProfiles profiles, AgentPublicationService publications) {
        this.agents = agents;
        this.publications = publications;
        this.bindings = bindings;
        this.sessions = sessions;
        this.userTeams = userTeams; this.teams = teams; this.profiles = profiles;
    }

    public record ChatAgent(String id, String name, boolean isDefault, String roleDescription, String iconUrl,
            List<TeamDefinition.CapabilitySummary> capabilities,
            @JsonProperty("delegation_config") AgentDelegationConfig delegationConfig) { }
    public record Catalog(String channelId, List<ChatAgent> items) { }

    public Catalog catalog() {
        long tenant = tenant();
        long channel = channel();
        var authorized = new LinkedHashMap<String, AuthorizedAgent>();
        var channelBindings = bindings.selectList(new LambdaQueryWrapper<ChannelAgentBindingDO>()
                .eq(ChannelAgentBindingDO::getTenantId, tenant)
                .eq(ChannelAgentBindingDO::getChannelId, channel)
                .orderByDesc(ChannelAgentBindingDO::getIsDefault)
                .orderByDesc(ChannelAgentBindingDO::getPriority));
        if (channelBindings.isEmpty()) return new Catalog(Long.toString(channel), List.of());
        var published = publications.currentPublishedAll(tenant,
                channelBindings.stream().map(ChannelAgentBindingDO::getAgentId).distinct().toList());
        for (var binding : channelBindings) {
            var publication = published.get(binding.getAgentId());
            AgentDO agent = publication == null ? null : publication.agent();
            if (agent != null) {
                authorized.putIfAbsent(agent.getId().toString(), new AuthorizedAgent(agent,
                        Boolean.TRUE.equals(binding.getIsDefault())));
            }
        }
        Set<String> authorizedIds = new LinkedHashSet<>(authorized.keySet());
        var items = new LinkedHashMap<String, ChatAgent>();
        var publicProfiles = profiles.membersFromPublications(tenant, authorized.keySet().stream()
                .map(Long::valueOf).map(published::get).toList(), "");
        authorized.forEach((id, authorizedAgent) -> {
            AgentDO agent = authorizedAgent.agent();
            var profile = publicProfiles.get(agent.getId());
            items.put(id, new ChatAgent(id, agent.getName(), authorizedAgent.defaultAgent(),
                    profile.roleDescription(), agent.getIconUrl(), profile.capabilities(),
                    catalogDelegationConfig(agent.getDelegationConfig(), authorizedIds)));
        });
        return new Catalog(Long.toString(channel), List.copyOf(items.values()));
    }

    /** 渠道目录只可暴露同一目录已经授权的委派目标。 */
    private AgentDelegationConfig catalogDelegationConfig(
            AgentDelegationConfig delegationConfig, Set<String> authorizedIds) {
        return new AgentDelegationConfig(delegationConfig.enabled(), delegationConfig.targetAgentIds().stream()
                .filter(authorizedIds::contains)
                .toList());
    }

    public long requireAgent(String ref) {
        if (ref == null || ref.isBlank()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择渠道绑定的智能体");
        long tenant = tenant();
        long channel = channel();
        AgentDO agent = publishedAgent(ref, tenant);
        if (bindings.selectCount(new LambdaQueryWrapper<ChannelAgentBindingDO>()
                .eq(ChannelAgentBindingDO::getTenantId, tenant)
                .eq(ChannelAgentBindingDO::getChannelId, channel)
                .eq(ChannelAgentBindingDO::getAgentId, agent.getId())) == 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "智能体未绑定当前渠道或已不可用");
        }
        return agent.getId();
    }

    /** 命令目录也供机构聊天使用；无渠道身份时必须有真实机构登录，不能仅凭租户上下文放行。 */
    public long requireCommandAgent(String ref) {
        if (ref == null || ref.isBlank()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择智能体");
        if (ChannelContext.getChannelId() != null) return requireAgent(ref);
        long tenant = tenant();
        if (!StpUtil.isLogin()) throw new ApiException(HttpStatus.UNAUTHORIZED, "需要有效登录或渠道凭证");
        return publishedAgent(ref, tenant).getId();
    }

    public void requireTarget(String agent, String team, String user) {
        requireTarget(agent, team, user, false);
    }

    /** 会话目录可列出停用团队的历史，仍要求当前身份和全部发布成员授权。 */
    public void requireHistoryTarget(String agent, String team, String user) {
        requireTarget(agent, team, user, true);
    }

    private void requireTarget(String agent, String team, String user, boolean history) {
        boolean hasAgent = agent != null && !agent.isBlank();
        boolean hasTeam = team != null && !team.isBlank();
        if (hasAgent == hasTeam) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择一个智能体或团队");
        if (hasTeam) {
            if (history) userTeams.requireHistory(team, user);
            else userTeams.requireAvailable(team, user);
        } else requireAgent(agent);
    }

    public void requireCommandTeam(String team, String user) {
        if (ChannelContext.getChannelId() != null) userTeams.requireAvailable(team, user);
        else {
            if (!StpUtil.isLogin()) throw new ApiException(HttpStatus.UNAUTHORIZED, "需要有效登录或渠道凭证");
            teams.published(Long.toString(tenant()), team);
        }
    }

    private AgentDO publishedAgent(String ref, long tenant) {
        var query = new LambdaQueryWrapper<AgentDO>().eq(AgentDO::getTenantId, tenant)
                .eq(AgentDO::getStatus, AgentConstants.STATUS_PUBLISHED);
        if (ref.matches("[0-9]+")) {
            try { query.eq(AgentDO::getId, Long.parseLong(ref)); }
            catch (NumberFormatException invalid) { throw new ApiException(HttpStatus.FORBIDDEN, "智能体不可用"); }
        }
        else query.eq(AgentDO::getAgentKey, ref).orderByDesc(AgentDO::getRevision).last("LIMIT 1");
        AgentDO agent = agents.selectOne(query);
        if (agent == null) throw new ApiException(HttpStatus.FORBIDDEN, "智能体未绑定当前渠道或已不可用");
        return agent;
    }

    public SessionView requireSession(String sessionId) {
        return requireSession(sessionId, false);
    }

    /** 只用于根会话公开历史及状态；不改变执行、成员来源或成果访问门禁。 */
    public SessionView requireSessionHistory(String sessionId) {
        return requireSession(sessionId, true);
    }

    private SessionView requireSession(String sessionId, boolean history) {
        SessionView session = sessions.require(Long.toString(tenant()), sessionId);
        if (!Long.valueOf(channel()).equals(session.getChannelId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "会话不属于当前渠道");
        }
        com.skillforge.institution.chat.ChatEndUserTokens.requireEndUser(
                session.getEndUserId() == null ? null : session.getEndUserId().toString());
        requireTarget(session.getAgentId() == null ? null : session.getAgentId().toString(),
                session.getTeamId(), session.getEndUserId() == null ? null : session.getEndUserId().toString(), history);
        return session;
    }

    private long tenant() { return identity(TenantContext.getTenantId()); }
    private long channel() { return identity(ChannelContext.getChannelId()); }
    private long identity(String value) {
        try { long id = Long.parseLong(value); if (id > 0) return id; }
        catch (RuntimeException ignored) { }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "需要有效渠道凭证");
    }

    private record AuthorizedAgent(AgentDO agent, boolean defaultAgent) { }
}
