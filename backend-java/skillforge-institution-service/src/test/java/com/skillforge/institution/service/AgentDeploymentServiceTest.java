package com.skillforge.institution.service;

import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.foundation.catalog.entitlements.DirectDeploymentScope;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.foundation.integration.PlatformManagementService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.*;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.deployment.AgentTemplateGrantService;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.model.*;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.skillforge.core.agent.deployment.AgentDeploymentContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 公共部署必须在真实执行解析处跟随定义，并把已解析值留给Run冻结保存。 */
class AgentDeploymentServiceTest {
    private final AgentDeploymentMapper mapper=mock(AgentDeploymentMapper.class);
    private final AgentTemplateGrantService grants=mock(AgentTemplateGrantService.class);
    private final AgentTemplateService templates=mock(AgentTemplateService.class);
    private final AgentService agents=mock(AgentService.class);
    private final AgentPublicationService publications=mock(AgentPublicationService.class);
    private final com.skillforge.core.agent.management.AgentConfigurationPolicy configurations=mock(com.skillforge.core.agent.management.AgentConfigurationPolicy.class);
    private final CapabilityMountManagementService mounts=mock(CapabilityMountManagementService.class);
    private final CapabilityMountPersistenceService persistence=mock(CapabilityMountPersistenceService.class);
    private final CapabilityCatalogService catalog=mock(CapabilityCatalogService.class);
    private final CapabilityCatalogDatabaseContext database=mock(CapabilityCatalogDatabaseContext.class);
    private final TenantToolDeploymentProviderValidator providers=mock(TenantToolDeploymentProviderValidator.class);
    private final PlatformManagementService platform=mock(PlatformManagementService.class);
    private final InstitutionIntegrationService integrations=mock(InstitutionIntegrationService.class);
    private final OpenPlatformAccessService external=mock(OpenPlatformAccessService.class);
    private final FoundationAccess foundation=mock(FoundationAccess.class);
    private final InstitutionResourceAccess access=mock(InstitutionResourceAccess.class);
    private final com.skillforge.core.foundation.access.FoundationOrganizationService organization=mock(com.skillforge.core.foundation.access.FoundationOrganizationService.class);
    private final com.skillforge.core.foundation.catalog.entitlements.ResourceQuota quota=mock(com.skillforge.core.foundation.catalog.entitlements.ResourceQuota.class);
    private final com.skillforge.core.foundation.catalog.entitlements.FeatureGate features=mock(com.skillforge.core.foundation.catalog.entitlements.FeatureGate.class);
    private final DirectDeploymentScope.ContextMapper scopeMapper=mock(DirectDeploymentScope.ContextMapper.class);
    private final DirectDeploymentScope directScope=new DirectDeploymentScope(platform,scopeMapper,integrations);
    private final com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService targetManagement=mock(com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService.class);
    private final Map<String,String> receipts=new HashMap<>();
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final AgentDeploymentService service=new AgentDeploymentService(mapper,grants,templates,agents,publications,
            configurations,mounts,persistence,catalog,database,providers,json,platform,integrations,external,foundation,access,directScope,organization,quota,features,targetManagement);

    @org.junit.jupiter.api.BeforeEach void prepareIdentity() {
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","70"));
        when(platform.actorRef()).thenReturn("platform:9");
        when(scopeMapper.current()).thenReturn(new DirectDeploymentScope.State("","true"));
        when(grants.requireChainForAdmission(anyLong(),anyLong(),anyLong(),anyString())).thenAnswer(call->chain(call.getArgument(0),call.getArgument(1)));
        when(mapper.saveRevisionResponse(anyLong(),anyLong(),anyString())).thenAnswer(call->{receipts.put(call.getArgument(0)+":"+call.getArgument(1),call.getArgument(2));return 1;});
        when(mapper.findRevisionResponse(anyLong(),anyLong())).thenAnswer(call->receipts.get(call.getArgument(0)+":"+call.getArgument(1)));
        when(templates.requireCurrentDefinitionAvailable(anyLong())).thenThrow(new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"定义不存在"));
        when(mapper.appendRevision(anyLong(),anyString(),anyString())).thenReturn(1);
    }

    /** 每个用例退出后清除可信租户，模拟后台线程池复用。 */
    @AfterEach void clear(){TenantContext.clear();ServicePrincipalContext.clear();com.skillforge.common.context.ManagementContext.clear();TransactionSynchronizationManager.clear();}

    /** 相同机构稳定实例在新Run解析新公共版本，先前解析值不会被重新生成。 */
    @Test void nextRunFollowsPublicationAndPreviousResolvedDefinitionStaysFrozen() throws Exception {
        row(7,11,new LocalBindings(List.of(),List.of(),Map.of("store","一号店")));
        var first=template("1.0.0","为{{parameters.store}}提供服务",List.of());
        var second=template("1.1.0","新版{{parameters.store}}服务",List.of());
        when(templates.getById(31L)).thenReturn(first);
        doReturn(first,second).when(templates).requireCurrentDefinitionAvailable(31L);
        when(templates.resolveCurrentDefinition(any())).thenAnswer(call->call.getArgument(0));
        when(agents.getOne(any())).thenAnswer(call->agent(7,11));
        when(configurations.requireDelegationConfig(any(),any())).thenAnswer(call->call.getArgument(1));
        var old=service.resolve(7,11).orElseThrow();
        var next=service.resolve(7,11).orElseThrow();
        assertEquals("1.0.0",old.definitionVersion());assertEquals("1.1.0",next.definitionVersion());
        assertEquals("为一号店提供服务",old.agent().getSystemPrompt());
        assertEquals("新版一号店服务",next.agent().getSystemPrompt());
        assertEquals(7L,next.agent().getTenantId());assertEquals(11L,next.agent().getId());
        verify(publications,never()).publish(any());
        verify(mapper,never()).updateBindings(anyLong(),anyLong(),anyLong(),anyString());
        assertNull(TenantContext.getTenantId());
    }

    /** 新公共模型替换已停用旧模型时，按新发布配置检查就绪，不要求机构重新保存绑定。 */
    @Test void readinessUsesNewPublishedModelAndBudgetWithoutMutatingStoredAgent()throws Exception {
        row(7,11,new LocalBindings(List.of(),List.of(),Map.of()));
        var current=template("1.1.0","当前定义",List.of());
        var budget=new AgentExecutionConfig(5,10,3,1000L,60L);
        current.setDefinition(new AgentTemplateDefinition("当前定义","角色","52",budget,List.of(),List.of(),List.of()));
        setup(current,7,11);
        var stored=agent(7,11);
        when(agents.getOne(any())).thenReturn(stored);
        doAnswer(call->{
            com.skillforge.core.agent.management.AgentConfigurationPolicy.ReadyConfiguration effective=call.getArgument(0);
            if(Objects.equals(51L,effective.modelConfigId()))
                throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"旧模型已停用");
            assertEquals(52L,effective.modelConfigId());
            assertEquals(budget,effective.executionConfig());
            assertEquals("当前定义",effective.systemPrompt());
            assertNotSame(stored,effective);
            return null;
        }).when(configurations).requireReady(any());
        TenantContext.setTenantId("7");

        var view=service.detail(11);

        assertTrue(view.ready());assertEquals("1.1.0",view.definitionVersion());
        assertEquals(51L,stored.getModelConfigId());assertEquals("旧机构定义",stored.getSystemPrompt());
        when(agents.getOne(any())).thenAnswer(call->agent(7,11));
        var runDefinition=service.resolve(7,11).orElseThrow();
        assertEquals(52L,runDefinition.agent().getModelConfigId());
        assertEquals(budget,runDefinition.agent().getExecutionConfig());
        assertEquals("1.1.0",runDefinition.definitionVersion());
        verify(agents,never()).updateById(any());verifyNoInteractions(publications);
    }

    /** 缺槽位可展示和修订，但不能通过旧机构发布快照继续新Run。 */
    @Test void missingDeclaredResourceReportsReadinessAndBlocksExecution() throws Exception {
        row(7,11,new LocalBindings(List.of(),List.of(),Map.of()));
        var template=template("1.1.0","新定义",List.of(new AgentTemplateDefinition.ResourceSlot("knowledge","机构知识库","",CapabilityType.KNOWLEDGE_SOURCE)));
        setup(template,7,11);
        TenantContext.setTenantId("7");
        var view=service.detail(11);
        assertFalse(view.ready());assertEquals("binding_required",view.issues().getFirst().code());
        var rejected=assertThrows(ApiException.class,()->service.resolve(7,11));
        assertEquals("AGENT_DEPLOYMENT_NOT_READY",rejected.getCode());
        verifyNoInteractions(publications);
    }

    @Test void declarationProjectionAndExecutionUseTheSameMissingRoleAndParameterReasons() throws Exception {
        row(7,11,new LocalBindings(List.of(),List.of(),Map.of()));
        var current=template("1.1.0","{{parameters.store}} {{parameters.store}}",List.of());
        current.setDefinition(new AgentTemplateDefinition("{{parameters.store}} {{parameters.store}}","角色","51",null,List.of(),
                List.of(),List.of(new AgentTemplateDefinition.DelegationSlot("assistant","助理",""))));
        setup(current,7,11);TenantContext.setTenantId("7");
        var issues=service.detail(11).issues();
        assertEquals(List.of("delegation_bindings.assistant","parameters.store"),issues.stream().map(Issue::path).toList());
        assertEquals(List.of("binding_required","parameter_required"),issues.stream().map(Issue::code).toList());
        assertThrows(ApiException.class,()->service.resolve(7,11));
        verifyNoInteractions(publications);verify(mapper,never()).updateBindings(anyLong(),anyLong(),anyLong(),anyString());
    }

    /** 相同外部身份只在integration与机构组成的命名空间内幂等。 */
    @Test void sameExternalIdentityCreatesSeparateInstitutionInstancesAndRetryReturnsOriginal() {
        var integrationMapper=mock(InstitutionIntegrationMapper.class);
        when(integrationMapper.tenantStatus(anyLong())).thenReturn("active");
        when(integrationMapper.integrationExists(anyLong(),eq("integration"))).thenAnswer(call->{assertEquals(call.getArgument(0).toString(),TenantContext.getTenantId());return true;});
        var realIntegration=new InstitutionIntegrationService(integrationMapper,mock(TenantMapper.class),mock(ServicePrincipalService.class),platform);
        var directService=new AgentDeploymentService(mapper,grants,templates,agents,publications,configurations,mounts,persistence,catalog,database,providers,json,
                platform,realIntegration,external,foundation,access,directScope,organization,quota,features,targetManagement);
        com.skillforge.common.context.ManagementContext.set(new com.skillforge.common.context.ManagementContext.Identity("9",1,1));
        var template=template("1.0.0","定义",List.of());setup(template,7,11);
        Map<String,AgentDeploymentMapper.Row> installed=new HashMap<>();
        when(mapper.findExternal(anyString(),anyLong(),anyString())).thenAnswer(call->installed.get(call.getArgument(0)+":"+call.getArgument(1)+":"+call.getArgument(2)));
        when(mapper.insert(any())).thenAnswer(call->{AgentDeploymentMapper.Row value=call.getArgument(0);installed.put(value.integrationId()+":"+value.tenantId()+":"+value.externalAgentRef(),value);return 1;});
        when(agents.save(any())).thenReturn(true);
        when(agents.getOne(any())).thenAnswer(call->{var result=agent(7,11);String tenant=TenantContext.getTenantId();
            var row=installed.values().stream().filter(r->r.tenantId().toString().equals(tenant)).findFirst().orElseThrow();
            result.setTenantId(row.tenantId());result.setId(row.agentId());return result;});
        when(catalog.listDeployments(any(),any())).thenReturn(List.of());
        var command=new Deploy(31L,41L,"integration","doctor",null,List.of(),List.of(),Map.of());
        var first=directService.deploy(7,command,"request");var second=directService.deploy(8,command,"request");var replay=directService.deploy(7,command,"request");
        assertNotEquals(first.agentId(),second.agentId());assertEquals(first.agentId(),replay.agentId());
        assertEquals(7L,first.tenantId());assertEquals(8L,second.tenantId());
        verify(mapper,times(2)).recordCreateRequest(first.deploymentId(),7L,"platform:9","request");
        verify(mapper).recordCreateRequest(second.deploymentId(),8L,"platform:9","request");
        verify(agents,times(2)).save(any());verify(publications,times(2)).publish(any());
        verify(features).requireDirectDeployment("7");verify(features).requireDirectDeployment("8");
        verify(quota).requireDirectAgentCapacity("7",1);verify(quota).requireDirectAgentCapacity("8",1);
        assertNull(TenantContext.getTenantId());
        verify(scopeMapper,times(3)).restore("","true");
        assertEquals("9",com.skillforge.common.context.ManagementContext.get().principalId());
    }

    @Test void nativeDeploymentAndReceiptUseTheSameServerGeneratedIdentity() {
        com.skillforge.common.context.ManagementContext.set(new com.skillforge.common.context.ManagementContext.Identity("9",1,1));
        var command=new Deploy(31L,41L,null,null,null,List.of(),List.of(),Map.of(),7L);
        assertThrows(ApiException.class,()->service.deploy(7,command,"request-a"));
        assertEquals("NOT_FOUND",service.deployReceipt(7,command,"request-a").status());
        assertEquals("NOT_FOUND",service.deployReceipt(7,command,"request-b").status());
        var refs=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper,times(3)).findExternal(isNull(),eq(7L),refs.capture());
        assertTrue(refs.getAllValues().get(0).startsWith("agent-"));
        assertEquals(refs.getAllValues().get(0),refs.getAllValues().get(1));
        assertNotEquals(refs.getAllValues().get(0),refs.getAllValues().get(2));
        var external=new Deploy(31L,41L,"integration",null,null,List.of(),List.of(),Map.of(),7L);
        assertThrows(ApiException.class,()->service.deploy(7,external,"request-c"));
    }

    /** 真正integration服务要求已绑定目标，平台机器空Tenant不能被mock遮蔽；失败后两种上下文都恢复。 */
    @Test void platformMachineBindsTargetBeforeRealIntegrationCheckAndRestoresAfterFailure() {
        var integrationMapper=mock(InstitutionIntegrationMapper.class);
        when(integrationMapper.tenantStatus(7L)).thenReturn("active");
        when(integrationMapper.integrationExists(7L,"integration")).thenAnswer(call->{assertEquals("7",TenantContext.getTenantId());return true;});
        var realIntegration=new InstitutionIntegrationService(integrationMapper,mock(TenantMapper.class),mock(ServicePrincipalService.class),platform);
        var subjectService=new AgentDeploymentService(mapper,grants,templates,agents,publications,configurations,mounts,persistence,catalog,database,providers,json,
                platform,realIntegration,external,foundation,access,directScope,organization,quota,features,targetManagement);
        com.skillforge.common.context.ManagementContext.set(new com.skillforge.common.context.ManagementContext.Identity("9",1,1));
        doAnswer(call->{assertNotNull(com.skillforge.common.context.ManagementContext.get());return null;}).when(platform).requireTenant("platform.agent_deployments.create",7L);
        // 到达固定域核验后故意停止在定义不存在，验证整个错误出口也恢复连接/线程。
        assertThrows(ApiException.class,()->subjectService.deploy(7,new Deploy(31L,41L,"integration","doctor",null,List.of(),List.of(),Map.of()),"machine-create"));
        verify(integrationMapper).integrationExists(7L,"integration");
        verify(platform).requireDefinition("platform.agent_deployments.create",31L);
        verify(scopeMapper).restore("","true");assertNull(TenantContext.getTenantId());
        assertEquals("9",com.skillforge.common.context.ManagementContext.get().principalId());
    }

    /** 原创建标识不得被不同模板或名字重用；冲突发生在实例写入之前。 */
    @Test void idempotentExternalReferenceRejectsDifferentPayloadBeforeWriting() throws Exception {
        when(mapper.findExternal("integration",7L,"doctor")).thenReturn(new AgentDeploymentMapper.Row(11L,"integration",7L,11L,31L,41L,1L,"doctor","old","{}",1,Instant.EPOCH,json.writeValueAsString(chain(41,7))));
        assertThrows(ApiException.class,()->service.deploy(7,new Deploy(31L,41L,"integration","doctor","different",null,null,null),"request"));
        verifyNoInteractions(agents,publications);assertNull(TenantContext.getTenantId());
    }
    @Test void receiptRequiresOriginalActorKeyTenantAndFingerprintWithoutWriting() throws Exception {
        var command=new Deploy(31L,41L,"integration","doctor",null,List.of(),List.of(),Map.of());
        String fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(command)));
        var row=new AgentDeploymentMapper.Row(11L,"integration",7L,12L,31L,41L,1L,"doctor",fingerprint,"{}",1,Instant.EPOCH,json.writeValueAsString(chain(41,7)));
        when(mapper.findExternal("integration",7L,"doctor")).thenReturn(row);
        var original=new View(11L,7L,"integration",12L,"doctor",31L,41L,1,"定义","1","FOLLOW_PUBLISHED",1,true,List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),chain(41,7));
        when(mapper.findCreateResponse(11L,7L,"platform:9","request")).thenReturn(json.writeValueAsString(original));
        assertEquals(original,service.deployReceipt(7,command,"request").result());
        assertEquals("NOT_FOUND",service.deployReceipt(7,command,"wrong").status());
        assertThrows(ApiException.class,()->service.deployReceipt(7,new Deploy(31L,41L,"integration","doctor","different",null,null,null),"request"));
        verify(platform,atLeastOnce()).requireTenant("platform.agent_deployments.read",7L);
        verify(platform,atLeastOnce()).requireDefinition("platform.agent_deployments.read",31L);
        verify(mapper,never()).insert(any());verify(mapper,never()).lockExternal(any(),anyLong(),anyString());
        verifyNoInteractions(agents,publications);assertNull(TenantContext.getTenantId());
    }

    /** 保存的资源仍需统一授权校验，跨机构资源失败不会写入绑定或发布。 */
    @Test void foreignResourceCannotBeSavedAndLocalRevisionUsesCas() throws Exception {
        row(7,11,new LocalBindings(List.of(),List.of(),Map.of()));
        var template=template("1.0.0","定义",List.of(new AgentTemplateDefinition.ResourceSlot("knowledge","机构知识库","",CapabilityType.KNOWLEDGE_SOURCE)));
        setup(template,7,11);when(agents.lockVersion(7,11)).thenReturn(agent(7,11));TenantContext.setTenantId("7");
        var mount=new com.skillforge.core.agent.dto.request.CapabilityMountRequest(CapabilityType.KNOWLEDGE_SOURCE,91L,null,null,null,null,
                new com.skillforge.core.agent.dto.request.KnowledgeMountConfigRequest(CapabilityMount.KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE,null),0);
        when(mounts.resolveDeploymentBinding(eq(7L),eq(11L),anyString(),anyLong(),any())).thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"资源不属于机构"));
        assertThrows(ApiException.class,()->service.bind(new Bindings(11L,1,List.of(new ResourceBinding("knowledge",mount)),List.of(),Map.of()),"request"));
        verify(mapper,never()).updateBindings(anyLong(),anyLong(),anyLong(),anyString());verifyNoInteractions(publications);
        assertThrows(ApiException.class,()->service.bind(new Bindings(11L,2,List.of(),List.of(),Map.of()),"request-2"));
    }

    /** 当前授权撤销必须在解析入口拒绝，不把机构旧发布当成替代定义。 */
    @Test void revokedGrantRejectsExecutionWithoutFallback() throws Exception {
        row(7,11,new LocalBindings(List.of(),List.of(),Map.of()));
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"撤销")).when(grants).requireFrozenChain(chain(41,7),7,31,"execute");
        assertThrows(ApiException.class,()->service.resolve(7,11));verifyNoInteractions(publications,templates);
    }

    /** 已认证机构不能被参数改写，拒绝后保留原线程身份。 */
    @Test void tenantMismatchRejectsBeforeReadingDeployment() {
        TenantContext.setTenantId("8");assertThrows(SecurityException.class,()->service.resolve(7,11));
        assertEquals("8",TenantContext.getTenantId());verifyNoInteractions(mapper);
    }

    /** 普通独立Agent无需公共定义部署解析。 */
    @Test void independentAgentUsesExistingPublicationPipeline() {
        when(mapper.findAgent(7,11)).thenReturn(null);assertTrue(service.resolve(7,11).isEmpty());
        verifyNoInteractions(grants,templates,publications);assertNull(TenantContext.getTenantId());
    }

    /** 平台增加公共工具时新Run准备自动创建机构部署，旧Run恢复没有写入入口。 */
    @Test void newRunPreparesNewPublicToolWithoutReplacingLocalBindings()throws Exception {
        row(7,11,new LocalBindings(List.of(),List.of(),Map.of()));
        var template=template("1.1.0","定义",List.of());
        var base=new AgentTemplateDefinition.BaseCapability(CapabilityType.TOOL,61L,62L,null,"json_schema_validate",0);
        template.setDefinition(new AgentTemplateDefinition("定义","角色","51",null,List.of(base),List.of(),List.of()));
        setup(template,7,11);when(agents.lockVersion(7,11)).thenReturn(agent(7,11));
        when(catalog.listDeployments(any(),isNull())).thenReturn(List.of());
        service.prepareExecution(7,11);
        verify(catalog).createDeployment(CapabilityCatalogScope.tenant(7L),"managed-agent-11-tool-61-json_schema_validate",62L,
                com.skillforge.core.capability.tool.ToolDeployment.ProviderType.PLATFORM,"json_schema_validate",
                com.skillforge.core.capability.tool.ToolDeployment.Environment.PRODUCTION,"ACTIVE");
        verify(mapper,never()).updateBindings(anyLong(),anyLong(),anyLong(),anyString());verifyNoInteractions(publications);
        assertNull(TenantContext.getTenantId());
    }

    /** 数据库绑定失败不污染线程或调用方连接。 */
    @Test void failedDatabaseContextBindingDoesNotLeakInstitution() {
        doThrow(new IllegalStateException("database unavailable")).when(database).bindTenant(7L);
        assertThrows(IllegalStateException.class,()->service.resolve(7,11));assertNull(TenantContext.getTenantId());
        verify(mapper,never()).findAgent(anyLong(),anyLong());
    }

    /** 撤销旧链后可显式修复；同键重投返回首次响应，后续发布与部署修订不改原回执。 */
    @Test void switchingFromRevokedChainPreservesIdentityBindingsAndOriginalReceipt() throws Exception {
        var local=new LocalBindings(List.of(),List.of(),Map.of("store","原门店"));
        row(7,11,local);setup(template("1.0.0","{{parameters.store}}",List.of()),7,11);
        when(agents.lockVersion(7,11)).thenReturn(agent(7,11));TenantContext.setTenantId("7");
        var initial=mapper.findDeployment(7,11);var current=new AtomicReference<>(initial);
        when(mapper.findDeployment(7,11)).thenAnswer(call->current.get());
        when(mapper.lockDeployment(7,11)).thenAnswer(call->current.get());
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"旧授权已撤销"))
                .when(grants).requireFrozenChain(eq(chain(41,7)),eq(7L),eq(31L),anyString());
        var receipt=new AtomicReference<AgentDeploymentMapper.Revision>();
        when(mapper.findRevisionRequest(11,"tenant:7:member:70","switch-1")).thenAnswer(call->receipt.get());
        when(mapper.switchAuthorization(eq(7L),eq(11L),eq(1L),eq(42L),eq(1L),anyString())).thenAnswer(call->{
            current.set(new AgentDeploymentMapper.Row(initial.deploymentId(),initial.integrationId(),initial.tenantId(),initial.agentId(),
                    initial.definitionId(),42L,1,initial.externalAgentRef(),initial.fingerprint(),initial.bindings(),2,initial.createdAt(),call.getArgument(5)));
            return 1;
        });
        when(mapper.appendAuthorizationRevision(eq(11L),anyString(),anyString(),anyString())).thenAnswer(call->{
            var row=current.get();receipt.set(new AgentDeploymentMapper.Revision(row.revision(),row.grantId(),row.grantRevision(),row.bindings(),
                    call.getArgument(3),row.grantChain(),null));return 1;
        });
        var command=new SwitchAuthorization(11L,1,42L);
        var first=service.switchAuthorization(command,"switch-1");
        assertEquals(11L,first.agentId());assertEquals(42L,first.grantId());assertEquals(2,first.deploymentRevision());
        assertEquals(local.parameters(),first.parameters());assertTrue(first.ready());
        var saved=receipt.get();receipt.set(new AgentDeploymentMapper.Revision(saved.revision(),saved.grantId(),saved.grantRevision(),saved.bindings(),
                saved.fingerprint(),saved.grantChain(),receipts.get("11:2")));
        doThrow(new IllegalStateException("后续源不可用不能影响原回执")).when(templates).requireCurrentDefinitionAvailable(31);
        clearInvocations(grants);
        assertEquals(first,service.switchAuthorization(command,"switch-1"));
        assertThrows(ApiException.class,()->service.switchAuthorization(new SwitchAuthorization(11L,1,43L),"switch-1"));
        verifyNoInteractions(grants,publications);
        verify(mapper,times(1)).switchAuthorization(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyString());
        verify(mapper,never()).updateBindings(anyLong(),anyLong(),anyLong(),anyString());
    }

    @Test void switchRequiresCurrentNewChainAndExactSourceBeforeWriting() throws Exception {
        row(7,11,new LocalBindings(List.of(),List.of(),Map.of()));TenantContext.setTenantId("7");
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"新授权已撤销"))
                .when(grants).requireChainForAdmission(42,7,31,"deploy");
        assertThrows(ApiException.class,()->service.switchAuthorization(new SwitchAuthorization(11L,1,42L),"revoked-new"));
        doReturn(new GrantChain(31L,"tenant",9L,List.of(new GrantHop(43L,1,"tenant",9L,7L,null,null,10L,1L))))
                .when(grants).requireChainForAdmission(43,7,31,"deploy");
        assertThrows(ApiException.class,()->service.switchAuthorization(new SwitchAuthorization(11L,1,43L),"foreign-source"));
        verify(mapper,never()).switchAuthorization(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyString());
        verifyNoInteractions(agents,publications);
    }

    /** 持久行携带稳定部署和授权依据，独立实例没有此行。 */
    private void row(long tenant,long agent,LocalBindings local)throws Exception {
        var value=new AgentDeploymentMapper.Row(agent,"integration",tenant,agent,31L,41L,1L,"doctor","fingerprint",json.writeValueAsString(local),1L,Instant.EPOCH,json.writeValueAsString(chain(41,tenant)));
        when(mapper.findAgent(tenant,agent)).thenReturn(value);when(mapper.findDeployment(tenant,agent)).thenReturn(value);
        when(mapper.lockAgent(tenant,agent)).thenReturn(value);when(mapper.lockDeployment(tenant,agent)).thenReturn(value);
    }
    /** 定义依赖由正式发布入口返回。 */
    private void setup(AgentTemplateDO template,long tenant,long agent) {
        doReturn(template).when(templates).requireCurrentDefinitionAvailable(31L);
        when(templates.resolveCurrentDefinition(any())).thenAnswer(call->call.getArgument(0));
        when(agents.getOne(any())).thenAnswer(call->agent(tenant,agent));
        when(configurations.requireDelegationConfig(any(),any())).thenAnswer(call->call.getArgument(1));
    }
    private static GrantChain chain(long grant,long tenant) {
        return new GrantChain(31L,"platform",null,List.of(new GrantHop(grant,1,"platform",null,tenant,null,null,null,null)));
    }
    /** 每次模拟数据库读取返回独立实体，符合发布解析不得修改先前Run值的语义。 */
    private AgentDO agent(long tenant,long id) {var a=new AgentDO();a.setTenantId(tenant);a.setId(id);a.setName("医生");a.setStatus("published");
        a.setSystemPrompt("旧机构定义");a.setModelConfigId(51L);a.setConfig(Map.of());a.setRevision(1);a.setPublishedRevisionId(101L);a.setPublishedAt(Instant.EPOCH);return a;}
    /** 最小可执行公共定义只需模型与提示词，无额外资源依赖。 */
    private AgentTemplateDO template(String version,String prompt,List<AgentTemplateDefinition.ResourceSlot> slots) {var t=new AgentTemplateDO();
        t.setId(31L);t.setTemplateKey("common");t.setName("公共医生");t.setStatus("published");t.setVersion(version);t.setPublishedAt(Instant.EPOCH);
        t.setDefinition(new AgentTemplateDefinition(prompt,"角色","51",AgentExecutionConfig.inherited(),List.of(),slots,List.of()));return t;}
}
