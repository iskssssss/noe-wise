package com.skillforge.institution.runtime;

import com.skillforge.core.billing.metering.OpenPlatformRequestMetering;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.ChatRuntimeGateway;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.core.foundation.integration.runtime.identity.TrustedAuthenticationRejectedException;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.WorkflowInteractionView;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeTrustedAccess;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import com.skillforge.institution.media.SessionMediaService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** 已授权产品输入进入唯一新执行受理事务；在线请求只等持久受理，不持执行槽位。 */
public final class AgentRuntimeExecutionGateway implements ChatRuntimeGateway, AgentRuntimeRunControls, com.skillforge.institution.group.GroupExecutionAdmission {
    private final ExecutionStore store;
    private final AgentExecutionProgram program;
    private final AgentRuntimeExecutionWorker worker;
    private final AgentRuntimeHostProvisioner provisioner;
    private final AgentRuntimeTrustedAccess access;
    private final JdbcIdentityPolicyPort identities;
    private final ServicePrincipalMapper principals;
    private final AgentRunBindings bindings;
    private final ChatInputReferenceResolver references;
    private final MemoryService memory;
    private final AgentExecutionCodec codec;
    private final ObjectMapper json;
    private final AgentRuntimePayloadValidation payloads;
    private final AgentRuntimeExecutionProjection history;
    private final SessionMediaService media;
    private final PublicOutputGuard publicGuard;
    private final AgentRuntimeExecutionCatalog catalog;
    private final AgentRuntimeExecutionSteering steering;
    private final WorkflowInteractionView workflowInteractions;
    private final AgentRuntimeWorkflowRecoveryWorker workflows;
    private final com.skillforge.core.chat.session.SessionService productSessions;
    private final org.springframework.beans.factory.ObjectProvider<AgentRuntimeGoalDriver> goals;
    private final SessionStorePort sessionStore;
    private final java.time.Clock clock;
    private final WorkflowTestRuntimePolicy tests;
    private enum SubmissionSource { USER, GOAL_ROUND }

    public AgentRuntimeExecutionGateway(ExecutionStore store, AgentExecutionProgram program, AgentRuntimeExecutionWorker worker,
            AgentRuntimeHostProvisioner provisioner, AgentRuntimeTrustedAccess access, JdbcIdentityPolicyPort identities,
            ServicePrincipalMapper principals, AgentRunBindings bindings, ChatInputReferenceResolver references,
            MemoryService memory, AgentExecutionCodec codec, ObjectMapper json, AgentRuntimePayloadValidation payloads,
            AgentRuntimeExecutionProjection history, SessionMediaService media, PublicOutputGuard publicGuard,
            AgentRuntimeExecutionCatalog catalog, AgentRuntimeExecutionSteering steering,
            WorkflowInteractionView workflowInteractions, AgentRuntimeWorkflowRecoveryWorker workflows,
            com.skillforge.core.chat.session.SessionService productSessions,
            org.springframework.beans.factory.ObjectProvider<AgentRuntimeGoalDriver> goals,SessionStorePort sessionStore,java.time.Clock clock,WorkflowTestRuntimePolicy tests,OpenPlatformRequestMetering requestMetering) {
        this.requestMetering=Objects.requireNonNull(requestMetering);
        this.store = store; this.program = program; this.worker = worker; this.provisioner = provisioner;
        this.access = access; this.identities = identities; this.principals = principals; this.bindings = bindings;
        this.references = references; this.memory = memory; this.codec = codec; this.json = json;
        this.payloads = Objects.requireNonNull(payloads);
        this.history = Objects.requireNonNull(history); this.media = Objects.requireNonNull(media);
        this.publicGuard = Objects.requireNonNull(publicGuard);
        this.catalog = Objects.requireNonNull(catalog);
        this.steering = Objects.requireNonNull(steering);
        this.workflowInteractions = Objects.requireNonNull(workflowInteractions);
        this.workflows = Objects.requireNonNull(workflows);
        this.productSessions = Objects.requireNonNull(productSessions);
        this.goals=Objects.requireNonNull(goals);
        this.sessionStore=Objects.requireNonNull(sessionStore);this.clock=Objects.requireNonNull(clock);this.tests=Objects.requireNonNull(tests);
    }

    private final OpenPlatformRequestMetering requestMetering;

    @Override public ChatResult submit(Submission submission) {
        return submit(submission,null,SubmissionSource.USER);
    }

    /** 仅宿主Goal回调可进入；来源是内部常量，不读取输入DTO中的字段。 */
    ChatResult submitGoalRound(Submission submission){return submit(submission,null,SubmissionSource.GOAL_ROUND);}

    private ChatResult submit(Submission submission, com.skillforge.common.context.ServicePrincipalContext.Identity external) {
        return submit(submission,external,SubmissionSource.USER);
    }

    private ChatResult submit(Submission submission, com.skillforge.common.context.ServicePrincipalContext.Identity external,SubmissionSource source) {
        return submit(submission,external,source,null);
    }

    private ChatResult submit(Submission submission, com.skillforge.common.context.ServicePrincipalContext.Identity external, SubmissionSource source, WorkflowInvocation invocation) {
        return submit(submission,external,source,invocation,false);
    }

    private ChatResult submit(Submission submission, com.skillforge.common.context.ServicePrincipalContext.Identity external,
            SubmissionSource source,WorkflowInvocation invocation,boolean meterMessage) {
        Objects.requireNonNull(submission);
        var session = Objects.requireNonNull(submission.session());
        if(session.getGroupBindingId()!=null)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"group_execution_not_available","群执行尚未开放",false);
        var tenant = new TenantId(session.getTenantId().toString());
        if (!tenant.value().equals(TenantContext.getTenantId()) || submission.endUserId() == null
                || !submission.endUserId().equals(session.getEndUserId())) throw new SecurityException("execution_session_owner_mismatch");
        var owner = SessionOwner.user(new SessionRef(tenant, session.getSessionId()),
                session.getTeamId() == null || session.getTeamId().isBlank() ? SessionTarget.agent(new AgentId(submission.agentId()))
                        : SessionTarget.team(session.getTeamId()), new EndUserId(submission.endUserId()));
        String inputId = context(submission, "input_id", submission.request().clientMessageId());
        if (inputId == null || inputId.isBlank()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "缺少 input_id");
        var response = submission.request().hitlResponse() == null || submission.request().hitlResponse().isEmpty()
                ? Optional.<AgentRuntimeExecutionResponses.Reply>empty()
                : Optional.of(AgentRuntimeExecutionResponses.decode(tenant, submission.request().hitlResponse(), payloads));
        tests.input(submission,inputId,response.map(AgentRuntimeExecutionResponses.Reply::run),source==SubmissionSource.GOAL_ROUND);
        var runRef = response.map(AgentRuntimeExecutionResponses.Reply::run).orElseGet(() -> initialMessageRun(tenant, inputId));
        var previous = store.load(runRef);
        if (previous.isPresent() && !previous.orElseThrow().identity().owner().equals(owner))
            throw new SecurityException("execution_input_owner_mismatch");
        if (response.isPresent() && previous.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "execution_run_missing");
        var actor = previous.map(value -> value.identity().agentId()).orElseGet(() -> bindings.currentPrimaryAgent(owner));
        var trace = previous.map(value -> value.identity().trace()).orElseGet(() -> new TraceEnvelope(
                requireTrace(), "span:" + UUID.randomUUID(), new InputId(context(submission, "root_input_id", inputId)), Optional.empty()));
        var entryService = external == null ? null : integration(external,session);
        var verified = external == null ? verify(owner, actor, trace, new InputId(inputId))
                : verify(owner,actor,trace,new InputId(inputId),entryService,"open-platform");
        if (response.isPresent()) {
            var reply = response.orElseThrow();
            var saved = switch (reply) {
                case AgentRuntimeExecutionResponses.ToolReply tool -> resume(previous.orElseThrow(), verified, new InputId(inputId), tool.waiting(), tool.revision(), tool.responses());
                case AgentRuntimeExecutionResponses.HumanReply human -> resumeHuman(previous.orElseThrow(), verified, new InputId(inputId), human, submission.request().clientMessageId());
                case AgentRuntimeExecutionResponses.WorkflowReply workflow -> resumeWorkflow(previous.orElseThrow(), verified, new InputId(inputId), workflow);
            };
            if(saved.accepted()&&source==SubmissionSource.USER&&tests.session(owner.sessionRef()).isEmpty())goals.getObject().yieldToUser(owner);
            return result(saved.run(), inputId, !saved.accepted());
        }
        var inputRef = new ResultRef("execution-input:" + runRef.runId());
        var oldInput = previous.map(ignored -> store.material(tenant, inputRef).orElseThrow(() -> new IllegalStateException("execution_input_missing")));
        var requested = references.request(submission.request().content(), submission.request().context(), submission.request().references());
        var frozen = references.freeze(session, requested, oldInput.map(value -> codec.frozenInput(value.content())));
        if (previous.isPresent()) {
            if(!codec.frozenInput(oldInput.orElseThrow().content()).equals(frozen))
                throw new ApiException(HttpStatus.CONFLICT,"execution_input_changed","同一输入标识不能替换原输入",false);
            if(source==SubmissionSource.USER)worker.wake(runRef);
            return result(previous.orElseThrow(), inputId, true);
        }
        var identity = new RunIdentity(owner, actor, new InputId(inputId), runRef, trace, verified.verificationRef(),
                new RunRequester.EndUser(owner.requireUser()),Optional.empty(),new RunOriginRef(tenant,"execution-input:"+runRef.runId()));
        var binding = bindings.freezePrimary(identity, frozen, entryService);
        var frozenCatalog = invocation == null ? catalog.freeze(identity, binding, java.time.Instant.now(), frozen)
                : catalog.freezeWorkflow(identity, binding, invocation);
        var preparation = provisioner.prepareExecutionRun(tenant.value(), actor.value(), runRef.runId(), binding, catalog.read(frozenCatalog, identity));
        var messages = new ArrayList<ModelMessage>();
        for (var message : history(session, inputId)) {
            if (!List.of("user", "assistant").contains(message.role())) continue;
            messages.add(new ModelMessage("history:" + message.messageId(), "user".equals(message.role()) ? ModelRole.USER : ModelRole.ASSISTANT,
                    historyContent(session, message)));
        }
        messages.add(new ModelMessage("input:" + inputId, ModelRole.USER, inputContent(owner.sessionRef(), frozen)));
        var input = new ExecutionStore.Material(inputRef, codec.encodeInput(messages, frozen));
        worker.observeReady(runRef);
        var admissionEvents = new ArrayList<>(List.of(AgentExecutionProgram.admissionEvent(identity),
                com.skillforge.runtime.session.SessionCollaboration.admissionMode(identity, catalog.read(frozenCatalog, identity).planSelection())));
        if (source == SubmissionSource.USER) {
            var request = submission.request();
            admissionEvents.add(userEvent(identity, new InputId(inputId), request.message(), request.clientMessageId(), request.content(), Optional.of(frozen)));
        }
        var admission = new ExecutionStore.Admission(verified, identity, digest(frozen.canonicalJson()), preparation.profile(),
                input, program.initialContext(inputRef), preparation.budget(), admissionEvents, List.of(frozenCatalog));
        final ExecutionStore.AdmissionResult admitted;
        if(meterMessage) {
            if(!(store instanceof com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore jdbc))throw new IllegalStateException("open_platform_message_transactional_store_required");
            admitted=jdbc.admit(admission,(sql,command)->{
                if(tests.session(owner.sessionRef()).isPresent())tests.admission(sql,command);
                // 用户原文与 Run 一起提交；失败不留下孤立历史，重放不重复归档。
                var request = submission.request();
                memory.appendBorrowed(sql, tenant.value(), session.getSessionId(), session.getEndUserId(),
                        "user", request.message(), Map.of("input_id", inputId, "root_input_id", trace.rootInputId().value(),
                                "trace_id", trace.traceId(), "span_id", trace.spanId(),
                                "content_parts", request.content(), "content_fingerprint", ChatContent.fingerprint(request.content())),
                        request.clientMessageId());
                requestMetering.record(sql,command.run(),external.integrationId());
            });
        } else if(tests.session(owner.sessionRef()).isPresent()) {
            if(!(store instanceof com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore jdbc))throw new IllegalStateException("workflow_test_transactional_store_required");
            admitted=jdbc.admit(admission,tests::admission);
        } else admitted=store.admit(admission);
        if(admitted.created()&&source==SubmissionSource.USER&&tests.session(owner.sessionRef()).isEmpty())goals.getObject().yieldToUser(owner);
        references.accepted(session, inputId, frozen);
        if(source==SubmissionSource.USER)worker.wake(runRef);
        return result(admitted.run(), inputId, !admitted.created());
    }

    /**
     * 群领域只传原持久trigger候选；候选当前资格、队首、slot和typed Run在同一Store事务提交。
     * 不创建群主Agent或个人Session，模型材料只读取原sourceSequence截止的已提交来源。
     */
    @Override public com.skillforge.institution.group.GroupExecutionAdmission.Admitted admit(
            com.skillforge.institution.group.GroupExecutionAdmission.Candidate candidate,
            com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore.AdmissionGuard guard) {
        Objects.requireNonNull(candidate);Objects.requireNonNull(guard);
        String previous=TenantContext.getTenantId();
        if(previous!=null&&!previous.equals(candidate.tenantId()))throw new SecurityException("group_admission_tenant_mismatch");
        var previousPrincipal=com.skillforge.common.context.ServicePrincipalContext.get();
        try {
            TenantContext.setTenantId(candidate.tenantId());
            var tenant=new TenantId(candidate.tenantId());
            var principal=groupPrincipal(candidate.tenantId(),candidate.integrationId(),candidate.principalId(),"groups.triggers.write");
            var row=principals.selectOne(new QueryWrapper<ServicePrincipalDO>().eq("tenant_id",Long.parseLong(candidate.tenantId()))
                    .eq("principal_id",principal.principalId()).eq("integration_id",principal.integrationId()).eq("status","active"));
            com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity(
                    principal.principalId(),candidate.tenantId(),principal.integrationId(),principal.credentialVersion(),Set.copyOf(row.getScopes())));
            var owner=groupOwner(tenant,candidate.groupBindingId(),candidate.sessionId());
            var actual=new AgentId(Long.toString(candidate.agentId()));
            var inputId=new InputId("group-trigger:"+candidate.triggerId());var runRef=new RunRef(tenant,"run:"+inputId.value());
            var old=store.load(runRef);
            if(old.isPresent()) {
                var value=old.orElseThrow().identity();
                if(!value.owner().equals(owner)||!value.agentId().equals(actual)
                        ||!value.requester().equals(new RunRequester.EndUser(new EndUserId(candidate.requesterEndUserId())))
                        ||!value.sourceMessageRef().equals(Optional.of(new SourceMessageRef(owner.sessionRef(),candidate.sourceMessageId())))
                        ||!value.originRef().equals(new RunOriginRef(tenant,"group-trigger:"+candidate.triggerId())))
                    throw new SecurityException("group_trigger_run_identity_changed");
                worker.wake(runRef);return new com.skillforge.institution.group.GroupExecutionAdmission.Admitted(inputId.value(),runRef.runId());
            }
            var trace=new TraceEnvelope("group-trigger:"+candidate.triggerId(),"span:"+candidate.triggerId(),inputId,Optional.empty());
            final VerifiedIdentity verified;
            try {verified=verify(owner,actual,trace,inputId,principal,"open-platform");}
            catch(ApiException denied) {
                if(denied.getStatus()==HttpStatus.FORBIDDEN) {
                    var current=groupPrincipal(candidate.tenantId(),candidate.integrationId(),candidate.principalId(),"groups.triggers.write");
                    if(current.credentialVersion()!=principal.credentialVersion())
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"group_runtime_credential_rotated","群服务凭据已轮换，等待重新核验",true);
                }
                throw denied;
            }
            var source=candidate.history().stream().filter(value->value.sourceMessageId().equals(candidate.sourceMessageId())
                    && value.sourceSequence()==candidate.sourceSequence()).findFirst().orElseThrow(()->new SecurityException("group_trigger_source_missing"));
            var frozen=references.freezeGroup(owner.sessionRef(),groupContent(source.contentJson()));
            var identity=new RunIdentity(owner,actual,inputId,runRef,trace,verified.verificationRef(),
                    new RunRequester.EndUser(new EndUserId(candidate.requesterEndUserId())),
                    Optional.of(new SourceMessageRef(owner.sessionRef(),candidate.sourceMessageId())),
                    new RunOriginRef(tenant,"group-trigger:"+candidate.triggerId()));
            var binding=bindings.freezePrimary(identity,frozen,principal);
            var frozenCatalog=catalog.freeze(identity,binding,clock.instant(),frozen);
            var preparation=provisioner.prepareExecutionRun(tenant.value(),actual.value(),runRef.runId(),binding,catalog.read(frozenCatalog,identity));
            var messages=new ArrayList<ModelMessage>();
            long sequence=0;
            for(var message:candidate.history()) {
                if(message.sourceSequence()<=sequence||message.sourceSequence()>candidate.sourceSequence())
                    throw new SecurityException("group_context_source_sequence_invalid");
                sequence=message.sourceSequence();
                var content=new ArrayList<ModelContent>();
                content.add(new ModelContent.Text("群消息来源："+message.authorJson()+"；消息："+message.sourceMessageId()));
                content.addAll(inputContent(owner.sessionRef(),references.freezeGroup(owner.sessionRef(),groupContent(message.contentJson()))));
                messages.add(new ModelMessage("group-source:"+message.sourceMessageId(),ModelRole.USER,content));
            }
            var inputRef=new ResultRef("execution-input:"+runRef.runId());
            var input=new ExecutionStore.Material(inputRef,codec.encodeInput(messages,frozen));
            if(!(store instanceof com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore jdbc))
                throw new IllegalStateException("group_requires_transactional_execution_store");
            worker.observeReady(runRef);
            var admitted=jdbc.admit(new ExecutionStore.Admission(verified,identity,digest(frozen.canonicalJson()),preparation.profile(),
                    input,program.initialContext(inputRef),preparation.budget(),List.of(AgentExecutionProgram.admissionEvent(identity)),List.of(frozenCatalog)),(sql,admission)->{
                        guard.require(sql,admission);bindings.freezeGroupSource(sql,candidate,identity,binding);
                        // 最终资格在预算根、队首与slot锁后读取；冻结材料不能替代本次原链和当前Agent授权。
                        var permission=new IdentityPolicyPort.AuthorizationRequest(verified,"session.execute",
                                com.skillforge.runtime.adapter.jdbc.identity.SessionPolicyResource.run(identity,Optional.empty()),trace.traceId(),trace.spanId());
                        var decision=identities.authorize(sql,permission);
                        if(decision instanceof IdentityPolicyPort.AuthorizationResult.Denied && identities.groupCredentialRotated(sql,permission))
                            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"group_runtime_credential_rotated","群服务凭据已轮换，等待重新核验",true);
                        if(decision instanceof IdentityPolicyPort.AuthorizationResult.Denied)
                            throw new ApiException(HttpStatus.FORBIDDEN,"group_runtime_authorization_denied","群当前执行资格已失效",false);
                        if(!(decision instanceof IdentityPolicyPort.AuthorizationResult.Allowed))
                            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"group_runtime_authorization_unavailable","群当前执行资格暂不可核验",true);
                    });
            worker.wake(admitted.run().identity().runRef());
            return new com.skillforge.institution.group.GroupExecutionAdmission.Admitted(inputId.value(),runRef.runId());
        } catch(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException revoked) {
            // 此类型只由已查询的确定性撤权路径签发；数据库/配置/身份不可用仍按技术故障保留queued。
            throw new ApiException(HttpStatus.FORBIDDEN,revoked.reasonCode(),"群原执行授权已撤回",false);
        } finally {
            if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);
            if(previousPrincipal==null)com.skillforge.common.context.ServicePrincipalContext.clear();else com.skillforge.common.context.ServicePrincipalContext.set(previousPrincipal);
        }
    }

    /**
     * 原Run的安全公开状态；等待卡只来自现有正式公开投影。
     * @param groupBindingId 原群生命周期，十进制字符串
     * @param sessionId 唯一共享产品Session
     * @param runId 精确原Run，不能用Session代替
     * @param agentId 实际执行Agent，十进制字符串
     * @param status 现有RunStatus的小写编码
     * @param phase 唯一ExecutionStore的当前阶段
     * @param revision 原Run当前CAS修订，十进制字符串
     * @param pendingInteractions 当前可公开等待卡；空集合表示没有可回复等待
     */
    @Schema(name="GroupRunDetail")
    public record GroupRunDetail(
            @Schema(requiredMode=REQUIRED) String groupBindingId,
            @Schema(requiredMode=REQUIRED) String sessionId,
            @Schema(requiredMode=REQUIRED) String runId,
            @Schema(requiredMode=REQUIRED) String agentId,
            @Schema(requiredMode=REQUIRED) String status,
            @Schema(requiredMode=REQUIRED) String phase,
            @Schema(requiredMode=REQUIRED) String revision,
            @Schema(requiredMode=REQUIRED) List<Map<String,Object>> pendingInteractions) { }

    /**
     * 群精确回复的持久受理回执；不把回复入队报告成模型执行完成。
     * @param groupBindingId 原群生命周期
     * @param sessionId 原共享Session
     * @param runId 继续原Run，不新建执行
     * @param inputId 本次回复的稳定幂等身份
     * @param status 原Run的当前状态
     * @param replayed 是否返回同输入的原持久回执
     */
    @Schema(name="GroupRunResponse")
    public record GroupRunResponse(
            @Schema(requiredMode=REQUIRED) String groupBindingId,
            @Schema(requiredMode=REQUIRED) String sessionId,
            @Schema(requiredMode=REQUIRED) String runId,
            @Schema(requiredMode=REQUIRED) String inputId,
            @Schema(requiredMode=REQUIRED) String status,
            @Schema(requiredMode=REQUIRED) boolean replayed) { }

    /** 已认证群领域控制器先核当前成员；此处继续核当前固定服务身份和原Run归属。 */
    public GroupRunDetail groupDetail(String tenantId,String integrationId,String groupBindingId,String sessionId,String runId) {
        var run=requireGroupRun(tenantId,integrationId,groupBindingId,sessionId,runId);
        var input=new InputId("group-read:"+UUID.randomUUID());
        var actor=verify(run.identity().owner(),run.identity().agentId(),run.identity().trace(),input,currentGroupPrincipal(tenantId,integrationId,"groups.runs.read"),"open-platform");
        requireReadAuthorization(actor,run.identity().trace(),"runtime.run.query",runId,input);
        var pending=history.readSource(run.identity().owner(),run.identity().runRef()).pendingInteractions().stream()
                .filter(value->runId.equals(value.get("execution_id"))).toList();
        return new GroupRunDetail(groupBindingId,sessionId,runId,run.identity().agentId().value(),status(run),run.phase().name(),Long.toString(run.revision()),pending);
    }

    /** 回复显式绑定原Run及等待代次，当前操作者与原请求者分开；Store事务再次核当前群资格。 */
    public GroupRunResponse groupRespond(String tenantId,String integrationId,String groupBindingId,String sessionId,String runId,
            String inputId,long responderEndUserId,Map<String,Object> hitlResponse) {
        var run=requireGroupRun(tenantId,integrationId,groupBindingId,sessionId,runId);var input=new InputId(inputId);
        if(responderEndUserId<=0)throw new IllegalArgumentException("group_responder_invalid");
        var principal=currentGroupPrincipal(tenantId,integrationId,"groups.runs.respond");
        var verified=verify(run.identity().owner(),run.identity().agentId(),run.identity().trace(),input,principal,"open-platform","group-respond:"+responderEndUserId);
        var reply=AgentRuntimeExecutionResponses.decode(run.identity().runRef().tenantId(),hitlResponse,payloads);
        if(!reply.run().equals(run.identity().runRef()))throw new SecurityException("group_response_run_mismatch");
        var saved=switch(reply) {
            case AgentRuntimeExecutionResponses.ToolReply tool -> resume(run,verified,input,tool.waiting(),tool.revision(),tool.responses());
            case AgentRuntimeExecutionResponses.HumanReply human -> resumeHuman(run,verified,input,human);
            case AgentRuntimeExecutionResponses.WorkflowReply workflow -> resumeWorkflow(run,verified,input,workflow);
        };
        return new GroupRunResponse(groupBindingId,sessionId,runId,inputId,status(saved.run()),!saved.accepted());
    }

    /**
     * 定向控制原Run，不进入触发队列；当前操作者在Store控制事务复验群成员及本人/manager资格。
     * 显式取消沿既有正式控制收尾，保留原Attempt和UNKNOWN，不表示外部副作用已撤销。
     */
    public GroupRunResponse groupCommand(String tenantId,String integrationId,String groupBindingId,String sessionId,String runId,
            String inputId,long operatorEndUserId,String action,String text) {
        var run=requireGroupRun(tenantId,integrationId,groupBindingId,sessionId,runId);var input=new InputId(inputId);
        if(operatorEndUserId<=0 || !("cancel_execution".equals(action)||"steer".equals(action))
                || "cancel_execution".equals(action)&&text!=null || "steer".equals(action)&&(text==null||text.isBlank()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"group_run_command_invalid","群控制动作或正文无效",false);
        var verified=verify(run.identity().owner(),run.identity().agentId(),run.identity().trace(),input,
                currentGroupPrincipal(tenantId,integrationId,"groups.runs.control"),"open-platform","group-control:"+operatorEndUserId);
        if("cancel_execution".equals(action)) {
            var saved=cancel(run,verified,input);
            return new GroupRunResponse(groupBindingId,sessionId,runId,inputId,status(saved),run.cancelled()||run.phase()==ExecutionStore.Phase.FINISHED);
        }
        final ExecutionStore.InputOffer offered;
        try {offered=steering.offer(run,verified,input,text);}
        catch(ExecutionStore.Rejection rejected){throw rejection(rejected);}
        catch(IllegalArgumentException invalid){throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"补充输入无效或超过输入限制",invalid);}
        return new GroupRunResponse(groupBindingId,sessionId,runId,inputId,status(offered.run()),!offered.created());
    }

    /** 原Run只接受完全相同的群生命周期和产品Session，不能借群ID查询子Run私有Session。 */
    private ExecutionStore.Run requireGroupRun(String tenantId,String integrationId,String group,String session,String runId) {
        if(!tenantId.equals(TenantContext.getTenantId()))throw new SecurityException("group_run_tenant_mismatch");
        var current=com.skillforge.common.context.ServicePrincipalContext.get();
        if(current==null||!tenantId.equals(current.tenantId())||!integrationId.equals(current.integrationId()))throw new SecurityException("group_current_principal_missing");
        var owner=groupOwner(new TenantId(tenantId),group,session);
        var run=store.load(new RunRef(owner.sessionRef().tenantId(),runId)).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"execution_run_missing"));
        if(!run.identity().owner().equals(owner))throw new SecurityException("group_run_owner_mismatch");
        return run;
    }

    /** GROUP主体与GROUP目标成对构造，实际Agent只能来自原trigger或原Run。 */
    private static SessionOwner groupOwner(TenantId tenant,String group,String session) {
        var binding=new GroupBindingRef(tenant,group);
        return new SessionOwner(new SessionRef(tenant,session),SessionSubject.group(binding),SessionTarget.group(binding));
    }

    /** 当前HTTP认证事实再次与数据库核对，不能将候选字段或上下文scope当成当前授权。 */
    private PrincipalRef.ServicePrincipal currentGroupPrincipal(String tenant,String integration,String scope) {
        var current=com.skillforge.common.context.ServicePrincipalContext.get();
        if(current==null||!tenant.equals(current.tenantId())||!integration.equals(current.integrationId()))throw new SecurityException("group_current_principal_missing");
        return groupPrincipal(tenant,integration,current.principalId(),current.credentialVersion(),scope);
    }

    /** 后台候选的原服务身份需要仍是当前固定域和版本，读取真实scope后才能签发Runtime认证。 */
    private PrincipalRef.ServicePrincipal groupPrincipal(String tenant,String integration,String principal,long version,String scope) {
        var current=groupPrincipal(tenant,integration,principal,scope);
        if(current.credentialVersion()!=version)throw new ApiException(HttpStatus.UNAUTHORIZED,"group_credential_changed","当前请求凭据已失效",false);
        return current;
    }

    /** 原trigger的凭据版本只作审计；耐久队列按稳定principal重新读取当前真实版本。 */
    private PrincipalRef.ServicePrincipal groupPrincipal(String tenant,String integration,String principal,String scope) {
        var row=principals.selectOne(new QueryWrapper<ServicePrincipalDO>().eq("tenant_id",Long.parseLong(tenant)).eq("principal_id",principal)
                .eq("integration_id",integration).eq("status","active"));
        if(row==null||row.getScopes()==null||!row.getScopes().contains(scope))throw new ApiException(HttpStatus.FORBIDDEN,"group_principal_authorization_revoked","群原服务身份或用途权限已失效",false);
        return new PrincipalRef.ServicePrincipal(new TenantId(tenant),principal,integration,row.getCredentialVersion());
    }

    /** 已提交群正文仍通过正式ChatContent封闭类型解析，不将作者JSON当作执行指令。 */
    private List<ChatContent> groupContent(String content) {
        try{return json.readValue(content,new com.fasterxml.jackson.core.type.TypeReference<List<ChatContent>>(){});}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("group_committed_content_invalid",failure);}
    }

    /** 仅Goal驱动在原create/accept日志提交后通知，实际派发仍核当前Goal和Store写权。 */
    void wakeGoalRound(RunRef run) { worker.wake(run); }

    /** 当前认证由可信Chat主体重新验证；长等待后不复用原已过期认证，也不改原因果身份。 */
    public VerifiedIdentity verify(ExecutionStore.Run run, InputId input) {
        return verify(run.identity().owner(), run.identity().agentId(), run.identity().trace(), input);
    }

    /** 精确响应只经Store同事务受理；稳定输入重复由唯一约束返回原回执。 */
    public ExecutionStore.ResumeResult resume(ExecutionStore.Run run, VerifiedIdentity actor, InputId input,
            ExecutionStore.WaitRef wait, long revision, java.util.Map<String, ToolPortal.ToolInputResponse> responses) {
        var payload = codec.encodeToolResponses(responses);
        var methods = new java.util.TreeMap<String, ToolPortal.ToolInputMethod>();
        responses.forEach((key, value) -> methods.put(key, value.method()));
        return resume(run, actor, input, wait, revision, payload, methods, Optional.empty());
    }

    /** AskUser的本地响应只受理文本，不调用ToolPortal或占用外部预算。 */
    private ExecutionStore.ResumeResult resumeHuman(ExecutionStore.Run run, VerifiedIdentity actor, InputId input,
            AgentRuntimeExecutionResponses.HumanReply reply) {
        return resumeHuman(run, actor, input, reply, input.value());
    }

    private ExecutionStore.ResumeResult resumeHuman(ExecutionStore.Run run, VerifiedIdentity actor, InputId input,
            AgentRuntimeExecutionResponses.HumanReply reply, String messageId) {
        final TypedPayload response;
        try { response = codec.encodeHumanResponse(reply.text()); }
        catch (IllegalArgumentException invalid) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "回复文本无效或超过输入限制", invalid); }
        return resume(run, actor, input, reply.waiting(), reply.revision(), response, java.util.Map.of(), Optional.of(userEvent(run.identity(), input, reply.text(), messageId, List.of(), Optional.empty())));
    }

    /** 团队控件绑定准确原子Run与人类等待，不通过产品Session伪装成父Run的回复。 */
    public ExecutionStore.ResumeResult resumeHuman(ExecutionStore.Run run, VerifiedIdentity actor, InputId input,
            ExecutionStore.WaitRef waiting, long revision, String text) {
        return resumeHuman(run, actor, input, new AgentRuntimeExecutionResponses.HumanReply(run.identity().runRef(), revision, waiting, text));
    }

    /** Workflow Human只交给SDK专有接受事务；普通Human/Tool恢复不读取或补齐SDK身份。 */
    private ExecutionStore.ResumeResult resumeWorkflow(ExecutionStore.Run run, VerifiedIdentity actor, InputId input,
            AgentRuntimeExecutionResponses.WorkflowReply reply) {
        var accepted = store.response(run.identity().runRef(), input);
        var waiting = accepted.or(() -> store.waiting(run.identity().runRef(), reply.waiting()))
                .orElseThrow(AgentRuntimeExecutionGateway::workflowConflict);
        requireWorkflowIdentity(run, waiting, reply);
        if (accepted.isPresent()) {
            var original = waiting.acceptedResponse().orElseThrow(AgentRuntimeExecutionGateway::workflowConflict);
            if (!original.input().equals(input)) throw workflowConflict();
        } else {
            if (run.cancelled() || run.phase() != ExecutionStore.Phase.WAITING || run.revision() != reply.revision()
                    || !run.waiting().equals(Optional.of(reply.waiting()))) throw workflowConflict();
            var pending = workflowInteractions.find(new WorkflowPortal.WorkflowQuery(actor, reply.execution()))
                    .orElseThrow(AgentRuntimeExecutionGateway::workflowConflict);
            if (!pending.owner().equals(run.identity().owner()) || !pending.runRef().equals(reply.run())
                    || !pending.executionRef().equals(reply.execution()) || pending.expectedRevision() != reply.executionRevision()
                    || !pending.suspendRef().equals(reply.suspendRef()) || !pending.actionKey().equals(reply.actionKey())) throw workflowConflict();
        }
        try { return workflows.respond(run, actor, input, reply.waiting(), reply.revision(), reply.response()); }
        catch (ExecutionStore.Rejection rejected) { throw rejection(rejected); }
    }

    /** 准确Run、Action代次与SDK四字段必须对应同一持久Workflow等待。 */
    static void requireWorkflowIdentity(ExecutionStore.Run run, ExecutionStore.Waiting waiting,
            AgentRuntimeExecutionResponses.WorkflowReply reply) {
        if (!run.identity().runRef().equals(reply.run()) || !waiting.run().equals(reply.run())
                || !waiting.reference().equals(reply.waiting()) || waiting.kind() != ExecutionStore.WaitKind.WORKFLOW
                || !(waiting.internal().orElse(null) instanceof ExecutionStore.WorkflowWait exact)
                || !exact.execution().equals(reply.execution()) || !exact.suspendRef().equals(reply.suspendRef())
                || !exact.actionKey().equals(reply.actionKey()) || exact.executionRevision() != reply.executionRevision()) throw workflowConflict();
    }

    private static ApiException workflowConflict() {
        return new ApiException(HttpStatus.CONFLICT, "workflow_human_wait_changed", "工作流等待已变化，请重新读取会话", false);
    }

    /** 两种等待共用同一持久输入去重与当前授权，已受理响应事件先于通知提交。 */
    private ExecutionStore.ResumeResult resume(ExecutionStore.Run run, VerifiedIdentity actor, InputId input,
            ExecutionStore.WaitRef wait, long revision, TypedPayload payload, java.util.Map<String, ToolPortal.ToolInputMethod> methods,
            Optional<ExecutionStore.Event> publicUserMessage) {
        var response = new ExecutionStore.Material(new ResultRef("execution-response:" + run.identity().runRef().runId() + ":" + input.value()), payload);
        var events = new ArrayList<ExecutionStore.Event>(); events.add(AgentExecutionProgram.resolved(run, input, wait));
        publicUserMessage.ifPresent(events::add);
        final ExecutionStore.ResumeResult accepted;
        try {
            accepted = store.resume(new ExecutionStore.Resume(actor, run.identity().runRef(), wait, revision, input,
                    response, digest(payload.canonicalJson()), methods, events));
        } catch (ExecutionStore.Rejection rejected) { throw rejection(rejected); }
        worker.wake(run.identity().runRef());
        return accepted;
    }

    /** 过期/关闭恢复只使用正式Worker系统身份取消原Run，保留原请求者和UNKNOWN。 */
    public void cancelIsolatedTest(SessionRef session,String workerId) {
        var test=tests.session(session).orElseThrow(()->new SecurityException("workflow_test_source_missing"));
        if(!"closing".equals(test.getStatus())&&test.getExpiresAt().isAfter(clock.instant()))throw new SecurityException("workflow_test_close_not_requested");
        var reference=new RunRef(session.tenantId(),"run:"+test.getInitialInputId());var found=store.load(reference);
        if(found.isEmpty()||found.orElseThrow().phase()==ExecutionStore.Phase.FINISHED)return;
        var run=found.orElseThrow();
        if(!run.identity().owner().sessionRef().equals(session)||!run.identity().agentId().value().equals(test.getAgentId().toString()))throw new SecurityException("workflow_test_close_owner_mismatch");
        provisioner.provisionAccess(session.tenantId().value());var input=new InputId("workflow-test-close:"+test.getId());
        var authentication=identities.registerTrusted(new PrincipalRef.SystemPrincipal(session.tenantId(),workerId),run.identity().owner(),"chat","workflow-test-close",java.time.Duration.ofMinutes(5));
        var verified=identities.verify(new IdentityPolicyPort.IdentityRequest(authentication,run.identity().owner(),"chat","workflow-test-close",new RequestId(input.value()),Optional.of(input),run.identity().trace().traceId(),run.identity().trace().spanId()));
        if(!(verified instanceof IdentityPolicyPort.IdentityResult.Verified value))throw new SecurityException("workflow_test_close_identity_rejected");
        cancel(run,value.identity(),input);
    }

    /** 产品取消与页面连接无关；持久取消先生效，再同步通知本进程活动控制。 */
    @Override public ChatResult cancelChat(SessionView session, String runId) {
        var run = requireOwned(session, runId);
        var input = new InputId("chat-cancel:" + UUID.randomUUID());
        var cancelled = cancel(run, verify(run, input), input);
        return new ChatResult(cancelled.cancelled() ? "cancelled" : status(cancelled), session.getSessionId(), runId, null, null, null);
    }

    /** 可信团队控制已经证明原child绑定；仍由Store核当前owner和取消权限。 */
    public ExecutionStore.Run cancel(ExecutionStore.Run run, VerifiedIdentity actor, InputId input) {
        final ExecutionStore.Run cancelled;
        try { cancelled = store.cancel(new ExecutionStore.Cancel(actor, run.identity().runRef(), input, List.of(cancelEvent(run)))); }
        catch (ExecutionStore.Rejection rejected) { throw rejection(rejected); }
        worker.cancelled(run.identity().runRef()); return cancelled;
    }

    /** 查询仅返回已保存状态，不触发wake或恢复。 */
    @Override public ChatResult chatRunStatus(SessionView session, String runId) {
        var run = requireOwned(session, runId);
        String observed = run.phase() == ExecutionStore.Phase.UNCERTAIN ? "outcome_unknown" : status(run);
        return new ChatResult(observed, session.getSessionId(), runId, null, null, null);
    }

    @Override public AgentRuntimeExecutionSteering.Snapshot steerChat(SessionView session, String runId, String inputId, String text) {
        var run = requireOwned(session, runId); var input = new InputId(inputId);
        if(tests.session(run.identity().owner().sessionRef()).isPresent())throw new ApiException(HttpStatus.FORBIDDEN,"workflow_test_steering_rejected","测试仅允许原Pending回复",false);
        try { steering.offer(run, verify(run, input), input, text); }
        catch (ExecutionStore.Rejection rejected) { throw rejection(rejected); }
        catch (IllegalArgumentException invalid) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "补充输入无效或超过输入限制", invalid); }
        return steering.snapshot(store.load(run.identity().runRef()).orElseThrow());
    }

    @Override public AgentRuntimeExecutionSteering.Snapshot steeringChat(SessionView session, String runId) {
        return steering.snapshot(requireOwned(session, runId));
    }

    @Override public AgentRuntimeExecutionSteering.Snapshot steerOpenPlatform(com.skillforge.common.context.ServicePrincipalContext.Identity principal,
            SessionView session, String runId, String inputId, String text) {
        var run = requireOwned(session, runId); var input = new InputId(inputId);
        if (principal == null || !principal.tenantId().equals(run.identity().runRef().tenantId().value())
                || principal.principalId() == null || principal.principalId().isBlank()
                || principal.integrationId() == null || principal.integrationId().isBlank() || principal.credentialVersion() <= 0)
            throw new SecurityException("execution_integration_identity_invalid");
        var service = new PrincipalRef.ServicePrincipal(run.identity().runRef().tenantId(), principal.principalId(), principal.integrationId(), principal.credentialVersion());
        try { steering.offer(run, verify(run.identity().owner(), run.identity().agentId(), run.identity().trace(), input, service, "open-platform"), input, text); }
        catch (ExecutionStore.Rejection rejected) { throw rejection(rejected); }
        catch (IllegalArgumentException invalid) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "补充输入无效或超过输入限制", invalid); }
        return steering.snapshot(store.load(run.identity().runRef()).orElseThrow());
    }

    public com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse acceptMessage(
            com.skillforge.common.context.ServicePrincipalContext.Identity principal, SessionView session, String inputId,
            com.skillforge.core.chat.dto.openplatform.OpenPlatformMessageRequest request) {
        return acceptExternalInput(principal,session,inputId,request,true);
    }

    private com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse acceptExternalInput(
            com.skillforge.common.context.ServicePrincipalContext.Identity principal,SessionView session,String inputId,
            com.skillforge.core.chat.dto.openplatform.OpenPlatformMessageRequest request,boolean meterMessage) {
        var accepted=submit(externalSubmission(session,inputId,request.content(),request.context(),request.references(),null),
                principal,SubmissionSource.USER,null,meterMessage);
        var run=requireOwned(session,accepted.executionId());
        return externalReceipt(run,inputId,AgentExecutionProgram.admissionEvent(run.identity()).id().value(),accepted.replayed());
    }

    /** 原消息的只读受理核对；未观察到提交事实不表示未发送，也不授予重试权。 */
    public Optional<com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse> findOpenPlatformMessageReceipt(
            com.skillforge.common.context.ServicePrincipalContext.Identity principal, SessionView session, String inputId) {
        if (!Objects.equals(principal.integrationId(), session.getIntegrationId()))
            throw new SecurityException("execution_integration_identity_invalid");
        authorizeExternal(principal, session, null, "runtime.session.query");
        var reference = initialMessageRun(new TenantId(session.getTenantId().toString()), inputId);
        var found = store.load(reference);
        if (found.isEmpty()) return Optional.empty();
        var run = found.orElseThrow();
        if (!run.identity().runRef().equals(reference) || !run.identity().causationInputId().value().equals(inputId)
                || !run.identity().owner().equals(com.skillforge.institution.team.TeamWorkService.owner(session)))
            throw new SecurityException("execution_input_owner_mismatch");
        authorizeExternal(principal, session, run, "runtime.run.query");
        return Optional.of(externalReceipt(run, inputId, AgentExecutionProgram.admissionEvent(run.identity()).id().value(), false));
    }

    /** 首次受理与只读回执核对共用同一 Run 身份派生入口。 */
    private static RunRef initialMessageRun(TenantId tenant, String inputId) { return new RunRef(tenant, "run:" + inputId); }

    /** 正式Workflow入口只携带业务参数；身份与精确目标由原会话及当前挂载决定。 */
    public com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse acceptWorkflow(
            com.skillforge.common.context.ServicePrincipalContext.Identity principal, SessionView session, String inputId,
            WorkflowInvocation invocation) {
        if (tests.session(new SessionRef(new TenantId(session.getTenantId().toString()),session.getSessionId())).isPresent())
            throw new ApiException(HttpStatus.FORBIDDEN,"workflow_invocation_test_session","正式调用不能使用测试会话",false);
        var business=Map.<String,Object>of("workflow_invocation",invocation);
        var accepted=submit(externalSubmission(session,inputId,List.of(new ChatContent.Text("调用正式Workflow")),business,List.of(),null),
                principal,SubmissionSource.USER,invocation);
        var run=requireOwned(session,accepted.executionId());
        return externalReceipt(run,inputId,AgentExecutionProgram.admissionEvent(run.identity()).id().value(),accepted.replayed());
    }

    public record WorkflowInvocation(String mountId,String capabilityId,String versionId,TypedPayload parameters) {
        public WorkflowInvocation {
            if(mountId==null||mountId.isBlank()||capabilityId==null||!capabilityId.matches("[1-9][0-9]*")||versionId==null||!versionId.matches("[1-9][0-9]*")
                    ||parameters==null||!"application/json".equals(parameters.schemaId()))
                throw new ApiException(HttpStatus.BAD_REQUEST,"workflow_invocation_invalid","需要精确正式能力版本和JSON业务参数",false);
        }
    }

    public com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse acceptAction(
            com.skillforge.common.context.ServicePrincipalContext.Identity principal, SessionView session, String inputId,
            String runId,String actionKey,java.util.Map<String,Object> fields) {
        var reply=AgentRuntimeExecutionResponses.decode(new TenantId(session.getTenantId().toString()),fields,payloads);
        if(!reply.run().runId().equals(runId)||!actionKey.equals(reply instanceof AgentRuntimeExecutionResponses.WorkflowReply workflow
                ? workflow.actionKey():reply.waiting().action().actionId()))throw workflowConflict();
        var accepted=submit(externalSubmission(session,inputId,List.of(),java.util.Map.of(),List.of(),fields),principal);
        var run=requireOwned(session,runId);
        return externalReceipt(run,inputId,AgentExecutionProgram.resolved(run,new InputId(inputId),reply.waiting()).id().value(),accepted.replayed());
    }

    /** 外部业务事件进入一条新输入；真实外部完成只能由注册callback的HMAC交付链证明。 */
    public com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse acceptCallback(
            com.skillforge.common.context.ServicePrincipalContext.Identity principal, SessionView session,String inputId,String callbackId,
            String eventType,String externalExecutionId,String externalWorkflowId,java.util.Map<String,Object> payload) {
        if(!"business_event".equals(eventType)||completionPayload(payload))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"callback_completion_requires_registered_delivery",
                    "业务事件只接受 business_event；真实执行完成必须使用已注册的回调交付入口",false);
        var source=new java.util.LinkedHashMap<String,Object>();source.put("callback_id",callbackId);source.put("event_type",eventType);
        if(externalExecutionId!=null)source.put("external_execution_id",externalExecutionId);
        if(externalWorkflowId!=null)source.put("external_workflow_id",externalWorkflowId);
        source.put("payload",payload==null?java.util.Map.of():payload);
        try {
            return acceptExternalInput(principal,session,inputId,new com.skillforge.core.chat.dto.openplatform.OpenPlatformMessageRequest(
                    List.of(new ChatContent.Text("外部业务事件：\n"+json.writeValueAsString(source))),java.util.Map.of("external_event",source),List.of()),false);
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"business_event_invalid",invalid);}
    }

    private static boolean completionPayload(Object value) {
        if(value instanceof Map<?,?> map)return map.entrySet().stream().anyMatch(entry->Set.of("workflow_input","tool_input","human_input").contains(entry.getKey())||completionPayload(entry.getValue()));
        if(value instanceof Iterable<?> values)for(var item:values)if(completionPayload(item))return true;
        return false;
    }

    public record OpenPlatformRunStatus(String sessionId,String runId,String status,List<java.util.Map<String,Object>> pendingInteractions) { }

    public OpenPlatformRunStatus openPlatformStatus(com.skillforge.common.context.ServicePrincipalContext.Identity principal,SessionView session,String runId) {
        var run=requireOwned(session,runId);authorizeExternal(principal,session,run,"runtime.run.query");
        // FINISHED 不再存在挂起交互；当前归属和IAM仍必须先核验。
        if (run.phase() == ExecutionStore.Phase.FINISHED)
            return new OpenPlatformRunStatus(session.getSessionId(),runId,status(run),List.of());
        return new OpenPlatformRunStatus(session.getSessionId(),runId,status(run),history.read(session).pendingInteractions().stream()
                .filter(value->runId.equals(value.get("execution_id"))).toList());
    }

    public com.skillforge.core.chat.dto.response.ChatHistoryResponse openPlatformHistory(
            com.skillforge.common.context.ServicePrincipalContext.Identity principal,SessionView session) {
        authorizeExternal(principal,session,null,"runtime.session.query");
        return history.read(session).withClosed(productSessions.isOpenPlatformClosed(session));
    }

    public com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse cancelExecution(
            com.skillforge.common.context.ServicePrincipalContext.Identity principal,SessionView session,String inputId,String runId,String reason) {
        var run=requireOwned(session,runId);var input=new InputId(inputId);
        var actor=verify(run.identity().owner(),run.identity().agentId(),run.identity().trace(),input,integration(principal,session),"open-platform");
        var cancelled=cancel(run,actor,input);
        return externalReceipt(cancelled,inputId,"execution-cancelled:"+runId,run.cancelled());
    }

    public com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse closeSession(
            com.skillforge.common.context.ServicePrincipalContext.Identity principal,SessionView session,String inputId,String reason) {
        var actor=authorizeExternal(principal,session,null,"runtime.session.close");
        productSessions.closeOpenPlatform(principal.tenantId(),session.getSessionId(),principal.integrationId(),reason);
        closeRuntimeSession(actor.owner(),new InputId(inputId));
        return new com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse(session.getSessionId(),inputId,inputId,inputId,"closed");
    }

    /** 生命周期关闭沿真实SessionStore CAS；已受理Run继续收尾，关闭不伪造取消。 */
    private void closeRuntimeSession(SessionOwner owner,InputId input) {
        var current=sessionStore.find(new SessionStorePort.SessionLookup(owner));
        var trace=new TraceEnvelope(input.value(),"session-close:"+input.value(),input,Optional.empty());
        for(int attempt=0;current.isPresent()&&attempt<3;attempt++) {
            var record=current.orElseThrow();
            if(!record.owner().equals(owner))throw new SecurityException("execution_session_close_owner_mismatch");
            if(record.status()==SessionStorePort.SessionStatus.CLOSED||record.status()==SessionStorePort.SessionStatus.CREATED)return;
            var result=sessionStore.compareAndSet(new SessionStorePort.ChangeSessionStatus(owner,SessionStorePort.SessionStatus.ACTIVE,
                    SessionStorePort.SessionStatus.CLOSED,record.sessionRevision(),input,trace,clock.instant()));
            switch(result) {
                case SessionStorePort.SessionCasResult.Applied value->{requireClosed(owner,value.session());return;}
                case SessionStorePort.SessionCasResult.Replayed value->{requireClosed(owner,value.session());return;}
                case SessionStorePort.SessionCasResult.Conflict value->current=Optional.of(value.current());
                case SessionStorePort.SessionCasResult.NotFound ignored->{return;}
                case SessionStorePort.SessionCasResult.Rejected ignored->throw new IllegalStateException("execution_session_close_rejected");
            }
        }
        if(current.isPresent()&&current.orElseThrow().status()==SessionStorePort.SessionStatus.ACTIVE)throw new IllegalStateException("execution_session_close_conflict");
        current.ifPresent(record->{if(!record.owner().equals(owner))throw new SecurityException("execution_session_close_owner_mismatch");});
    }
    private static void requireClosed(SessionOwner owner,SessionStorePort.SessionRecord record) {
        if(!record.owner().equals(owner)||record.status()!=SessionStorePort.SessionStatus.CLOSED)throw new IllegalStateException("execution_session_close_receipt_mismatch");
    }

    private Submission externalSubmission(SessionView session,String inputId,List<ChatContent> content,java.util.Map<String,Object> business,
            List<com.skillforge.core.chat.contract.ChatInputReference> selected,java.util.Map<String,Object> reply) {
        var context=new java.util.TreeMap<String,Object>(business==null?java.util.Map.of():business);
        for(var reserved:List.of("input_id","root_input_id","trace_id","span_id","tenant_id","end_user_id"))context.remove(reserved);
        context.put("input_id",inputId);
        // Runtime输入保留原幂等身份；公开消息归档另用确定性的64字符标识。
        var request=new com.skillforge.core.chat.dto.request.ChatRequest(content,session.getSessionId(),
                session.getAgentId()==null?null:session.getAgentId().toString(),session.getTeamId(),null,null,null,null,
                digest("open-platform.public-message.v1\n"+inputId),reply,context,selected);
        return new Submission(request,session,bindings.currentPrimaryAgent(com.skillforge.institution.team.TeamWorkService.owner(session)).value(),session.getEndUserId(),null);
    }

    private com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse externalReceipt(ExecutionStore.Run run,String inputId,String eventId,Boolean replayed) {
        return new com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse(run.identity().owner().sessionRef().sessionId(),inputId,
                run.identity().trace().rootInputId().value(),eventId,"accepted",run.identity().runRef().runId(),Boolean.TRUE.equals(replayed));
    }

    private PrincipalRef.ServicePrincipal integration(com.skillforge.common.context.ServicePrincipalContext.Identity principal,SessionView session) {
        if(principal==null||session.getTenantId()==null||!session.getTenantId().toString().equals(principal.tenantId())
                ||!principal.tenantId().equals(TenantContext.getTenantId())||principal.credentialVersion()<=0
                ||!("open-platform:"+principal.integrationId()).equals(session.getScopeType()))
            throw new SecurityException("execution_integration_identity_invalid");
        provisioner.provisionAccess(principal.tenantId());
        return new PrincipalRef.ServicePrincipal(new TenantId(principal.tenantId()),principal.principalId(),principal.integrationId(),principal.credentialVersion());
    }

    private VerifiedIdentity authorizeExternal(com.skillforge.common.context.ServicePrincipalContext.Identity principal,SessionView session,ExecutionStore.Run run,String action) {
        var owner=com.skillforge.institution.team.TeamWorkService.owner(session);var input=new InputId("external-read:"+UUID.randomUUID());
        var trace=run==null?new TraceEnvelope(input.value(),"span:"+input.value(),input,Optional.empty()):run.identity().trace();
        var actor=run==null?bindings.currentPrimaryAgent(owner):run.identity().agentId();
        var verified=verify(owner,actor,trace,input,integration(principal,session),"open-platform");
        requireReadAuthorization(verified,trace,action,run==null?owner.sessionRef().sessionId():run.identity().runRef().runId(),input);
        return verified;
    }

    /** 产品领域命令复用当前可信身份和IAM，不需要旧Runtime façade或旧Session激活链。 */
    public VerifiedIdentity authorizeSession(SessionView session,InputId input,String action) {
        if(!java.util.Set.of("runtime.session.query","runtime.plan.select","runtime.session.human-assistance").contains(action))throw new IllegalArgumentException("execution_session_action_invalid");
        var owner=com.skillforge.institution.team.TeamWorkService.owner(session);var trace=new TraceEnvelope(input.value(),"span:"+input.value(),input,Optional.empty());
        var verified=verify(owner,bindings.currentPrimaryAgent(owner),trace,input);
        requireReadAuthorization(verified,trace,action,owner.sessionRef().sessionId(),input);return verified;
    }

    private void requireReadAuthorization(VerifiedIdentity actor,TraceEnvelope trace,String action,String target,InputId input) {
        var owner=actor.owner();var values=List.of(owner.sessionRef().tenantId().value(),owner.target().kind().name(),owner.target().id(),
                owner.sessionRef().sessionId(),com.skillforge.runtime.adapter.jdbc.identity.OwnerPolicyFields.subject(owner),action,"runtime.run.query".equals(action)?"run":"session",target,"0",input.value());
        try {
            var resource=new TypedPayload("skillforge.runtime.facade-access.v1",json.writeValueAsString(values));
            if(!(identities.authorize(new IdentityPolicyPort.AuthorizationRequest(actor,action,resource,trace.traceId(),trace.spanId())) instanceof IdentityPolicyPort.AuthorizationResult.Allowed))
                throw new SecurityException("execution_query_unauthorized");
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("execution_query_resource_invalid",invalid);}
    }

    /** 数据库租户隔离之外继续核实完整产品会话与终端用户归属。 */
    private ExecutionStore.Run requireOwned(SessionView session, String runId) {
        String tenant = session.getTenantId().toString();
        if (!tenant.equals(TenantContext.getTenantId())) throw new SecurityException("execution_tenant_mismatch");
        var run = store.load(new RunRef(new TenantId(tenant), runId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "execution_run_missing"));
        if (!run.identity().owner().sessionRef().sessionId().equals(session.getSessionId())
                || run.identity().owner().requireUser().value() != session.getEndUserId()) throw new SecurityException("execution_session_owner_mismatch");
        return run;
    }

    /** 认证与当前IAM分属受理和提交边界；本方法不缓存认证结果。 */
    private VerifiedIdentity verify(SessionOwner owner, AgentId actor, TraceEnvelope trace, InputId input) {
        provisioner.provisionAccess(owner.sessionRef().tenantId().value());
        return verify(owner, actor, trace, input, principal(owner.sessionRef().tenantId()), "chat");
    }

    private VerifiedIdentity verify(SessionOwner owner, AgentId actor, TraceEnvelope trace, InputId input,
            PrincipalRef.ServicePrincipal principal, String channel) {
        return verify(owner,actor,trace,input,principal,channel,"request");
    }

    /** 受信控制来源仅由宿主方法构造，当前群操作者不覆盖原RunRequester。 */
    private VerifiedIdentity verify(SessionOwner owner,AgentId actor,TraceEnvelope trace,InputId input,
            PrincipalRef.ServicePrincipal principal,String channel,String source) {
        final AccessEnvelope envelope;
        try {
            envelope = access.serviceAccess(principal, owner, actor, channel, source,
                    new RequestId("runtime-inbound:" + input.value()), trace.traceId(), trace.spanId());
        } catch (TrustedAuthenticationRejectedException rejected) {
            // 当前撤权是确定性拒绝，发生在受理/预算/输入写入前；技术异常不在此捕获。
            throw new ApiException(HttpStatus.FORBIDDEN, "当前执行身份或Agent授权已失效", rejected);
        }
        return switch (identities.verify(new IdentityPolicyPort.IdentityRequest(envelope.authenticationRef(), owner,
                envelope.channel(), envelope.source(), envelope.requestId(), Optional.of(input), trace.traceId(), trace.spanId()))) {
            case IdentityPolicyPort.IdentityResult.Verified value -> value.identity();
            case IdentityPolicyPort.IdentityResult.Rejected value -> throw identityRejection(value.failure());
        };
    }

    /** 稳定身份拒绝与权威存储不可用分别映射，不从通用异常文案推断授权结果。 */
    private ApiException identityRejection(PortFailure failure) {
        return switch (failure.reasonCode()) {
            case "identity_rejected" -> new ApiException(HttpStatus.FORBIDDEN, "当前执行身份或Agent授权已失效");
            case "identity_unavailable" -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "当前身份授权暂不可核验");
            default -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "身份验证未形成有效判定");
        };
    }

    /** 状态来自结构化Run；未知与等待不能伪装成已完成。 */
    private static String status(ExecutionStore.Run run) {
        return run.status().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 只映射明确业务拒绝；SQL、网络及编码异常继续保留技术故障语义。 */
    private static ApiException rejection(ExecutionStore.Rejection rejected) {
        var status = switch (rejected.reason()) {
            case "execution_resume_owner_mismatch", "execution_resume_unauthorized", "execution_cancel_owner_mismatch", "execution_cancel_unauthorized",
                    "execution_supplement_owner_mismatch", "execution_supplement_unauthorized" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.CONFLICT;
        };
        var result = new ApiException(status, rejected.reason(), "当前运行或等待状态已变化，请刷新后重试", false);
        result.initCause(rejected);
        return result;
    }

    /** 取消终态事件与取消状态同事务保存，不伪造外部结果或已报告用量。 */
    private ExecutionStore.Event cancelEvent(ExecutionStore.Run run) {
        var value = json.createObjectNode();
        value.put("text", ""); value.put("status", RunStatus.CANCELLED.name()); value.put("reason", "user_cancelled");
        value.put("inputId", run.identity().causationInputId().value());
        value.put("rootInputId", run.identity().trace().rootInputId().value());
        value.put("traceId", run.identity().trace().traceId()); value.put("spanId", run.identity().trace().spanId());
        value.put("agentId", run.identity().agentId().value());
        try {
            return new ExecutionStore.Event(new EventId("execution-cancelled:" + run.identity().runRef().runId()),
                    AgentExecutionProgram.COMPLETED_EVENT, new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA, json.writeValueAsString(value)));
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("execution_cancel_event_invalid", invalid); }
    }

    /** 实际用户回复的公开副本按当前呈现政策保护；拒绝公开不改私有模型输入或已保存原文。 */
    /** 初始输入和恢复回复共用公开事件；消息身份与执行input身份分别冻结，防止外部接入重复显示。 */
    private ExecutionStore.Event userEvent(RunIdentity identity, InputId input, String text,
            String messageId, List<ChatContent> contentParts, Optional<TypedPayload> frozenInput) {
        var value = json.createObjectNode();
        var scope = publicGuard.capture(identity.owner());
        boolean rejected = scope.rejects(text);
        value.put("text", rejected ? PublicOutputGuard.SAFE_MESSAGE : text);
        value.put("messageId", messageId);
        if (!rejected && !contentParts.isEmpty()) value.set("contentParts", json.valueToTree(contentParts));
        value.put("inputId", input.value()); value.put("rootInputId", identity.trace().rootInputId().value());
        value.put("traceId", identity.trace().traceId()); value.put("spanId", identity.trace().spanId());
        value.put("agentId", identity.agentId().value());
        try {
            if (!rejected && frozenInput.isPresent()) {
                var extra = json.readTree(frozenInput.orElseThrow().canonicalJson()).path("extraParameters");
                if (extra.has("attachments")) {
                    List<com.skillforge.institution.attachment.SessionAttachmentService.FrozenMetadata> files = json.convertValue(extra.get("attachments"),
                            json.getTypeFactory().constructCollectionType(List.class, com.skillforge.institution.attachment.SessionAttachmentService.FrozenMetadata.class));
                    var visible = files.stream().map(file -> new com.skillforge.institution.attachment.SessionAttachmentService.FrozenMetadata(
                            file.id(), scope.rejects(file.name()) ? "所选资料" : file.name(), file.inputPath(), file.size(), file.sha256(), file.expiresAt())).toList();
                    value.set("attachmentIds", json.valueToTree(visible.stream().map(com.skillforge.institution.attachment.SessionAttachmentService.FrozenMetadata::id).toList()));
                    value.set("attachments", json.valueToTree(visible));
                }
            }
            return new ExecutionStore.Event(new EventId("execution-user-message:" + identity.runRef().runId() + ":" + input.value()),
                    AgentRuntimeExecutionResponses.USER_MESSAGE_EVENT, new TypedPayload(AgentRuntimeExecutionResponses.USER_MESSAGE_SCHEMA, json.writeValueAsString(value)));
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("execution_user_event_invalid", invalid); }
    }

    private List<ModelContent> inputContent(SessionRef owner, TypedPayload frozen) {
        try {
            var root = json.readTree(frozen.canonicalJson());
            var result = new ArrayList<ModelContent>();
            for (var part : root.path("content")) {
                switch (part.path("type").asText()) {
                    case "text" -> result.add(new ModelContent.Text(part.path("text").asText()));
                    case "image" -> {
                        var media = part.path("media"); var scope = media.path("owner");
                        var reference = new MediaRef(new SessionRef(new TenantId(scope.path("tenantId").asText()), scope.path("sessionId").asText()),
                                media.path("id").asText(), media.path("sha256").asText(), media.path("mimeType").asText(),
                                media.path("byteLength").asLong(), media.path("width").asInt(), media.path("height").asInt());
                        if (!reference.owner().equals(owner)) throw new SecurityException("execution_media_owner_mismatch");
                        result.add(new ModelContent.Image(reference, ImageDetail.valueOf(part.path("detail").asText())));
                    }
                    default -> throw new IllegalArgumentException("execution_input_content_invalid");
                }
            }
            JsonNode context = root.path("extraParameters");
            if (!context.isMissingNode() && context.size() > 0) result.add(new ModelContent.Text("\n输入业务上下文：\n" + context));
            if (result.isEmpty()) throw new IllegalArgumentException("execution_input_empty");
            return List.copyOf(result);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("execution_input_invalid", invalid); }
    }

    /** 加载完整未摘要来源，不按消息条数丢弃旧轮；本次用户消息由当前input单独追加一次。 */
    private List<ChatMessageRecord> history(SessionView session, String currentInput) {
        var byId = new java.util.LinkedHashMap<String, ChatMessageRecord>();
        memory.listArchived(session.getTenantId().toString(), session.getSessionId(), 0)
                .forEach(message -> byId.put(message.messageId(), message));
        history.read(session).messages().forEach(message -> byId.put(message.messageId(), message));
        var ordered = byId.values().stream().filter(message -> !message.messageId().equals(currentInput)
                && !(message.metadata() != null && currentInput.equals(message.metadata().get("input_id"))))
                .sorted(java.util.Comparator.comparing(ChatMessageRecord::createdAt).thenComparingLong(message ->
                        message.metadata() != null && message.metadata().get("source_sequence") instanceof Number sequence ? sequence.longValue() : 0L)
                        .thenComparing(ChatMessageRecord::messageId)).toList();
        return ordered;
    }

    /** 历史图片从原选择器重新核验当前会话媒体归属，不把UI元数据直接当成可信MediaRef。 */
    private List<ModelContent> historyContent(SessionView session, ChatMessageRecord message) {
        if (message.metadata() == null || !message.metadata().containsKey("content_parts"))
            return List.of(new ModelContent.Text(message.content() == null ? "" : message.content()));
        final List<ChatContent> content;
        try { content = json.convertValue(message.metadata().get("content_parts"), new com.fasterxml.jackson.core.type.TypeReference<List<ChatContent>>() { }); }
        catch (IllegalArgumentException malformed) { throw new IllegalStateException("execution_history_content_invalid", malformed); }
        if (content == null || content.isEmpty()) return List.of(new ModelContent.Text(message.content() == null ? "" : message.content()));
        if (content.stream().noneMatch(ChatContent.Image.class::isInstance))
            return content.stream().map(ChatContent.Text.class::cast).map(value -> (ModelContent) new ModelContent.Text(value.text())).toList();
        var result = new ArrayList<ModelContent>();
        for (var part : media.resolveContent(session.getSessionId(), content)) {
            switch (part) {
                case SessionMediaService.TextContent text -> result.add(new ModelContent.Text(text.text()));
                case SessionMediaService.ImageContent image -> {
                    var value = image.media(); var scope = value.owner();
                    var reference = new MediaRef(new SessionRef(new TenantId(scope.tenantId()), scope.sessionId()), value.id(), value.sha256(),
                            value.mimeType(), value.byteLength(), value.width(), value.height());
                    result.add(new ModelContent.Image(reference, ImageDetail.valueOf(image.detail())));
                }
            }
        }
        return List.copyOf(result);
    }

    private PrincipalRef.ServicePrincipal principal(TenantId tenant) {
        var row = principals.selectOne(new QueryWrapper<ServicePrincipalDO>().eq("tenant_id", Long.parseLong(tenant.value()))
                .eq("integration_id", AgentRuntimeHostContracts.CHAT_INTEGRATION_ID).eq("status", "active").last("LIMIT 1"));
        if (row == null || row.getCredentialVersion() == null || row.getCredentialVersion() <= 0)
            throw new IllegalStateException("chat_service_principal_missing");
        return new PrincipalRef.ServicePrincipal(tenant, row.getPrincipalId(), AgentRuntimeHostContracts.CHAT_INTEGRATION_ID, row.getCredentialVersion());
    }

    private static ChatResult result(ExecutionStore.Run run, String inputId, boolean replayed) {
        return new ChatResult("queued", run.identity().owner().sessionRef().sessionId(), run.identity().runRef().runId(),
                null, null, null, inputId, replayed);
    }
    private static String context(Submission submission, String field, String fallback) {
        Object value = submission.request().context() == null ? null : submission.request().context().get(field);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }
    private static String requireTrace() {
        String trace = RequestContext.getTraceId();
        if (trace == null || trace.isBlank()) throw new IllegalStateException("runtime_request_trace_missing");
        return trace;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
