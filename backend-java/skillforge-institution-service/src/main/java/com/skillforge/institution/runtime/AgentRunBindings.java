package com.skillforge.institution.runtime;

import com.skillforge.common.context.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantChain;
import com.skillforge.institution.artifact.ArtifactMapper;
import com.skillforge.institution.team.TeamDefinition;
import com.skillforge.institution.team.TeamService;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.SessionDelegations;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Agent Run 的不可变执行配置；工作室只是可选的目标范围。 */
@Service
public class AgentRunBindings {
    private static final org.slf4j.Logger DATABASE_LOG = org.slf4j.LoggerFactory.getLogger(AgentRunBindings.class);
    public static final String TARGET_SNAPSHOT_SCHEMA = "skillforge.agent.delegation-target.v1";
    private final AgentRunBindingsMapper mapper;
    private final TeamService teams;
    private final SessionDelegations.Store delegations;
    private org.springframework.beans.factory.ObjectProvider<com.skillforge.runtime.session.spi.ExecutionStore> executions;
    private final AgentMapper agents;
    private final com.skillforge.core.agent.mapper.AgentDeploymentMapper deployments;
    private final com.skillforge.core.agent.deployment.AgentTemplateGrantService grants;
    private final com.skillforge.core.foundation.access.FoundationAccess foundation;
    private final ArtifactMapper access;
    private final ObjectMapper json;
    private final com.skillforge.core.foundation.integration.IntegrationProvenanceService provenance;

    /**
     * 装配本领域持久化和协作依赖。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param mapper 领域持久化入口，使用调用方事务
     * @param teams 团队定义与发布版本服务
     * @param delegations 委派执行引擎
     * @param dataSource 宿主提供的 Runtime 数据源
     * @param json 领域序列化器
     * @param agents Agent 当前状态查询入口
     * @param access 会话访问权查询入口
     */
    public AgentRunBindings(AgentRunBindingsMapper mapper, TeamService teams,
            SessionDelegations.Store delegations, DataSource dataSource, ObjectMapper json,
            AgentMapper agents, ArtifactMapper access, com.skillforge.core.foundation.access.FoundationAccess foundation,
            com.skillforge.core.foundation.integration.IntegrationProvenanceService provenance,
            com.skillforge.core.agent.mapper.AgentDeploymentMapper deployments,
            com.skillforge.core.agent.deployment.AgentTemplateGrantService grants) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "AgentRunBindings", TenantContext.getTenantId());
        try {
            this.mapper=Objects.requireNonNull(mapper); this.teams=Objects.requireNonNull(teams);
            this.delegations=Objects.requireNonNull(delegations); this.agents=Objects.requireNonNull(agents);
            this.foundation=Objects.requireNonNull(foundation); this.access=Objects.requireNonNull(access);
            this.provenance=Objects.requireNonNull(provenance);
            this.deployments=Objects.requireNonNull(deployments);
            this.grants=Objects.requireNonNull(grants);
            this.json=com.skillforge.institution.runtime.SessionSubjectJson.configure(json.copy()).setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            this.json.addMixIn(com.skillforge.core.capability.CapabilityMount.MountConfig.class, MountConfigJson.class);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "AgentRunBindings", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "AgentRunBindings", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 延迟解析唯一新执行Store，避免Run绑定与能力材料装配形成构造循环。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void executions(org.springframework.beans.factory.ObjectProvider<com.skillforge.runtime.session.spi.ExecutionStore> value) {
        executions = Objects.requireNonNull(value);
    }

    /**
     * 按租户与运行标识读取不可变绑定。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param run 带租户的运行引用
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly=true)
    public Optional<Binding> find(RunRef run) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "find", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = run.tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            var row=mapper.find(run.tenantId().value(),run.runId());
            return row==null?Optional.empty():Optional.of(restore(row));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "find", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "find", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 从运行绑定或当前团队定义解析主助手。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param owner 由可信入口确定的会话所有者
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly=true)
    public AgentId primaryAgent(SessionOwner owner) {
        owner.requireUser();
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "primaryAgent", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = owner.sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            if(owner.target().kind()==SessionTarget.Kind.AGENT)return owner.target().requireAgent();
            var row=mapper.findLatestPrimary(owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId(),
                    owner.target().kind().name(),owner.target().id(),owner.requireUser().value());
            return row==null?new AgentId(teams.primaryAgentId(owner.sessionRef().tenantId().value(),owner.target().id())):restore(row).actor();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "primaryAgent", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "primaryAgent", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 下一轮按稳定团队身份读取当前发布主助手，既有 Run 仍从 find(run) 读取原身份。 */
    @Transactional(readOnly=true)
    public AgentId currentPrimaryAgent(SessionOwner owner) {
        owner.requireUser();
        if (owner.target().kind() == SessionTarget.Kind.AGENT) return owner.target().requireAgent();
        return new AgentId(teams.primaryAgentId(owner.sessionRef().tenantId().value(), owner.target().id()));
    }

    /** 自动目标续轮复用已持久认证来源，不从模型正文或后台线程补造用户身份。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param owner 由可信入口确定的会话所有者
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly=true)
    public Map<String,Object> requesterContext(SessionOwner owner) {
        owner.requireUser();
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "requesterContext", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = owner.sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            var requester=persistedRequester(owner);
            return requester.channelId().isEmpty()?Map.of("delegation_requester_id",requester.requesterId()):Map.of("delegation_channel_id",requester.channelId());

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "requesterContext", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "requesterContext", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 后台续轮只从精确会话的持久绑定恢复请求者，先复核当前执行权限再解析新配置。 */
    public <T> T withContinuationRequester(SessionOwner owner, Supplier<T> operation) {
        owner.requireUser();
        String tenant = owner.sessionRef().tenantId().value();
        String previous = TenantContext.getTenantId();
        if (previous != null && !previous.equals(tenant))
            throw new SecurityException("database_tenant_scope_mismatch");
        try {
            TenantContext.setTenantId(tenant);
            return withRequester(owner, persistedRequester(owner), null, operation);
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    private Requester persistedRequester(SessionOwner owner) {
        var row=mapper.findLatestPrimary(owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId(),
                owner.target().kind().name(),owner.target().id(),owner.requireUser().value());
        if(row==null)throw new SecurityException("delegation_requester_not_bound");
        var binding=restore(row);
        if(binding.role()!=Role.PRIMARY || !binding.owner().equals(owner))
            throw new SecurityException("delegation_requester_binding_mismatch");
        return binding.scope().requester();
    }

    /** 只读取入口已认证的身份；客户端的同名上下文字段已由 Chat 入口覆盖。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param identity 当前受理边界创建的真实RunIdentity，含精确输入和原root
     * @param input 冻结工作输入
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Binding freezePrimary(com.skillforge.runtime.api.port.RunIdentity identity,TypedPayload input) {
        return freezePrimary(identity, input, null);
    }

    /** 服务入口的主体来自认证边界；不能从客户端 extraParameters 构造服务身份。 */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Binding freezePrimary(com.skillforge.runtime.api.port.RunIdentity identity,TypedPayload input,
            com.skillforge.runtime.api.PrincipalRef.ServicePrincipal servicePrincipal) {
        SessionOwner owner=identity.owner();RunRef run=identity.runRef();AgentId primary=identity.agentId();
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "freezePrimary", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = owner.sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            if(!owner.sessionRef().tenantId().equals(run.tenantId())
                    || owner.target().kind()==SessionTarget.Kind.AGENT&&!owner.target().requireAgent().equals(primary))
                throw new SecurityException("delegation_primary_identity_invalid");
            var existing=find(run);
            if(existing.isPresent()) {
                var value=existing.orElseThrow();
                if(!value.owner().equals(owner)||!value.actor().equals(primary)||value.role()!=Role.PRIMARY)
                    throw new SecurityException("delegation_primary_binding_conflict");
                return value;
            }
            String tenant=run.tenantId().value();
            Requester requester=servicePrincipal == null ? requester(owner,input)
                    : Requester.service(servicePrincipal);
            if(owner.subject() instanceof SessionSubject.Group) requireGroupRequester(owner,identity.requester(),requester,primary.value());
            return withRequester(owner, requester, primary.value(), () -> {
                requireAgent(tenant,primary.value());
                var members=new LinkedHashMap<String,TeamService.FrozenMember>();
                var directory=new LinkedHashMap<String,TeamDefinition.Member>();
                Optional<Studio> studio=Optional.empty();
                if(owner.target().kind()==SessionTarget.Kind.TEAM) {
                    var frozen=teams.freezePublished(tenant,owner.target().id());
                    if(!frozen.team().primaryAgentId().equals(primary.value()))throw new SecurityException("delegation_primary_mismatch");
                    members.putAll(frozen.members());
                    frozen.team().members().forEach(member->directory.put(member.agentId(),member));
                    studio=Optional.of(new Studio(frozen.team().id(),frozen.team().revision()));
                } else {
                    var own=teams.freezeAgent(tenant,primary.value());members.put(primary.value(),own);
                    directory.put(primary.value(),teams.publicProfile(tenant,own));
                    var config=own.delegationConfig();
                    if(config.enabled()) for(String id:config.targetAgentIds()) {
                        try { requireAgent(tenant,id); requireRequester(owner,requester,id); }
                        catch(SecurityException denied) { continue; }
                        var member=teams.freezeAgent(tenant,id);members.put(id,member);
                        directory.put(id,teams.publicProfile(tenant,member));
                    }
                }
                var config=members.get(primary.value()).delegationConfig();
                // 与已解析成员一起冻结部署授权；子任务只继承这里的原依据，不查询当前部署换链。
                var authorizations=new LinkedHashMap<String,DeploymentAuthorization>();
                for(String memberId:members.keySet()) {
                    var deployment=deployments.findAgent(Long.parseLong(tenant),Long.parseLong(memberId));
                    if(deployment!=null) {
                        var chain=deploymentChain(deployment.grantChain());
                        grants.requireFrozenChainForAdmission(chain,Long.parseLong(tenant),deployment.definitionId(),"execute");
                        authorizations.put(memberId,new DeploymentAuthorization(deployment.deploymentId(),
                            deployment.revision(),deployment.grantId(),deployment.grantRevision(),chain));
                    }
                }
                var scope=new Scope(owner,run,primary,config,studio,members,directory,requester,authorizations,identity.requester());
                var binding=new Binding(owner,primary,scope,members.get(primary.value()),Role.PRIMARY,Optional.empty());
                authorize(binding);
                var stored = persist(run,identity.causationInputId().value(),binding);
                var source=scope.authorizations().get(primary.value());
                if(owner.subject() instanceof SessionSubject.Group) return stored;
                provenance.freezeRun(tenant,run.runId(),owner.sessionRef().sessionId(),Long.valueOf(primary.value()),identity.trace().rootInputId().value(),
                        source==null?null:source.deploymentId(),source==null?null:source.deploymentRevision(),
                        source==null?null:source.grantId(),source==null?null:source.grantRevision());
                return stored;
            });

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "freezePrimary", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "freezePrimary", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 已锁定trigger的同一Store事务写原来源，不从群Session创建者补造请求者。 */
    public void freezeGroupSource(org.apache.ibatis.session.SqlSession sql,
            com.skillforge.institution.group.GroupExecutionAdmission.Candidate candidate,
            com.skillforge.runtime.api.port.RunIdentity identity,Binding binding) {
        if(!identity.owner().equals(binding.owner())||!identity.agentId().equals(binding.actor())
                ||!identity.originRef().originId().equals("group-trigger:"+candidate.triggerId()))
            throw new SecurityException("group_run_source_identity_mismatch");
        var selected=binding.scope().authorizations().get(binding.actor().value());
        provenance.freezeGroupRun(sql,candidate.tenantId(),identity.runRef().runId(),
                new com.skillforge.core.foundation.integration.IntegrationProvenanceService.Source("external",
                    "service_principal:"+candidate.principalId(),"end_user:"+candidate.requesterEndUserId(),
                    candidate.integrationId(),candidate.businessScopeId(),candidate.agentId(),candidate.sessionId(),identity.trace().rootInputId().value(),
                    selected==null?null:selected.deploymentId(),selected==null?null:selected.deploymentRevision(),
                    selected==null?null:selected.grantId(),selected==null?null:selected.grantRevision()));
    }

    /**
     * 根据可信委派登记冻结子运行配置。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param entry 已持久化的委派登记事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Binding freezeChild(SessionDelegations.Entry entry) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "freezeChild", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = entry.childRun().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            var parent=find(entry.parent().runRef()).orElseThrow(()->new SecurityException("delegation_parent_missing"));
            if(parent.role()!=Role.PRIMARY||!parent.owner().equals(entry.parent().owner())
                    ||!parent.actor().equals(entry.parent().agentId()))throw new SecurityException("delegation_parent_invalid");
            requireTargetSnapshot(entry,parent);
            authorizeTarget(parent,entry.target().agentId().value());
            var owner=AgentDelegationHost.childOwner(entry);
            var binding=new Binding(owner,entry.target().agentId(),parent.scope(),
                    parent.scope().members().get(entry.target().agentId().value()),Role.DELEGATE,Optional.of(entry.parent().runRef()));
            var stored = persist(entry.childRun(),entry.childInput().value(),binding);
            var source=parent.scope().authorizations().get(entry.target().agentId().value());
            provenance.inheritRun(databaseTenant,entry.childRun().runId(),entry.parent().runRef().runId(),
                    Long.valueOf(entry.target().agentId().value()),source==null?null:source.deploymentId(),
                    source==null?null:source.deploymentRevision(),source==null?null:source.grantId(),source==null?null:source.grantRevision());
            return stored;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "freezeChild", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "freezeChild", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 空目录只披露配置类别，不返回被渠道或工作室隐藏的目标身份。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param binding 持久化运行身份绑定
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly=true)
    public SessionDelegations.EmptyTargetReason emptyTargetReason(Binding binding) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "emptyTargetReason", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = binding.owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            authorize(binding);
            var current = binding.scope().delegation();
            if (!current.enabled()) return SessionDelegations.EmptyTargetReason.DISABLED;
            if (current.targetAgentIds().isEmpty()) return SessionDelegations.EmptyTargetReason.NO_CONFIGURED_TARGETS;
            if (!binding.scope().delegation().enabled() || binding.scope().delegation().targetAgentIds().isEmpty())
                return SessionDelegations.EmptyTargetReason.FROZEN_SCOPE_EMPTY;
            return SessionDelegations.EmptyTargetReason.NO_AUTHORIZED_TARGETS;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "emptyTargetReason", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "emptyTargetReason", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 目标执行权限：冻结名单是上界，当前名单、授权和工作室成员只能进一步收紧。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param parent 父运行身份或绑定事实
     * @param agent 待校验或执行的 Agent 标识
     */
    @Transactional(readOnly=true)
    public void authorizeTarget(Binding parent,String agent) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "authorizeTarget", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = parent.owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            if(parent.role()!=Role.PRIMARY||parent.actor().value().equals(agent)
                    ||!parent.scope().delegation().enabled()||!parent.scope().delegation().targetAgentIds().contains(agent))
                throw new SecurityException("delegation_target_outside_scope");
            requireAgent(parent.owner().sessionRef().tenantId().value(), parent.actor().value());
            requireBindingAccess(parent,agent,"execute");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "authorizeTarget", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "authorizeTarget", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 结果读取不因委派开关关闭而失去收敛通路，仍核对原请求者与当前成员授权。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param binding 持久化运行身份绑定
     * @param agent 待校验或执行的 Agent 标识
     */
    @Transactional(readOnly=true)
    public void authorizeRead(Binding binding,String agent) {
        authorizeRead(binding,agent,binding.scope().requester());
    }

    /** 原来源与当前调用者同时复核；requester 只能来自当前可信 Run 的持久绑定。 */
    @Transactional(readOnly=true)
    public void authorizeRead(Binding binding,String agent,Requester requester) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "authorizeRead", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = binding.owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            requireBindingAccess(binding,agent,"read");
            if (!binding.scope().requester().equals(requester))
                requireRequester(binding.scope().origin(),requester,agent,"read");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "authorizeRead", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "authorizeRead", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 每次模型、能力和工具操作前复核；取消与状态收敛不调用此执行授权。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param binding 持久化运行身份绑定
     */
    @Transactional(readOnly=true)
    public void authorize(Binding binding) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "authorize", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = binding.owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            requireBindingAccess(binding,binding.actor().value(),"execute");
            if(binding.role()==Role.PRIMARY)return;
            var parent=find(binding.parentRunRef().orElseThrow()).orElseThrow(()->new SecurityException("delegation_parent_missing"));
            authorizeTarget(parent,binding.actor().value());
            var fact=executions.getObject().load(binding.parentRunRef().orElseThrow())
                    .orElseThrow(()->new SecurityException("delegation_parent_missing"));
            if (!fact.identity().owner().equals(parent.owner()) || !fact.identity().agentId().equals(parent.actor())
                    || !fact.identity().runRef().equals(binding.parentRunRef().orElseThrow())) throw new SecurityException("delegation_parent_invalid");
            if(fact.cancelled()||terminal(fact.status()))throw new SecurityException("delegation_parent_ended");
            String id=binding.owner().sessionRef().sessionId().substring("delegation:".length());
            var entry=delegations.read(fact.identity(),id).orElseThrow(()->new SecurityException("delegation_missing"));
            if(!entry.childRun().tenantId().equals(binding.owner().sessionRef().tenantId())
                    ||!AgentDelegationHost.childOwner(entry).equals(binding.owner())
                    ||!entry.target().agentId().equals(binding.actor())||entry.cancelKey().isPresent()||SessionDelegations.terminal(entry.state()))
                throw new SecurityException("delegation_cancelled_or_invalid");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "authorize", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "authorize", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    private void requireBindingAccess(Binding binding, String agent, String action) {
        String tenant=binding.scope().origin().sessionRef().tenantId().value();
        if(!binding.scope().members().containsKey(agent))throw new SecurityException("delegation_target_not_frozen");
        requireAgent(tenant,agent);
        requireRequester(binding.scope().origin(),binding.scope().requester(),agent,action);
        if(binding.scope().origin().subject() instanceof SessionSubject.Group)
            requireGroupRequester(binding.scope().origin(),binding.scope().runRequester(),binding.scope().requester(),agent);
        if ("execute".equals(action)) requireFrozenDeploymentAuthorization(binding, agent);
        binding.scope().studio().ifPresent(studio->teams.requireEnabledMember(tenant,studio.id(),studio.revision(),agent));
    }

    /** 后续动作和恢复沿原授权依据复验当前源，不得借当前部署的新授权或新发布替换原链。 */
    private void requireFrozenDeploymentAuthorization(Binding binding, String agent) {
        var member = binding.scope().members().get(agent);
        var frozen = binding.scope().authorizations().get(agent);
        // 原运行的受控模式由冻结授权map决定；Agent的templateId创建溯源不能变成受控执行权。
        if (frozen == null) return;
        if (member.templateId() == null) throw new IllegalStateException("run_deployment_definition_missing");
        try {
            grants.requireFrozenChain(frozen.grantChain(),
                    Long.parseLong(binding.owner().sessionRef().tenantId().value()), member.templateId(),"execute");
        } catch (com.skillforge.core.shared.exception.ApiException rejected) {
            if (!rejected.getStatus().is4xxClientError()) throw rejected;
            throw new com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException("run_deployment_authorization_revoked");
        }
    }

    private GrantChain deploymentChain(String value) {
        if(value==null)throw new IllegalStateException("run_deployment_chain_missing");
        try {return Objects.requireNonNull(json.readValue(value,GrantChain.class));}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("run_deployment_chain_invalid",failure);}
    }

    /**
     * 生成仅包含运行范围与目标的委派快照。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param binding 持久化运行身份绑定
     * @param agent 待校验或执行的 Agent 标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    public TypedPayload targetSnapshot(Binding binding,String agent) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "targetSnapshot", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = binding.owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            return new TypedPayload(TARGET_SNAPSHOT_SCHEMA,write(Map.of("scope_run_id",binding.scope().primaryRun().runId(),"agent_id",agent)));

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "targetSnapshot", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "targetSnapshot", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 拒绝不属于父运行范围的目标快照。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param entry 已持久化的委派登记事实
     * @param parent 父运行身份或绑定事实
     */
    private void requireTargetSnapshot(SessionDelegations.Entry entry,Binding parent) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "requireTargetSnapshot", TenantContext.getTenantId());
        try {
            if(!targetSnapshot(parent,entry.target().agentId().value()).equals(entry.target().snapshot()))
                throw new SecurityException("delegation_target_snapshot_invalid");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "requireTargetSnapshot", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "requireTargetSnapshot", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 解析经过入口认证覆盖的请求者信息。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param owner 由可信入口确定的会话所有者
     * @param payload 内部序列化数据，不进入日志
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Requester requester(SessionOwner owner,TypedPayload payload) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "requester", TenantContext.getTenantId());
        try {
            try {
                var extra=json.readTree(payload.canonicalJson()).path("extraParameters");
                var requester=new Requester(extra.path("delegation_requester_id").asText(""),extra.path("delegation_channel_id").asText(""));
                requireRequester(owner,requester,owner.target().kind()==SessionTarget.Kind.AGENT?owner.target().id():null);
                return requester;
            } catch(java.io.IOException failure) { throw new IllegalArgumentException("delegation_requester_invalid",failure); }

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "requester", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "requester", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 核验请求者访问权及终端用户有效性。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param owner 由可信入口确定的会话所有者
     * @param requester 已认证请求来源
     * @param agent 待校验或执行的 Agent 标识
     */
    private void requireRequester(SessionOwner owner,Requester requester,String agent) {
        requireRequester(owner, requester, agent, "execute");
    }

    private void requireRequester(SessionOwner owner,Requester requester,String agent,String action) {
        if(owner.subject() instanceof SessionSubject.Group) {
            var principal=requester.servicePrincipal().orElseThrow(()->new SecurityException("group_service_identity_required"));
            if(!mapper.groupServiceRequesterAvailable(Long.parseLong(owner.sessionRef().tenantId().value()),principal.principalId(),principal.integrationId())
                    ||!mapper.groupActorAvailable(Long.parseLong(owner.sessionRef().tenantId().value()),owner.target().id(),
                        principal.integrationId(),agent))
                throw new com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException("group_actor_authorization_revoked");
            return;
        }
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "requireRequester", TenantContext.getTenantId());
        try {
            String tenant=owner.sessionRef().tenantId().value();
            provenance.requireCurrentSessionAccess(tenant,owner.sessionRef().sessionId());
            boolean permitted=requester.servicePrincipal().isPresent()
                    ? serviceRequesterAvailable(tenant, requester.servicePrincipal().orElseThrow())
                    : requester.channelId().isEmpty()
                    ?memberAvailable(tenant,requester.requesterId(),owner.sessionRef().sessionId(),agent,action)
                    :access.canChannelAccess(tenant,requester.channelId(),owner.sessionRef().sessionId());
            if(!permitted)throw new com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException("delegation_requester_revoked");
            if(!requester.channelId().isEmpty()&&agent!=null&&!Boolean.TRUE.equals(mapper.channelTargetAvailable(Long.valueOf(tenant), Long.valueOf(requester.channelId()), Long.valueOf(agent))))
                throw new com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException("delegation_channel_target_revoked");
            if(!Boolean.TRUE.equals(mapper.endUserAvailable(Long.valueOf(tenant), owner.sessionRef().sessionId(), owner.requireUser().value())))
                throw new com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException("delegation_end_user_revoked");

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "requireRequester", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "requireRequester", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 当前成员按读取或执行动作核验会话及对象权限，不改写冻结绑定。 */
    private boolean memberAvailable(String tenant, String member, String session, String agent, String action) {
        try {
            var subject = new com.skillforge.core.foundation.access.FoundationAccess.Subject(false, tenant, member);
            foundation.requireSession(subject, session, "read".equals(action) ? "read" : "write");
            return agent == null || foundation.canAccessResource(subject, "agent", agent, action);
        } catch (com.skillforge.core.shared.exception.ApiException denied) { return false; }
    }

    private <T> T withRequester(SessionOwner owner, Requester requester, String agent, Supplier<T> operation) {
        requireRequester(owner, requester, agent);
        String tenant = owner.sessionRef().tenantId().value();
        if (!requester.requesterId().isEmpty()) {
            var subject = new com.skillforge.core.foundation.access.FoundationAccess.Subject(false, tenant, requester.requesterId());
            return foundation.withSubject(subject, operation);
        }
        if (!requester.channelId().isEmpty()) {
            // 后台公共投影复用当前渠道授权入口；身份只来自已复核的持久绑定，不能退回工作线程的成员登录态。
            String previousChannel = com.skillforge.common.context.ChannelContext.getChannelId();
            try {
                com.skillforge.common.context.ChannelContext.setChannelId(requester.channelId());
                return com.skillforge.institution.service.InstitutionResourceAccess.withExternalExecution(tenant, operation);
            } finally {
                if (previousChannel == null) com.skillforge.common.context.ChannelContext.clear();
                else com.skillforge.common.context.ChannelContext.setChannelId(previousChannel);
            }
        }
        return com.skillforge.institution.service.InstitutionResourceAccess.withExternalExecution(tenant, operation);
    }

    private boolean serviceRequesterAvailable(String tenant,
            com.skillforge.runtime.api.PrincipalRef.ServicePrincipal principal) {
        return principal.tenantId().value().equals(tenant) && mapper.serviceRequesterAvailable(
                Long.parseLong(tenant), principal.principalId(), principal.integrationId(), principal.credentialVersion());
    }
    /** 群运行的真实请求者必须仍是原群当前成员；执行身份和实际Agent独立核验。 */
    private void requireGroupRequester(SessionOwner owner,com.skillforge.runtime.api.RunRequester runRequester,
            Requester actor,String agent) {
        if(!(runRequester instanceof com.skillforge.runtime.api.RunRequester.EndUser user))
            throw new SecurityException("group_system_trigger_not_registered");
        var service=actor.servicePrincipal().orElseThrow(()->new SecurityException("group_service_identity_required"));
        if(!mapper.groupRequesterAvailable(Long.parseLong(owner.sessionRef().tenantId().value()),owner.target().id(),
                service.integrationId(),user.endUserId().value(),agent))
            throw new com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException("group_requester_authorization_revoked");
    }

    /**
     * 校验 Agent 的租户归属与当前发布状态。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param tenant 已授权租户标识
     * @param id 当前领域对象标识
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private AgentDO requireAgent(String tenant,String id) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "requireAgent", TenantContext.getTenantId());
        try {
            var agent=agents.selectById(Long.valueOf(id));
            if(agent==null||!Long.valueOf(tenant).equals(agent.getTenantId())||!"published".equals(agent.getStatus()))
                throw new com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException("delegation_agent_unavailable");
            return agent;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "requireAgent", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "requireAgent", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 在同一事务内校验冲突并持久化不可变事实。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param run 带租户的运行引用
     * @param input 绑定工作输入或规范输入标识
     * @param binding 持久化运行身份绑定
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Binding persist(RunRef run,String input,Binding binding) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "persist", TenantContext.getTenantId());
        try {
            var row=new AgentRunBindingsMapper.Row(); row.tenantId=run.tenantId().value();row.runId=run.runId();row.inputId=input;
            row.sessionId=binding.owner().sessionRef().sessionId();row.targetKind=binding.owner().target().kind().name();row.targetId=binding.owner().target().id();
            row.endUserId=binding.owner().subject() instanceof SessionSubject.User user?user.endUserId().value():null;
            row.requesterEndUserId=binding.scope().runRequester() instanceof com.skillforge.runtime.api.RunRequester.EndUser user?user.endUserId().value():null;row.actorAgentId=binding.actor().value();row.role=binding.role().name();
            row.parentRunId=binding.parentRunRef().map(RunRef::runId).orElse(null);row.scope=write(binding.scope());row.frozenMember=write(binding.member());
            mapper.insert(row);var stored=find(run).orElseThrow();
            if(!stored.equals(binding))throw new IllegalStateException("agent_run_binding_conflict");return stored;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "persist", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "persist", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 恢复不可变运行绑定并校验租户一致性。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param row Mapper 返回的领域数据行
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Binding restore(AgentRunBindingsMapper.Row row) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "restore", TenantContext.getTenantId());
        try {
            var tenant=new TenantId(row.tenantId);
            var target=switch(SessionTarget.Kind.valueOf(row.targetKind)) {
                case AGENT -> SessionTarget.agent(new AgentId(row.targetId));
                case TEAM -> SessionTarget.team(row.targetId);
                case GROUP -> SessionTarget.group(new GroupBindingRef(tenant,row.targetId));
            };
            var owner=target.kind()==SessionTarget.Kind.GROUP
                    ?new SessionOwner(new SessionRef(tenant,row.sessionId),SessionSubject.group(new GroupBindingRef(tenant,row.targetId)),target)
                    :SessionOwner.user(new SessionRef(tenant,row.sessionId),target,new EndUserId(row.endUserId));
            var scope=read(row.scope,Scope.class);var member=read(row.frozenMember,TeamService.FrozenMember.class);
            if(!Objects.equals(row.requesterEndUserId,scope.runRequester() instanceof com.skillforge.runtime.api.RunRequester.EndUser user?user.endUserId().value():null))
                throw new IllegalStateException("agent_run_requester_mismatch");
            var parent=Optional.ofNullable(row.parentRunId).map(id->new RunRef(tenant,id));
            var binding=new Binding(owner,new AgentId(row.actorAgentId),scope,member,Role.valueOf(row.role),parent);
            if(!scope.primaryRun().tenantId().equals(tenant))throw new IllegalStateException("agent_run_scope_tenant_invalid");
            return binding;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "restore", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "restore", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
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
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "write", TenantContext.getTenantId());
        try {
    try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("agent_run_snapshot_invalid",e);}
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "write", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "write", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 反序列化内部持久化快照，格式损坏时失败。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param value 待校验或序列化的领域值
     * @param type 目标反序列化类型
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private <T>T read(String value,Class<T> type) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "read", TenantContext.getTenantId());
        try {
    try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("agent_run_snapshot_invalid",e);}
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "read", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "read", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 判断运行是否已经终结。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param status 当前运行状态
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private static boolean terminal(RunStatus status){
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "AgentRunBindings", "terminal", TenantContext.getTenantId());
        try {
    return Set.of(RunStatus.COMPLETED,RunStatus.FAILED,RunStatus.TERMINATED,RunStatus.CANCELLED).contains(status);
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "AgentRunBindings", "terminal", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "AgentRunBindings", "terminal", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    public enum Role { PRIMARY,DELEGATE }
    public record Studio(String id,long revision) {public Studio{if(id==null||id.isBlank()||revision<1)throw new IllegalArgumentException("studio_scope_invalid");}}
    public record Requester(String requesterId,String channelId,
            Optional<com.skillforge.runtime.api.PrincipalRef.ServicePrincipal> servicePrincipal) {
        public Requester {
            Objects.requireNonNull(requesterId);Objects.requireNonNull(channelId);Objects.requireNonNull(servicePrincipal);
            int sources=(requesterId.isEmpty()?0:1)+(channelId.isEmpty()?0:1)+(servicePrincipal.isPresent()?1:0);
            if(sources!=1)throw new IllegalArgumentException("delegation_requester_required");
        }
        public Requester(String requesterId, String channelId) { this(requesterId, channelId, Optional.empty()); }
        public static Requester service(com.skillforge.runtime.api.PrincipalRef.ServicePrincipal principal) {
            return new Requester("", "", Optional.of(principal));
        }
    }
    public record Scope(SessionOwner origin,RunRef primaryRun,AgentId primaryAgent,AgentDelegationConfig delegation,
            Optional<Studio> studio,Map<String,TeamService.FrozenMember> members,Map<String,TeamDefinition.Member> directory,Requester requester,Map<String,DeploymentAuthorization> authorizations,com.skillforge.runtime.api.RunRequester runRequester) {
        public Scope {Objects.requireNonNull(origin);Objects.requireNonNull(primaryRun);Objects.requireNonNull(primaryAgent);Objects.requireNonNull(delegation);
            Objects.requireNonNull(runRequester);
            Objects.requireNonNull(studio);members=Map.copyOf(members);directory=Map.copyOf(directory);Objects.requireNonNull(requester);authorizations=Map.copyOf(authorizations);
            if(!members.keySet().equals(directory.keySet())||!members.containsKey(primaryAgent.value())
                    ||!origin.sessionRef().tenantId().equals(primaryRun.tenantId())
                    ||(origin.target().kind()==SessionTarget.Kind.TEAM)!=studio.isPresent()
                    ||studio.isPresent()&&!origin.target().id().equals(studio.orElseThrow().id()))throw new IllegalArgumentException("agent_scope_invalid");}
    }
    /** 新 Run 受理时选定的精确机构部署/授权修订，子任务沿父范围继承。 */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeploymentAuthorization(long deploymentId,long deploymentRevision,long grantId,long grantRevision,GrantChain grantChain) {
        public DeploymentAuthorization {
            Objects.requireNonNull(grantChain,"run_deployment_chain_missing");
            if(grantChain.hops().isEmpty())throw new IllegalArgumentException("run_deployment_chain_empty");
            var leaf=grantChain.hops().getLast();
            if(leaf.grantId()!=grantId||leaf.revision()!=grantRevision)throw new IllegalArgumentException("run_deployment_chain_leaf_mismatch");
        }
    }
    public record Binding(SessionOwner owner,AgentId actor,Scope scope,TeamService.FrozenMember member,Role role,Optional<RunRef> parentRunRef) {
        public Binding {Objects.requireNonNull(owner);Objects.requireNonNull(actor);Objects.requireNonNull(scope);Objects.requireNonNull(member);Objects.requireNonNull(role);Objects.requireNonNull(parentRunRef);
            if(!member.agentId().equals(actor.value())||!member.equals(scope.members().get(actor.value()))
                    ||!owner.subject().equals(scope.origin().subject())||!owner.sessionRef().tenantId().equals(scope.primaryRun().tenantId())
                    ||owner.target().kind()==SessionTarget.Kind.AGENT&&!owner.target().requireAgent().equals(actor)
                    ||role==Role.PRIMARY&&(!owner.equals(scope.origin())||!actor.equals(scope.primaryAgent())||parentRunRef.isPresent())
                    ||role==Role.DELEGATE&&(!parentRunRef.equals(Optional.of(scope.primaryRun()))||actor.equals(scope.primaryAgent())||!owner.sessionRef().sessionId().startsWith("delegation:")))
                throw new IllegalArgumentException("agent_run_binding_invalid");}
    }
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,property="type")
    @com.fasterxml.jackson.annotation.JsonSubTypes({
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=com.skillforge.core.capability.CapabilityMount.SkillMountConfig.class,name="SKILL"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=com.skillforge.core.capability.CapabilityMount.ToolMountConfig.class,name="TOOL"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=com.skillforge.core.capability.CapabilityMount.WorkflowMountConfig.class,name="WORKFLOW"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig.class,name="KNOWLEDGE_SOURCE")})
    private abstract static class MountConfigJson { }
}
