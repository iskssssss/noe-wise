package com.skillforge.core.chat.session;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.chat.access.SessionAccessPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话元数据服务。
 *
 * <p>负责会话的创建、归属校验、滑动过期、消息计数与冷摘要维护；
 * 个人会话必须有真实 EndUser 与 Agent/Team；群会话通过专属入口固定 GroupBinding。
 */
@Service
public class SessionService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SessionService.class);

    /** 会话表映射器。 */
    private final SessionMapper sessionMapper;

    /** 版本化运营配置。 */
    private final OperationalSettingsService settings;
    /** 当前请求的Session授权与创建所有权登记。 */
    private final SessionAccessPolicy access;

    /**
     * 构造会话服务。
     *
     * @param sessionMapper 会话表映射器
     * @param settings      版本化运营配置
     */
    public SessionService(SessionMapper sessionMapper, OperationalSettingsService settings,SessionAccessPolicy access) {
        this.sessionMapper = sessionMapper;
        this.settings = settings;
        this.access = access;
    }

    /**
     * 获取或创建会话；认证渠道在首次创建时冻结，续会话必须保持同一渠道。
     *
     * @param tenantId        租户 ID
     * @param endUserId       C 端用户 ID
     * @param sessionId       前端续会话 ID，可空或 new
     * @param agentId         单 Agent 目标
     * @param scopeId         三方透传实体标识，可空
     * @param scopeType       三方透传类型标识，可空
     * @return 会话实体
     */
    @Transactional
    public SessionView getOrCreate(
            String tenantId,
            Long endUserId,
            String sessionId,
            Long agentId,
            String scopeId,
            String scopeType) {
        return view(getOrCreateTarget(tenantId, endUserId, sessionId, agentId, null, scopeId, scopeType));
    }

    /** 获取或创建 Team 目标会话；续会话必须保持原 Team 身份。 */
    @Transactional
    public SessionView getOrCreateTeam(
            String tenantId,
            Long endUserId,
            String sessionId,
            String teamId,
            String scopeId,
            String scopeType) {
        return view(getOrCreateTarget(tenantId, endUserId, sessionId, null, teamId, scopeId, scopeType));
    }

    /** 机构后台一次创建，并将请求身份与会话同事务保存。重复请求不执行回放。 */
    @Transactional
    public SessionView createWithReceipt(String tenantId, Long endUserId, Long agentId, String teamId,
            String scopeId, String scopeType, String requestId) {
        if (RequestContext.getUserId() == null || ServicePrincipalContext.get() != null || ChannelContext.getChannelId() != null)
            throw new ApiException(HttpStatus.FORBIDDEN, "仅机构成员可使用会话创建回执");
        return view(getOrCreateTarget(tenantId, endUserId, null, agentId, teamId, scopeId, scopeType,
                null, null, Map.of(), requestId));
    }

    /** 原创建操作的只读核对；不存在不代表原请求未受理。 */
    @Transactional(readOnly = true)
    public String creationResult(String tenantId, String requestId) {
        access.requireExistingIdentity(tenantId);
        if (RequestContext.getUserId() == null || ServicePrincipalContext.get() != null || ChannelContext.getChannelId() != null)
            throw new ApiException(HttpStatus.FORBIDDEN, "仅机构成员可核对会话创建回执");
        SessionDO session = sessionMapper.selectOne(new LambdaQueryWrapper<SessionDO>()
                .eq(SessionDO::getTenantId, Long.parseLong(tenantId))
                .eq(SessionDO::getCreateRequestId, requestId)
                .eq(SessionDO::getActorRef, "member:" + RequestContext.getUserId()));
        if (session == null) return null;
        access.requireCurrent(view(session), "read");
        return session.getSessionId();
    }

    private SessionDO getOrCreateTarget(
            String tenantId,
            Long endUserId,
            String sessionId,
            Long agentId,
            String teamId,
            String scopeId,
            String scopeType) {
        return getOrCreateTarget(tenantId, endUserId, sessionId, agentId, teamId, scopeId, scopeType, null, null, Map.of(), null);
    }

    /** 创建时一次冻结可信来源与范围，避免先插入无来源Session再补写不可变字段。 */
    private SessionDO getOrCreateTarget(String tenantId, Long endUserId, String sessionId, Long agentId,
            String teamId, String scopeId, String scopeType, String integrationId, String businessScopeId,
            Map<String, Object> metadata, String createRequestId) {
        if (endUserId == null || endUserId <= 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Session 必须关联真实 end_user_id");
        requireExactlyOneTarget(agentId, teamId);
        access.requireTarget(tenantId,sessionId,agentId);
        access.requireEndUser(tenantId,sessionId,endUserId);
        if (isRealSessionId(sessionId)) {
            access.requireExistingIdentity(tenantId);
            SessionDO existing = findByTenantAndSession(tenantId, sessionId);
            if (existing == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "会话不存在: " + sessionId);
            }
            requireCurrentChannel(existing);
            access.requireCurrent(view(existing),"write");
            if (!endUserId.equals(existing.getEndUserId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "会话不属于当前用户");
            }
            if (isClosed(existing)) {
                throw new ApiException(HttpStatus.CONFLICT, "会话已关闭");
            }
            requireFrozenTarget(existing, agentId, teamId);
            return existing;
        }

        access.requireCreation(tenantId);
        SessionDO session = new SessionDO();
        session.setId(SnowflakeIds.nextId());
        session.setSessionId(String.valueOf(SnowflakeIds.nextId()));
        session.setCreateRequestId(createRequestId);
        session.setTenantId(Long.parseLong(tenantId));
        session.setEndUserId(endUserId);
        freezeSource(session, tenantId, endUserId, integrationId);
        session.setIntegrationId(integrationId);
        session.setBusinessScopeId(businessScopeId);
        // 渠道身份来自已认证入口；首次写入即冻结，续会话不补写或重新归属。
        session.setChannelId(currentChannelId());
        session.setScopeId(scopeId);
        session.setScopeType(scopeType);
        session.setAgentId(agentId);
        session.setTeamId(teamId);
        session.setMessageCount(0);
        session.setLastEventId(0L);
        session.setMetadata(Map.copyOf(metadata));
        var memory = settings.read("memory");
        session.setExpiredAt(Instant.now().plus(java.time.Duration.ofMinutes(
                memory.integer("session_ttl_minutes"))));
        Instant now = Instant.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        access.registerCreated(tenantId,session.getSessionId());
        return session;
    }

    /**
     * Open Platform 代理会话入口：externalConversationRef 在 tenant+integration 内稳定映射，
     * externalUserRef 只保存为审计元数据，不允许覆盖内部 endUserId。
     */
    @Transactional
    public SessionView getOrCreateOpenPlatform(
            String tenantId, String integrationId, String externalUserRef,
            String externalConversationRef, String sessionId, Long endUserId, Long agentId) {
        return getOrCreateOpenPlatform(tenantId, integrationId, externalUserRef, externalConversationRef,
                sessionId, endUserId, agentId, null);
    }

    /** 固定integration、可信执行主体和已授权范围在Session首次创建时冻结。 */
    @Transactional
    public SessionView getOrCreateOpenPlatform(
            String tenantId, String integrationId, String externalUserRef,
            String externalConversationRef, String sessionId, Long endUserId, Long agentId,
            String businessScopeId) {
        LOG.debug("开放平台会话解析进入 tenant={} integration={} agent={}", tenantId, integrationId, agentId);
        if (integrationId == null || integrationId.isBlank()
                || integrationId.length() > 128
                || externalConversationRef == null || externalConversationRef.isBlank()
                || externalConversationRef.length() > 512 || externalUserRef == null || externalUserRef.isBlank()
                || externalUserRef.length() > 512 || endUserId == null || endUserId <= 0 || agentId == null || agentId <= 0) {
            throw new IllegalArgumentException("integration and external identity are required");
        }
        var principal = ServicePrincipalContext.get();
        if (principal == null || !tenantId.equals(principal.tenantId()) || !integrationId.equals(principal.integrationId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "Open Platform Session 必须使用固定执行身份");
        sessionMapper.lockOpenPlatform(tenantId + ":" + integrationId + ":" + externalConversationRef + ":" + agentId);
        if(!sessionMapper.ownsAgent(Long.parseLong(tenantId),agentId))
            throw new ApiException(HttpStatus.NOT_FOUND,"Agent 实例不属于当前机构");
        String scopeType = "open-platform:" + integrationId;
        SessionDO existing = sessionMapper.selectOne(new LambdaQueryWrapper<SessionDO>()
                .eq(SessionDO::getTenantId, Long.parseLong(tenantId))
                .eq(SessionDO::getScopeType, scopeType)
                .eq(SessionDO::getScopeId, externalConversationRef)
                .eq(SessionDO::getAgentId, agentId)
                .last("LIMIT 1"));
        if (existing != null) {
            if (existing.getEndUserId() != null && !existing.getEndUserId().equals(endUserId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "外部用户身份与已冻结会话不一致");
            }
            validateOpenPlatformIdentity(existing, externalUserRef, agentId);
            requireOpenPlatformProvenance(existing, integrationId, businessScopeId);
            if (isClosed(existing)) throw new ApiException(HttpStatus.CONFLICT, "会话已关闭");
            if (isRealSessionId(sessionId) && !sessionId.equals(existing.getSessionId()))
                throw new ApiException(HttpStatus.CONFLICT, "外部会话已绑定其他 sessionId");
            LOG.debug("开放平台会话解析退出 outcome=reused");
            return view(existing);
        }
        Map<String, Object> metadata = Map.of("open_platform_integration_id", integrationId,
                "external_user_ref", externalUserRef, "external_conversation_ref", externalConversationRef);
        SessionDO session = getOrCreateTarget(tenantId, endUserId, sessionId, agentId, null,
                externalConversationRef, scopeType, integrationId, businessScopeId, metadata, null);
        if (!scopeType.equals(session.getScopeType()) || !externalConversationRef.equals(session.getScopeId())) {
            throw new ApiException(HttpStatus.CONFLICT, "sessionId 已绑定其他 integration 或 conversation");
        }
        requireOpenPlatformProvenance(session, integrationId, businessScopeId);
        LOG.debug("开放平台会话解析退出 outcome=created");
        return view(session);
    }

    /** 创建群产品 Session；GroupBinding 与 Session 必须由调用者在同库事务中一并提交。 */
    @Transactional
    public SessionView createGroup(String tenantId, String integrationId, String businessScopeId, String groupBindingId) {
        requireGroupPrincipal(tenantId, integrationId);
        long binding = groupId(groupBindingId);
        if (businessScopeId == null || businessScopeId.isBlank())
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "群会话必须固定业务范围");
        var session = new SessionDO();
        session.setId(SnowflakeIds.nextId());
        session.setSessionId(String.valueOf(SnowflakeIds.nextId()));
        session.setTenantId(Long.parseLong(tenantId));
        session.setGroupBindingId(binding);
        session.setIntegrationId(integrationId);
        session.setBusinessScopeId(businessScopeId);
        session.setScopeType("group:" + integrationId);
        session.setScopeId(groupBindingId);
        freezeSource(session, tenantId, null, integrationId);
        session.setMessageCount(0);
        session.setLastEventId(0L);
        session.setMetadata(Map.of());
        Instant now = Instant.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        // 群身份生命周期由绑定关闭决定，不套用个人会话的滑动过期策略。
        sessionMapper.insert(session);
        return view(session);
    }

    /** 绑定领域关闭时保留原 Session 与历史，个人 close 路径不能调用群会话。 */
    @Transactional
    public SessionView closeGroup(String tenantId, String integrationId, String groupBindingId, String sessionId) {
        requireGroupPrincipal(tenantId, integrationId);
        SessionDO session = requireForSystemEntity(tenantId, sessionId);
        if (!Objects.equals(session.getGroupBindingId(), groupId(groupBindingId))
                || !Objects.equals(session.getIntegrationId(), integrationId))
            throw new ApiException(HttpStatus.NOT_FOUND, "群会话不属于当前执行域");
        if (!isClosed(session)) {
            var metadata = new java.util.LinkedHashMap<>(session.getMetadata() == null ? Map.of() : session.getMetadata());
            metadata.put("closed_at", com.skillforge.common.time.BusinessTime.format(Instant.now()));
            session.setMetadata(Map.copyOf(metadata));
            session.setUpdatedAt(Instant.now());
            sessionMapper.updateById(session);
        }
        return view(session);
    }

    /** 群身份只能由已认证的固定服务主体创建或关闭，不能回退成 end_user:null。 */
    private static void requireGroupPrincipal(String tenantId, String integrationId) {
        var principal = ServicePrincipalContext.get();
        if (principal == null || !Objects.equals(tenantId, principal.tenantId())
                || !Objects.equals(integrationId, principal.integrationId()) || !principal.scopes().contains("groups.write"))
            throw new ApiException(HttpStatus.FORBIDDEN, "群会话必须使用当前执行域的群管理身份");
    }

    private static long groupId(String value) {
        try {
            if (value != null && value.matches("[1-9][0-9]{0,18}")) return Long.parseLong(value);
        } catch (NumberFormatException ignored) { }
        throw new ApiException(HttpStatus.BAD_REQUEST, "group_binding_id 无效");
    }

    /** 所有个人公开查询和写操作共用相同的固定Tenant/integration归属边界。 */
    private SessionDO requireOpenPlatformEntity(String tenantId, String sessionId, String integrationId) {
        LOG.debug("开放平台会话授权进入 tenant={} integration={}", tenantId, integrationId);
        try {
            var session = requireForSystemEntity(tenantId, sessionId);
            if (session.getGroupBindingId() != null || !Objects.equals(session.getIntegrationId(), integrationId)
                    || !("open-platform:" + integrationId).equals(session.getScopeType())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "会话不属于当前 integration");
            }
            return session;
        } finally { LOG.debug("开放平台会话授权退出"); }
    }

    /** 续接不得把既有Session接管到不同执行域或业务范围。 */
    private void requireOpenPlatformProvenance(SessionDO session, String integrationId, String businessScopeId) {
        if (!Objects.equals(session.getIntegrationId(), integrationId)
                || !Objects.equals(session.getBusinessScopeId(), businessScopeId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Session 的 integration 与业务范围已冻结");
        }
    }

    /** 来源只取当前可信入口；外部执行使用SP，渠道和原生使用真实渠道/成员或EndUser引用。 */
    private void freezeSource(SessionDO session, String tenantId, Long endUserId, String integrationId) {
        var principal = ServicePrincipalContext.get();
        if (principal != null) {
            if (!tenantId.equals(principal.tenantId()) || (integrationId != null && !integrationId.equals(principal.integrationId())))
                throw new ApiException(HttpStatus.FORBIDDEN, "Session 来源与固定执行身份不一致");
            session.setOriginKind("external");
            session.setActorRef("service_principal:" + principal.principalId());
        } else if (ChannelContext.getChannelId() != null) {
            session.setOriginKind("native"); session.setActorRef("channel:" + ChannelContext.getChannelId());
        } else {
            session.setOriginKind("native");
            session.setActorRef(RequestContext.getUserId() == null ? "end_user:" + endUserId : "member:" + RequestContext.getUserId());
        }
        session.setResponsibilityRef("tenant:" + tenantId);
    }

    private void validateOpenPlatformIdentity(SessionDO session, String externalUserRef, Long agentId) {
        Object existingUser = session.getMetadata() == null ? null : session.getMetadata().get("external_user_ref");
        if (existingUser != null && !externalUserRef.equals(String.valueOf(existingUser))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "外部用户身份与已冻结会话不一致");
        }
        if (session.getTeamId() != null || (agentId != null && !agentId.equals(session.getAgentId()))) {
            throw new ApiException(HttpStatus.CONFLICT, "会话的 Agent deployment 已冻结");
        }
    }

    private void requireExactlyOneTarget(Long agentId, String teamId) {
        boolean hasAgent = agentId != null;
        boolean hasTeam = teamId != null && !teamId.isBlank();
        if (hasAgent == hasTeam) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "会话必须且只能指定一个 Agent 或 Team 目标");
        }
    }

    private void requireFrozenTarget(SessionDO session, Long agentId, String teamId) {
        if (!Objects.equals(session.getAgentId(), agentId)
                || !Objects.equals(session.getTeamId(), teamId)) {
            throw new ApiException(HttpStatus.CONFLICT, "续会话不可变更 Agent 或 Team 目标");
        }
    }

    /**
     * 受控关闭 Open Platform 会话。关闭是会话元数据状态而不是物理删除，
     * 便于 Outbox/Inbox 重放和审计继续按原 sessionId 定位。
     */
    @Transactional
    public SessionView closeOpenPlatform(String tenantId, String sessionId, String integrationId, String reason) {
        SessionDO session = requireOpenPlatformEntity(tenantId, sessionId, integrationId);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(
                session.getMetadata() == null ? Map.of() : session.getMetadata());
        if (!metadata.containsKey("open_platform_closed_at")) {
            metadata.put("open_platform_closed_at", com.skillforge.common.time.BusinessTime.format(Instant.now()));
            if (reason != null && !reason.isBlank()) {
                metadata.put("open_platform_close_reason", reason.length() > 256
                        ? reason.substring(0, 256) : reason);
            }
            session.setMetadata(Map.copyOf(metadata));
            session.setUpdatedAt(Instant.now());
            sessionMapper.updateById(session);
        }
        return view(session);
    }

    /** 判断 Open Platform 会话是否已经接受 close_session 命令。 */
    public boolean isOpenPlatformClosed(SessionView session) {
        return session != null && session.getMetadata() != null
                && (session.getMetadata().containsKey("closed_at")
                || session.getMetadata().containsKey("open_platform_closed_at"));
    }

    /**
     * 受控关闭机构端会话。关闭只更新会话元数据，不物理删除消息、Inbox、Outbox 或审计记录。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @param reason    关闭原因，可空
     * @return 已关闭的会话
     */
    @Transactional
    public SessionView closeForTenant(String tenantId, String sessionId, String reason) {
        SessionDO session = requireWritableEntity(tenantId, sessionId);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(
                session.getMetadata() == null ? Map.of() : session.getMetadata());
        if (!metadata.containsKey("closed_at")) {
            metadata.put("closed_at", com.skillforge.common.time.BusinessTime.format(Instant.now()));
            if (reason != null && !reason.isBlank()) {
                metadata.put("close_reason", reason.length() > 256
                        ? reason.substring(0, 256) : reason);
            }
            session.setMetadata(Map.copyOf(metadata));
            session.setUpdatedAt(Instant.now());
            sessionMapper.updateById(session);
        }
        return view(session);
    }

    /** 后台只可关闭已经进入closing的测试资源；不依赖原成员继续持有登录权限。 */
    @Transactional
    public SessionView closeIsolatedTest(String tenantId,String sessionId) {
        access.requireClosingTest(tenantId,sessionId);
        SessionDO session=requireForSystemEntity(tenantId,sessionId);
        Map<String,Object> metadata=new java.util.LinkedHashMap<>(session.getMetadata()==null?Map.of():session.getMetadata());
        metadata.putIfAbsent("closed_at",com.skillforge.common.time.BusinessTime.format(Instant.now()));
        metadata.putIfAbsent("close_reason","workflow_test_closed");session.setMetadata(Map.copyOf(metadata));session.setUpdatedAt(Instant.now());
        sessionMapper.updateById(session);return view(session);
    }

    /**
     * 会话活跃触碰：刷新过期时间与更新时间。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     */
    public void touch(String tenantId, String sessionId) {
        SessionDO session = findByTenantAndSession(tenantId, sessionId);
        if (session == null) {
            return;
        }
        var memory = settings.read("memory");
        session.setExpiredAt(Instant.now().plus(java.time.Duration.ofMinutes(
                memory.integer("session_ttl_minutes"))));
        session.setUpdatedAt(Instant.now());
        sessionMapper.updateById(session);
    }

    /**
     * 列出租户下会话，按最新消息时间倒序；无消息会话排在最后。
     *
     * @param tenantId  租户 ID
     * @param endUserId C 端用户 ID，可空
     * @param agentId   Agent 归属过滤（管理端对话记录），可空
     * @return 符合筛选条件的全部会话
     */
    public List<SessionDirectoryItem> list(String tenantId, Long endUserId, Long agentId) {
        return list(tenantId, endUserId, agentId, null);
    }

    public List<SessionDirectoryItem> list(String tenantId, Long endUserId, Long agentId, String teamId) {
        if (agentId != null && teamId != null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "会话目标筛选不能同时指定智能体和团队");
        Long channel = currentChannelId();
        List<String> visible=access.visibleSessionIds(tenantId);
        if(visible!=null&&visible.isEmpty())return List.of();
        return sessionMapper.listByLatestMessage(Long.parseLong(tenantId), endUserId, agentId, teamId, channel,visible);
    }

    /**
     * 按租户与会话 ID 查询并在短读事务内校验当前权限；独立调用返回前释放事务。
     * 不存在时抛 404；外部渠道上下文只允许读取本渠道会话。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @return 会话实体
     */
    @Transactional(readOnly = true)
    public SessionView require(String tenantId, String sessionId) {
        SessionDO session = requireForSystemEntity(tenantId,sessionId);
        access.requireCurrent(view(session),"read");
        return view(session);
    }

    /**
     * SSE 每次发送前使用已认证成员身份校验真实会话；同次权限和范围来自短事务的一致当前事实。
     * 主体必须由认证入口捕获，不能从请求体构造；事务在返回时结束，网络发送不持有数据库事务。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public SessionView requireCurrentRead(String tenantId, String sessionId, FoundationAccess.Subject subject) {
        if (subject == null || subject.platform() || !tenantId.equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "session_access_denied", "没有会话访问权限", false);
        String previous = TenantContext.getTenantId();
        try {
            // 与认证主体一起固定 SQL 的 RLS 范围；真实会话和 IAM 不能借用线程的其他租户。
            TenantContext.setTenantId(tenantId);
            SessionDO session = requireForSystemEntity(tenantId, sessionId);
            access.requireCurrentRead(view(session), subject);
            return view(session);
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    /** 独立公开调用在返回前结束当前写权的短读事务；写服务内部调用仍归原写事务。 */
    private SessionDO requireWritableEntity(String tenantId,String sessionId){SessionDO session=requireForSystemEntity(tenantId,sessionId);access.requireCurrent(view(session),"write");return session;}

    /**
     * 内部系统入口，仅供从已持久执行事实/已授权开放平台对象恢复的调用。
     * 不能将请求体tenant/session直接交给此方法取得用户权限；HTTP入口必须使用require/requireWritable。
     */
    private SessionDO requireForSystemEntity(String tenantId, String sessionId) {
        SessionDO session = findByTenantAndSession(tenantId, sessionId);
        if (session == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "会话不存在: " + sessionId);
        }
        requireCurrentChannel(session);
        return session;
    }

    /**
     * 消息计数加一并刷新活跃时间。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     */
    public void incrementMessageCount(String tenantId, String sessionId) {
        SessionDO session = findByTenantAndSession(tenantId, sessionId);
        if (session == null) {
            return;
        }
        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setUpdatedAt(Instant.now());
        sessionMapper.updateById(session);
    }

    /**
     * 写入会话冷摘要。
     *
     * @param tenantId   租户 ID
     * @param sessionId  会话 ID
     * @param summaryText 摘要文本
     */
    public void updateSummary(String tenantId, String sessionId, String summaryText) {
        SessionDO session = findByTenantAndSession(tenantId, sessionId);
        if (session == null) {
            return;
        }
        session.setSummaryText(summaryText);
        session.setUpdatedAt(Instant.now());
        sessionMapper.updateById(session);
    }

    /**
     * 判断是否为真实会话 ID。
     *
     * @param sessionId 会话 ID
     * @return true 表示续会话
     */
    public static boolean isRealSessionId(String sessionId) {
        return sessionId != null
                && !sessionId.isBlank()
                && !"new".equals(sessionId)
                && !"pending".equals(sessionId);
    }

    /**
     * 按租户与会话 ID 查询。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @return 会话实体；不存在返回 null
     */
    private SessionDO findByTenantAndSession(String tenantId, String sessionId) {
        return sessionMapper.selectOne(
                new LambdaQueryWrapper<SessionDO>()
                        .eq(SessionDO::getTenantId, Long.parseLong(tenantId))
                        .eq(SessionDO::getSessionId, sessionId));
    }

    /** 无外部渠道上下文的管理与内部调用不改变原权限；有效渠道不可接管其他或未归属会话。 */
    private static void requireCurrentChannel(SessionDO session) {
        Long channel=currentChannelId();
        if(channel!=null&&!channel.equals(session.getChannelId())) {
            throw new ApiException(HttpStatus.FORBIDDEN,"会话不属于当前渠道");
        }
    }

    private static Long currentChannelId() {
        String channel=ChannelContext.getChannelId();
        if(channel==null)return null;
        try {
            long id=Long.parseLong(channel);
            if(id>0)return id;
        }catch(NumberFormatException ignored){ }
        throw new ApiException(HttpStatus.UNAUTHORIZED,"渠道身份无效");
    }

    private boolean isClosed(SessionDO session) {
        return session != null && session.getMetadata() != null
                && (session.getMetadata().containsKey("closed_at")
                || session.getMetadata().containsKey("open_platform_closed_at"));
    }

    /** 内部执行事实恢复只返回只读快照，不授予请求成员读取权。 */
    public SessionView requireForSystem(String tenantId, String sessionId) {
        return view(requireForSystemEntity(tenantId, sessionId));
    }

    /** 固定执行域内的个人会话归属查询。 */
    public SessionView requireOpenPlatform(String tenantId, String sessionId, String integrationId) {
        return view(requireOpenPlatformEntity(tenantId, sessionId, integrationId));
    }

    /** 在当前写权校验完成后返回快照。 */
    @Transactional(readOnly = true)
    public SessionView requireWritable(String tenantId, String sessionId) {
        return view(requireWritableEntity(tenantId, sessionId));
    }

    static SessionView view(SessionDO session) {
        return new SessionView(
                session.getSessionId(),
                session.getTenantId(),
                session.getEndUserId(),
                session.getGroupBindingId(),
                session.getChannelId(),
                session.getScopeId(),
                session.getScopeType(),
                session.getOriginKind(),
                session.getActorRef(),
                session.getResponsibilityRef(),
                session.getIntegrationId(),
                session.getBusinessScopeId(),
                session.getAgentId(),
                session.getTeamId(),
                session.getMessageCount(),
                session.getSummaryText(),
                session.getMetadata(),
                session.getCreatedAt(),
                session.getUpdatedAt());
    }
}
