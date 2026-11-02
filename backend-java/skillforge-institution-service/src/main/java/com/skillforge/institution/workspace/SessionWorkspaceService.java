package com.skillforge.institution.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.execution.WorkspaceBinding;
import com.skillforge.execution.WorkspaceCommand;
import com.skillforge.execution.WorkspacePolicy;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.artifact.ArtifactStorage;
import com.skillforge.institution.artifact.StudioArtifactProjection;
import com.skillforge.institution.execution.CodeExecutionToolHandler;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimePublishedCapabilities;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 会话工作区授权和宿主编排；Sandbox持有文件与执行状态的唯一事实。 */
@Service
public final class SessionWorkspaceService {
    private static final Logger LOG=LoggerFactory.getLogger(SessionWorkspaceService.class);
    private static final Set<String> FILE_FIELDS=Set.of("op","path","target_path","content","expected_revision","expected_sha256",
            "offset","limit","old_text","new_text","artifact_inputs","attachment_ids");
    private final AgentRunBindings bindings;
    private final AgentRuntimePublishedCapabilities capabilities;
    private final RunSettingsService settings;
    private final SessionWorkspaceRegistry registry;
    private final WorkspaceSandboxClient sandbox;
    private final ArtifactAccess access;
    private final ArtifactStorage artifacts;
    private final StudioArtifactProjection projection;
    private final ObjectProvider<CodeExecutionToolHandler> execution;
    private final ObjectMapper json;

    /** 通过Provider延迟获取执行器，工作区导入与代码执行共享同一隔离管线。 */
    public SessionWorkspaceService(AgentRunBindings bindings,AgentRuntimePublishedCapabilities capabilities,
            RunSettingsService settings,SessionWorkspaceRegistry registry,WorkspaceSandboxClient sandbox,
            ArtifactAccess access,ArtifactStorage artifacts,StudioArtifactProjection projection,ObjectProvider<CodeExecutionToolHandler> execution,ObjectMapper json) {
        LOG.debug("工作区宿主服务装配进入");
        this.bindings=bindings;this.capabilities=capabilities;this.settings=settings;this.registry=registry;
        this.sandbox=sandbox;this.access=access;this.artifacts=artifacts;this.projection=projection;this.execution=execution;this.json=json;
        LOG.debug("工作区宿主服务装配退出");
    }

    /** 代码执行挂载持久文件必须额外拥有精确workspace_files授权，程序子调用失败关闭。 */
    public WorkspaceBinding forCode(PlatformToolExecutionContext context)throws Exception {
        LOG.debug("代码工作区授权进入 invocationId={}",context.invocationId());
        try{return binding(context,"workspace_files");}
        finally{LOG.debug("代码工作区授权退出 invocationId={}",context.invocationId());}
    }

    /** 文件API只使用相对路径；模型不携带租户、工作区身份或来源URL。 */
    public Map<String,Object> files(Map<String,Object> args,PlatformToolExecutionContext context)throws Exception {
        LOG.debug("工作区文件操作进入 invocationId={}",context.invocationId());
        try {
            requireFields(args,FILE_FIELDS);
            String op=text(args,"op",true);
            if(!Set.of("list","read","write","patch","mkdir","move","delete","import").contains(op))throw invalid();
            Set<String> fields=switch(op) {
                case "list","read"->Set.of("op","path","offset","limit");
                case "write"->Set.of("op","path","content","expected_revision","expected_sha256");
                case "patch"->Set.of("op","path","old_text","new_text","expected_revision","expected_sha256");
                case "mkdir"->Set.of("op","path","expected_revision");
                case "move"->Set.of("op","path","target_path","expected_revision","expected_sha256");
                case "delete"->Set.of("op","path","expected_revision","expected_sha256");
                case "import"->Set.of("op","target_path","artifact_inputs","attachment_ids");
                default->throw invalid();
            };
            requireFields(args,fields);
            if("import".equals(op))return importFiles(args,context);
            var binding=binding(context,"workspace_files");
            String content=text(args,"content",false);
            if("patch".equals(op)) {
                String replacement=text(args,"new_text",false);if(replacement==null)throw invalid();
                content=json.writeValueAsString(Map.of("old_text",text(args,"old_text",true),"new_text",replacement));
            }
            var command=new WorkspaceCommand(operationId(context),binding,op,text(args,"path",false),text(args,"target_path",false),content,
                    number(args,"expected_revision"),text(args,"expected_sha256",false),integer(args,"offset"),integer(args,"limit"),null,context.deadlineAt());
            var result=sandbox.operate(command,context);
            if("read".equals(op)&&result.get("content") instanceof String body&&artifacts.publicScope(context.run()).rejects(body))
                return Map.of("success",false,"error","workspace_content_withheld");
            return result;
        }finally{LOG.debug("工作区文件操作退出 invocationId={}",context.invocationId());}
    }

    /** 完整Bash命令使用既有任务提交、日志、取消和未知结果流程。 */
    public Map<String,Object> exec(Map<String,Object> args,PlatformToolExecutionContext context)throws Exception {
        LOG.debug("工作区命令进入 invocationId={}",context.invocationId());
        try {
            requireFields(args,Set.of("command","cwd","timeout_seconds","skill_handle"));
            var binding=binding(context,"workspace_exec");
            String command=text(args,"command",true),cwd=text(args,"cwd",false);
            if(command.length()>65_536)throw invalid();
            cwd=cwd==null?".":relative(cwd,true);
            Integer timeout=integer(args,"timeout_seconds");
            if(timeout!=null&&timeout<1)throw invalid();
            String skillHandle=args.containsKey("skill_handle")?text(args,"skill_handle",true):null;
            // cwd先作相对路径约束，再由Bash物理路径校验阻止已存在符号链接逃逸。
            String shell="cd -- /workspace/project || exit 73\ncd -- "+quote(cwd)+" || exit 73\n"
                    +"case \"$(pwd -P)\" in /workspace/project|/workspace/project/*) ;; *) exit 73 ;; esac\n"+command;
            return execution.getObject().executeWorkspaceShell(shell,timeout,binding,skillHandle,context);
        }finally{LOG.debug("工作区命令退出 invocationId={}",context.invocationId());}
    }

    /** 查询、检查点和显式恢复都从Sandbox投影；恢复必须携带当前基准修订。 */
    public Map<String,Object> checkpoint(Map<String,Object> args,PlatformToolExecutionContext context)throws Exception {
        LOG.debug("工作区检查点进入 invocationId={}",context.invocationId());
        try {
            requireFields(args,Set.of("operation","snapshot_id","expected_revision"));
            String operation=text(args,"operation",true);
            if(!Set.of("status","list","create","restore").contains(operation))throw invalid();
            Long revision=number(args,"expected_revision");
            if("restore".equals(operation)&&(revision==null||text(args,"snapshot_id",true).isBlank()))throw invalid();
            var binding=binding(context,"workspace_checkpoint");
            return sandbox.operate(new WorkspaceCommand(operationId(context),binding,"checkpoint_"+operation,null,null,null,
                    revision,null,null,null,text(args,"snapshot_id",false),context.deadlineAt()),context);
        }finally{LOG.debug("工作区检查点退出 invocationId={}",context.invocationId());}
    }

    /** 用户展示先核验产品Session，子目录摘要必须仍在精确授权投影中。 */
    public Map<String,Object> statuses(String productSession)throws Exception {
        LOG.debug("产品工作区状态进入");
        try {
            String tenant=access.tenant();access.authorizeCurrent(tenant,productSession);
            Set<String> visible=Set.copyOf(projection.visibleSessions(tenant,productSession));
            var result=new java.util.ArrayList<Map<String,Object>>();
            for(var row:registry.list(tenant,productSession)) {
                if(!visible.contains(row.sessionId()))continue;
                result.add(Map.of("sessionId",row.sessionId(),"isChild",!productSession.equals(row.sessionId()),"status",sandbox.status(row.workspaceId())));
            }
            return Map.of("workspaces",List.copyOf(result));
        }finally{LOG.debug("产品工作区状态退出");}
    }

    /** 从不可变Run生成身份和冻结策略，所有后台身份都恢复调用前租户上下文。 */
    private WorkspaceBinding binding(PlatformToolExecutionContext context,String capability)throws Exception {
        LOG.debug("工作区绑定进入 invocationId={} capability={}",context.invocationId(),capability);
        try {
            if(context.programChild())throw new SecurityException("workspace_program_child_forbidden");
            var run=context.run();String tenant=run.owner().sessionRef().tenantId().value();
            String operation=operationId(context);
            WorkspaceBinding candidate=inTenant(tenant,()->{
                var frozen=bindings.find(run.runRef()).orElseThrow(()->new SecurityException("workspace_run_binding_missing"));
                if(!frozen.owner().equals(run.owner())||!frozen.actor().equals(run.agentId()))throw new SecurityException("workspace_run_binding_mismatch");
                bindings.authorize(frozen);
                if(capabilities.list(run).stream().noneMatch(entry->entry.binding().providerType()==ToolBinding.ProviderType.PLATFORM
                        &&capability.equals(entry.binding().toolName())))throw new SecurityException("workspace_tool_not_authorized");
                var config=settings.read(settings.captureTool(tenant,run.runRef().runId(),context.invocationId()),"workspace");
                if(!config.bool("enabled"))throw new IllegalStateException("workspace_disabled");
                String session=run.owner().sessionRef().sessionId(),product=frozen.scope().origin().sessionRef().sessionId();
                var binding=new WorkspaceBinding(digest(tenant+"\u0000"+session),tenant,session,run.owner().requireUser().value(),product,
                        new WorkspacePolicy(config.revision(),config.integer("checkpoint_interval_seconds"),config.integer("local_idle_hours"),config.integer("snapshot_history")));
                boolean initialize=registry.requireActive(binding,operation);
                return new WorkspaceBinding(binding.workspaceId(),binding.tenantId(),binding.sessionId(),binding.endUserId(),
                        binding.productSessionId(),binding.policy(),initialize);
            });
            if(candidate.initializeAllowed()) {
                var initialized=sandbox.operate(new WorkspaceCommand(digest("initialize:"+operation),candidate,"initialize",null,null,null,null,null,null,null,null,
                        context.deadlineAt()),context);
                if(!Boolean.TRUE.equals(initialized.get("success")))throw new IllegalStateException("workspace_initialization_rejected");
                inTenant(tenant,()->{registry.confirmInitialization(candidate,operation);return null;});
            }
            // 文件和执行请求始终禁止重新空建；initialize只用于尚无文件操作的首次确认阶段。
            return new WorkspaceBinding(candidate.workspaceId(),candidate.tenantId(),candidate.sessionId(),candidate.endUserId(),
                    candidate.productSessionId(),candidate.policy(),false);
        }finally{LOG.debug("工作区绑定退出 invocationId={}",context.invocationId());}
    }

    /** 授权附件/成果复制到新目录；独占执行内以exclusive-create防止覆盖已存在文件。 */
    private Map<String,Object> importFiles(Map<String,Object> args,PlatformToolExecutionContext context)throws Exception {
        LOG.debug("工作区导入进入 invocationId={}",context.invocationId());
        try {
            var binding=binding(context,"workspace_files");
            String target=relative(text(args,"target_path",true),false);
            boolean artifacts=args.get("artifact_inputs") instanceof List<?> a&&!a.isEmpty();
            boolean attachments=args.get("attachment_ids") instanceof List<?> a&&!a.isEmpty();
            if(artifacts==attachments)throw invalid();
            var codeArgs=new LinkedHashMap<String,Object>();
            codeArgs.put("mode","inline");codeArgs.put("language","python");codeArgs.put("use_workspace",true);
            if(artifacts)codeArgs.put("artifact_inputs",args.get("artifact_inputs"));else codeArgs.put("attachment_ids",args.get("attachment_ids"));
            // 输入均由既有archive鉴权和摘要校验；目标只能是不存在的目录，无覆盖窗口。
            String script="import os, pathlib, shutil, stat\nroot=pathlib.Path('/workspace/project')\n"
                    +"target=root / "+json.writeValueAsString(target)+"\n"
                    +"current=root\nfor segment in target.relative_to(root).parts[:-1]:\n"
                    +"    current=current/segment\n    if current.is_symlink(): raise RuntimeError('workspace_import_path_rejected')\n"
                    +"    current.mkdir(exist_ok=True)\n"
                    +"if target.exists() or target.is_symlink(): raise RuntimeError('workspace_import_target_exists')\n"
                    +"source=pathlib.Path('/input')\nfiles=[]\n"
                    +"for item in source.rglob('*'):\n"
                    +"    if item.is_symlink(): raise RuntimeError('workspace_import_source_rejected')\n"
                    +"    if item.is_file(): files.append(item)\n"
                    +"target.mkdir()\nfor item in files:\n"
                    +"    dest=target/item.relative_to(source)\n    dest.parent.mkdir(parents=True,exist_ok=True)\n"
                    +"    with item.open('rb') as inp, dest.open('xb') as out: shutil.copyfileobj(inp,out)\n";
            codeArgs.put("code",script);codeArgs.put("output_files",List.of());
            var result=new LinkedHashMap<>(execution.getObject().execute(codeArgs,context));
            result.put("target_path",target);
            if(Boolean.TRUE.equals(result.get("success"))) {
                var evidence=new LinkedHashMap<String,Object>();evidence.put("target_path",target);
                evidence.put("artifact_inputs",result.getOrDefault("input_provenance",List.of()));
                evidence.put("attachment_inputs",result.getOrDefault("attachment_inputs",List.of()));
                // 任务身份与本次执行管线一致；不能把随后查询到的当前修订冒充本次导入修订。
                String task=digest(json.writeValueAsString(List.of(binding.tenantId(),context.run().runRef().runId(),context.invocationId(),context.attemptId())));
                evidence.put("task_id",task);
                inTenant(binding.tenantId(),()->{try{registry.recordImport(binding,operationId(context),json.writeValueAsString(evidence));return null;}
                    catch(java.io.IOException invalid){throw new IllegalStateException("workspace_import_evidence_invalid");}});
            }
            return result;
        }finally{LOG.debug("工作区导入退出 invocationId={}",context.invocationId());}
    }

    /** 可信调用identity参与幂等键；原始Session不进入日志。 */
    private static String operationId(PlatformToolExecutionContext context) {
        LOG.debug("工作区操作身份生成进入");
        try{return digest(context.run().runRef().tenantId().value()+"\u0000"+context.run().runRef().runId()+"\u0000"+context.invocationId()+"\u0000"+context.attemptId());}
        finally{LOG.debug("工作区操作身份生成退出");}
    }
    /** 只散列身份，不将模型正文或凭据构造为内部标识。 */
    private static String digest(String value) {
        LOG.debug("工作区身份摘要进入");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        finally{LOG.debug("工作区身份摘要退出");}
    }
    /** 固定字段集合拒绝身份注入和未定义参数。 */
    private static void requireFields(Map<String,Object> args,Set<String> fields){
        LOG.debug("工作区参数字段校验进入");
        try{if(args==null||!fields.containsAll(args.keySet()))throw invalid();}
        finally{LOG.debug("工作区参数字段校验退出");}
    }
    /** 文本只作类型验证，不回显模型内容。 */
    private static String text(Map<String,Object> args,String field,boolean required) {
        LOG.debug("工作区文本字段校验进入 field={}",field);
        try {
            Object raw=args.get(field);if(raw==null&&!required)return null;
            if(!(raw instanceof String value)||required&&value.isBlank())throw invalid();return value;
        }finally{LOG.debug("工作区文本字段校验退出 field={}",field);}
    }
    /** JSON整数不得通过浮点或截断进入revision和分页。 */
    private static Long number(Map<String,Object> args,String field) {
        LOG.debug("工作区整数字段校验进入 field={}",field);
        try {
            Object raw=args.get(field);if(raw==null)return null;
            if(!(raw instanceof Number n))throw invalid();
            try{return new java.math.BigDecimal(n.toString()).longValueExact();}catch(ArithmeticException invalid){throw invalid();}
        }finally{LOG.debug("工作区整数字段校验退出 field={}",field);}
    }
    /** 分页与超时使用可精确表示的int，拒绝溢出。 */
    private static Integer integer(Map<String,Object> args,String field) {
        LOG.debug("工作区整数范围校验进入 field={}",field);
        try {
            Long raw=number(args,field);if(raw==null)return null;
            try{return Math.toIntExact(raw);}catch(ArithmeticException invalid){throw invalid();}
        }finally{LOG.debug("工作区整数范围校验退出 field={}",field);}
    }
    /** 归一化相对路径，禁止绝对路径、父目录跳转、空段和控制字符。 */
    static String relative(String path,boolean allowDot) {
        LOG.debug("工作区相对路径校验进入");
        try {
            if(path==null||path.isBlank()||path.startsWith("/")||path.contains("\\")||path.contains(":")||path.chars().anyMatch(c->c<32||c==127))throw invalid();
            if(allowDot&&path.equals("."))return path;
            for(String part:path.split("/",-1))if(part.isBlank()||part.equals(".")||part.equals(".."))throw invalid();return path;
        }finally{LOG.debug("工作区相对路径校验退出");}
    }
    /** 单引号转义只作用cwd，命令正文保留完整Bash表达能力。 */
    static String quote(String value){
        LOG.debug("工作区Shell路径编码进入");
        try{return "'"+value.replace("'","'\"'\"'")+"'";}finally{LOG.debug("工作区Shell路径编码退出");}
    }
    /** 内部错误码固定，避免将输入正文传入异常。 */
    private static IllegalArgumentException invalid(){
        LOG.debug("工作区参数错误生成进入");
        try{return new IllegalArgumentException("workspace_arguments_invalid");}finally{LOG.debug("工作区参数错误生成退出");}
    }
    /** 显式租户上下文供MyBatis RLS复用；退出必须恢复而非清除调用方身份。 */
    private static <T>T inTenant(String tenant,Supplier<T> work) {
        LOG.debug("工作区租户作用域进入");
        String previous=TenantContext.getTenantId();
        if(previous!=null&&!previous.equals(tenant))throw new SecurityException("workspace_tenant_mismatch");
        try{TenantContext.setTenantId(tenant);return work.get();}
        finally{if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);LOG.debug("工作区租户作用域退出");}
    }
}
