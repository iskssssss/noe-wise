package com.skillforge.institution.service;

import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityDeploymentView;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentExecutionPublicationResolver;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.deployment.AgentTemplateGrantService;
import com.skillforge.core.agent.deployment.AgentBindingDeclarations;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService;
import com.skillforge.institution.common.TenantIds;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.skillforge.core.agent.deployment.AgentDeploymentContracts.*;

/** 公共定义在机构中的独立部署；解析为纯读取，新Run冻结后由原Run绑定恢复。 */
@Service
public class AgentDeploymentService implements AgentExecutionPublicationResolver {
    private static final Logger LOG=LoggerFactory.getLogger(AgentDeploymentService.class);
    private static final Pattern PARAMETER=AgentBindingDeclarations.PARAMETER;
    private final AgentDeploymentMapper mapper;
    private final AgentTemplateGrantService grants;
    private final AgentTemplateService templates;
    private final AgentService agents;
    private final AgentPublicationService publications;
    private final com.skillforge.core.agent.management.AgentConfigurationPolicy configurations;
    private final CapabilityMountManagementService mounts;
    private final CapabilityMountPersistenceService persistence;
    private final CapabilityCatalogService catalog;
    private final CapabilityCatalogDatabaseContext database;
    private final TenantToolDeploymentProviderValidator providers;
    private final ObjectMapper json;
    private final PlatformManagementService platform;
    private final InstitutionIntegrationService integrations;
    private final OpenPlatformAccessService externalAccess;
    private final FoundationAccess foundation;
    private final com.skillforge.core.foundation.catalog.entitlements.DirectDeploymentScope directScope;
    private final InstitutionResourceAccess access;
    private final com.skillforge.core.foundation.access.FoundationOrganizationService organization;
    private final com.skillforge.core.foundation.catalog.entitlements.ResourceQuota quota;
    private final com.skillforge.core.foundation.catalog.entitlements.FeatureGate features;
    private final InstitutionDeploymentManagementService targetManagement;

    /** 延迟注入的执行扩展由PublicationService的ObjectProvider获取，避免发布与部署循环构造。 */
    public AgentDeploymentService(AgentDeploymentMapper mapper,AgentTemplateGrantService grants,
            AgentTemplateService templates,AgentService agents,AgentPublicationService publications,
            com.skillforge.core.agent.management.AgentConfigurationPolicy configurations,CapabilityMountManagementService mounts,
            CapabilityMountPersistenceService persistence,CapabilityCatalogService catalog,
            CapabilityCatalogDatabaseContext database,TenantToolDeploymentProviderValidator providers,ObjectMapper json,
            PlatformManagementService platform,InstitutionIntegrationService integrations,
            OpenPlatformAccessService externalAccess,FoundationAccess foundation,InstitutionResourceAccess access,
            com.skillforge.core.foundation.catalog.entitlements.DirectDeploymentScope directScope,
            com.skillforge.core.foundation.access.FoundationOrganizationService organization,
            com.skillforge.core.foundation.catalog.entitlements.ResourceQuota quota,
            com.skillforge.core.foundation.catalog.entitlements.FeatureGate features,
            InstitutionDeploymentManagementService targetManagement) {
        this.targetManagement=targetManagement;
        this.access=access; this.organization=organization; this.quota=quota; this.features=features;
        this.mapper=mapper;this.grants=grants;this.templates=templates;this.agents=agents;
        this.publications=publications;this.configurations=configurations;this.mounts=mounts;
        this.persistence=persistence;this.catalog=catalog;this.database=database;this.providers=providers;
        this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.platform=platform;this.integrations=integrations;this.externalAccess=externalAccess;this.foundation=foundation;
        this.directScope=directScope;
    }

    /** 机构JWT和固定SP分别核验当前动作及执行域，写入始终只落本机构。 */
    @Transactional(rollbackFor=Exception.class)
    public View deploy(Deploy command,String requestKey) {
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        if(command.tenantId()!=null&&command.tenantId()!=tenant)
            return targetManagement.withTarget(command.tenantId().toString(),"tenant.agent_deployments.create",
                    ()->create(command.tenantId(),command,requestKey,false));
        requireInstitution("create",tenant,command.integrationId());
        return create(tenant,command,requestKey,false);
    }

    /** 平台明确代部署仍复用相同事务及实例机制，不获得任意聊天执行权。 */
    @Transactional(rollbackFor=Exception.class)
    public View deploy(long tenant,Deploy command,String requestKey) {
        requireExplicitTarget(tenant,command.tenantId());
        platform.requireTenant("platform.agent_deployments.create",tenant);
        platform.requireDefinition("platform.agent_deployments.create",command.definitionId());
        return create(tenant,command,requestKey,true);
    }

    /** 只读核对原部署命令，不复用会创建实例的部署入口。 */
    @Transactional(readOnly=true)
    public Receipt<View> deployReceipt(long tenant,Deploy command,String requestKey) {
        requireExplicitTarget(tenant,command.tenantId());
        platform.requireTenant("platform.agent_deployments.read",tenant);
        platform.requireDefinition("platform.agent_deployments.read",command.definitionId());
        return creationReceipt(tenant,command,requestKey,true);
    }

    /** 机构人员或固定执行身份只读核对原操作，跨机构须具备目标读取许可。 */
    @Transactional(rollbackFor=Exception.class)
    public Receipt<View> deployReceipt(Deploy command,String requestKey) {
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        if(command.tenantId()!=null && command.tenantId()!=tenant)
            return targetManagement.withTarget(command.tenantId().toString(),"tenant.agent_deployments.read",
                    ()->creationReceipt(command.tenantId(),command,requestKey,false));
        requireInstitution("read",tenant,command.integrationId());
        return creationReceipt(tenant,command,requestKey,false);
    }

    private Receipt<View> creationReceipt(long tenant,Deploy command,String requestKey,boolean platformActor) {
        AgentTemplateGrantService.requireKey(requestKey);
        Deploy requested=deploymentIdentity(tenant,command,requestKey,platformActor);
        return inTenant(tenant,()->{
            var row=mapper.findExternal(requested.integrationId(),tenant,requested.externalAgentRef());
            if(row==null)return new Receipt<>("NOT_FOUND",null);
            var response=mapper.findCreateResponse(row.deploymentId(),tenant,actorRef(platformActor),requestKey);
            if(response==null)return new Receipt<>("NOT_FOUND",null);
            if(!row.fingerprint().equals(fingerprint(requested)))throw conflict("外部Agent标识已用于不同部署参数");
            return new Receipt<>("FOUND",readResponse(response));
        });
    }

    /** 映射、实例、绑定修订和发布原子提交，授权读锁与撤销行更新串行。 */
    private View create(long tenant,Deploy command,String requestKey,boolean platformActor) {
        AgentTemplateGrantService.requireKey(requestKey);
        Deploy requested=deploymentIdentity(tenant,command,requestKey,platformActor);
        if(requested.externalAgentRef()==null||requested.externalAgentRef().isBlank()||requested.externalAgentRef().length()>128
                ||requested.externalAgentRef().chars().anyMatch(Character::isISOControl))throw invalid("外部Agent标识格式非法");
        java.util.function.Supplier<View> work=()->{
            // 平台入口不携带执行Tenant；先由受控目标范围绑定线程/数据库，再核验固定integration。
            if(requested.integrationId()!=null)integrations.requireIntegration(Long.toString(tenant),requested.integrationId());
            String fingerprint=fingerprint(requested);
            mapper.lockExternal(requested.integrationId(),tenant,requested.externalAgentRef());
            var previous=mapper.findExternal(requested.integrationId(),tenant,requested.externalAgentRef());
            if(previous!=null) {
                if(!previous.fingerprint().equals(fingerprint))throw conflict("外部Agent标识已用于不同部署参数");
                var response=readResponse(mapper.findRevisionResponse(previous.deploymentId(),1));
                mapper.recordCreateRequest(previous.deploymentId(),tenant,actorRef(platformActor),requestKey);
                return response;
            }
            var chain=grants.requireChainForAdmission(requested.grantId(),tenant,requested.definitionId(),"deploy");
            grants.requireFrozenChainForAdmission(chain,tenant,requested.definitionId(),"execute");
            var grant=chain.hops().getLast();
            if(platformActor||ServicePrincipalContext.get()!=null||InstitutionDeploymentManagementService.currentTarget()!=null) {
                features.requireDirectDeployment(Long.toString(tenant));
                quota.requireDirectAgentCapacity(Long.toString(tenant),1);
            } else {
                features.require(Long.toString(tenant),"agents");
                quota.requireCapacity(Long.toString(tenant),com.skillforge.core.foundation.catalog.entitlements.ResourceKind.AGENT,1);
            }
            var template=published(requested.definitionId());
            var local=new LocalBindings(requested.resourceBindings(),requested.delegationBindings(),requested.parameters());
            validateSyntax(template.getDefinition(),local);
            long agentId=SnowflakeIds.nextId();
            validateResources(tenant,agentId,local,1L);
            var agent=new AgentDO();
            agent.setId(agentId);agent.setTenantId(tenant);agent.setAgentKey("managed-agent-"+agentId);
            agent.setTemplateId(template.getId());agent.setTemplateVersion(template.getVersion());
            agent.setName(requested.name()==null||requested.name().isBlank()?template.getName():requested.name().trim());
            agent.setIconUrl(template.getIconUrl());applyDefinition(agent,template.getDefinition(),local);
            agent.setStatus("draft");agent.setCreatedAt(Instant.now());agent.setUpdatedAt(agent.getCreatedAt());
            agent.setDelegationConfig(delegation(agent,local));
            configurations.requireReady(readyConfiguration(agent));
            if(!agents.save(agent))throw new IllegalStateException("机构实例写入失败");
            if(!platformActor&&ServicePrincipalContext.get()==null&&InstitutionDeploymentManagementService.currentTarget()==null)
                organization.registerResource("agent",Long.toString(agentId));
            persistence.ensure(tenant,agentId);
            ensurePlatformTools(tenant,agentId,template.getDefinition());
            publications.publish(agent);
            var row=new AgentDeploymentMapper.Row(SnowflakeIds.nextId(),requested.integrationId(),tenant,agentId,template.getId(),
                    grant.grantId(),grant.revision(),requested.externalAgentRef(),fingerprint,write(local),1L,agent.getCreatedAt(),write(chain));
            if(mapper.insert(row)!=1)throw new IllegalStateException("部署回执写入失败");
            if(mapper.appendRevision(row.deploymentId(),actorRef(platformActor),requestKey)!=1)throw new IllegalStateException("部署修订写入失败");
            templates.recordInstallation(template.getId());
            LOG.info("机构直管部署完成 tenantId={} deploymentId={} agentId={}",tenant,row.deploymentId(),agentId);
            var response=saveResponse(row);
            mapper.recordCreateRequest(row.deploymentId(),tenant,actorRef(platformActor),requestKey);
            return response;
        };
        return platformActor?directScope.inTenant(Long.toString(tenant),work):inTenant(tenant,work);
    }

    /** 本机构只替换明确实例绑定，外部SP必须匹配原integration。 */
    @Transactional(rollbackFor=Exception.class)
    public View bind(Bindings command,String requestKey) {
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        if(command.tenantId()!=null&&command.tenantId()!=tenant)
            return targetManagement.withTarget(command.tenantId().toString(),"tenant.agent_deployments.bind",
                    ()->inTenant(command.tenantId(),()->saveBindings(command.tenantId(),
                            requireDeployment(command.tenantId(),command.deploymentId()),command,requestKey,false)));
        return inTenant(tenant,()->{
            var row=requireDeployment(tenant,command.deploymentId());
            requireInstitution("bind",tenant,row.integrationId());
            if(ServicePrincipalContext.get()==null)access.require("agent",Long.toString(row.agentId()),"manage");
            return saveBindings(tenant,row,command,requestKey,false);
        });
    }

    /** 平台目标动作不隐含源维护权或任意业务执行权限。 */
    @Transactional(rollbackFor=Exception.class)
    public View bind(long tenant,Bindings command,String requestKey) {
        requireExplicitTarget(tenant,command.tenantId());
        platform.requireTenant("platform.agent_deployments.bind",tenant);
        return inTenant(tenant,()->saveBindings(tenant,requireDeployment(tenant,command.deploymentId()),command,requestKey,true));
    }

    /** 实例行锁、授权读锁和CAS保护绑定替换，不重选授权，也不重启旧Run。 */
    private View saveBindings(long tenant,AgentDeploymentMapper.Row row,Bindings command,String requestKey,boolean platformActor) {
        AgentTemplateGrantService.requireKey(requestKey);
        var current=lockedDeployment(tenant,row.deploymentId());
        String actor=actorRef(platformActor);
        String fingerprint=HexFormat.of().formatHex(digest("bindings:"+write(command)));
        var replay=mapper.findRevisionRequest(row.deploymentId(),actor,requestKey);
        if(replay!=null) {
            if(!fingerprint.equals(replay.fingerprint()))throw conflict("绑定幂等键已用于不同参数");
            return readResponse(replay.response());
        }
        if(current.revision()!=command.expectedRevision())throw conflict("部署绑定版本已变化，请重新加载");
        grants.requireFrozenChainForAdmission(readChain(current.grantChain()),tenant,current.definitionId(),"deploy");
        var agent=agents.lockVersion(tenant,current.agentId());
        if("archived".equals(agent.getStatus()))throw conflict("已归档实例不能修改绑定");
        var template=published(row.definitionId());
        var local=new LocalBindings(command.resourceBindings(),command.delegationBindings(),command.parameters());
        validateSyntax(template.getDefinition(),local);
        validateResources(tenant,agent.getId(),local,Math.addExact(current.revision(),1));
        agent.setDelegationConfig(delegation(agent,local));applyDefinition(agent,template.getDefinition(),local);agent.setTemplateVersion(template.getVersion());
        configurations.requireReady(readyConfiguration(agent));ensurePlatformTools(tenant,agent.getId(),template.getDefinition());
        if(mapper.updateBindings(tenant,agent.getId(),current.revision(),write(local))!=1)throw conflict("部署绑定版本已变化");
        if(mapper.appendBindingRevision(row.deploymentId(),actor,requestKey,fingerprint)!=1)throw new IllegalStateException("部署修订写入失败");
        String lifecycle=agent.getStatus();publications.publish(agent);
        if(!"published".equals(lifecycle)&&!agents.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentDO>()
                .eq(AgentDO::getTenantId,tenant).eq(AgentDO::getId,agent.getId()).set(AgentDO::getStatus,lifecycle)))throw new IllegalStateException("实例状态保存失败");
        return saveResponse(requireDeployment(tenant,row.deploymentId()));
    }

    /** 同源换授只修改当前部署依据；原运行的授权链和事实不被重写。 */
    @Transactional(rollbackFor=Exception.class)
    public View switchAuthorization(SwitchAuthorization command,String requestKey) {
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        if(command.tenantId()!=null&&command.tenantId()!=tenant)
            return targetManagement.withTarget(command.tenantId().toString(),"tenant.agent_deployments.switch_authorization",
                    ()->inTenant(command.tenantId(),()->saveAuthorization(command.tenantId(),command,requestKey,false)));
        return inTenant(tenant,()->{
            var row=requireDeployment(tenant,command.deploymentId());
            requireInstitution("switch_authorization",tenant,row.integrationId());
            if(ServicePrincipalContext.get()==null)access.require("agent",Long.toString(row.agentId()),"manage");
            return saveAuthorization(tenant,command,requestKey,false);
        });
    }

    @Transactional(rollbackFor=Exception.class)
    public View switchAuthorization(long tenant,SwitchAuthorization command,String requestKey) {
        requireExplicitTarget(tenant,command.tenantId());
        platform.requireTenant("platform.agent_deployments.switch_authorization",tenant);
        return inTenant(tenant,()->saveAuthorization(tenant,command,requestKey,true));
    }

    private View saveAuthorization(long tenant,SwitchAuthorization command,String requestKey,boolean platformActor) {
        AgentTemplateGrantService.requireKey(requestKey);
        var current=lockedDeployment(tenant,command.deploymentId());
        String actor=actorRef(platformActor),fingerprint=HexFormat.of().formatHex(digest("authorization:"+write(command)));
        var replay=mapper.findRevisionRequest(current.deploymentId(),actor,requestKey);
        if(replay!=null) {
            if(!fingerprint.equals(replay.fingerprint()))throw conflict("换授幂等键已用于不同命令或参数");
            return readResponse(replay.response());
        }
        if(current.revision()!=command.expectedRevision())throw conflict("部署版本已变化，请重新加载");
        // 故意不复验旧链有效性：撤权后的实例必须仍可显式修复。
        var original=readChain(current.grantChain());
        var selected=grants.requireChainForAdmission(command.grantId(),tenant,current.definitionId(),"deploy");
        if(!Objects.equals(original.definitionId(),selected.definitionId())||!Objects.equals(original.ownerKind(),selected.ownerKind())
                ||!Objects.equals(original.ownerTenantId(),selected.ownerTenantId()))throw invalid("只能切换同一来源定义的授权");
        var agent=agents.lockVersion(tenant,current.agentId());
        if("archived".equals(agent.getStatus()))throw conflict("已归档实例不能切换授权");
        var template=published(current.definitionId());var local=read(current.bindings());
        validateSyntax(template.getDefinition(),local);validateResources(tenant,current.agentId(),local,Math.addExact(current.revision(),1));
        delegation(agent,local);
        var leaf=selected.hops().getLast();
        if(mapper.switchAuthorization(tenant,current.deploymentId(),current.revision(),leaf.grantId(),leaf.revision(),write(selected))!=1)
            throw conflict("部署版本已变化");
        if(mapper.appendAuthorizationRevision(current.deploymentId(),actor,requestKey,fingerprint)!=1)
            throw new IllegalStateException("部署换授修订写入失败");
        return saveResponse(requireDeployment(tenant,current.deploymentId()));
    }

    /** 当前机构目录始终复验动作和外部integration，不因空应用放行。 */
    @Transactional(readOnly=true)
    public Listing list() {
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        String integration=ServicePrincipalContext.get()==null?null:ServicePrincipalContext.get().integrationId();
        requireInstitution("read",tenant,integration);
        return listing(tenant,integration,false);
    }
    /** 平台列表仅读取明确授权目标。 */
    @Transactional(readOnly=true)
    public Listing list(long tenant) { platform.requireTenant("platform.agent_deployments.read",tenant);return listing(tenant,null,true); }
    @Transactional(rollbackFor=Exception.class)
    public Listing listTarget(long tenant) {
        if(Long.toString(tenant).equals(TenantIds.requireTenantId()))return list();
        return targetManagement.withTarget(Long.toString(tenant),"tenant.agent_deployments.read",()->listing(tenant,null,true));
    }
    private Listing listing(long tenant,String integration,boolean platformActor) {
        var items=inTenant(tenant,()->mapper.list(tenant,integration).stream().filter(row->platformActor||ServicePrincipalContext.get()!=null
                ||access.allowed("agent",Long.toString(row.agentId()),"read")).map(this::view).toList());
        return new Listing(items.size(),items);
    }
    /** 当前机构详情以部署id定位，不能混用其他机构或integration的实例。 */
    @Transactional(readOnly=true)
    public View detail(long deploymentId) {
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        return inTenant(tenant,()->{
            var row=requireDeployment(tenant,deploymentId);requireInstitution("read",tenant,row.integrationId());
            if(ServicePrincipalContext.get()==null)access.require("agent",Long.toString(row.agentId()),"read");
            return view(row);
        });
    }
    @Transactional(readOnly=true)
    public View detail(long tenant,long deploymentId) {
        platform.requireTenant("platform.agent_deployments.read",tenant);
        return inTenant(tenant,()->view(requireDeployment(tenant,deploymentId)));
    }
    @Transactional(rollbackFor=Exception.class)
    public View detailTarget(long tenant,long deploymentId) {
        if(Long.toString(tenant).equals(TenantIds.requireTenantId()))return detail(deploymentId);
        return targetManagement.withTarget(Long.toString(tenant),"tenant.agent_deployments.read",
                ()->inTenant(tenant,()->view(requireDeployment(tenant,deploymentId))));
    }
    @Transactional(rollbackFor=Exception.class)
    public com.skillforge.core.agent.dto.response.AgentTemplateListResponse definitionsTarget(long tenant) {
        return directoryTarget(tenant,()->grants.available(tenant));
    }
    @Transactional(rollbackFor=Exception.class)
    public Grants grantsTarget(long tenant) {return directoryTarget(tenant,()->grants.targetGrants(tenant));}
    private <T>T directoryTarget(long tenant,java.util.function.Supplier<T> work) {
        if(Long.toString(tenant).equals(TenantIds.requireTenantId())) {requireDirectoryAccess();return work.get();}
        return targetManagement.withTarget(Long.toString(tenant),"tenant.agent_deployments.read",work);
    }
    /** 授权目录仍由入口的当前动作/域授权限定，不借读权限签发执行身份。 */
    public void requireDirectoryAccess() {
        long tenant=Long.parseLong(TenantIds.requireTenantId());
        requireInstitution("read",tenant,ServicePrincipalContext.get()==null?null:ServicePrincipalContext.get().integrationId());
    }
    private void requireInstitution(String action,long tenant,String integration) {
        var principal=ServicePrincipalContext.get();
        if(principal!=null) {
            externalAccess.require("read".equals(action)?"resources.read":"resources.write");
            if(!principal.tenantId().equals(Long.toString(tenant))||!Objects.equals(principal.integrationId(),integration))throw new ApiException(HttpStatus.FORBIDDEN,"执行域不匹配");
            integrations.requireIntegration(principal.tenantId(),principal.integrationId());
        } else {
            var subject=foundation.currentSubject();
            if(subject.platform()||!Long.toString(tenant).equals(subject.tenantId()))throw new ApiException(HttpStatus.FORBIDDEN,"机构主体不匹配");
            foundation.require("tenant.agent_deployments."+action);
        }
    }
    private String actorRef(boolean platformActor) {
        if(platformActor)return platform.actorRef();
        var principal=ServicePrincipalContext.get();
        return principal==null?"tenant:"+foundation.currentSubject().tenantId()+":member:"+foundation.currentSubject().memberId():"service:"+principal.tenantId()+":"+principal.principalId();
    }
    private AgentDeploymentMapper.Row requireDeployment(long tenant,long id) {
        var row=mapper.findDeployment(tenant,id);if(row==null)throw new ApiException(HttpStatus.NOT_FOUND,"机构部署不存在");return row;
    }
    private AgentDeploymentMapper.Row lockedDeployment(long tenant,long id) {
        var row=mapper.lockDeployment(tenant,id);if(row==null)throw new ApiException(HttpStatus.NOT_FOUND,"机构部署不存在");return row;
    }

    /** 统一发布入口在Chat、Team和委派冻结时调用；缺槽位拒绝新Run，不读取旧机构快照回退。 */
    @Override
    @Transactional(readOnly=true)
    public Optional<AgentPublicationService.Publication> resolve(long tenantId,long agentId) {
        LOG.debug("公共Agent执行配置解析进入 tenantId={} agentId={}",tenantId,agentId);
        return inTenant(tenantId,()->{
            var row=mapper.findAgent(tenantId,agentId);
            if(row==null)return Optional.empty();
            grants.requireFrozenChain(readChain(row.grantChain()),tenantId,row.definitionId(),"execute");
            var evaluated=evaluate(row);
            if(!evaluated.issues().isEmpty())throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "AGENT_DEPLOYMENT_NOT_READY","公共Agent部署未就绪："+evaluated.issues().getFirst().message(),false);
            var agent=requireAgent(tenantId,agentId);
            if(!"published".equals(agent.getStatus())||agent.getPublishedRevisionId()==null)
                throw new ApiException(HttpStatus.NOT_FOUND,"Agent未发布或已停用");
            applyDefinition(agent,evaluated.template().getDefinition(),evaluated.local());
            agent.setTemplateVersion(evaluated.template().getVersion());
            agent.setDelegationConfig(delegation(agent,evaluated.local()));
            var mounted=new CapabilityMountSetResult(tenantId,agentId,row.revision(),evaluated.mounts());
            var result=new AgentPublicationService.Publication(agent.getPublishedRevisionId(),agent.getRevision(),agent,
                    mounted,evaluated.template().getPublishedAt(),evaluated.template().getVersion(),evaluated.template().getId());
            LOG.debug("公共Agent执行配置解析退出 tenantId={} agentId={} version={}",tenantId,agentId,evaluated.template().getVersion());
            return Optional.of(result);
        });
    }

    /** 仅新Run准备当前公共工具；与冻结共享事务，绑定和平台工具创建使用同一实例锁。 */
    @Override
    @Transactional(rollbackFor=Exception.class)
    public void prepareExecution(long tenantId,long agentId) {
        LOG.debug("公共Agent新Run准备进入 tenantId={} agentId={}",tenantId,agentId);
        inTenant(tenantId,()->{
            var row=mapper.lockAgent(tenantId,agentId);
            if(row!=null) {
                grants.requireFrozenChainForAdmission(readChain(row.grantChain()),tenantId,row.definitionId(),"execute");
                agents.lockVersion(tenantId,agentId);
                ensurePlatformTools(tenantId,agentId,published(row.definitionId()).getDefinition());
            }
            return null;
        });
        LOG.debug("公共Agent新Run准备退出 tenantId={} agentId={}",tenantId,agentId);
    }

    /** 管理投影可以返回未就绪，不把权限失败误报为可运行。 */
    private View view(AgentDeploymentMapper.Row row) {
        var evaluated=evaluate(row);var template=evaluated.template();var local=evaluated.local();
        return new View(row.deploymentId(),row.tenantId(),row.integrationId(),row.agentId(),row.externalAgentRef(),row.definitionId(),row.grantId(),row.grantRevision(),
                template==null?null:template.getName(),template==null?null:template.getVersion(),"FOLLOW_PUBLISHED",row.revision(),
                evaluated.issues().isEmpty(),evaluated.issues(),template==null?List.of():template.getDefinition().resourceSlots(),
                template==null?List.of():template.getDefinition().delegationSlots(),local.resourceBindings(),local.delegationBindings(),local.parameters(),readChain(row.grantChain()));
    }

    /** 只聚合已知业务未就绪原因；数据库、解码等技术失败继续传播。 */
    private Evaluated evaluate(AgentDeploymentMapper.Row row) {
        var local=read(row.bindings());List<Issue> issues=new ArrayList<>();List<CapabilityMount> resolved=new ArrayList<>();
        AgentTemplateDO template=null;
        try {grants.requireFrozenChain(readChain(row.grantChain()),row.tenantId(),row.definitionId(),"execute");}
        catch(ApiException rejected) {issues.add(new Issue("grant_id","authorization_unavailable","平台定义授权已失效"));}
        try {template=published(row.definitionId());}
        catch(ApiException rejected) {issues.add(new Issue("definition_id","definition_unavailable","公共Agent定义已下架或未发布"));}
        var agent=requireAgent(row.tenantId(),row.agentId());
        if(!"published".equals(agent.getStatus()))issues.add(new Issue("agent_id","agent_disabled","机构Agent实例已停用"));
        if(template==null)return new Evaluated(null,local,List.copyOf(issues),List.of());
        var definition=template.getDefinition();
        try {validateSyntax(definition,local);}
        catch(ApiException rejected) {issues.add(new Issue("bindings","binding_definition_changed","本地绑定与当前公共定义不匹配，请重新保存"));}
        issues.addAll(AgentBindingDeclarations.missingRequirements(definition,AgentBindingDeclarations.BindingShape.from(local)));
        // 就绪检查消费本次公共发布定义；旧实例行只承载身份和生命周期，不能用旧模型或预算阻断跟随。
        var executionAgent=new AgentDO();
        org.springframework.beans.BeanUtils.copyProperties(agent,executionAgent);
        applyDefinition(executionAgent,definition,local);
        try {configurations.requireReady(readyConfiguration(executionAgent));delegation(executionAgent,local);}
        catch(ApiException rejected) {issues.add(new Issue("agent_id","execution_dependency_unavailable","模型、预算或委派目标当前不可用"));}
        AgentTemplateDefinition current;
        try {templates.requireInstallable(template);current=templates.resolveCurrentDefinition(definition);}
        catch(ApiException rejected) {issues.add(new Issue("definition_id","definition_dependency_unavailable","公共定义的模型或基础能力不可用"));
            return new Evaluated(template,local,List.copyOf(issues),List.of());}
        for(var base:current.capabilities()) {
            try {resolved.add(baseMount(row,base));}
            catch(ApiException rejected) {issues.add(new Issue("capabilities."+base.capabilityDefinitionId(),"base_tool_unavailable","公共工具部署不可用，请重新保存部署绑定"));}
        }
        for(var binding:local.resourceBindings()) {
            try {resolved.add(mounts.resolveDeploymentBinding(row.tenantId(),row.agentId(),mountId(row.agentId(),"slot:"+binding.slotKey()),row.revision(),binding.mount()));}
            catch(ApiException rejected) {issues.add(new Issue("resource_bindings."+binding.slotKey(),"resource_unavailable","绑定资源或连接当前不可用"));}
        }
        if(resolved.stream().map(m->m.capability().capabilityId()).distinct().count()!=resolved.size())
            issues.add(new Issue("resource_bindings","duplicate_capability","基础能力与本地槽位不能重复挂载同一能力"));
        return new Evaluated(template,local,List.copyOf(issues),List.copyOf(resolved));
    }

    /** 公共Skill仅引用发布版本；公共Tool使用本机构独立生产部署。 */
    private CapabilityMount baseMount(AgentDeploymentMapper.Row row,AgentTemplateDefinition.BaseCapability base) {
        CapabilityMount.MountConfig config;
        if(base.capabilityType()==CapabilityType.SKILL)
            config=new CapabilityMount.SkillMountConfig(base.skillConfig().activationMode(),base.skillConfig().tokenBudget());
        else {
            var deployment=catalog.getDeployment(CapabilityCatalogScope.tenant(row.tenantId()),toolKey(row.agentId(),base));
            if(!"ACTIVE".equals(deployment.status())||deployment.providerType()!=ToolDeployment.ProviderType.PLATFORM
                    ||!base.platformToolName().equals(deployment.providerRef()))throw invalid("公共工具连接不可用");
            providers.validate(row.tenantId(),ToolDeployment.ProviderType.PLATFORM,base.platformToolName(),ToolDeployment.Environment.PRODUCTION);
            config=new CapabilityMount.ToolMountConfig(deployment.deploymentKey(),deployment.revision());
        }
        return new CapabilityMount(mountId(row.agentId(),"base:"+base.capabilityDefinitionId()),row.tenantId(),row.agentId(),
                new CapabilityRef(base.capabilityType(),base.capabilityDefinitionId().toString(),base.capabilityVersionId().toString()),
                config,base.priority()==null?0:base.priority(),row.revision(),true);
    }

    /** 显式部署或新Run准备创建缺少公共Tool连接；恢复既有Run不触发当前版本准备。 */
    private void ensurePlatformTools(long tenant,long agent,AgentTemplateDefinition definition) {
        var scope=CapabilityCatalogScope.tenant(tenant);
        var existing=catalog.listDeployments(scope,null).stream().collect(java.util.stream.Collectors.toMap(CapabilityDeploymentView::deploymentKey,d->d));
        for(var base:templates.resolveCurrentDefinition(definition).capabilities())if(base.capabilityType()==CapabilityType.TOOL) {
            providers.validate(tenant,ToolDeployment.ProviderType.PLATFORM,base.platformToolName(),ToolDeployment.Environment.PRODUCTION);
            String key=toolKey(agent,base);
            if(!existing.containsKey(key))catalog.createDeployment(scope,key,base.capabilityVersionId(),ToolDeployment.ProviderType.PLATFORM,
                    base.platformToolName(),ToolDeployment.Environment.PRODUCTION,"ACTIVE");
        }
    }

    /** 保存只允许当前定义声明的槽位；缺槽位可保存为未就绪，但跨机构引用立即拒绝。 */
    private void validateSyntax(AgentTemplateDefinition definition,LocalBindings local) {
        if(!AgentBindingDeclarations.declarationsMatch(definition,AgentBindingDeclarations.BindingShape.from(local)))
            throw invalid("本地槽位声明、类型或参数键不匹配");
        for(var entry:local.parameters().entrySet())if(!entry.getKey().matches("[a-z][a-z0-9_]{0,63}")
                ||!(entry.getValue() instanceof String||entry.getValue() instanceof Number||entry.getValue() instanceof Boolean)
                ||entry.getValue().toString().length()>4096)throw invalid("提示词参数必须使用合法标识和有界标量值");
    }

    /** 提交时复验资源；保存为引用后仍在每次新Run解析时重新确认连接和版本。 */
    private void validateResources(long tenant,long agent,LocalBindings local,long revision) {
        for(var binding:local.resourceBindings())mounts.resolveDeploymentBinding(tenant,agent,mountId(agent,"slot:"+binding.slotKey()),revision,binding.mount());
    }

    /** 公共定义与实例参数结合，不把租户参数写回平台公共快照。 */
    private void applyDefinition(AgentDO agent,AgentTemplateDefinition definition,LocalBindings local) {
        var matcher=PARAMETER.matcher(definition.systemPrompt());StringBuilder prompt=new StringBuilder();
        while(matcher.find())matcher.appendReplacement(prompt,java.util.regex.Matcher.quoteReplacement(
                local.parameters().containsKey(matcher.group(1))?String.valueOf(local.parameters().get(matcher.group(1))):matcher.group()));
        matcher.appendTail(prompt);agent.setSystemPrompt(prompt.toString());agent.setRoleDescription(definition.roleDescription());
        agent.setModelConfigId(Long.valueOf(definition.modelConfigId()));agent.setExecutionConfig(definition.executionConfig());
        agent.setConfig(Map.of());
    }

    /** 机构委派授权仍由现有服务判断租户、状态、重复及自委派。 */
    private AgentDelegationConfig delegation(AgentDO agent,LocalBindings local) {
        var targets=local.delegationBindings().stream().map(DelegationBinding::targetAgentId).toList();
        return configurations.requireDelegationConfig(new com.skillforge.core.agent.management.AgentConfigurationPolicy.DelegationSource(
                agent.getTenantId(),agent.getId(),agent.getDelegationConfig()),new AgentDelegationConfig(!targets.isEmpty(),targets));
    }

    /** 状态来自稳定公共定义，内容来自其不可变当前发布快照。 */
    private AgentTemplateDO published(long templateId) {
        return templates.requireCurrentDefinitionAvailable(templateId);
    }

    /** 始终限定机构身份，不以全局主键替代机构授权。 */
    private AgentDO requireAgent(long tenant,long agent) {
        var value=agents.getOne(new LambdaQueryWrapper<AgentDO>().eq(AgentDO::getTenantId,tenant).eq(AgentDO::getId,agent));
        if(value==null)throw new ApiException(HttpStatus.NOT_FOUND,"机构Agent实例不存在");return value;
    }

    /** 部署详情的所有查询带机构；没有部署的独立Agent不会被误判为公共实例。 */
    private AgentDeploymentMapper.Row requireRow(long tenant,long agent) {
        var row=mapper.findAgent(tenant,agent);
        if(row==null)throw new ApiException(HttpStatus.NOT_FOUND,"公共Agent部署不存在");return row;
    }

    /** 同事务绑定已获授权机构并恢复线程上下文，禁止跨机构污染调用链。 */
    private <T>T inTenant(long tenant,java.util.function.Supplier<T> work) {
        LOG.debug("部署机构事务作用域进入 tenantId={}",tenant);
        String previous=TenantContext.getTenantId();
        if(previous!=null&&!previous.equals(Long.toString(tenant)))throw new SecurityException("database_tenant_scope_mismatch");
        var previousDatabase=mapper.databaseContext();
        TenantContext.setTenantId(Long.toString(tenant));
        Throwable failure=null;
        try{database.bindTenant(tenant);return work.get();}
        catch(RuntimeException|Error rejected){failure=rejected;throw rejected;}
        finally{
            try {if(previousDatabase!=null)mapper.restoreDatabaseContext(previousDatabase.tenant(),previousDatabase.platformAdmin());}
            catch(RuntimeException|Error restoreFailure){if(failure!=null)failure.addSuppressed(restoreFailure);else throw restoreFailure;}
            finally {if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);
                LOG.debug("部署机构事务作用域退出 tenantId={} success={}",tenant,failure==null);}
        }
    }

    /** 槽位引用序列化按键排序，技术错误不降级为就绪状态。 */
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("部署配置编码失败",e);}}
    /** 数据库快照必须完整解码，损坏配置不作默认绑定处理。 */
    private LocalBindings read(String value) {try{return json.readValue(value,LocalBindings.class);}catch(JsonProcessingException e){throw new IllegalStateException("部署配置解码失败",e);}}
    private GrantChain readChain(String value) {
        if(value==null)throw new IllegalStateException("部署授权链缺失");
        try {var chain=json.readValue(value,GrantChain.class);if(chain==null||chain.hops().isEmpty())throw new IllegalStateException("部署授权链为空");return chain;}
        catch(JsonProcessingException e){throw new IllegalStateException("部署授权链解码失败",e);}
    }
    private View saveResponse(AgentDeploymentMapper.Row row) {
        var response=view(row);
        if(mapper.saveRevisionResponse(row.deploymentId(),row.revision(),write(response))!=1)throw new IllegalStateException("部署幂等回执写入失败");
        return response;
    }
    private View readResponse(String value) {
        if(value==null)throw conflict("原部署回执不存在，不能重建历史响应");
        try {return Objects.requireNonNull(json.readValue(value,View.class));}
        catch(JsonProcessingException e){throw new IllegalStateException("部署幂等回执解码失败",e);}
    }
    /** 原生部署由服务端确定稳定标识，使原请求及其只读核对使用相同身份。 */
    private Deploy deploymentIdentity(long tenant,Deploy command,String requestKey,boolean platformActor) {
        if(command.integrationId()!=null || command.externalAgentRef()!=null) return command;
        String identity=HexFormat.of().formatHex(digest(write(List.of(tenant,actorRef(platformActor),requestKey))));
        return new Deploy(command.definitionId(),command.grantId(),null,"agent-"+identity,command.name(),
                command.resourceBindings(),command.delegationBindings(),command.parameters(),command.tenantId());
    }
    /** 首次请求摘要不含凭据，CAS绑定修改与创建幂等账本独立。 */
    private String fingerprint(Deploy value) {return HexFormat.of().formatHex(digest(write(value)));}
    /** 合成执行挂载在同一实例槽位中保持稳定ID；数据由Run快照持有，不写可变挂载表。 */
    private static String mountId(long agent,String slot) {long value=ByteBuffer.wrap(digest(agent+":"+slot)).getLong()&Long.MAX_VALUE;return Long.toString(Math.max(1,value));}
    /** 公共工具连接键属于该机构实例，不共享另一机构的凭证或状态。 */
    private static String toolKey(long agent,AgentTemplateDefinition.BaseCapability base) {return "managed-agent-"+agent+"-tool-"+base.capabilityDefinitionId()+"-"+base.platformToolName();}
    /** 摘要同时用于幂等指纹及执行挂载身份，不携带明文参数日志。 */
    private static byte[] digest(String value) {try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static ApiException invalid(String message) {return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,message);}
    private static ApiException conflict(String message) {return new ApiException(HttpStatus.CONFLICT,message);}
    private static void requireExplicitTarget(long tenant,Long declared) {
        if(declared!=null&&declared!=tenant)throw invalid("部署管理目标与正文机构不一致");
    }
    private record Evaluated(AgentTemplateDO template,LocalBindings local,List<Issue> issues,List<CapabilityMount> mounts) { }
    private com.skillforge.core.agent.management.AgentConfigurationPolicy.ReadyConfiguration readyConfiguration(AgentDO agent) {
        return new com.skillforge.core.agent.management.AgentConfigurationPolicy.ReadyConfiguration(agent.getTenantId(),
                agent.getSystemPrompt(),agent.getModelConfigId(),agent.getExecutionConfig(),agent.getConfig());
    }

}
