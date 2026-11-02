package com.skillforge.institution.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.common.context.TenantContext;
import com.skillforge.execution.*;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.chat.contract.ChatInputReference;
import com.skillforge.core.chat.contract.ChatReferenceView;
import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.core.capability.connector.CodeExecutionArgumentException;
import com.skillforge.core.capability.connector.CodeExecutionArguments;
import com.skillforge.core.shared.logging.SafeFailureDiagnostics;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.core.capability.connector.PlatformMediaToolHandler;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.artifact.ArtifactMetadata;
import com.skillforge.institution.artifact.ArtifactProvenance;
import com.skillforge.institution.artifact.ArtifactPublicInspection;
import com.skillforge.institution.artifact.ArtifactStorage;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.institution.workspace.SessionWorkspaceService;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 唯一宿主代码执行入口；结果未知只查询既有任务，绝不重新提交执行。 */
@Component
public class CodeExecutionToolHandler implements PlatformMediaToolHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CodeExecutionToolHandler.class);
    // 仅允许已定义的 Runtime 安全原因码；未知异常正文不能进入日志。
    private static final Set<String> PROGRAM_RPC_SAFE_REASONS = Set.of(
            "program_scope_rejected", "program_scope_closed", "program_recursion_forbidden",
            "program_tool_requires_direct_call", "program_tool_contract_changed", "program_tool_budget_exceeded",
            "program_prepare_rejected", "program_child_identity_required", "program_child_mismatch",
            "program_child_source_rejected", "program_tool_outcome_unknown", "program_tool_stopped",
            "session_tool_directory_rejected", "studio_tool_no_longer_authorized",
            "studio_run_binding_missing", "studio_run_binding_mismatch", "program_rpc_invalid");
    private static final String PUBLIC_PRESENTATION_FIELD = "public_presentation";
    private static final String PLAIN_RESULT_FORMAT = "plain";
    private static final String CODE_PRESENTATION_TITLE = "执行代码";
    private static final String CODE_FAILED_SUMMARY = "代码执行未完成。";
    private static final String CODE_DELIVERY_FAILED_SUMMARY = "代码已执行，生成文件未能交付。";
    /** 代码已运行但输出选择未能形成受管候选，与正式成果发布区分。 */
    private static final String CODE_COLLECTION_FAILED_SUMMARY = "代码已执行，声明的文件未能收集。";
    private static final String CODE_COMPLETED_SUMMARY = "代码执行完成。";
    private static final String CODE_FILES_READY_SUMMARY = "代码执行完成，生成文件可读取。";
    private static final String CODE_ARGUMENTS_REJECTED_SUMMARY = "执行参数校验未通过，本次尚未执行。";
    private static final String CODE_RESOURCES_REJECTED_SUMMARY = "执行所需资源不可用或未获授权，本次尚未执行。";
    private static final String CODE_ADMISSION_UNAVAILABLE_SUMMARY = "执行条件未就绪，本次尚未执行。";
    private static final String FAILED_DELIVERY_STATUS = "failed";
    private final ObjectMapper json;private final ObjectProvider<FrozenScriptResolver> scripts;
    private final com.skillforge.institution.artifact.DeliverableService deliverables;
    private final ArtifactStorage artifacts;private final ArtifactAccess access;
    private final String endpoint,token;private final RunSettingsService settings;
    private final Duration leaseDuration;private final int previewPort;
    private final com.skillforge.institution.attachment.SessionAttachmentService attachments;
    private final ObjectProvider<SessionWorkspaceService> workspaces;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public CodeExecutionToolHandler(ObjectMapper json,ObjectProvider<FrozenScriptResolver> scripts,
            ArtifactStorage artifacts,ArtifactAccess access,com.skillforge.institution.artifact.DeliverableService deliverables,
            @Value("${skillforge.code-execution.endpoint:}") String endpoint,
            @Value("${skillforge.code-execution.service-token:}") String token,
            RunSettingsService settings,
            AgentRuntimeProperties runtimeProperties,
            @Value("${skillforge.code-execution.preview-port:0}") int previewPort,
            com.skillforge.institution.attachment.SessionAttachmentService attachments,
            ObjectProvider<SessionWorkspaceService> workspaces) {
        this.attachments=attachments;
        this.deliverables=deliverables;this.json=json;this.scripts=scripts;this.artifacts=artifacts;this.access=access;
        this.endpoint=endpoint;this.token=token;this.settings=settings;
        this.leaseDuration=runtimeProperties.getWorkerLeaseDuration();this.previewPort=previewPort;
        this.workspaces=workspaces;
    }
    @Override public String toolName(){return "code_execute";}
    @Override public Map<String,Object> execute(Map<String,Object> args,PlatformToolExecutionContext context)throws Exception {
        return executeInternal(args, context, null, null, true);
    }
    /** Renderer拥有独立输出契约；复用执行管线但不增加普通代码的stdout观察字段。 */
    Map<String,Object> executeForRender(Map<String,Object> args,PlatformToolExecutionContext context)throws Exception {
        return executeInternal(args, context, null, null, false);
    }
    /** PTC 与普通代码执行共享隔离、产物和取消管线，只有受信 Runtime 可承接工具 RPC。 */
    public Map<String,Object> executeProgram(Map<String,Object> args, PlatformToolExecutionContext context,
            com.skillforge.core.capability.connector.ProgramToolInvoker invoker) throws Exception {
        return executeInternal(args, context, Objects.requireNonNull(Objects.requireNonNull(invoker).bind(context)), null, false);
    }
    /** Shell调用复用唯一执行管线；语言和Profile由宿主设置而非模型覆盖代码工具协议。 */
    public Map<String,Object> executeWorkspaceShell(String command,Integer timeout,WorkspaceBinding workspace,String skillHandle,
            PlatformToolExecutionContext context)throws Exception {
        LOG.debug("工作区Shell进入 invocationId={}",context.invocationId());
        try {
            if(context.programChild())return rejected("workspace_program_child_forbidden");
            return executeInternal(Map.of("mode","inline","language","node","code",command,"output_files",List.of()),context,null,
                    new WorkspaceShell(workspace,timeout,skillHandle),false);
        }finally{LOG.debug("工作区Shell退出 invocationId={}",context.invocationId());}
    }
    /** Shell模式只用于可信工作区入口，不形成第二套任务与取消状态机。 */
    private record WorkspaceShell(WorkspaceBinding workspace,Integer timeout,String skillHandle) { }
    /**
     * 后台工具从已授权 Run 建立整次调用的租户作用域，配置准入也必须先于 MyBatis RLS 查询绑定。
     * 已存在的其他租户身份直接拒绝；所有返回与异常均恢复调用者上下文，不借 HTTP 登录或连接残留身份。
     */
    private Map<String,Object> executeInternal(Map<String,Object> args,PlatformToolExecutionContext context,
            com.skillforge.core.capability.connector.ProgramToolInvoker.Calls invoker,WorkspaceShell shell,boolean stdoutObservation)throws Exception {
        String tenant=context.run().runRef().tenantId().value();
        String previous=TenantContext.getTenantId();
        if(previous!=null&&!previous.equals(tenant))
            return resourceRejected(context,"tenant_scope","RESOURCE_ACCESS_REJECTED",
                    new SecurityException("database_tenant_scope_mismatch"));
        TenantContext.setTenantId(tenant);
        try { return executeInTenantScope(args,context,invoker,shell,stdoutObservation); }
        finally { if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous); }
    }

    /** 在当前可信 Run 的租户作用域内完成冻结配置、资源准入、执行和成果收集。 */
    private Map<String,Object> executeInTenantScope(Map<String,Object> args,PlatformToolExecutionContext context,
            com.skillforge.core.capability.connector.ProgramToolInvoker.Calls invoker,WorkspaceShell shell,boolean stdoutObservation)throws Exception {
        String tenant=context.run().runRef().tenantId().value();
        String run=context.run().runRef().runId();
        OperationalSettingsService.Snapshot sandbox;
        Duration transferTimeout;
        try {
            var operation=settings.captureTool(tenant,run,context.invocationId());
            sandbox=settings.read(operation,"sandbox");
            var delivery=settings.read(operation,"artifacts");
            if(!sandbox.bool("enabled"))return rejected("code_execution_disabled");
            if(endpoint.isBlank()||token.length()<32||previewPort<=0||leaseDuration.compareTo(Duration.ofSeconds(200))<0)
                return rejected("code_execution_deployment_unavailable");
            var uri=java.net.URI.create(endpoint);
            if (!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                return rejected("code_execution_deployment_unavailable");
            access.requireConfiguration();
            transferTimeout=Duration.ofSeconds(delivery.longValue("transfer_timeout_seconds"));
        } catch (RuntimeException unavailable) {
            logAdmission(context,"configuration","ADMISSION_UNAVAILABLE","",unavailable);
            return rejected("code_execution_admission_unavailable");
        }
        CodeExecutionArguments parsed;
        List<com.skillforge.institution.attachment.SessionAttachmentService.Metadata> attachmentEvidence = List.of();
        ExecuteRequest request;
        var sources=new ArrayList<ExecutionInputSource>();
        String code;String scriptPath=null;List<ArtifactFile> scriptInputs=List.of();
        List<CodeExecutionArguments.Dependency> effectiveDependencies;
        String admissionStage="arguments";
        try {
            parsed=CodeExecutionArguments.parse(args);
            WorkspaceBinding workspace=shell==null?null:shell.workspace();
            if(parsed.useWorkspace()) {
                if(context.programChild())return rejected("workspace_program_child_forbidden");
                admissionStage="workspace";
                workspace=workspaces.getObject().forCode(context);
            }
            effectiveDependencies=parsed.dependencies();
            if(!parsed.outputFiles().isEmpty()) {
                try { deliverables.admitCollection(context.run()); }
                catch(IllegalArgumentException unavailable) {
                    String rejectionCode = com.skillforge.institution.artifact.ArtifactClosingBudget.REJECTION_CODE.equals(unavailable.getMessage())
                            ? com.skillforge.institution.artifact.ArtifactClosingBudget.REJECTION_CODE : "OUTPUT_COLLECTION_UNAVAILABLE";
                    logAdmission(context,"output_collection",rejectionCode,"/output_files",unavailable);
                    return admissionRejected(rejectionCode,Map.of("status","available",
                            "code",rejectionCode,"field","/output_files",
                            "message", "OUTPUT_COLLECTION_UNAVAILABLE".equals(rejectionCode)
                                    ? "当前运行不能再收集候选文件。" : "剩余预算无法覆盖文件收集与交付收尾。",
                            "hint","本次代码尚未执行；依据当前任务状态处理，不要原样重试。"),CODE_RESOURCES_REJECTED_SUMMARY);
                }
            }
            admissionStage="attachments";
            var resolved=attachments.resolve(context,parsed.attachmentIds(),parsed.inputFiles());
            sources.addAll(resolved.files());attachmentEvidence=resolved.attachments();
            code=parsed.code();
            if("skill".equals(parsed.mode())||shell!=null&&shell.skillHandle()!=null) {
                admissionStage="skill_script";
                List<com.skillforge.runtime.session.FrozenSkillResource> resources;
                if(shell==null) {
                    var script=scripts.getObject().resolve(context,parsed.skillHandle(),parsed.scriptPath(),parsed.language());
                    effectiveDependencies=script.dependencies(parsed.language(),parsed.dependencies());
                    code="";scriptPath=script.path();resources=script.inputs();
                } else {
                    resources=scripts.getObject().resolveResources(context,shell.skillHandle());
                    // main.sh 是本次可信 Shell 入口，冻结包不得覆盖它；依赖不在命令入口自动安装。
                    if(resources.stream().anyMatch(resource->resource.path().equals("main.sh")||resource.path().startsWith("main.sh/")))
                        throw new IllegalArgumentException("skill_resource_entry_conflict");
                    code="export SKILL_DIR=/input\n"+code;
                }
                var frozen=new ArrayList<ArtifactFile>();
                for(var resource:resources) {
                    byte[] bytes="UTF8".equals(resource.encoding())?resource.content().getBytes(StandardCharsets.UTF_8)
                            :Base64.getDecoder().decode(resource.content());
                    var file=new ArtifactFile(resource.path(),resource.size(),resource.sha256());
                    frozen.add(file);sources.add(new ExecutionInputSource(file,()->new ByteArrayInputStream(bytes)));
                }
                scriptInputs=List.copyOf(frozen);
            }
            if(invoker!=null) {
                if(!"inline".equals(parsed.mode())||!"node".equals(parsed.language()))return rejected("program_requires_inline_node");
                code="import { tools } from './__skillforge_tools.mjs';\n"+parsed.code();
            }
            admissionStage="execution_policy";
            ExecutionPolicy policy;
            // revision 不属于values，只由捕获配置的可信回执提供。
            int executionTimeout=sandbox.integer("execution_timeout_seconds");
            if(shell!=null&&shell.timeout()!=null)executionTimeout=Math.min(executionTimeout,shell.timeout());
            policy=new ExecutionPolicy(sandbox.revision(),executionTimeout,
                    com.skillforge.execution.ExecutionLimits.DOCKER_CONTROL_TIMEOUT_SECONDS,sandbox.integer("workspace_retention_hours"),
                    com.skillforge.execution.ExecutionLimits.WORKSPACE_CLEANUP_INTERVAL_MINUTES,sandbox.integer("output_capture_bytes"),
                    sandbox.integer("max_artifact_files"),sandbox.integer("max_concurrent_executions"),
                    sandbox.text("memory_limit"),sandbox.decimal("cpu_limit"),sandbox.integer("pids_limit"));
            String taskId=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(List.of(
                    context.run().owner().sessionRef().tenantId().value(),run,context.invocationId(),context.attemptId()))));
            request=new ExecuteRequest(taskId,shell==null?Language.fromValue(parsed.language()):Language.SHELL,code,
                    effectiveDependencies.stream().map(value->new DependencySpec(value.name(),value.version())).toList(),
                    scriptInputs,scriptPath,invoker!=null,context.deadlineAt(),policy,
                    parsed.outputFiles(),shell==null?ExecutionProfile.STANDARD:ExecutionProfile.WORKSPACE_SHELL_V1,null,null,workspace);
        }catch(ExternalInvocationGuard.OutcomeUnknownException unknown) {
            // 工作区首次初始化发送后的未知回执不能被包装成尚未提交的准入拒绝。
            throw unknown;
        }catch(CodeExecutionArgumentException invalid) {
            boolean frozen=invalid.code()==CodeExecutionArgumentException.Code.FROZEN_MANIFEST_INVALID;
            logAdmission(context,admissionStage,invalid.code().name(),invalid.field(),invalid);
            return admissionRejected(frozen?"code_execution_resources_rejected":"code_execution_arguments_rejected",
                    invalid.diagnostic(),frozen?CODE_RESOURCES_REJECTED_SUMMARY:CODE_ARGUMENTS_REJECTED_SUMMARY);
        }catch(SecurityException|com.skillforge.core.shared.exception.ApiException rejected) {
            return resourceRejected(context,admissionStage,"RESOURCE_ACCESS_REJECTED",rejected);
        }catch(IllegalArgumentException|IllegalStateException rejected) {
            if(Set.of("attachments","skill_script","workspace").contains(admissionStage))
                return resourceRejected(context,admissionStage,"RESOURCE_UNAVAILABLE",rejected);
            logAdmission(context,admissionStage,"ADMISSION_UNAVAILABLE","",rejected);
            return rejected("code_execution_admission_unavailable");
        }catch(Exception unavailable) {
            logAdmission(context,admissionStage,"ADMISSION_UNAVAILABLE","",unavailable);
            return rejected("code_execution_admission_unavailable");
        }
        // 配置的是执行上限，不是最短准入预算；Runner 会取该上限与真实截止时间的较早值。
        if(context.cancellationSignal().isCancellationRequested()||!Instant.now().isBefore(context.deadlineAt()))
            return rejected("code_execution_deadline_insufficient");
        String taskId=request.taskId();
        boolean sent=false;
        try {
            List<ArtifactProvenance> provenance=List.of();
            ExecutionResponse state;
            try(var archive=artifacts.executionArchive(context,parsed.artifactInputs(),request,sources)) {
                provenance=archive.inputs();
                sent=true;
                state=sendArchive(archive,context);
            }
            catch(IOException disconnected){if(!sent)return rejected("code_execution_artifact_inputs_rejected");state=send("GET","/detail?taskId="+taskId,null,context.deadlineAt());}
            while(Set.of(ExecutionState.QUEUED,ExecutionState.RUNNING).contains(state.state())) {
                if(context.cancellationSignal().isCancellationRequested()||!Instant.now().isBefore(context.deadlineAt()))throw new InterruptedException();
                if (invoker != null && ExecutionState.RUNNING==state.state())
                    serviceProgramCalls(taskId, context, invoker);
                Thread.sleep(100);state=send("GET","/detail?taskId="+taskId,null,context.deadlineAt());
            }
            if(!Set.of(ExecutionState.COMPLETED,ExecutionState.FAILED,ExecutionState.CANCELLED,ExecutionState.TIMED_OUT).contains(state.state()))throw unknown();
            Map<String,Object> result=observation(state);
            // 只在沙箱已确认执行后报告本次只读包目录，准入失败或未知结果不伪造挂载事实。
            if(shell!=null&&shell.skillHandle()!=null&&state.exitCode()!=null)
                result.put("skill_resource_base","/input");
            if(request.workspace()!=null) {
                result.put("input_provenance",provenance);
                if(shell!=null)workspaceLogs(taskId,state,result,context,List.of("stdout","stderr"));
            }
            // 普通代码的已知成功输出仅作为内部观察；与公开业务块和正式文件无关。
            if(stdoutObservation && state.error()==null && Boolean.TRUE.equals(result.get("success"))
                    && state.logs().stream().anyMatch(value->"stdout".equals(value.stream())&&value.characters()>0))
                workspaceLogs(taskId,state,result,context,List.of("stdout"));
            if (shell==null && "PUBLIC_RESULT_FILE_INVALID".equals(state.error())) {
                // 文件协议错误不一定有stderr；反馈固定结构修正，不读取或回显不合法文件。
                result.put("diagnostic", publicResultDiagnostic("PUBLIC_RESULT_FILE_INVALID", parsed.language()));
            } else if (state.state()==ExecutionState.FAILED
                    || state.state()==ExecutionState.COMPLETED && state.exitCode()!=null && state.exitCode()!=0) {
                result.put("diagnostic", failureDiagnostic(taskId,state,context));
            } else if(!stdoutObservation && shell==null && Boolean.TRUE.equals(result.get("success"))
                    && state.logs().stream().anyMatch(value->"stdout".equals(value.stream())&&value.characters()>0)
                    && (state.publicResults()==null||state.publicResults().results().isEmpty())) {
                result.put("diagnostic",publicResultDiagnostic("EXECUTION_LOGS_NOT_OBSERVATIONS",parsed.language()));
            }
            present(result,state.publicResults(),context);
            if(PublicOutputGuard.REJECTION_CODE.equals(result.get("delivery_error")))return result;

            if (!attachmentEvidence.isEmpty()) result.put("attachment_inputs", attachmentEvidence.stream()
                    .map(com.skillforge.institution.attachment.SessionAttachmentService.Metadata::publicValue).toList());
            List<ArtifactMetadata.File> files=state.files().stream().map(file->new ArtifactMetadata.File(file.path(),file.size(),file.sha256())).toList();
            if (files.stream().anyMatch(file -> parsed.outputFiles().stream().noneMatch(path ->
                    path.endsWith("/") ? file.path().startsWith(path) : file.path().equals(path)))) {
                result.put("delivery_status", "failed");
                result.put("delivery_error", "undeclared_artifact_file");
                present(result,state.publicResults(),context);
                return result;
            }
            // 失败、取消与超时产生的半成品不得成为用户产物。
            if(Boolean.TRUE.equals(result.get("success")) && !files.isEmpty()) {
                var deliveryContext=com.skillforge.institution.artifact.ArtifactClosingBudget.deliveryContext(context,transferTimeout);
                Instant end=deliveryContext.deadlineAt();
                try {
                    var response=http.send(builder("/files?taskId="+taskId,end).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                    try(var body=response.body()) {
                        if(response.statusCode()!=200)throw new IOException("artifact_transfer_rejected");
                        // BodyHandlers.ofInputStream的HTTP超时不覆盖正文读取，独立闹钟关闭流以落实传输截止时间。
                        try(var timer=new ScheduledThreadPoolExecutor(1,
                                Thread.ofPlatform().name("code-artifact-transfer-deadline").daemon().factory())) {
                            var timeout=timer.schedule(()->{try{body.close();}catch(IOException ignored){}},Math.max(1,Duration.between(Instant.now(),end).toMillis()),TimeUnit.MILLISECONDS);
                            try{var artifact=artifacts.publish(deliveryContext,files,provenance,body);result.put("artifacts",List.of(Map.of("id",artifact.id(),"type",artifact.type(),"name",artifact.name(),"size",artifact.size(),"sha256",artifact.sha256(),"expires_at",com.skillforge.common.time.BusinessTime.format(artifact.expiresAt()),"status",artifact.status(),"paths",artifact.files().stream().map(ArtifactMetadata.File::path).toList())));result.put("delivery_status","ready");}
                            finally{timeout.cancel(false);}
                        }
                    }
                }catch(ArtifactPublicInspection.Rejection rejected){
                    result.put("success",false); result.put("delivery_status",FAILED_DELIVERY_STATUS);
                    result.put("delivery_error",PublicOutputGuard.REJECTION_CODE);
                }catch(Exception deliveryFailed){result.put("delivery_status","failed");result.put("delivery_error","artifact_delivery_failed");}
            }
            present(result,state.publicResults(),context);
            return result;
        }catch(ExecutionSubmissionRejectedException rejected) {
            // 准入回执证明本次未提交执行；不得再查询、取消或自动重发同一次请求。
            throw rejected;
        }catch(InterruptedException cancelled) {
            Thread.interrupted();
            try{send("DELETE","/delete",json.writeValueAsString(new CancelRequest(taskId)),Instant.now().plusSeconds(10));}catch(Exception ignored){/* 未确认终态保持unknown。 */}
            Thread.currentThread().interrupt();throw unknown();
        }catch(Exception failure){
            if(sent){
                if(invoker != null) try { send("DELETE","/delete",json.writeValueAsString(new CancelRequest(taskId)),
                        Instant.now().plusSeconds(10)); } catch(Exception ignored) { /* 保持结果未知，不重放程序。 */ }
                throw unknown();
            }
            return rejected("code_execution_artifact_inputs_rejected");
        }
    }
    private ExecutionResponse send(String method,String suffix,String body,Instant deadline)throws Exception {
        var builder=builder(suffix,deadline).method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return response(builder.build(),deadline,ExecutionResponse.class);
    }
    private void serviceProgramCalls(String taskId, PlatformToolExecutionContext context,
            com.skillforge.core.capability.connector.ProgramToolInvoker.Calls invoker) throws Exception {
        int ordinal=0;
        try {
            var pending = response(builder("/tool-calls?taskId="+taskId,context.deadlineAt()).GET().build(),
                    context.deadlineAt(),ToolCalls.class);
            if(pending.calls()==null||pending.calls().size()>ExecutionLimits.PROGRAM_CALLS)throw new IOException("program_rpc_invalid");
            for(var call:pending.calls()) {
                ordinal=call.ordinal();
                if(call.ordinal()<1||call.ordinal()>ExecutionLimits.PROGRAM_CALLS||call.handle()==null
                        ||call.arguments()==null||!call.arguments().isObject())throw new IOException("program_rpc_invalid");
                if(context.cancellationSignal().isCancellationRequested())throw new InterruptedException();
                com.skillforge.runtime.api.TypedPayload result;
                try {
                    result=invoker.invoke(call.ordinal(),call.handle(),
                            new com.skillforge.runtime.api.TypedPayload("application/json",json.writeValueAsString(call.arguments())));
                } catch(com.skillforge.core.capability.connector.ProgramToolInvoker.AdmissionRejected rejected) {
                    result=new com.skillforge.runtime.api.TypedPayload("application/json",json.writeValueAsString(Map.of(
                            "content",List.of(),"isError",true,"resultType","complete","structuredContent",Map.of(
                                    "success",false,"error",rejected.reasonCode()))));
                }
                response(builder("/tool-results",context.deadlineAt()).POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(new ToolResult(taskId,call.ordinal(),json.readTree(result.canonicalJson()))))).build(),
                        context.deadlineAt(),Void.class);
            }
        } catch(Exception failure) {
            String message=failure.getMessage();
            String reason=message!=null&&PROGRAM_RPC_SAFE_REASONS.contains(message)?message:"unknown";
            // 不传 Throwable，也不记录 handle、参数、HTTP 正文或凭据；发送后的未知结果语义不变。
            LOG.warn("程序工具调用失败 phase=program_rpc parentInvocationId={} taskId={} ordinal={} reason={} exceptionClass={} sourceLocation={}",
                    context.invocationId(),taskId,ordinal,reason,failure.getClass().getName(),programRpcSource(failure));
            throw failure;
        }
    }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    private record ToolCalls(List<ToolCall> calls) { }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    private record ToolCall(int ordinal,String handle,com.fasterxml.jackson.databind.JsonNode arguments) { }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    private record ToolResult(String taskId,int ordinal,com.fasterxml.jackson.databind.JsonNode result) { }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    private record CancelRequest(String taskId) { }
    private ExecutionResponse sendArchive(ArtifactStorage.ExecutionArchive archive,PlatformToolExecutionContext context)throws Exception {
        var request=builder("/artifact-inputs",context.deadlineAt()).setHeader("Content-Type","application/zip")
                .POST(HttpRequest.BodyPublishers.ofFile(archive.path())).build();
        var future=http.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
        try {
            while(true) {
                if(context.cancellationSignal().isCancellationRequested()||!Instant.now().isBefore(context.deadlineAt()))throw new InterruptedException();
                try{return response(future.get(100,TimeUnit.MILLISECONDS),context.deadlineAt(),ExecutionResponse.class,true);}
                catch(TimeoutException pending){/* 传输期间继续检查可信取消信号。 */}
                catch(ExecutionException failed){if(failed.getCause() instanceof IOException io)throw io;throw failed;}
            }
        } finally { if(!future.isDone())future.cancel(true); }
    }
    private <T> T response(HttpRequest request,Instant deadline,Class<T> type)throws Exception {
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        return response(response,deadline,type);
    }
    private <T> T response(HttpResponse<InputStream> response,Instant deadline,Class<T> type)throws Exception {
        return response(response, deadline, type, false);
    }
    private <T> T response(HttpResponse<InputStream> response,Instant deadline,Class<T> type,boolean submission)throws Exception {
        try(var input=response.body();var timer=new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("code-sandbox-response-deadline").daemon().factory())) {
            if(response.statusCode()==204)return null;
            var timeout=timer.schedule(()->{try{input.close();}catch(IOException ignored){}},Math.max(1,Duration.between(Instant.now(),deadline).toMillis()),TimeUnit.MILLISECONDS);
            try {
                byte[] bytes=input.readNBytes(ExecutionLimits.CONTROL_RESPONSE_BYTES+1);if(bytes.length>ExecutionLimits.CONTROL_RESPONSE_BYTES)throw new IOException("sandbox_status_too_large");
                if(response.statusCode()<200||response.statusCode()>=300) {
                    if(submission && response.statusCode()==400) {
                        var receipt=json.copy()
                                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                                .readValue(bytes,ExecutionAdmissionRejection.class);
                        throw new ExecutionSubmissionRejectedException(receipt);
                    }
                    throw new IOException("sandbox_request_rejected");
                }
                return json.readValue(bytes,type);
            }finally{timeout.cancel(false);}
        }
    }
    private HttpRequest.Builder builder(String suffix,Instant deadline) {
        Duration timeout=Duration.between(Instant.now(),deadline);if(timeout.isNegative()||timeout.isZero())throw new IllegalStateException("sandbox_deadline_elapsed");
        return HttpRequest.newBuilder(URI.create(endpoint+"/internal/executions"+suffix)).timeout(timeout).header("Authorization","Bearer "+token).header("Content-Type","application/json");
    }
    private static Map<String,Object> observation(ExecutionResponse state) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("success",state.state()==ExecutionState.COMPLETED&&Integer.valueOf(0).equals(state.exitCode()));
        result.put("state",state.state().value());result.put("phase",state.phase());
        if(state.error()!=null)result.put("error",state.error());
        if(state.exitCode()!=null)result.put("exit_code",state.exitCode());
        result.put("logs",state.logs());
        result.put("stdout_truncated",state.stdoutTruncated());result.put("stderr_truncated",state.stderrTruncated());
        result.put("delivery_status",state.deliveryError()==null?"none":"failed");
        if(state.deliveryError()!=null) {
            result.put("delivery_error","artifact_collection_failed");
            result.put("diagnostic", Map.of("status","available","code","OUTPUT_COLLECTION_FAILED",
                    "field","/output_files",
                    "message","程序已执行，但声明的候选文件未能收集；执行成功不代表候选文件已经可用。",
                    "hint","output_files 的相对路径只从 /workspace/output 收集；/workspace/work 是本次执行的临时目录，不会被收集或跨调用保留。"
                            + "核对实际写入目录、文件名以及普通文件或目录声明；仅需读取和检查时使用 output_files: []。"
                            + "确需在下一次调用复用的中间文件，应先写入 /workspace/output 并用 output_files 收集，再通过真实 artifact_inputs 挂载。"
                            + "本次没有可用成果引用，不能猜测 artifact_id 或继续读取上一调用的临时路径；使用原始受管输入完成必要修正。"
                            + "原程序已经执行，不要原样重跑含外部副作用的代码，不因文件收集失败推定业务操作未发生。"));
        }
        result.put("artifacts",List.of());return result;
    }

    /** 执行观察仅向模型返回有限日志；受保护内部身份和服务凭据不能经stdout泄露。 */
    private void workspaceLogs(String taskId,ExecutionResponse state,Map<String,Object> result,PlatformToolExecutionContext context,List<String> streams) {
        LOG.debug("工作区日志投影进入 invocationId={}",context.invocationId());
        try {
            for(String stream:streams) {
                try {
                    var ref=state.logs().stream().filter(value->stream.equals(value.stream())).findFirst();
                    if(ref.isEmpty()||ref.get().characters()==0){result.put(stream,"");continue;}
                    if(context.cancellationSignal().isCancellationRequested()||!Instant.now().isBefore(context.deadlineAt())) {
                        result.put(stream+"_unavailable",true);continue;
                    }
                    var page=response(builder("/logs?taskId="+taskId+"&stream="+stream+"&offset=0&limit=32768",context.deadlineAt())
                            .GET().build(),context.deadlineAt(),DiagnosticLogPage.class);
                    if(page==null||page.text()==null||page.text().length()>32768||!stream.equals(page.stream())) {
                        result.put(stream+"_unavailable",true);continue;
                    }
                    String text=page.text();
                    String clean=text.replaceAll("[\\p{Cc}&&[^\\n\\t]]","");
                    var scope=artifacts.publicScope(context.run());
                    // 清理控制字符可能重组凭据或内部标识，原文与实际返回文本都必须检查。
                    if(text.contains(token)||text.contains(taskId)||scope.rejects(text)
                            ||clean.contains(token)||clean.contains(taskId)||scope.rejects(clean)) {
                        result.put(stream,"");result.put(stream+"_withheld",true);
                    }else result.put(stream,clean);
                    result.put(stream+"_truncated",page.truncated()||page.hasMore()||ref.get().truncated());
                }catch(Exception unavailable) {
                    if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
                    result.put(stream+"_unavailable",true);
                }
            }
        }finally{LOG.debug("工作区日志投影退出 invocationId={}",context.invocationId());}
    }

    /** 固定协议示例只帮助下一次已授权调用修正结果通道，不合成业务值或重新执行。 */
    private static Map<String,Object> publicResultDiagnostic(String code, String language) {
        String message = "PUBLIC_RESULT_FILE_INVALID".equals(code)
                ? "固定公开业务结果文件不符合类型化results结构；代码已经实际执行，请保留本次执行事实。"
                : "本次 stdout 打印内容未作为模型可读取的结果返回，当前尚未观察到日志中的字段、结构或业务值；"
                        + "success 只表示执行成功，不能证明打印的完整性条件成立。";
        String example = "node".equals(language) ? """
                import { mkdirSync, writeFileSync } from 'node:fs';
                function publishResult(value) {
                  mkdirSync('/workspace/output', { recursive: true });
                  writeFileSync('/workspace/output/result.json', JSON.stringify({results: [{kind: 'text', format: 'plain', text: JSON.stringify(value)}]}), 'utf8');
                }
                // 在同次程序计算真实值后调用 publishResult(实际结果变量)。
                """ : """
                import json
                from pathlib import Path
                def publish_result(value):
                    path = Path('/workspace/output/result.json')
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(json.dumps({'results': [{'kind': 'text', 'format': 'plain', 'text': json.dumps(value, ensure_ascii=False)}]}, ensure_ascii=False), encoding='utf-8')
                # 在同次程序计算真实值后调用 publish_result(实际结果变量)。
                """;
        return Map.of("status","available", "code",code, "field","/workspace/output/result.json", "message",message,
                "hint", "完整性检查请用 assert 或显式抛出异常，不要原样重复 print。"
                        + "计算依赖未知原件结构时，用原attachment_ids/artifact_inputs返回最小必要类型化观察，"
                        + "确认后再编写依赖它的计算或成果。已有可信结构和值直接复用，不重复读取，不强制增加调用回合。"
                        + "需要返回实际业务值时，在同次程序计算真实值后写类型化结果文件；"
                        + "固定写入/workspace/output/result.json，无需额外调用参数；缺文件合法，纯校验不需要额外调用。只做字段/内容检查时output_files=[]，"
                        + "不要把检查日志和调试文件当作交付文件；下载成果先生成并检查文件，用真实output_files收集候选，再由finalize_task_outputs选择，Run成功后发布。"
                        + "不要把旧stdout或下列示例当成已经观察到的值。可复制的写入函数：\n" + example);
    }

    /** 只读本次已知失败的 stderr 尾部；诊断不可用不能改变执行事实或触发重新执行。 */
    private Map<String,Object> failureDiagnostic(String taskId,ExecutionResponse state,PlatformToolExecutionContext context) {
        try {
            if(context.cancellationSignal().isCancellationRequested()
                    ||Duration.between(Instant.now(),context.deadlineAt()).toMillis()<100)
                return Map.of("status","unavailable");
            var log=state.logs()==null?Optional.<ExecutionResponse.LogReference>empty():state.logs().stream()
                    .filter(value->"stderr".equals(value.stream())&&value.characters()>0).findFirst();
            if(log.isEmpty())return Map.of("status","unavailable");
            int limit=4096,offset=Math.max(0,log.orElseThrow().characters()-limit);
            Instant end=Instant.now().plusSeconds(2);if(context.deadlineAt().isBefore(end))end=context.deadlineAt();
            var page=response(builder("/logs?taskId="+taskId+"&stream=stderr&offset="+offset+"&limit="+limit,end)
                    .GET().build(),end,DiagnosticLogPage.class);
            if(page==null||!"stderr".equals(page.stream())||page.text()==null||page.text().length()>limit
                    ||page.nextOffset()!=offset+page.text().length()||page.nextOffset()!=log.orElseThrow().characters()||page.hasMore()
                    ||context.cancellationSignal().isCancellationRequested())return Map.of("status","unavailable");
            String text=page.text();
            // 私有平台标识和凭据绝不进入模型诊断；脚本路径只保留文件名，去除控制字符。
            String clean=text.replaceAll("(?m)(File \"[^\"\\r\\n]*[/\\\\])([^\"/\\\\]+)(\")","File \"$2$3")
                    .replaceAll("[\\p{Cc}&&[^\\n\\t]]","");
            var scope=artifacts.publicScope(context.run());
            if(text.contains(token)||text.contains(taskId)||scope.rejects(text)
                    ||clean.contains(token)||clean.contains(taskId)||scope.rejects(clean))
                return Map.of("status","withheld");
            if(clean.isBlank())return Map.of("status","unavailable");
            return Map.of("status","available","stream","stderr","text",clean,
                    "truncated",offset>0||page.hasMore()||page.truncated()||log.orElseThrow().truncated());
        }catch(Exception unavailable) {
            if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            return Map.of("status","unavailable");
        }
    }
    private static String programRpcSource(Exception failure) {
        var frames = new ArrayList<String>();
        for(var frame:failure.getStackTrace()) {
            if(frame.getClassName().startsWith("com.skillforge.")&&frame.getLineNumber()>0)
                frames.add(frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());
            if(frames.size()==3)break;
        }
        return frames.isEmpty()?"unknown":String.join(" <- ",frames);
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    private record DiagnosticLogPage(String stream,String text,int nextOffset,boolean hasMore,boolean truncated) { }
    /** 工具 owner 只声明可读业务结果；真实文件引用单独提交，不拼接到用户正文。 */
    private void present(Map<String,Object> result,PublicResults publicResults,PlatformToolExecutionContext context) {
        var references=new ArrayList<ChatReferenceView>();
        if(result.get("artifacts") instanceof List<?> entries) for(Object value:entries) {
            if(!(value instanceof Map<?,?> artifact) || !(artifact.get("id") instanceof String id))continue;
            if(!(artifact.get("paths") instanceof List<?> paths))continue;
            var files=new ArrayList<ChatInputReference.ArtifactFile>();
            for(Object path:paths)if(path instanceof String filename) {
                files.add(new ChatInputReference.ArtifactFile(id,filename));
            }
            if(!files.isEmpty()) references.add(new ChatReferenceView(String.valueOf(artifact.get("name")),
                    new ChatInputReference.ArtifactFiles(files)));
        }
        var scope=artifacts.publicScope(context.run());
        var blocks=new ArrayList<ProcessPresentation.Result>();
        if(publicResults!=null)for(var value:publicResults.results()) {
            if(value instanceof PublicResults.Text text)blocks.add(new ProcessPresentation.Text(text.format(),text.text()));
            else if(value instanceof PublicResults.Table table)blocks.add(new ProcessPresentation.Table(
                    table.columns().stream().map(column->new ProcessPresentation.Column(column.key(),column.label())).toList(),table.rows()));
        }
        // 使用同一宿主策略检查业务文字与表格；原始stdout/stderr从不成为公开候选。
        boolean rejected=PublicOutputGuard.REJECTION_CODE.equals(result.get("delivery_error"))
                ||references.stream().anyMatch(reference->scope.rejects(reference.label()));
        for(var block:blocks) {
            if(block instanceof ProcessPresentation.Text text)rejected|=scope.rejects(text.text());
            else if(block instanceof ProcessPresentation.Table table) {
                rejected|=table.columns().stream().anyMatch(column->scope.rejects(column.key())||scope.rejects(column.label()));
                rejected|=table.rows().stream().flatMap(Collection::stream).anyMatch(cell->cell!=null&&scope.rejects(String.valueOf(cell)));
            }
        }
        if(rejected) {
            result.put("success",false);result.put("delivery_status",FAILED_DELIVERY_STATUS);
            result.put("delivery_error",PublicOutputGuard.REJECTION_CODE);references.clear();blocks.clear();
            blocks.add(new ProcessPresentation.Text(PLAIN_RESULT_FORMAT,PublicOutputGuard.SAFE_MESSAGE));
        }
        String summary=PublicOutputGuard.REJECTION_CODE.equals(result.get("delivery_error")) ? PublicOutputGuard.SAFE_MESSAGE
                : !Boolean.TRUE.equals(result.get("success")) ? CODE_FAILED_SUMMARY
                : "artifact_collection_failed".equals(result.get("delivery_error")) ? CODE_COLLECTION_FAILED_SUMMARY
                : FAILED_DELIVERY_STATUS.equals(result.get("delivery_status")) ? CODE_DELIVERY_FAILED_SUMMARY
                : references.isEmpty() ? CODE_COMPLETED_SUMMARY : CODE_FILES_READY_SUMMARY;
        result.put(PUBLIC_PRESENTATION_FIELD,new ProcessPresentation(CODE_PRESENTATION_TITLE,summary,blocks,references));
        // 正文通道只使用已通过宿主公开策略的业务块；不把 stdout/stderr 或诊断包装当作正文。
        var contents = new ArrayList<Map<String,String>>();
        for (var block : blocks) {
            String body = block instanceof ProcessPresentation.Text text ? text.text() : json.valueToTree(block).toString();
            contents.add(Map.of("type", "text", "text", body));
        }
        contents.add(Map.of("type", "text", "text", summary));
        result.put("content", List.copyOf(contents));
    }
    /** 准入失败不会提交 Runner；公开过程只说明事实，详细修正提示留在模型观察中。 */
    private static Map<String,Object> admissionRejected(String error,Map<String,Object> diagnostic,String summary) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("success",false);result.put("state","rejected");result.put("phase","admission");
        result.put("error",error);result.put("diagnostic",diagnostic);
        result.put("delivery_status","none");result.put("artifacts",List.of());
        result.put(PUBLIC_PRESENTATION_FIELD,ProcessPresentation.of(CODE_PRESENTATION_TITLE,summary));
        result.put("content",List.of(Map.of("type","text","text",summary)));
        return result;
    }

    private static Map<String,Object> rejected(String error) {
        return admissionRejected(error,Map.of("status","unavailable"),CODE_ADMISSION_UNAVAILABLE_SUMMARY);
    }

    private static Map<String,Object> resourceRejected(PlatformToolExecutionContext context,String stage,
            String reason,Exception failure) {
        if ("workspace".equals(stage)) {
            String code = "RESOURCE_ACCESS_REJECTED".equals(reason) ? "WORKSPACE_ACCESS_REJECTED" : "WORKSPACE_UNAVAILABLE";
            logAdmission(context,stage,code,"/use_workspace",failure);
            return admissionRejected("code_execution_resources_rejected",Map.of("status","available", "code",code,
                    "field","/use_workspace", "message","本次未获得可用工作区，代码未执行。",
                    "hint","不启用use_workspace；每次普通执行使用独立输出目录。复用前次文件须以成功回执的真实artifact_id/path声明artifact_inputs，从/input/artifacts读取；不能直接读取前次/workspace/output或绕过授权。"),
                    CODE_RESOURCES_REJECTED_SUMMARY);
        }
        String field="attachments".equals(stage)?"/attachment_ids":"skill_script".equals(stage)?"/skill_handle":"";
        logAdmission(context,stage,reason,field,failure);
        return admissionRejected("code_execution_resources_rejected",Map.of("status","available",
                "code",reason,"field",field,"message","执行所需资源不可用或未获授权。",
                "hint","确认附件或产物仍可访问，技能已激活且脚本属于冻结清单；不能猜测资源标识或绕过授权。"),
                CODE_RESOURCES_REJECTED_SUMMARY);
    }

    private static void logAdmission(PlatformToolExecutionContext context,String stage,String reason,
            String field,Exception failure) {
        var safe=SafeFailureDiagnostics.of(failure);
        // 不传 Throwable、原始参数、异常正文或资源标识；原因码与字段只来自宿主受控契约。
        LOG.warn("代码执行准入拒绝 phase=admission invocationId={} stage={} reason={} field={} exceptionClass={} httpStatus={} sourceLocation={}",
                context.invocationId(),stage,reason,field,safe.exceptionType(),safe.httpStatus(),safe.stackTop());
    }
    private static ExternalInvocationGuard.OutcomeUnknownException unknown(){return new ExternalInvocationGuard.OutcomeUnknownException("code_execution_outcome_unknown",null);}
}
