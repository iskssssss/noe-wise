package com.skillforge.institution.workflowtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentTestProvisioningService;
import com.skillforge.core.capability.*;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.foundation.workflowtest.*;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.workflow.*;
import com.skillforge.core.workflow.execution.WorkflowDefinitionContract;
import com.skillforge.institution.service.WorkflowManagementService;
import com.skillforge.institution.runtime.AgentRuntimeRunControls;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.skillforge.institution.workflowtest.WorkflowTestContracts.*;

/** 一次事务创建完整私有资源包；唯一执行和 Pending 仍归正式 Runtime。 */
@Service
public class WorkflowTestSessionService {
    public static final Budget RECOMMENDED=new Budget(4,8,8,16000,120);
    public static final Budget MAXIMUM=new Budget(16,32,32,64000,300);
    private final WorkflowTestRegistry tests;
    private final WorkflowTestIsolation isolation;
    private final FoundationAccess access;
    private final WorkflowManagementService management;
    private final WorkflowTestDefinitionService definitions;
    private final WorkflowInstallationValidator bindings;
    private final WorkflowInstallationTargetAccess targets;
    private final CapabilityCatalogService catalog;
    private final CapabilityMountResolver.Registry registry;
    private final AgentTestProvisioningService agents;
    private final EndUserService users;
    private final SessionService sessions;
    private final FoundationOrganizationService organization;
    private final ExecutionStore store;
    private final ObjectProvider<AgentRuntimeRunControls> controls;
    private final ObjectProvider<WorkflowTestCancellation> cancellation;
    private final ObjectMapper json;
    private final SessionRuntimePayloads.InputValidator inputs;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public WorkflowTestSessionService(WorkflowTestRegistry tests,WorkflowTestIsolation isolation,FoundationAccess access,
            WorkflowManagementService management,WorkflowTestDefinitionService definitions,WorkflowInstallationValidator bindings,
            WorkflowInstallationTargetAccess targets,CapabilityCatalogService catalog,CapabilityMountResolver.Registry registry,
            AgentTestProvisioningService agents,EndUserService users,
            SessionService sessions,FoundationOrganizationService organization,ExecutionStore store,ObjectProvider<AgentRuntimeRunControls> controls,
            ObjectMapper json,AgentRuntimeProperties properties,org.springframework.transaction.PlatformTransactionManager transactionManager,ObjectProvider<WorkflowTestCancellation> cancellation) {
        this.tests=tests;this.isolation=isolation;this.access=access;this.management=management;this.definitions=definitions;
        this.bindings=bindings;this.targets=targets;this.catalog=catalog;this.registry=registry;this.agents=agents;
        this.users=users;this.sessions=sessions;this.organization=organization;this.store=store;this.controls=controls;this.cancellation=cancellation;
        this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        transactions=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        inputs=new SessionRuntimePayloads.InputValidator(properties.getMaxPayloadCodeUnits(),properties.getMaxPayloadNodes(),properties.getMaxPayloadDepth());
    }

    @Transactional(readOnly=true)
    public Options options(String workflowId,Long draftRevision,String definitionVersionId) {
        long tenant=tenant();member();management.require(tenant,workflowId,"manage");
        var selected=definitions.readSource(tenant,workflowId,new WorkflowTestDefinitionService.SourceSelection(draftRevision==null?"publication":"draft",draftRevision,definitionVersionId));
        definitions.requireDefinition(selected.definition());
        var slots=new ArrayList<Slot>();
        for(var requirement:WorkflowDefinitionContract.requirements(selected.definition()).values()) {
            var candidates=new ArrayList<Candidate>();
            var type=CapabilityType.valueOf(requirement.type().toUpperCase(Locale.ROOT));
            for(var definition:catalog.listDefinitions(CapabilityCatalogScope.tenant(tenant),type,null)) {
                if(!definition.capabilityKey().equals(requirement.capabilityId()))continue;
                CapabilityVersionView version;
                try { version=catalog.getCurrentPublishedVersion(CapabilityCatalogScope.tenant(tenant),definition.id()); }
                catch(ApiException denied) { if(denied.getStatus()==HttpStatus.NOT_FOUND)continue;throw denied; }
                if(!WorkflowDefinitionContract.matchesVersion(requirement.versionConstraint(),version.versionKey()))continue;
                var ref=new CapabilityRef(type,definition.id().toString(),version.id().toString());
                var descriptor=registry.find(tenant,ref).orElse(null);
                if(descriptor instanceof ToolVersion tool && tool.policy().effect().name().equals("READ_ONLY")
                        && tool.policy().confirmation().name().equals("NEVER") && tool.metadata().invocationPolicy().modelInvocable()) {
                    for(var deployment:catalog.listDeployments(CapabilityCatalogScope.tenant(tenant),"ACTIVE")) {
                        if(!deployment.toolVersionId().equals(version.id())||deployment.providerType()!=ToolDeployment.ProviderType.MCP
                                ||deployment.environment()!=ToolDeployment.Environment.TEST)continue;
                        try { targets.requireProvider(tenant,registry.findToolDeployment(tenant,deployment.deploymentKey()).orElseThrow()); }
                        catch(ApiException denied) { if(Set.of(HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND).contains(denied.getStatus()))continue;throw denied; }
                        candidates.add(new Candidate(identity(ref),deployment.deploymentKey(),deployment.revision(),null,definition.name()+" / "+deployment.deploymentKey()));
                    }
                } else if(descriptor instanceof KnowledgeSourceDescriptor knowledge) {
                    try { long revision=targets.currentKnowledgeRevision(tenant,knowledge);targets.requireKnowledge(tenant,knowledge,revision);
                        candidates.add(new Candidate(identity(ref),null,null,revision,definition.name()+" / 修订 "+revision)); }
                    catch(ApiException denied) { if(!Set.of(HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND).contains(denied.getStatus()))throw denied; }
                }
            }
            slots.add(new Slot(requirement.slot(),requirement.required(),requirement.type(),List.copyOf(candidates)));
        }
        var meta=WorkflowDefinitionContract.object(selected.definition().get("meta"),"meta");
        var capability=WorkflowDefinitionContract.object(meta.get("capability"),"meta.capability");
        return new Options(sourceReceipt(selected),WorkflowDefinitionContract.object(capability.get("input_schema"),"input_schema"),
                meta.getOrDefault("config_schema",List.of()),List.copyOf(slots),new BudgetOptions(RECOMMENDED,MAXIMUM),
                List.of("仅允许唯一目标工作流；后续仅可回复原 Pending","仅 TEST 环境的 MCP READ_ONLY 工具；禁止脚本、异步任务、嵌套和 Agent 委派",
                        "禁止记忆读写与自动提取、成果发布、工作区和渠道外发","测试资源保留30分钟，关闭需等待真实执行和取消收敛"),
                "模型和知识查询按实际用量计费。READ_ONLY 是供应商声明，TEST 环境不保证外部物理沙箱，请确认真实供应商数据和副作用边界。");
    }

    @Transactional(rollbackFor=Exception.class)
    public Receipt create(Create request) {
        long tenant=tenant();long member=member();
        if(request==null||request.requestId()==null||!request.requestId().matches("[A-Za-z0-9:_-]{1,128}"))throw invalid("workflow_test_request_invalid","request_id 必须为1至128位稳定标识");
        management.require(tenant,request.workflowId(),"manage");
        String fingerprint=TokenHash.sha256Hex(encode(request));
        tests.lockRequest("workflow-test:"+tenant+":"+member+":"+request.requestId());
        var previous=tests.byRequest(tenant,member,request.requestId());
        if(previous!=null) {
            if(!previous.getRequestFingerprint().equals(fingerprint))throw conflict("workflow_test_request_conflict","同一 request_id 不能替换测试内容");
            return receipt(previous);
        }
        requireBudget(request.budget());
        if(request.source()==null)throw invalid("workflow_test_source_invalid","必须指定已保存草稿或发布版本");
        var selected=definitions.lockSource(tenant,request.workflowId(),new WorkflowTestDefinitionService.SourceSelection(request.source().kind(),request.source().draftRevision(),request.source().definitionVersionId()));
        definitions.requireDefinition(selected.definition());
        var chosen=request.bindings()==null?WorkflowInstallationBindings.empty():request.bindings().domain();
        if(!chosen.workflows().isEmpty()||!chosen.agents().isEmpty())throw invalid("workflow_test_binding_rejected","测试不允许子图或Agent绑定");
        requireBindings(tenant,chosen);
        if(!chosen.equals(bindings.currentBindings(tenant,chosen)))throw conflict("workflow_test_binding_drift","候选版本已变化，请刷新后重新选择");
        long id=SnowflakeIds.nextId();String privateWorkflow="workflow-test-"+id;
        String userInput=request.userInput()==null?"":request.userInput();
        if(userInput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw invalid("workflow_test_input_invalid","测试文本不能超过64KiB");
        var parameters=new LinkedHashMap<String,Object>(request.parameters()==null?Map.of():request.parameters());
        if(parameters.containsKey("user_input"))throw invalid("workflow_test_input_invalid","parameters 不能覆盖 user_input");
        var schema=WorkflowDefinitionContract.object(WorkflowDefinitionContract.object(WorkflowDefinitionContract.object(selected.definition().get("meta"),"meta").get("capability"),"capability").get("input_schema"),"input_schema");
        if(WorkflowDefinitionContract.object(schema.getOrDefault("properties",Map.of()),"properties").containsKey("user_input")
                ||!Boolean.FALSE.equals(schema.get("additionalProperties")))parameters.put("user_input",userInput);
        String parameterJson=encode(parameters);
        if(parameterJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw invalid("workflow_test_input_invalid","测试输入不能超过256KiB");
        try { inputs.validate(new TypedPayload("json-schema",encode(schema)),new TypedPayload("application/json",parameterJson)); }
        catch(IllegalArgumentException rejected) {throw invalid("workflow_test_input_invalid","测试输入不符合当前工作流 input_schema");}
        var copy=definitions.createCopy(tenant,privateWorkflow,selected,request.config()==null?Map.of():request.config(),chosen);
        var capability=catalog.getVersion(CapabilityCatalogScope.tenant(tenant),copy.capabilityVersionId());
        var agent=agents.create(tenant,id,new CapabilityRef(CapabilityType.WORKFLOW,capability.definitionId().toString(),capability.id().toString()),
                new CapabilityMount.WorkflowMountConfig(Long.toString(copy.instanceId()),copy.definitionVersionId(),copy.installationRevision()),
                new AgentExecutionConfig(request.budget().maxBusinessModelCalls(),request.budget().maxToolCalls(),request.budget().maxKnowledgeCalls(),request.budget().maxTotalTokens(),request.budget().maxWallTimeSeconds()));
        organization.registerResource("agent",Long.toString(agent.agentId()));organization.registerResource("workflow",Long.toString(copy.containerId()));
        var endUser=users.createTestIdentity(Long.toString(tenant),id);
        var session=sessions.getOrCreate(Long.toString(tenant),endUser.endUserId(),null,agent.agentId(),null,null);
        var manifest=new ArrayList<WorkflowTestRegistry.Binding>();
        for(var binding:chosen.capabilities()) {
            var deployment=binding.toolDeploymentId()==null?null:catalog.getDeployment(CapabilityCatalogScope.tenant(tenant),binding.toolDeploymentId());
            manifest.add(new WorkflowTestRegistry.Binding(tenant,id,binding.slot(),binding.capability().type().name(),Long.parseLong(binding.capability().capabilityId()),
                    Long.parseLong(binding.capability().versionId()),deployment==null?null:deployment.id(),binding.toolDeploymentId(),binding.toolDeploymentRevision(),binding.knowledgeRevision()));
        }
        var test=tests.register(new WorkflowTestRegistry.Registration(id,tenant,member,request.requestId(),fingerprint,
                new WorkflowTestRegistry.Source(selected.kind(),selected.containerId(),request.workflowId(),selected.draftRevision(),
                        selected.definitionVersionId()==null?null:Long.valueOf(selected.definitionVersionId()),selected.hash()),
                new WorkflowTestRegistry.Owned(copy.containerId(),copy.definitionVersionId(),capability.definitionId(),capability.id(),copy.instanceId(),copy.installationRevision(),agent.agentId(),endUser.endUserId(),session.getSessionId()),
                "workflow-test:"+id,userInput.isBlank()?"测试工作流":userInput,parameters,
                new WorkflowTestRegistry.Limits(request.budget().maxBusinessModelCalls(),request.budget().maxToolCalls(),request.budget().maxKnowledgeCalls(),request.budget().maxTotalTokens(),request.budget().maxWallTimeSeconds()),
                Instant.now().plus(Duration.ofMinutes(30))),manifest);
        return receipt(test);
    }

    public Receipt byRequest(String requestId) { var test=tests.byRequest(tenant(),member(),requestId);if(test==null)throw missing();return receipt(test); }
    public Receipt get(String id) {
        var test=require(id);
        if("ready".equals(test.getStatus())&&!test.getExpiresAt().isAfter(Instant.now()))return close(id);
        return "closing".equals(test.getStatus())?settle(test):receipt(test);
    }
    public Receipt close(String id) {
        var test=require(id);if("closed".equals(test.getStatus()))return receipt(test);
        test=tests.beginClosing(test.getTenantId(),test.getId());
        var run=store.load(runRef(test));
        if(run.isPresent()&&run.orElseThrow().phase()!=ExecutionStore.Phase.FINISHED)
            controls.getObject().cancelChat(sessions.require(test.getTenantId().toString(),test.getSessionId()),run.orElseThrow().identity().runRef().runId());
        return settle(test);
    }
    /** 复用现有宿主Worker的有界恢复回调；不启动额外调度器或伪造已取消回执。 */
    public void recover(TenantId tenant,int limit) {
        if(!tenant.value().equals(TenantContext.getTenantId())||limit<1)throw new SecurityException("workflow_test_recovery_tenant_invalid");
        for(var test:tests.expiring(Long.parseLong(tenant.value())).stream().limit(limit).toList()) {
            test=tests.beginClosing(test.getTenantId(),test.getId());
            cancellation.getObject().cancelExpired(new com.skillforge.runtime.api.SessionRef(tenant,test.getSessionId()));
            settle(test);
        }
    }
    /** 查询只推进已经发起的关闭，不重放任何初始输入或外部调用。 */
    private Receipt settle(WorkflowTestRegistry.Snapshot test) {
        if(!"closing".equals(test.getStatus()))return receipt(test);
        var settled=transactions.execute(ignored->{
            tests.lockRequest("workflow-test-close:"+test.getTenantId()+":"+test.getId());
            if(!tests.quiet(test.getTenantId(),test.getSessionId()))return test;
            sessions.closeIsolatedTest(test.getTenantId().toString(),test.getSessionId());
            agents.archive(test.getTenantId(),test.getAgentId());
            users.retireTestIdentity(test.getTenantId(),test.getEndUserId());
            definitions.archiveCopy(test.getTenantId(),test.getContainerId(),test.getInstanceId());
            return tests.finishClosing(test.getTenantId(),test.getId());
        });
        return receipt(Objects.requireNonNull(settled));
    }
    private WorkflowTestRegistry.Snapshot require(String id) {
        long parsed;try { parsed=Long.parseLong(id); }catch(RuntimeException bad){throw missing();}
        var test=tests.find(tenant(),parsed);
        if(test==null)throw missing();isolation.requireMember(test);return test;
    }
    private Receipt receipt(WorkflowTestRegistry.Snapshot test) {
        var run=store.load(runRef(test));String runStatus=run.map(value->value.status().name().toLowerCase(Locale.ROOT)).orElse(null);
        String closeStatus=switch(test.getStatus()){case "closed"->"closed";case "closing"->"cancelling";default->"open";};
        String status="closed".equals(test.getStatus())?"closed":"closing".equals(test.getStatus())?"cancelling":run.map(value->value.phase()==ExecutionStore.Phase.WAITING?"waiting":value.phase()==ExecutionStore.Phase.FINISHED?runStatus:"running").orElse("ready");
        return new Receipt(test.getId().toString(),test.getRequestId(),status,new SourceReceipt(test.getSourceKind(),test.getSourceWorkflowId(),test.getSourceDraftRevision(),
                test.getSourceDefinitionVersionId()==null?null:test.getSourceDefinitionVersionId().toString(),test.getSourceHash()),test.getSessionId(),test.getAgentId().toString(),test.getEndUserId().toString(),
                test.getInitialInputId(),test.getInitialMessage(),test.getExpiresAt(),run.map(value->value.identity().runRef().runId()).orElse(null),runStatus,closeStatus,
                "closing".equals(test.getStatus())?"等待原执行、未知结果或取消清理收敛":null);
    }
    public void requireBindings(long tenant,WorkflowInstallationBindings selected) {
        for(var binding:selected.capabilities()) {
            var descriptor=registry.find(tenant,binding.capability()).orElseThrow(()->invalid("workflow_test_binding_rejected","精确能力版本不可用"));
            if(descriptor instanceof ToolVersion tool) {
                var deployment=registry.findToolDeployment(tenant,binding.toolDeploymentId()).orElseThrow(()->invalid("workflow_test_binding_rejected","精确部署不可用"));
                if(deployment.environment()!=ToolDeployment.Environment.TEST||deployment.providerType()!=ToolDeployment.ProviderType.MCP||!deployment.active()
                        ||!tool.policy().effect().name().equals("READ_ONLY")||!tool.policy().confirmation().name().equals("NEVER")||!tool.metadata().invocationPolicy().modelInvocable()
                        ||!deployment.toolVersion().equals(binding.capability())||!Objects.equals(deployment.revision(),binding.toolDeploymentRevision()))
                    throw invalid("workflow_test_binding_rejected","仅允许精确 TEST MCP READ_ONLY 且无需确认的工具部署");
            } else if(!(descriptor instanceof KnowledgeSourceDescriptor))throw invalid("workflow_test_binding_rejected","测试仅允许工具与知识能力槽");
        }
    }
    private static void requireBudget(Budget value) {
        if(value==null||value.maxBusinessModelCalls()<1||value.maxBusinessModelCalls()>MAXIMUM.maxBusinessModelCalls()||value.maxToolCalls()<1||value.maxToolCalls()>MAXIMUM.maxToolCalls()
                ||value.maxKnowledgeCalls()<1||value.maxKnowledgeCalls()>MAXIMUM.maxKnowledgeCalls()||value.maxTotalTokens()<1||value.maxTotalTokens()>MAXIMUM.maxTotalTokens()
                ||value.maxWallTimeSeconds()<1||value.maxWallTimeSeconds()>MAXIMUM.maxWallTimeSeconds())throw invalid("workflow_test_budget_invalid","预算必须为正数且不能超过测试上限");
    }
    private long member() { var subject=access.currentSubject();if(subject.platform()||!Long.toString(tenant()).equals(subject.tenantId()))throw WorkflowTestIsolation.denied("workflow_test_member_required","测试必须由机构成员发起");return Long.parseLong(subject.memberId()); }
    private static long tenant() { String value=TenantContext.getTenantId();if(value==null)throw WorkflowTestIsolation.denied("workflow_test_tenant_required","缺少可信机构身份");return Long.parseLong(value); }
    private String encode(Object value) { try {return json.writeValueAsString(value);}catch(java.io.IOException bad){throw new IllegalArgumentException("workflow_test_document_invalid",bad);} }
    private static CapabilityIdentity identity(CapabilityRef value) {return new CapabilityIdentity(value.type().name(),value.capabilityId(),value.versionId());}
    private static RunRef runRef(WorkflowTestRegistry.Snapshot test) {return new RunRef(new TenantId(test.getTenantId().toString()),"run:"+test.getInitialInputId());}
    private static SourceReceipt sourceReceipt(WorkflowTestDefinitionService.SourceSnapshot value) {
        return new SourceReceipt(value.kind(),value.workflowId(),value.draftRevision(),value.definitionVersionId(),value.hash());
    }
    private static ApiException missing() {return new ApiException(HttpStatus.NOT_FOUND,"workflow_test_not_found","测试 Session 或源版本不存在",false);}
    private static ApiException invalid(String code,String message) {return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,code,message,false);}
    private static ApiException conflict(String code,String message) {return new ApiException(HttpStatus.CONFLICT,code,message,false);}
}
