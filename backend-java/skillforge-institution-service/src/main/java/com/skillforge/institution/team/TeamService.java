package com.skillforge.institution.team;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 草稿与已发布版本分别存储；冻结配置不替代成员当前授权。 */
@Service
public class TeamService implements com.skillforge.core.chat.ChatTeamGateway {
    private static final org.slf4j.Logger DATABASE_LOG = org.slf4j.LoggerFactory.getLogger(TeamService.class);
    private final TeamDefinitionMapper mapper;
    private final ObjectMapper json;
    private final AgentMapper agents;
    private final AgentPublicationService publications;
    private final CapabilityMountResolver mountResolver;
    private final PlatformModelCatalogService models;
    private final TeamMemberProfiles profiles;

    /**
     * 装配本领域持久化和协作依赖。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param mapper 领域持久化入口，使用调用方事务
     * @param json 领域序列化器
     * @param agents Agent 当前状态查询入口
     * @param publications Agent 已发布快照读取入口
     * @param mountResolver 新执行的能力版本解析入口
     * @param models 模型目录与配置解析入口
     * @param profiles 成员公开资料生成入口
     */
    public TeamService(TeamDefinitionMapper mapper, ObjectMapper json, AgentMapper agents,
            AgentPublicationService publications, CapabilityMountResolver mountResolver, PlatformModelCatalogService models,
            TeamMemberProfiles profiles) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "TeamService", TenantContext.getTenantId());
        try {
            this.mapper = mapper; this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            this.json.addMixIn(com.skillforge.core.capability.CapabilityMount.MountConfig.class, MountConfigJson.class);
            this.agents = agents; this.publications = publications; this.mountResolver = mountResolver; this.models = models;
            this.profiles = profiles;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "TeamService", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "TeamService", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 列出授权范围内的团队定义。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly = true)
    public List<TeamDefinition> list(String tenant) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "list", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            return mapper.definitions(Long.parseLong(tenant)).stream().map(payload -> read(payload, TeamDefinition.class)).toList();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "list", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "list", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取当前团队定义。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly = true)
    public TeamDefinition definition(String tenant, String id) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "definition", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
     return require(tenant, id, false);
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "definition", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "definition", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 按期望版本停用团队，失效成员不阻止停用。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @param revision 期望版本，用于拒绝并发覆盖
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional
    public TeamDefinition disable(String tenant, String id, long revision) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "disable", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            var previous = require(tenant, id, true);
            if (previous.revision() != revision) throw new ApiException(HttpStatus.CONFLICT, "团队配置已更新，请刷新后再操作");
            var disabled = new TeamDefinition(id, previous.name(), previous.description(), "disabled", revision + 1,
                    previous.members(), previous.primaryAgentId());
            // 停用不重新解析成员，成员已删除或撤权也必须允许停止团队。
            mapper.disable(disabled.revision(), write(disabled), Long.parseLong(tenant), id);
            return disabled;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "disable", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "disable", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 校验成员后保存团队草稿。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @param request 待处理的领域请求
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional
    public TeamDefinition save(String tenant, String id, TeamDefinition request) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "save", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            long revision = 1;
            String state = "draft";
            if (id != null) {
                TeamDefinition previous = require(tenant, id, true);
                if (request.revision() != previous.revision()) throw new ApiException(HttpStatus.CONFLICT, "团队配置已更新，请刷新后再保存");
                revision = previous.revision() + 1;
                state = "disabled".equals(request.status()) ? "disabled" : previous.status();
            } else id = UUID.randomUUID().toString();
            TeamDefinition definition = new TeamDefinition(id, request.name(), request.description(), state,
                    revision, request.members().stream().map(member -> profiles.member(
                            requireAgent(tenant, member.agentId(), false), "")).toList(), request.primaryAgentId());
            requireDelegationScope(tenant, definition);
            if (revision == 1) mapper.insertDraft(Long.parseLong(tenant), id, revision, state, write(definition));
            else mapper.updateDraft(revision, state, write(definition), Long.parseLong(tenant), id);
            return definition;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "save", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "save", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 在一致快照中冻结成员配置并发布新版本。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TeamDefinition publish(String tenant, String id) {
        return publish(tenant,id,null);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TeamDefinition publish(String tenant, String id, Long expectedRevision) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "publish", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            TeamDefinition draft = require(tenant, id, true);
            if (expectedRevision != null && draft.revision() != expectedRevision)
                throw new ApiException(HttpStatus.CONFLICT, "团队配置已更新，请刷新后再发布");
            if (expectedRevision != null && "disabled".equals(draft.status()))
                throw new ApiException(HttpStatus.CONFLICT, "停用团队不能发布");
            requireDelegationScope(tenant, draft);
            Map<String, FrozenMember> frozen = new LinkedHashMap<>();
            List<TeamDefinition.Member> members = new ArrayList<>();
            for (var member : draft.members()) {
                var snapshot = freezeAgent(tenant, member.agentId());
                frozen.put(member.agentId(), snapshot);
                members.add(publicProfile(tenant, snapshot));
            }
            // 发布生成新版本；重复发布不会修改已引用版本。
            var published = new TeamDefinition(id, draft.name(), draft.description(), "published", draft.revision() + 1,
                    members, draft.primaryAgentId());
            mapper.insertVersion(Long.parseLong(tenant), id, published.revision(), write(new FrozenTeam(published, frozen)));
            mapper.publish(published.revision(), published.revision(), write(published), Long.parseLong(tenant), id);
            return published;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "publish", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "publish", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 读取当前仍发布且有效的团队版本。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly = true)
    public FrozenTeam published(String tenant, String id) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "published", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            return mapper.published(Long.parseLong(tenant), id).stream().map(payload -> read(payload, FrozenTeam.class)).toList()
                    .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "团队尚未发布或已停用"));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "published", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "published", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 根会话历史使用当前发布指针对应的公开定义；停用只允许读取，不授予执行或成员来源访问权。 */
    @Transactional(readOnly = true)
    public TeamDefinition publishedForHistory(String tenant, String id) {
        String previous = TenantContext.getTenantId();
        if (previous != null && !previous.equals(tenant))
            throw new SecurityException("database_tenant_scope_mismatch");
        return TenantContext.callWithTenant(tenant, () -> mapper.publishedForHistory(Long.parseLong(tenant), id).stream()
                .findFirst().map(payload -> read(payload, TeamDefinition.class))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "团队没有可读取的发布记录")));
    }

    /** 每个新 Run 按发布团队的稳定成员身份解析当前发布配置，历史和在途 Run 保留原快照。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FrozenTeam freezePublished(String tenant, String id) {
        var source = published(tenant, id);
        Map<String, FrozenMember> members = new LinkedHashMap<>();
        List<TeamDefinition.Member> directory = new ArrayList<>();
        for (var reference : source.team().members()) {
            var member = freezeAgent(tenant, reference.agentId());
            members.put(reference.agentId(), member);
            directory.add(publicProfile(tenant, member));
        }
        var team = source.team();
        return new FrozenTeam(new TeamDefinition(team.id(), team.name(), team.description(), team.status(),
                team.revision(), directory, team.primaryAgentId()), members);
    }

    /** 修订历史仅公开团队配置，不向管理 API 暴露成员私有执行快照。 */
    @Transactional(readOnly = true)
    public List<TeamDefinition> revisions(String tenant, String id) {
        String previous = TenantContext.getTenantId();
        if (previous != null && !previous.equals(tenant)) throw new SecurityException("database_tenant_scope_mismatch");
        TenantContext.setTenantId(tenant);
        try {
            require(tenant, id, false);
            return mapper.versions(Long.parseLong(tenant), id).stream()
                    .map(payload -> read(payload, FrozenTeam.class).team()).toList();
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    /**
     * 读取指定冻结版本，不改写历史事实。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @param revision 期望版本，用于拒绝并发覆盖
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly = true)
    public FrozenTeam version(String tenant, String id, long revision) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "version", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            return mapper.version(Long.parseLong(tenant), id, revision).stream().map(payload -> read(payload, FrozenTeam.class)).toList()
                    .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "团队发布版本不存在"));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "version", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "version", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 普通工作室对话固定由当前发布版本的主助手受理。 */
    @Override
    /**
     * 读取团队当前发布版本的主助手。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly = true)
    public String primaryAgentId(String tenant, String id) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "primaryAgentId", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            return published(tenant, id).team().primaryAgentId();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "primaryAgentId", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "primaryAgentId", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 每次使用能力和披露结果仍校验当前成员状态；仅恢复事实不调用本授权入口。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @param team 当前团队标识
     * @param agent 待校验或执行的 Agent 标识
     */
    @Transactional(readOnly = true)
    public void requireEnabledMember(String tenant, String team, long revision, String agent) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "requireEnabledMember", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            published(tenant, team); // 实时停用立即生效，成员身份仍由本 Run 的发布修订限定。
            if (!version(tenant, team, revision).members().containsKey(agent))
                throw new ApiException(HttpStatus.FORBIDDEN, "成员不属于执行采用的团队修订");
            requireAgent(tenant, agent, true);
            // 用户团队只能使用来源渠道当前仍授权的成员；恢复事实登记不经过此能力入口。
            if (Boolean.TRUE.equals(mapper.revokedChannelMember(Long.parseLong(tenant), team, agent)))
                throw new ApiException(HttpStatus.FORBIDDEN, "团队成员的渠道授权已撤销");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "requireEnabledMember", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "requireEnabledMember", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 工作室只收窄主 Agent 自身允许的目标，不在发布时授予能力。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param tenant 已授权租户标识
     * @param definition 待校验团队定义
     */
    private void requireDelegationScope(String tenant, TeamDefinition definition) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "requireDelegationScope", TenantContext.getTenantId());
        try {
            var primary = publications.requirePublished(Long.parseLong(tenant), Long.parseLong(definition.primaryAgentId())).agent();
            var config = primary.getDelegationConfig();
            for (var member : definition.members()) {
                if (!member.agentId().equals(definition.primaryAgentId())
                        && (!config.enabled() || !config.targetAgentIds().contains(member.agentId())))
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "协作成员不在主助手已启用的委派名单中，请调整成员");
            }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "requireDelegationScope", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "requireDelegationScope", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 普通 Agent Run 与工作室发布共用同一份私有执行配置捕获。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(rollbackFor=Exception.class)
    public FrozenMember freezeAgent(String tenant, String id) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "freezeAgent", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            requireAgent(tenant, id, true);
            publications.prepareExecution(Long.parseLong(tenant),Long.parseLong(id));
            var publication = publications.requirePublished(Long.parseLong(tenant), Long.parseLong(id));
            var agent = publication.agent();
            var model = agent.getModelConfigId() == null ? models.resolveDefault(tenant, "LLM")
                    : models.requireEnabled(agent.getModelConfigId().toString(), "LLM");
            var source = publication.mountSet();
            String inputId = "publication:" + publication.revisionId();
            var resolved = mountResolver.freeze(CapabilityMountSnapshot.snapshotIdForInput(inputId),
                    inputId, source.tenantId(), source.agentId(),
                    source.generation(), source.mounts());
            var mounted = new CapabilityMountSetResult(source.tenantId(), source.agentId(),
                    source.generation(), resolved.mounts());
            return new FrozenMember(id, agent.getName(), agent.getSystemPrompt(),
                    Objects.requireNonNullElse(agent.getRoleDescription(), ""),
                    agent.getExecutionConfig(),
                    model.id(), model.revision(), mounted, publication.revisionId(), publication.revision(),
                    agent.getDelegationConfig(),publication.definitionVersion(),publication.definitionTemplateId());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "freezeAgent", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "freezeAgent", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 从冻结身份生成不包含私有提示词的公开成员资料。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param tenant 已授权租户标识
     * @param member 冻结的团队成员配置
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    public TeamDefinition.Member publicProfile(String tenant, FrozenMember member) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "publicProfile", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = tenant;
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            var source = new com.skillforge.core.agent.model.AgentDO();
            source.setId(Long.valueOf(member.agentId())); source.setTenantId(Long.valueOf(tenant));
            source.setName(member.name()); source.setRoleDescription(member.roleDescription());
            return profiles.member(source, hash(write(member)), member.mounts());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "publicProfile", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "publicProfile", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 校验 Agent 的租户归属与当前发布状态。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @param published 是否必须满足发布状态
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private com.skillforge.core.agent.model.AgentDO requireAgent(String tenant, String id, boolean published) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "requireAgent", TenantContext.getTenantId());
        try {
            var agent = agents.selectById(Long.parseLong(id));
            if (agent == null || !Objects.equals(agent.getTenantId(), Long.valueOf(tenant))
                    || published && !"published".equals(agent.getStatus()))
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "团队成员必须属于当前机构且已发布");
            return agent;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "requireAgent", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "requireAgent", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 读取领域事实，不存在时抛出业务异常。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @param lock 是否为后续原子修改持有行锁
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private TeamDefinition require(String tenant, String id, boolean lock) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "require", TenantContext.getTenantId());
        try {
            return mapper.definition(Long.parseLong(tenant), id, lock).stream().map(payload -> read(payload, TeamDefinition.class)).toList()
                    .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "团队不存在"));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "require", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "require", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 反序列化内部持久化快照，格式损坏时失败。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param payload 内部序列化数据，不进入日志
     * @param type 目标反序列化类型
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private <T> T read(String payload, Class<T> type) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "read", TenantContext.getTenantId());
        try {
            try { return json.readValue(payload, type); }
            catch (java.io.IOException failure) { throw new IllegalStateException("team_snapshot_invalid", failure); }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "read", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "read", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 生成稳定 JSON 快照供持久化与摘要计算使用。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param value 待校验或序列化的领域值
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String write(Object value) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "write", TenantContext.getTenantId());
        try {
            try { return json.writeValueAsString(value); }
            catch (java.io.IOException failure) { throw new IllegalStateException("team_snapshot_invalid", failure); }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "write", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "write", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 计算稳定 SHA-256 摘要，避免依赖运行环境编码。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param value 待校验或序列化的领域值
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String hash(String value) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamService", "hash", TenantContext.getTenantId());
        try {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamService", "hash", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamService", "hash", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    public record FrozenTeam(TeamDefinition team, Map<String, FrozenMember> members) {
        public FrozenTeam { members = Map.copyOf(members); }
    }
    /** 只保存配置和模型版本引用，不复制模型凭据。 */
    public record FrozenMember(String agentId, String name, String systemPrompt, String roleDescription,
            AgentExecutionConfig executionConfig, String modelId, long modelRevision, CapabilityMountSetResult mounts,
            Long agentRevisionId, int agentRevision, AgentDelegationConfig delegationConfig,
            String agentDefinitionVersion,Long templateId) {
        /** 独立Agent的定义源是自身发布，公共部署由解析器显式提供模板版本。 */
        public FrozenMember(String agentId,String name,String systemPrompt,String roleDescription,
                AgentExecutionConfig executionConfig,String modelId,long modelRevision,CapabilityMountSetResult mounts,
                Long agentRevisionId,int agentRevision,AgentDelegationConfig delegationConfig) {
            this(agentId,name,systemPrompt,roleDescription,executionConfig,modelId,modelRevision,mounts,
                    agentRevisionId,agentRevision,delegationConfig,"agent:"+agentRevision,null);
        }
        public FrozenMember {
            Objects.requireNonNull(executionConfig, "executionConfig");
            Objects.requireNonNull(agentRevisionId, "agentRevisionId");
            if (agentRevision < 1) throw new IllegalArgumentException("agent_revision_invalid");
            Objects.requireNonNull(delegationConfig, "delegationConfig");
            Objects.requireNonNull(agentDefinitionVersion,"agentDefinitionVersion");
        }
    }

    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME, property = "type")
    @com.fasterxml.jackson.annotation.JsonSubTypes({
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = com.skillforge.core.capability.CapabilityMount.SkillMountConfig.class, name = "SKILL"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = com.skillforge.core.capability.CapabilityMount.ToolMountConfig.class, name = "TOOL"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = com.skillforge.core.capability.CapabilityMount.WorkflowMountConfig.class, name = "WORKFLOW"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig.class, name = "KNOWLEDGE_SOURCE")
    })
    private abstract static class MountConfigJson { }
}
