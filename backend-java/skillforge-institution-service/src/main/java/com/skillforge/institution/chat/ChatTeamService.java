package com.skillforge.institution.chat;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.institution.team.TeamDefinition;
import com.skillforge.institution.team.TeamService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 渠道内的个人团队管理；client_ref 只选择本机身份，认证仍由渠道凭证提供。 */
@Service
public class ChatTeamService {
    private static final org.slf4j.Logger DATABASE_LOG = org.slf4j.LoggerFactory.getLogger(ChatTeamService.class);
    private final ChatTeamMapper mapper;
    private final TeamService teams;
    private final ChatClientProfileMapper profiles;
    private final EndUserService users;

    /**
     * 装配本领域持久化和协作依赖。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param mapper 领域持久化入口，使用调用方事务
     * @param teams 团队定义与发布版本服务
     * @param profiles 成员公开资料生成入口
     * @param users 终端用户状态读取服务
     */
    public ChatTeamService(ChatTeamMapper mapper, TeamService teams, ChatClientProfileMapper profiles, EndUserService users) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "ChatTeamService", TenantContext.getTenantId());
        try {
            this.mapper = mapper; this.teams = teams; this.profiles = profiles; this.users = users;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "ChatTeamService", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "ChatTeamService", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 列出授权范围内的团队定义。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param clientRef 已登记的渠道客户端引用
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly = true)
    public List<TeamDefinition> list(String clientRef) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "list", TenantContext.getTenantId());
        try {
            String tenant = tenant(); long channel = channel();
            requireProfile(tenant, channel, clientRef);
            return mapper.teamIds(Long.parseLong(tenant), channel, clientRef).stream()
                    .map(id -> teams.definition(tenant, id)).toList();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "list", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "list", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 保存和发布处于同一事务；发布失败不留下半成品团队或新草稿。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param clientRef 已登记的渠道客户端引用
     * @param draft 待保存团队草稿
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TeamDefinition save(String clientRef, TeamDefinition draft) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "save", TenantContext.getTenantId());
        try {
            String tenant = tenant(); long channel = channel();
            requireProfile(tenant, channel, clientRef);
            if (draft.id() != null) requireOwner(tenant, channel, clientRef, draft.id());
            for (var member : draft.members()) requireAuthorizedAgent(tenant, channel, member.agentId());
            var saved = teams.save(tenant, draft.id(), draft);
            if (draft.id() == null) mapper.insertOwner(Long.parseLong(tenant), saved.id(), channel, clientRef);
            return teams.publish(tenant, saved.id());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "save", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "save", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 按期望版本停用团队，失效成员不阻止停用。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param clientRef 已登记的渠道客户端引用
     * @param id 当前领域对象标识
     * @param revision 期望版本，用于拒绝并发覆盖
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional
    public TeamDefinition disable(String clientRef, String id, long revision) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "disable", TenantContext.getTenantId());
        try {
            String tenant = tenant(); long channel = channel();
            requireProfile(tenant, channel, clientRef); requireOwner(tenant, channel, clientRef, id);
            return teams.disable(tenant, id, revision);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "disable", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "disable", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 创建、查询会话均验证团队所属渠道和终端用户，并实时核对全部成员授权。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param id 当前领域对象标识
     * @param endUserId 会话绑定终端用户标识
     */
    @Transactional(readOnly = true)
    public void requireAvailable(String id, String endUserId) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "requireAvailable", TenantContext.getTenantId());
        try {
            String tenant = tenant(); long channel = channel();
            if (id == null || id.isBlank() || endUserId == null || !endUserId.matches("[1-9][0-9]*"))
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择团队和已登记的聊天身份");
            boolean allowed = Boolean.TRUE.equals(mapper.available(Long.parseLong(tenant), channel, id, endUserId));
            if (!allowed) throw new ApiException(HttpStatus.FORBIDDEN, "团队不属于当前渠道和聊天身份");
            var team = teams.published(tenant, id);
            for (var member : team.team().members()) requireAuthorizedAgent(tenant, channel, member.agentId());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "requireAvailable", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "requireAvailable", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 校验当前渠道仍授权该发布 Agent。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param tenant 已授权租户标识
     * @param channel 已认证渠道标识
     * @param agent 待校验或执行的 Agent 标识
     */
    private void requireAuthorizedAgent(String tenant, long channel, String agent) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "requireAuthorizedAgent", TenantContext.getTenantId());
        try {
            if (!Boolean.TRUE.equals(mapper.authorizedAgent(Long.parseLong(tenant), channel, agent)))
                throw new ApiException(HttpStatus.FORBIDDEN, "团队成员未获当前渠道授权或已不可用，请重新选择");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "requireAuthorizedAgent", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "requireAuthorizedAgent", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 校验个人团队属于当前渠道客户端。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param tenant 已授权租户标识
     * @param channel 已认证渠道标识
     * @param clientRef 已登记的渠道客户端引用
     * @param id 当前领域对象标识
     */
    private void requireOwner(String tenant, long channel, String clientRef, String id) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "requireOwner", TenantContext.getTenantId());
        try {
            if (!Boolean.TRUE.equals(mapper.owns(Long.parseLong(tenant), channel, clientRef, id)))
                throw new ApiException(HttpStatus.FORBIDDEN, "只能维护当前聊天身份创建的团队");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "requireOwner", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "requireOwner", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 校验客户端引用及终端用户登记状态。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param tenant 已授权租户标识
     * @param channel 已认证渠道标识
     * @param ref 客户端身份引用或已绑定事实引用
     */
    private void requireProfile(String tenant, long channel, String ref) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "requireProfile", TenantContext.getTenantId());
        try {
            try { if (!UUID.fromString(ref).toString().equalsIgnoreCase(ref)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException | NullPointerException invalid) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "client_ref 必须为已登记的 UUID"); }
            Long user = profiles.find(Long.parseLong(tenant), channel, ref);
            if (user == null || !profiles.channelExists(Long.parseLong(tenant), channel)
                    || !"active".equals(users.require(tenant, user).getStatus()))
                throw new ApiException(HttpStatus.FORBIDDEN, "请先登记当前渠道的聊天身份");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "requireProfile", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "requireProfile", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 读取所属租户标识。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String tenant() {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "tenant", TenantContext.getTenantId());
        try {
            String tenant = TenantContext.getTenantId(); identity(tenant);
            /* 租户由入口作用域绑定，连接状态由 RLS 拦截器管理。 */;
            return tenant;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "tenant", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "tenant", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 读取并校验当前认证渠道标识。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private long channel() {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "channel", TenantContext.getTenantId());
        try {
     return identity(ChannelContext.getChannelId());
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "channel", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "channel", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 解析正整数身份，非法身份按未认证拒绝。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param value 待校验或序列化的领域值
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private long identity(String value) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "ChatTeamService", "identity", TenantContext.getTenantId());
        try {
            try { long result = Long.parseLong(value); if (result > 0) return result; } catch (RuntimeException ignored) { }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "需要有效渠道凭证");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "ChatTeamService", "identity", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "ChatTeamService", "identity", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
}
